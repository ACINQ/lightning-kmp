import Foundation
import Network
import os.log

#if DEBUG && true
fileprivate var log = Logger(
	subsystem: Bundle.main.bundleIdentifier!,
	category: "NativeSocket"
)
#else
fileprivate var log = Logger(OSLog.disabled)
#endif

enum DecodePubKeyError: Error {
	case stringToBase64
	case dataToSecKey
}

@objc
public class NativeSocket: NSObject {
	
	private let host: String
	private let connection: NWConnection
	private let parentConnections: [NWConnection]
	
	private init(host: String, connection: NWConnection, parentConnections: [NWConnection] = []) {
		self.host = host
		self.connection = connection
		self.parentConnections = parentConnections
	}
	
	@objc
	public class func connect(
		host: String,
		port: UInt16,
		tls: NativeSocketTLS,
		success: @escaping (NativeSocket) -> Void,
		failure: @escaping (NativeSocketError) -> Void
	) -> Void {
		
		rawConnect(
			host: host,
			port: port,
			tls: tls.makeProtocolOptions()
		) { (connection: NWConnection) in
			success(NativeSocket(host: host, connection: connection))
		} failure: { (error: NWError) in
			failure(error.toNativeSocketError())
		}
	}
	
	private class func rawConnect(
		host: String,
		port: UInt16,
		tls: NWProtocolTLS.Options?,
		success: @escaping (NWConnection) -> Void,
		failure: @escaping (NWError) -> Void
	) -> Void {
		
		var _connection: NWConnection? = nil
		var completionCalled = false
		
		let finish = {(result: Result<NWConnection, NWError>) in
			DispatchQueue.main.async {
				
				guard !completionCalled else { return }
				completionCalled = true
				_connection?.stateUpdateHandler = nil
				
				switch result {
				case .success(let socket):
					success(socket)
				case .failure(let error):
					failure(error)
				}
			}
		}
		
		let ep_host = NWEndpoint.Host(host)
		guard let ep_port = NWEndpoint.Port(rawValue: port) else {
			return finish(.failure(.posix(.EINVAL)))
		}
		
		let tcpOptions = NWProtocolTCP.Options()
		let options = NWParameters(tls: tls, tcp: tcpOptions)
		let connection = NWConnection(host: ep_host, port: ep_port, using: options)
		
		connection.stateUpdateHandler = {(state: NWConnection.State) in
			
			switch state {
			case .setup:
				log.debug("NWConnection.state => setup")
			case .waiting(let error):
				switch error {
				case .posix(_):
					log.debug("NWConnection.state => waiting: err(posix): \(error)")
				case .dns(_):
					log.debug("NWConnection.state => waiting: err(dns): \(error)")
				case .tls(_):
					log.debug("NWConnection.state => waiting: err(tls): \(error))")
				case .wifiAware(_):
					log.debug("NWConnection.state => waiting: err(wifiAware): \(error))")
				@unknown default:
					log.debug("NWConnection.state => waiting: err(unknown): \(error)")
				}
				if case NWError.tls = error {
					finish(.failure(error))
					connection.cancel()
				}
			case .preparing:
				log.debug("NWConnection.state => preparing")
			case .ready:
				log.debug("NWConnection.state => ready")
				finish(.success(connection))
			case .failed(let error):
				log.debug("NWConnection.state => failed")
				finish(.failure(error))
				connection.cancel()
			case .cancelled:
				log.debug("NWConnection.state => cancelled")
				finish(.failure(.posix(.ECANCELED)))
			@unknown default:
				log.error("NWConnection.state => unknown")
			}
			
		} // </stateUpdateHandler>
		
		_connection = connection
		connection.start(queue: DispatchQueue.global(qos: .userInitiated))
	}
	
	@objc
	public func send(
		data: Data,
		completion: @escaping (NativeSocketError?) -> Void
	) {
		connection.send(
			content: data,
			contentContext: .defaultMessage,
			isComplete: true,
			completion: .contentProcessed({(error: NWError?) in
				DispatchQueue.main.async {
					if let error = error {
						completion(error.toNativeSocketError())
					} else {
						completion(nil)
					}
				}
		}))
	}
	
