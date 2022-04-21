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
	
	private let connection: NWConnection
	
	private init(connection: NWConnection) {
		self.connection = connection
	}
	
	@objc
	public class func connect(
		host: String,
		port: UInt16,
		tls: NativeSocketTLS,
		success: @escaping (NativeSocket) -> Void,
		failure: @escaping (NativeSocketError) -> Void
	) -> Void {
		
		var _connection: NWConnection? = nil
		var completionCalled = false
		let finish = {(result: Result<NativeSocket, NWError>) in
			_connection?.stateUpdateHandler = nil
			DispatchQueue.main.async {
				guard !completionCalled else { return }
				completionCalled = true
				switch result {
				case .success(let socket):
					success(socket)
				case .failure(let error):
					failure(error.toNativeSocketError())
				}
			}
		}
		
		let ep_host = NWEndpoint.Host(host)
		guard let ep_port = NWEndpoint.Port(rawValue: port) else {
			return finish(.failure(.posix(.EINVAL)))
		}
		
		let tlsOptions: NWProtocolTLS.Options?
		let tcpOptions = NWProtocolTCP.Options()
		
		if let pinnedPubKeyStr = tls.pinnedPublicKey {
			tlsOptions = NWProtocolTLS.Options()
			
			let verify_queue = DispatchQueue.global()
			let verify_block: sec_protocol_verify_t = {(
				metadata   : sec_protocol_metadata_t,
				trust      : sec_trust_t,
				completion : @escaping sec_protocol_verify_complete_t
			) in
				
				let result = decodePublicKey(pinnedPubKeyStr)
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
			
			sec_protocol_options_set_verify_block(tlsOptions!.securityProtocolOptions, verify_block, verify_queue)
			
		} else if tls.allowUnsafeCertificates {
			tlsOptions = NWProtocolTLS.Options()
			
			let verify_queue = DispatchQueue.global()
			let verify_block: sec_protocol_verify_t = {(
				metadata   : sec_protocol_metadata_t,
				trust      : sec_trust_t,
				completion : @escaping sec_protocol_verify_complete_t
			) in
				completion(true)
			}
			
			sec_protocol_options_set_verify_block(tlsOptions!.securityProtocolOptions, verify_block, verify_queue)
			
		} else if tls.disabled {
			tlsOptions = nil
			
		} else {
			tlsOptions = NWProtocolTLS.Options()
		}
		
		let options = NWParameters(tls: tlsOptions, tcp: tcpOptions)
		let connection = NWConnection(host: ep_host, port: ep_port, using: options)
		
		connection.stateUpdateHandler = {(state: NWConnection.State) in
			
			switch state {
				case .failed(let error):
					finish(.failure(error))
					connection.cancel()
				case .ready:
					finish(.success(NativeSocket(connection: connection)))
				case .cancelled:
					finish(.failure(.posix(.ECANCELED)))
				default:
					break
			}
			
		} // </stateUpdateHandler>
		
		_connection = connection
		connection.start(queue: DispatchQueue.global(qos: .userInitiated))
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
	public func close() {
		connection.cancel()
	}
}

@objc
public class NativeSocketTLS: NSObject {
	
	@objc public let disabled: Bool
	@objc public let allowUnsafeCertificates: Bool
	@objc public let pinnedPublicKey: String?
	
	private init(disabled: Bool, allowUnsafeCertificates: Bool, pinnedPublicKey: String?) {
		self.disabled = disabled
		self.allowUnsafeCertificates = allowUnsafeCertificates
		self.pinnedPublicKey = pinnedPublicKey
	}
	
	@objc
	public class func trustedCertificates() -> NativeSocketTLS {
		return NativeSocketTLS(disabled: false, allowUnsafeCertificates: false, pinnedPublicKey: nil)
	}
	
	@objc
	public class func pinnedPublicKey(_ str: String) -> NativeSocketTLS {
		return NativeSocketTLS(disabled: false, allowUnsafeCertificates: false, pinnedPublicKey: str)
	}
	
	@objc
	public class func allowUnsafeCertificates() -> NativeSocketTLS {
		return NativeSocketTLS(disabled: false, allowUnsafeCertificates: true, pinnedPublicKey: nil)
	}
	
	@objc
	public class func disabled() -> NativeSocketTLS {
		return NativeSocketTLS(disabled: true, allowUnsafeCertificates: false, pinnedPublicKey: nil)
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
