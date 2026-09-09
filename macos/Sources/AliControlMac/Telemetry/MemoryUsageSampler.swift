import Darwin
import Foundation

/// Reads physical memory usage through `host_statistics64(HOST_VM_INFO64)` —
/// again a native Mach call, not a subprocess.
///
/// ## What "used" means here
///
/// This deliberately mirrors what Activity Monitor labels **Memory Used**, so
/// the number on the tablet matches what the user can verify on their own
/// screen:
///
/// ```text
/// used  = app memory + wired + compressed
///       = (internal_page_count - purgeable_count)   // app memory
///       + wire_count                                // wired (unswappable)
///       + compressor_page_count                     // compressed
/// usedBytes    = used × vm_page_size
/// percent      = usedBytes / ProcessInfo.physicalMemory × 100
/// ```
///
/// Notes on the choices:
///
/// - **Purgeable pages are subtracted** because the kernel can reclaim them
///   on demand; counting them would inflate the number against memory that
///   isn't really committed.
/// - **File-backed cache (`external_page_count`) is excluded.** macOS
///   deliberately fills spare RAM with cache, so including it would pin the
///   reading near 100% on every healthy machine and tell the user nothing.
/// - **`compressor_page_count` is the compressed pool's real footprint**, not
///   the logical size of what's inside it — that's why it, and not
///   `total_uncompressed_pages_in_compressor`, is the term that belongs in a
///   physical-usage figure.
/// - This is a **usage** figure, not memory *pressure*. Pressure is a
///   different kernel signal with different semantics, and presenting one as
///   the other would be misleading (TASK-009 calls this out explicitly).
final class MemoryUsageSampler {

    private let physicalMemoryBytes: UInt64

    init(physicalMemoryBytes: UInt64 = ProcessInfo.processInfo.physicalMemory) {
        self.physicalMemoryBytes = physicalMemoryBytes
    }

    func sample() -> Double? {
        guard let statistics = Self.readStatistics() else { return nil }
        return Self.usagePercent(
            internalPages: UInt64(statistics.internal_page_count),
            purgeablePages: UInt64(statistics.purgeable_count),
            wiredPages: UInt64(statistics.wire_count),
            compressedPages: UInt64(statistics.compressor_page_count),
            pageSizeBytes: UInt64(vm_kernel_page_size),
            physicalMemoryBytes: physicalMemoryBytes
        )
    }

    /// Pure: the documented used/total calculation, so the arithmetic can be
    /// verified against known page counts without a real kernel.
    static func usagePercent(
        internalPages: UInt64,
        purgeablePages: UInt64,
        wiredPages: UInt64,
        compressedPages: UInt64,
        pageSizeBytes: UInt64,
        physicalMemoryBytes: UInt64
    ) -> Double? {
        guard physicalMemoryBytes > 0, pageSizeBytes > 0 else { return nil }
        // Purgeable is a subset of internal; guard the subtraction rather
        // than trusting two counters sampled microseconds apart.
        let appPages = internalPages >= purgeablePages ? internalPages - purgeablePages : 0
        let usedPages = appPages &+ wiredPages &+ compressedPages
        let usedBytes = Double(usedPages) * Double(pageSizeBytes)
        let percent = (usedBytes / Double(physicalMemoryBytes)) * 100
        return percent.clampedPercent()
    }

    private static func readStatistics() -> vm_statistics64_data_t? {
        var statistics = vm_statistics64_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<vm_statistics64_data_t>.stride / MemoryLayout<integer_t>.stride)
        let result = withUnsafeMutablePointer(to: &statistics) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { rebound in
                host_statistics64(mach_host_self(), HOST_VM_INFO64, rebound, &count)
            }
        }
        guard result == KERN_SUCCESS else { return nil }
        return statistics
    }
}