	@objc
	public func receiveAvailable(
		maximumLength: Int,
		success: @escaping (Data) -> Void,
		failure: @escaping (NativeSocketError) -> Void
	) -> Void {
		
		let finish = {(result: Result<Data, NWError>) in
			DispatchQueue.main.async {
				switch result {
				case .success(let data):
					success(data)
				case .failure(let error):
					failure(error.toNativeSocketError())
				}
			}
		}
		
		if maximumLength < 1 {
			return finish(.failure(.posix(.EINVAL)))
		}
		
		connection.receive(
			minimumIncompleteLength: 1,
			maximumLength: maximumLength
		) { (data: Data?, context: NWConnection.ContentContext?, isComplete: Bool, error: NWError?) in
			
			if let error = error {
				finish(.failure(error))
			} else if let data = data, !data.isEmpty {
				finish(.success(data))
			} else /* end of stream */ {
				finish(.failure(.posix(.ECONNRESET)))
			}
		}
	}
	
	@objc
	public func receiveFully(
		length: Int,
		success: @escaping (Data) -> Void,
		failure: @escaping (NativeSocketError) -> Void
	) -> Void {
		
		let finish = {(result: Result<Data, NWError>) in
			DispatchQueue.main.async {
				switch result {
				case .success(let data):
					success(data)
				case .failure(let error):
					failure(error.toNativeSocketError())
				}
			}
		}
		
		if length < 1 {
			return finish(.failure(.posix(.EINVAL)))
		}
		
		connection.receive(
			minimumIncompleteLength: length,
			maximumLength: length
		) { (data: Data?, context: NWConnection.ContentContext?, isComplete: Bool, error: NWError?) in
			
			if let error = error {
				finish(.failure(error))
			} else if let data = data, data.count == length {
				finish(.success(data))
			} else /* end of stream */ {
				finish(.failure(.posix(.ECONNRESET)))
			}
		}
	}
	
