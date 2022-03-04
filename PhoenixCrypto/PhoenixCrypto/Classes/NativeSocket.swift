import Foundation
import Network

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
		
		if tls.disabled {
			tlsOptions = nil
			
		} else if tls.allowUntrustedCerts {
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
	@objc public let allowUntrustedCerts: Bool
	
	@objc
	init(disabled: Bool, allowUntrustedCerts: Bool) {
		self.disabled = disabled
		self.allowUntrustedCerts = allowUntrustedCerts
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
