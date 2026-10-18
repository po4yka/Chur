import Foundation
import CryptoKit
import UniformTypeIdentifiers

/// The byte-preserving resource table in LIVE_PHOTO_BUNDLE_V1.md.
struct LivePhotoBundleFile {
    static let contentType = "application/vnd.chur.live-photo"
    static let maxBytes: UInt64 = 2_147_483_648
    struct Resource {
        let type: UInt32
        var length: UInt64
        var digest: Data
        let filename: String
        let uti: String
        let mime: String
        var offset: UInt64 = 0
    }
    let captureTime: Int64
    var resources: [Resource]
    enum Failure: Error, Equatable { case invalid, limit, cancelled, changed, unavailable, unsupported }

    func header() throws -> Data {
        guard captureTime >= -1, (2...32).contains(resources.count), resources.filter({ $0.type == 1 }).count == 1,
              resources.filter({ $0.type == 9 }).count == 1 else { throw Failure.invalid }
        var table = Data()
        for r in resources {
            let strings = [Data(r.filename.utf8), Data(r.uti.utf8), Data(r.mime.utf8)]
            guard r.type > 0, r.type <= Int32.max, r.digest.count == 32,
                  (1...1024).contains(strings[0].count), (1...255).contains(strings[1].count),
                  (3...63).contains(strings[2].count), !strings.contains(where: { $0.contains(0) }),
                  Self.validMime(r.mime), r.length > 0 || (r.type != 1 && r.type != 9) else { throw Failure.invalid }
            table.appendBE(r.type); table.appendBE(r.length); table.append(r.digest)
            for s in strings { table.appendBE(UInt16(s.count)) }
            for s in strings { table.append(s) }
        }
        guard table.count + 24 <= 65_536 else { throw Failure.limit }
        var result = Data("CHURLIVE".utf8)
        result.appendBE(UInt16(1)); result.appendBE(UInt16(resources.count))
        result.appendBE(UInt32(table.count + 24)); result.appendBE(UInt64(bitPattern: captureTime)); result.append(table)
        return result
    }

    static func read(_ url: URL, verify: Bool = true, shouldContinue: () -> Bool = { true }) throws -> LivePhotoBundleFile {
        let input = try FileHandle(forReadingFrom: url); defer { try? input.close() }
        let total = try input.seekToEnd(); try input.seek(toOffset: 0)
        guard total <= maxBytes, total >= 24, let fixed = try input.read(upToCount: 24), fixed.count == 24,
              fixed.prefix(8) == Data("CHURLIVE".utf8), fixed.be(8, 2) == 1 else { throw Failure.invalid }
        let count = Int(fixed.be(10, 2)), headerLength = Int(fixed.be(12, 4))
        guard (2...32).contains(count), (24...65_536).contains(headerLength), UInt64(headerLength) <= total,
              let table = try input.read(upToCount: headerLength - 24), table.count == headerLength - 24 else { throw Failure.invalid }
        var cursor = 0, offset = UInt64(headerLength), resources: [Resource] = []
        for _ in 0..<count {
            guard cursor + 50 <= table.count else { throw Failure.invalid }
            let type = UInt32(table.be(cursor, 4)), length = table.be(cursor + 4, 8)
            let digest = table.subdata(in: cursor + 12..<cursor + 44)
            let sizes = [Int(table.be(cursor + 44, 2)), Int(table.be(cursor + 46, 2)), Int(table.be(cursor + 48, 2))]
            cursor += 50
            var strings: [String] = []
            for size in sizes {
                guard size <= table.count - cursor, let s = String(data: table.subdata(in: cursor..<cursor + size), encoding: .utf8) else { throw Failure.invalid }
                strings.append(s); cursor += size
            }
            guard length <= total - offset else { throw Failure.invalid }
            resources.append(Resource(type: type, length: length, digest: digest, filename: strings[0], uti: strings[1], mime: strings[2], offset: offset))
            offset += length
        }
        let bundle = LivePhotoBundleFile(captureTime: Int64(bitPattern: fixed.be(16, 8)), resources: resources)
        guard cursor == table.count, offset == total, try bundle.header().count == headerLength else { throw Failure.invalid }
        if verify {
            for r in resources {
                var sha = SHA256(), left = r.length
                while left > 0 {
                    guard shouldContinue() else { throw Failure.cancelled }
                    guard let chunk = try input.read(upToCount: Int(min(left, 65_536))), !chunk.isEmpty else { throw Failure.invalid }
                    sha.update(data: chunk); left -= UInt64(chunk.count)
                }
                guard Data(sha.finalize()) == r.digest else { throw Failure.invalid }
            }
        }
        return bundle
    }

    func extract(_ source: URL, into folder: URL, shouldContinue: () -> Bool = { true }) throws -> [URL] {
        let input = try FileHandle(forReadingFrom: source); defer { try? input.close() }
        var output: [URL] = []
        do {
            for r in resources {
                // Original names remain metadata. They never become filesystem paths.
                let ext = UTType(r.uti)?.preferredFilenameExtension ?? "bin"
                let suffix = ext.range(of: "^[A-Za-z0-9]{1,10}$", options: .regularExpression) == nil ? "bin" : ext
                let url = folder.appendingPathComponent(UUID().uuidString).appendingPathExtension(suffix)
                guard FileManager.default.createFile(atPath: url.path, contents: nil, attributes: [.protectionKey: FileProtectionType.complete, .posixPermissions: 0o600]) else { throw Failure.unavailable }
                output.append(url)
                let sink = try FileHandle(forWritingTo: url); defer { try? sink.close() }
                try input.seek(toOffset: r.offset)
                var sha = SHA256(), left = r.length
                while left > 0 {
                    guard shouldContinue() else { throw Failure.cancelled }
                    guard let chunk = try input.read(upToCount: Int(min(left, 65_536))), !chunk.isEmpty else { throw Failure.invalid }
                    sha.update(data: chunk); try sink.write(contentsOf: chunk); left -= UInt64(chunk.count)
                }
                guard Data(sha.finalize()) == r.digest else { throw Failure.invalid }
            }
            return output
        } catch {
            output.forEach { try? FileManager.default.removeItem(at: $0) }; throw error
        }
    }

    private static func validMime(_ value: String) -> Bool {
        let parts = value.split(separator: "/", omittingEmptySubsequences: false)
        return parts.count == 2 && parts.allSatisfy { !$0.isEmpty && $0.utf8.allSatisfy { (97...122).contains($0) || (48...57).contains($0) || Array("!#$&^_.+-".utf8).contains($0) } }
    }
}

private extension Data {
    mutating func appendBE<T: FixedWidthInteger>(_ value: T) { var v = value.bigEndian; Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) } }
    func be(_ start: Int, _ count: Int) -> UInt64 { self[start..<start + count].reduce(0) { ($0 << 8) | UInt64($1) } }
}
