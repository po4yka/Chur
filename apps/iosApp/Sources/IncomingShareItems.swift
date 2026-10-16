import CryptoKit
import Foundation

/// Preserve source captions as well as attachments. Cap validation happens
/// after collection, so captions cannot bypass the per-request item bound.
enum IncomingShareItems {
    static func providers(from items: [NSExtensionItem]) -> [NSItemProvider] {
        items.flatMap { item in
            var providers = item.attachments ?? []
            if let text = item.attributedContentText?.string, !text.isEmpty {
                providers.append(NSItemProvider(object: text as NSString))
            }
            return providers
        }
    }
}

/// Batch-local exact text deduplication. Keep only digests, never 64 text bodies.
final class IncomingShareTextSet: @unchecked Sendable {
    private let lock = NSLock()
    private var digests = Set<SHA256.Digest>()
    func insert(_ text: String) -> Bool {
        var bytes = Data(text.utf8)
        defer { bytes.resetBytes(in: 0..<bytes.count) }
        let digest = SHA256.hash(data: bytes)
        lock.lock(); defer { lock.unlock() }
        return digests.insert(digest).inserted
    }
}