	@objc
	public func startTLS(
		tls: NativeSocketTLS,
		success: @escaping (NativeSocket) -> Void,
		failure: @escaping (NativeSocketError) -> Void
	) -> Void {
		
		// NWConnection doesn't support startTLS (or SOCKS).
		// So we have to manually code a workaround.
		// Here's the general idea:
		//
		// A. We have an existing connection to the remote host.
		//
		//    [us]*===========================*[server]
		//        ^oldClientConnection
		//
		// B. We then create a new connection to ourself:
		//
		//    [us]*===========================*[also us]
		//        ^newClientConnection        ^proxyConnection
		//
		//    Remember that there are two sides to every connection.
		//    We're creating a connection to ourself, such that we will control both sides.
		//
		// C. We attach the connections together:
		//
		//    [us]*===========================*[us]*===========================*[server]
		//        ^newClientConnection        ^    ^oldClientConnection
		//                                    ^proxyConnection
		//
		//    - everything we read from the proxyConnection we forward to the oldClientConnection
		//    - everything we read from the oldClientConnection we forward to the proxyConnection
		//
		// D. When we open the newClientConnection, we do so with the given TLS options.
		//    So it will automatically execute the TLS handshake.
		//    If it succeeds, then we hand the newClientConnection to the caller via the `success` callback.
		//
		//
		// The code; step-by-step:
		//
		// 1. We start a listener to accept incoming connections.
		//
		//    This will only accept connections from the localhost on loopback.
		//    This will only accept a single connection.
		//    The port number is randomly assigned by the OS.
		//
		// 2. We immediately connect to the listener.
		//
		//    This yields 2 new connections:
		//    newClientConnection -> the "outgoing" connection to our listener
		//    proxyConnection -> the "incoming" accepted connection from the listener
		//
		// 3. We use the proxyConnection to bind to the oldClientConnection
		//
		//    Any data we receive on the proxyConnection is forwarded to the oldClientConnection.
		//    Any data we receive on the oldClientConnection is forwarded to the proxyConnection.
		//
		//    So the proxy connection is just a dumb pipe connectiong the old & new client connections.
		//
		// 4. We enabled TLS on the newClientConnection
		//
		//    Note that if standard TLS verification is being used, then we must override the "tlsServerName".
		//    If we don't, then the TLS verification routine will expect a certificate for "127.0.0.1".
		//    Obviously, that's not what we want.
		//    It needs to verify against the hostname used for the oldClientConnection.
		
		var _listener: NWListener? = nil
		var completionCalled = false
		
		let finish = {(result: Result<NativeSocket, NWError>) in
			DispatchQueue.main.async {
				
				guard !completionCalled else { return }
				completionCalled = true
				_listener?.stateUpdateHandler = nil
				_listener?.cancel()
				
				switch result {
				case .success(let socket):
					success(socket)
				case .failure(let error):
					failure(error.toNativeSocketError())
				}
			}
		}
		
		let oldServerName: String = tls.expectedHostName ?? host
		let oldClientConnection: NWConnection = connection
		var _newClientConnection: NWConnection? = nil
		var _proxyConnection: NWConnection? = nil
		
		let finishSubTask = {(newClientConnection: NWConnection?, proxyConnection: NWConnection?) in
			DispatchQueue.main.async {
				
				if let newClientConnection = newClientConnection {
					log.debug("finishSubTask: newClientConnection")
					_newClientConnection = newClientConnection
				}
				if let proxyConnection = proxyConnection {
					log.debug("finishSubTask: proxyConnection")
					_proxyConnection = proxyConnection
				}
				
				if let _newClientConnection = _newClientConnection,
				   let _proxyConnection = _proxyConnection
				{
					finish(.success(NativeSocket(
						host: oldServerName,
						connection: _newClientConnection,
						parentConnections: [_proxyConnection, oldClientConnection]
					)))
				}
			}
		}
		
		let startClientConnection = {(port: NWEndpoint.Port) in
			log.debug("startClientConnection(\(port.rawValue))")
			
			NativeSocket.rawConnect(
				host: "127.0.0.1",
				port: port.rawValue,
				tls: tls.makeProtocolOptions(overrideServerName: oldServerName)
			) { (connection: NWConnection) in
				log.debug("newOutgoingConnection.connect: success")
				finishSubTask(connection, nil)
			} failure: { (error: NWError) in
				log.debug("newOutgoingConnection.connect: error: \(error)")
				finish(.failure(error))
			}
		}
		
		let tcpOptions = NWProtocolTCP.Options()
		let params = NWParameters(tls: nil, tcp: tcpOptions)
		params.requiredInterfaceType = .loopback
		
		let listener: NWListener
		do {
			listener = try NWListener(using: params, on: .any)
		} catch {
			log.error("Error opening NWListener on loopback: \(error)")
			if let nwerr = error as? NWError {
				return finish(.failure(nwerr))
			} else {
				return finish(.failure(.posix(.EDEVERR)))
			}
		}
		
		listener.newConnectionLimit = 1
		listener.newConnectionHandler = {(proxyConnection: NWConnection) in
			
			proxyConnection.start(queue: DispatchQueue.global())
			NativeSocket.transferLoop(rcv: proxyConnection, snd: oldClientConnection, label: "->prxy->oldClnt")
			NativeSocket.transferLoop(rcv: oldClientConnection, snd: proxyConnection, label: "->oldClnt->prxy")
			
			finishSubTask(nil, proxyConnection)
		}
		
		listener.stateUpdateHandler = {(state: NWListener.State) in
			
			switch state {
			case .setup:
				log.debug("NWListener.state => setup")
			case .waiting(let error):
				switch error {
				case .posix(_):
					log.debug("NWListener.state => waiting: err(posix): \(error)")
				case .dns(_):
					log.debug("NWListener.state => waiting: err(dns): \(error)")
				case .tls(_):
					log.debug("NWListener.state => waiting: err(tls): \(error)")
				case .wifiAware(_):
					log.debug("NWListener.state => waiting: err(wifiAware): \(error)")
				@unknown default:
					log.debug("NWListener.state => waiting: err(unknown): \(error)")
				}
			case .ready:
				log.debug("NWListener.state => ready")
				if let port = listener.port {
					log.debug("NWListener.port => \(port.rawValue)")
					startClientConnection(port)
				} else {
					log.warning("NWListener.port => nil ?!?")
					listener.cancel()
				}
			case .failed(let nwerr):
				log.warning("NWListener.state => failed: \(nwerr)")
				finish(.failure(nwerr))
			case .cancelled:
				log.warning("NWListener.state => cancelled")
				finish(.failure(.posix(.ECANCELED)))
			@unknown default:
				log.error("NWListener.state => unknown")
			}
		}
		
		_listener = listener
		listener.start(queue: DispatchQueue.global(qos: .default))
	}
	
	private class func transferLoop(rcv: NWConnection, snd: NWConnection, label: String) {
		
		rcv.receive(minimumIncompleteLength: 1, maximumLength: 1024 * 8) {
			(content: Data?, contentContext: NWConnection.ContentContext?, isComplete: Bool, error: NWError?) in
			
			if let content = content {
				log.error("TransferLoop[\(label)]: receive.length=\(content.count))")
				
				snd.send(
					content: content,
					contentContext: .defaultStream,
					isComplete: false,
					completion: .contentProcessed({ (error: NWError?) in
						if let error = error {
							log.error("TransferLoop[\(label)]: send.error: \(error)")
						}
					})
				)
				transferLoop(rcv: rcv, snd: snd, label: label)
				
			} else if let error = error {
				
				log.error("TransferLoop[\(label)]: rcv.receive.error: \(error)")
				rcv.cancel()
			}
		}
	}
	
