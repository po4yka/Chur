import Foundation
import XCTest
import CryptoKit
@testable import ChurScratch

final class LivePhotoBundleTests: XCTestCase {
    private var golden: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("tests/media/live-photo-v1")
    }
    private func complete() throws -> Data {
        try Data(contentsOf: golden.appendingPathComponent("header.bin")) + Data(contentsOf: golden.appendingPathComponent("payload.bin"))
    }
    private func temporary(_ body: (URL) throws -> Void) throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        try body(folder)
    }
    func testSharedGoldenEncoderAndDigestsMatch() throws {
        try temporary { folder in
            let url = folder.appendingPathComponent("bundle.churlive"); try complete().write(to: url)
            let bundle = try LivePhotoBundleFile.read(url)
            XCTAssertEqual(bundle.captureTime, 1_700_000_123_456)
            XCTAssertEqual(bundle.resources.map(\.type), [1, 9, 42])
            XCTAssertEqual(bundle.resources[0].filename, "снимок.JPG")
            XCTAssertEqual(try bundle.header(), try Data(contentsOf: golden.appendingPathComponent("header.bin")))
            let extracted = try bundle.extract(url, into: folder)
            XCTAssertEqual(extracted.count, 3)
            for (r, file) in zip(bundle.resources, extracted) {
                let mode = try FileManager.default.attributesOfItem(atPath: file.path)[.posixPermissions] as? NSNumber
                XCTAssertEqual(mode?.intValue, 0o600)
                XCTAssertEqual(Data(SHA256.hash(data: try Data(contentsOf: file))), r.digest)
            }
        }
    }
    func testChecksumMismatchTruncationAndTrailingBytesFail() throws {
        try temporary { folder in
            let original = try complete(), url = folder.appendingPathComponent("bundle")
            var corrupt = original; corrupt[corrupt.count - 1] ^= 1
            for bytes in [corrupt, original.dropLast(), original + Data([0])] {
                try Data(bytes).write(to: url)
                XCTAssertThrowsError(try LivePhotoBundleFile.read(url))
            }
        }
    }
    func testInvalidRoleAndMissingPairFail() throws {
        try temporary { folder in
            var bytes = try complete(); bytes[24] = 0xff
            let url = folder.appendingPathComponent("bundle"); try bytes.write(to: url)
            XCTAssertThrowsError(try LivePhotoBundleFile.read(url))
            let bundle = LivePhotoBundleFile(captureTime: -1, resources: [
                .init(type: 1, length: 1, digest: Data(repeating: 0, count: 32), filename: "x", uti: "public.jpeg", mime: "image/jpeg"),
                .init(type: 42, length: 1, digest: Data(repeating: 0, count: 32), filename: "y", uti: "public.data", mime: "application/octet-stream")])
            XCTAssertThrowsError(try bundle.header())
        }
    }
    func testCancelledExtractionRemovesAllPartialResources() throws {
        try temporary { folder in
            let url = folder.appendingPathComponent("bundle"); try complete().write(to: url)
            let bundle = try LivePhotoBundleFile.read(url)
            var checks = 0
            XCTAssertThrowsError(try bundle.extract(url, into: folder, shouldContinue: { checks += 1; return checks < 2 }))
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: folder.path), ["bundle"])
        }
    }
    func testCaptureTimeBelowAbsentSentinelFails() throws {
        try temporary { folder in
            var bytes = try complete()
            for index in 16..<24 { bytes[index] = 0xff }; bytes[23] = 0xfe
            let url = folder.appendingPathComponent("bundle"); try bytes.write(to: url)
            XCTAssertThrowsError(try LivePhotoBundleFile.read(url))
        }
    }
    func testInvalidUtf8AndNulAreRejected() throws {
        try temporary { folder in
            let original = try complete(), url = folder.appendingPathComponent("bundle")
            for replacement: UInt8 in [0, 0xff] {
                var bytes = original; bytes[74] = replacement; try bytes.write(to: url)
                XCTAssertThrowsError(try LivePhotoBundleFile.read(url))
            }
        }
    }
}
