import Foundation
import CryptoKit
import os.log

fileprivate var log = Logger(
	subsystem: Bundle.main.bundleIdentifier!,
	category: "NativeWeakRandom"
)

@objc
public class NativeWeakRandom: NSObject {
	
	@objc
	public class func gen32() -> Data {
		
		// Gameplan:
		// - get a bunch of UInt64 values
		// - xor them all together
		// - hash it & return the result (32 bytes)
		
		// Get the current time (as a UInt64 value)
		
		let now_dbl: Double = Date().timeIntervalSince1970
		let now: UInt64 = withUnsafePointer(to: now_dbl) { (ptr: UnsafePointer<Double>) -> UInt64 in
			ptr.withMemoryRebound(to: UInt64.self, capacity: 1) { (ptr: UnsafePointer<UInt64>) -> UInt64 in
				ptr.pointee
			}
		}
		
		// Get the process ID
		
		let pid = UInt64(getpid())
		
		// Get a bunch of info about the current proceess
		
		let usage = rusage_info_current()
		
		let uuid = usage.ri_uuid
		
		let uuid_array_a : [UInt8] = [uuid.0, uuid.1, uuid.2, uuid.3, uuid.4, uuid.5, uuid.6, uuid.7]
		let uuid_array_b : [UInt8] = [uuid.8, uuid.9, uuid.10, uuid.11, uuid.12, uuid.13, uuid.14, uuid.15]
		
		var uuid_a: UInt64 = 0
		for byte in uuid_array_a {
			uuid_a = uuid_a << 8
			uuid_a = uuid_a | UInt64(byte)
		}
		
		var uuid_b: UInt64 = 0
		for byte in uuid_array_b {
			uuid_b = uuid_b << 8
			uuid_b = uuid_b | UInt64(byte)
		}
		
		// Xor all the stuff together
		
		var mixed: UInt64 = 0
		
		mixed ^= now
		mixed ^= pid
		mixed ^= uuid_a
		mixed ^= uuid_b
		mixed ^= usage.ri_user_time
		mixed ^= usage.ri_system_time
		mixed ^= usage.ri_pkg_idle_wkups
		mixed ^= usage.ri_interrupt_wkups
		mixed ^= usage.ri_pageins
		mixed ^= usage.ri_wired_size
		mixed ^= usage.ri_resident_size
		mixed ^= usage.ri_phys_footprint
		mixed ^= usage.ri_proc_start_abstime
		mixed ^= usage.ri_proc_exit_abstime
		mixed ^= usage.ri_child_user_time
		mixed ^= usage.ri_child_system_time
		mixed ^= usage.ri_child_pkg_idle_wkups
		mixed ^= usage.ri_child_interrupt_wkups
		mixed ^= usage.ri_child_pageins
		mixed ^= usage.ri_child_elapsed_abstime
		mixed ^= usage.ri_diskio_bytesread
		mixed ^= usage.ri_diskio_byteswritten
		mixed ^= usage.ri_cpu_time_qos_default
		mixed ^= usage.ri_cpu_time_qos_maintenance
		mixed ^= usage.ri_cpu_time_qos_background
		mixed ^= usage.ri_cpu_time_qos_utility
		mixed ^= usage.ri_cpu_time_qos_legacy
		mixed ^= usage.ri_cpu_time_qos_user_initiated
		mixed ^= usage.ri_cpu_time_qos_user_interactive
		mixed ^= usage.ri_billed_system_time
		mixed ^= usage.ri_serviced_system_time
		mixed ^= usage.ri_logical_writes
		mixed ^= usage.ri_lifetime_max_phys_footprint
		mixed ^= usage.ri_instructions
		mixed ^= usage.ri_cycles
		mixed ^= usage.ri_billed_energy
		mixed ^= usage.ri_serviced_energy
		mixed ^= usage.ri_interval_max_phys_footprint
		mixed ^= usage.ri_runnable_time
		
		let mixedData = Data(bytes: &mixed, count: MemoryLayout.size(ofValue: mixed))
		let hashedData = SHA256.hash(data: mixedData)
		
		return Data(hashedData)
	}
}