	@objc
	public func close() {
		connection.cancel()
		for parentConnection in parentConnections {
			parentConnection.cancel()
		}
	}
}

@objc
public class NativeSocketTLS: NSObject {
	
	@objc public let disabled: Bool
	@objc public let allowUnsafeCertificates: Bool
	@objc public let expectedHostName: String?
	@objc public let pinnedPublicKey: String?
	
	private init(
		disabled: Bool,
		allowUnsafeCertificates: Bool,
		expectedHostName: String?,
		pinnedPublicKey: String?
	) {
		self.disabled = disabled
		self.allowUnsafeCertificates = allowUnsafeCertificates
		self.expectedHostName = expectedHostName
		self.pinnedPublicKey = pinnedPublicKey
	}
	
	@objc
	public class func trustedCertificates(_ expectedHostName: String?) -> NativeSocketTLS {
		return NativeSocketTLS(
			disabled: false,
			allowUnsafeCertificates: false,
			expectedHostName: expectedHostName,
			pinnedPublicKey: nil
		)
	}
	
	@objc
	public class func pinnedPublicKey(_ str: String) -> NativeSocketTLS {
		return NativeSocketTLS(
			disabled: false,
			allowUnsafeCertificates: false,
			expectedHostName: nil,
			pinnedPublicKey: str
		)
	}
	
	@objc
	public class func allowUnsafeCertificates() -> NativeSocketTLS {
		return NativeSocketTLS(
			disabled: false,
			allowUnsafeCertificates: true,
			expectedHostName: nil,
			pinnedPublicKey: nil
		)
	}
	
	@objc
	public class func disabled() -> NativeSocketTLS {
		return NativeSocketTLS(
			disabled: true,
			allowUnsafeCertificates: false,
			expectedHostName: nil,
			pinnedPublicKey: nil
		)
	}
	
	public func makeProtocolOptions(overrideServerName: String? = nil) -> NWProtocolTLS.Options? {
		
		if let pinnedPubKeyStr = self.pinnedPublicKey {
			let tlsOptions = NWProtocolTLS.Options()
			
			let verify_queue = DispatchQueue.global()
			let verify_block: sec_protocol_verify_t = {(
				metadata   : sec_protocol_metadata_t,
				trust      : sec_trust_t,
				completion : @escaping sec_protocol_verify_complete_t
			) in
				
				let result = NativeSocketTLS.decodePublicKey(pinnedPubKeyStr)
				guard case .success(let pinnedPubKey) = result else {
					if case .failure(let error) = result {
						switch error {
						case .stringToBase64:
							log.warning("Unable to decode tls.pinnedPublicKey")
						case .dataToSecKey:
							log.warning("Unable to create pubKey using tls.pinnedPublicKey")
						}
					}
					return completion(false)
				}
				
				let serverPubKey: SecKey? = {
					
					let sec_trust = sec_trust_copy_ref(trust).takeRetainedValue()
					let serverCerts: [SecCertificate]
					if #available(iOS 15, macOS 12, tvOS 15, watchOS 8, *) {
						serverCerts = SecTrustCopyCertificateChain(sec_trust) as? [SecCertificate] ?? []
					} else {
						serverCerts = (0 ..< SecTrustGetCertificateCount(sec_trust)).compactMap { index in
							SecTrustGetCertificateAtIndex(sec_trust, index)
						}
					}
					if let serverCert = serverCerts.first {
						return SecCertificateCopyKey(serverCert)
					} else {
						return nil
					}
				}()
				
				if let serverPubKey = serverPubKey {
					if pinnedPubKey == serverPubKey {
						completion(true)
					} else {
						completion(false)
					}
				} else {
					completion(false)
				}
			}
			
			sec_protocol_options_set_verify_block(tlsOptions.securityProtocolOptions, verify_block, verify_queue)
			return tlsOptions
			
		} else if self.allowUnsafeCertificates {
			let tlsOptions = NWProtocolTLS.Options()
			
			let verify_queue = DispatchQueue.global()
			let verify_block: sec_protocol_verify_t = {(
				metadata   : sec_protocol_metadata_t,
				trust      : sec_trust_t,
				completion : @escaping sec_protocol_verify_complete_t
			) in
				completion(true)
			}
			
			sec_protocol_options_set_verify_block(tlsOptions.securityProtocolOptions, verify_block, verify_queue)
			return tlsOptions
			
		} else if self.disabled {
			return nil
			
		} else {
			let tlsOptions = NWProtocolTLS.Options()
			
			if let serverName = overrideServerName {
				sec_protocol_options_set_tls_server_name(tlsOptions.securityProtocolOptions, serverName)
			}
			return tlsOptions
		}
	}

	private class func decodePublicKey(_ pubKeyStr: String) -> Result<SecKey, DecodePubKeyError> {
		
		guard let pubKeyData = Data(base64Encoded: pubKeyStr, options: .ignoreUnknownCharacters) else {
			return .failure(.stringToBase64)
		}
		
		let tryCreateKey = {(attributes: [String:String]) -> Result<SecKey, Error> in
			
			var cfError: Unmanaged<CFError>? = nil
			let pubKey = SecKeyCreateWithData(pubKeyData as CFData, attributes as CFDictionary, &cfError)
			
			if let pubKey = pubKey {
				return .success(pubKey)
			} else if let cfError = cfError {
				let error = cfError.takeRetainedValue()
				return .failure(error)
			} else {
				fatalError("SecKeyCreateWithData() failed without error")
			}
		}
		
		let attributes_1: [String: String] = [
			kSecAttrKeyType  as String : kSecAttrKeyTypeRSA as String,
			kSecAttrKeyClass as String : kSecAttrKeyClassPublic as String
		]
		let attributes_2: [String: String] = [
			kSecAttrKeyType  as String : kSecAttrKeyTypeEC as String,
			kSecAttrKeyClass as String : kSecAttrKeyClassPublic as String
		]
		let attributes_3: [String: String] = [
			kSecAttrKeyType  as String : kSecAttrKeyTypeECSECPrimeRandom as String,
			kSecAttrKeyClass as String : kSecAttrKeyClassPublic as String
		]
		
		for attributes in [attributes_1, attributes_2, attributes_3] {
			
			let result = tryCreateKey(attributes)
			if case .success(let pubKey) = result {
				return .success(pubKey)
			}
		}
		
		return .failure(.dataToSecKey)
	}
}

/**
 * NWError is a native Swift enum, and cannot be used with obj-c compatibility layers.
 * There's an opque type that we could use (`nw_error_t`), but Apple doesn't make it publicly usable (AFAIK).
 * So we're converting it into an NSObject type.
 */
@objc
public class NativeSocketError: NSObject {
	
	@objc public let isPosixErrorCode: Bool
	@objc public let posixErrorCode: Int32
	
	@objc public let isDnsServiceErrorType: Bool
	@objc public let dnsServiceErrorType: Int32
	
	@objc public let isTlsStatus: Bool
	@objc public let tlsStatus: OSStatus
	
	@objc public let errorDescription: String
	
	init(posixErrorCode: POSIXErrorCode, description: String) {
		self.isPosixErrorCode = true
		self.isDnsServiceErrorType = false
		self.isTlsStatus = false
		
		self.posixErrorCode = posixErrorCode.rawValue
		self.dnsServiceErrorType = 0
		self.tlsStatus = 0
		
		self.errorDescription = description
	}
	
	init(dnsServiceErrorType: DNSServiceErrorType, description: String) {
		self.isPosixErrorCode = false
		self.isDnsServiceErrorType = true
		self.isTlsStatus = false
		
		self.posixErrorCode = 0
		self.dnsServiceErrorType = dnsServiceErrorType
		self.tlsStatus = 0
		
		self.errorDescription = description
	}
	
	init(tlsStatus: OSStatus, description: String) {
		self.isPosixErrorCode = false
		self.isDnsServiceErrorType = false
		self.isTlsStatus = true
		
		self.posixErrorCode = 0
		self.dnsServiceErrorType = 0
		self.tlsStatus = tlsStatus
		
		self.errorDescription = description
	}
}

extension NWError {
	
	func toNativeSocketError() -> NativeSocketError {
		switch self {
		case .posix(let code):
			return NativeSocketError(posixErrorCode: code, description: self.debugDescription)
		case .dns(let type):
			return NativeSocketError(dnsServiceErrorType: type, description: self.debugDescription)
		case .tls(let status):
			return NativeSocketError(tlsStatus: status, description: self.debugDescription)
		default:
			return NativeSocketError(posixErrorCode: POSIXErrorCode.ENOSYS, description: self.debugDescription)
		}
	}
}

