import Foundation
import XCTest
@testable import ChurScratch

final class PublicCameraTests: XCTestCase {
    private var directory: URL!
    private var source: URL { directory.appendingPathComponent("capture.jpg") }
    private var root: URL { directory.appendingPathComponent("public/camera", isDirectory: true) }
    private var jpeg: Data {
        get throws {
            let asset = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
                .appendingPathComponent("../../androidApp/src/androidTest/assets/audio-art/cover.jpg").standardizedFileURL
            return try Data(contentsOf: asset)
        }
    }

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: directory) }

    func testNewCaptureFinalizationPreservesLegacyAlbumAndHasNoCountQuota() throws {
        let album = PublicCameraAlbum(root: root)
        try album.prepare()
        try jpeg.write(to: source)
        let oldID = try album.save(source)
        let bytes = try jpeg
        for _ in 0..<101 {
            let pending = try album.pendingMedia(video: false)
            try bytes.write(to: pending)
            XCTAssertFalse(try album.mediaEntries().contains { $0.id == pending.lastPathComponent })
            try album.commitMedia(pending)
            XCTAssertFalse(FileManager.default.fileExists(atPath: pending.path))
        }
        let reopened = PublicCameraAlbum(root: root)
        XCTAssertEqual(try reopened.mediaEntries().count, 102)
        XCTAssertEqual(try Data(contentsOf: reopened.mediaURL(oldID + ".jpg")), bytes)
        let invalid = try album.pendingMedia(video: false)
        try Data("invalid".utf8).write(to: invalid)
        XCTAssertThrowsError(try album.commitMedia(invalid))
        XCTAssertTrue(FileManager.default.fileExists(atPath: invalid.path))
        XCTAssertEqual(try reopened.mediaEntries().count, 102)
        XCTAssertThrowsError(try album.mediaURL("../capture.jpg"))
    }

    func testOnlyOwnRandomScratchJpegCanBeDeleted() throws {
        let files = PublicCameraFiles(root: directory)
        let photo = files.photo(id: UUID())
        let review = files.preview(for: photo)
        let unrelated = directory.appendingPathComponent("unrelated.jpg")
        try Data([1]).write(to: photo)
        try Data([2]).write(to: review)
        try Data([3]).write(to: unrelated)
        XCTAssertTrue(files.owns(photo))
        XCTAssertFalse(files.owns(review))
        XCTAssertFalse(files.owns(unrelated))
        XCTAssertFalse(files.owns(directory.appendingPathComponent("../" + photo.lastPathComponent)))
        files.discard(unrelated)
        XCTAssertTrue(FileManager.default.fileExists(atPath: unrelated.path))
        files.discard(photo)
        XCTAssertFalse(FileManager.default.fileExists(atPath: photo.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: review.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: unrelated.path))
    }

    func testAlbumPersistsListsReopensAndDeletesExactJpeg() throws {
        let bytes = try jpeg
        try bytes.write(to: source)
        let album = PublicCameraAlbum(root: root)
        let id = try album.save(source)
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
        let reopened = PublicCameraAlbum(root: root)
        XCTAssertEqual(try reopened.entries().map(\.id), [id])
        XCTAssertEqual(try reopened.read(id), bytes)
        let attributes = try FileManager.default.attributesOfItem(atPath: root.appendingPathComponent(id + ".jpg").path)
        XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        try reopened.delete(id)
        XCTAssertTrue(try reopened.entries().isEmpty)
    }

    func testCountQuotaRetainsCaptureAndPreviousPhoto() throws {
        try jpeg.write(to: source)
        let album = PublicCameraAlbum(root: root, maxCount: 1)
        let id = try album.save(source)
        XCTAssertThrowsError(try album.save(source)) { XCTAssertEqual($0 as? PublicCameraAlbumError, .full) }
        XCTAssertEqual(try album.entries().map(\.id), [id])
        XCTAssertEqual(try album.read(id), try jpeg)
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
    }

    func testByteQuotaRejectsWithoutPartialOrReplacingOldPhoto() throws {
        let bytes = try jpeg
        try bytes.write(to: source)
        let album = PublicCameraAlbum(root: root, maxBytes: Int64(bytes.count * 2 - 1))
        let id = try album.save(source)
        XCTAssertThrowsError(try album.save(source)) { XCTAssertEqual($0 as? PublicCameraAlbumError, .full) }
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path), [id + ".jpg"])
        XCTAssertEqual(try album.read(id), bytes)
    }

    func testInvalidAndOversizedCaptureAreNotSaved() throws {
        let album = PublicCameraAlbum(root: root)
        try Data([1, 2, 3]).write(to: source)
        XCTAssertThrowsError(try album.save(source)) { XCTAssertEqual($0 as? PublicCameraAlbumError, .invalidPhoto) }
        try Data(repeating: 0, count: PublicCameraFiles.photoLimit + 1).write(to: source)
        XCTAssertThrowsError(try album.save(source)) { XCTAssertEqual($0 as? PublicCameraAlbumError, .invalidPhoto) }
        XCTAssertTrue(try album.entries().isEmpty)
    }

    func testTraversalAndSymlinkCannotReadOrDeleteOutsideAlbum() throws {
        let bytes = try jpeg
        try bytes.write(to: source)
        let album = PublicCameraAlbum(root: root)
        try album.prepare()
        XCTAssertThrowsError(try album.read("../../capture"))
        XCTAssertThrowsError(try album.delete("../../capture"))
        let id = UUID().uuidString
        let link = root.appendingPathComponent(id + ".jpg")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: source)
        XCTAssertThrowsError(try album.read(id))
        XCTAssertThrowsError(try album.delete(id))
        XCTAssertTrue(try album.entries().isEmpty)
        XCTAssertEqual(try Data(contentsOf: source), bytes)
    }

    func testInitialPublicParentSymlinkCannotCreateOrChangeOutsideAlbum() throws {
        try jpeg.write(to: source)
        let outside = directory.appendingPathComponent("chur", isDirectory: true)
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        let marker = outside.appendingPathComponent("private-marker")
        let bytes = Data([8, 9, 10])
        try bytes.write(to: marker)
        try FileManager.default.createSymbolicLink(at: root.deletingLastPathComponent(), withDestinationURL: outside)
        let album = PublicCameraAlbum(root: root)
        XCTAssertThrowsError(try album.prepare())
        XCTAssertThrowsError(try album.save(source))
        XCTAssertThrowsError(try album.entries())
        XCTAssertThrowsError(try album.delete(UUID().uuidString))
        XCTAssertEqual(try Data(contentsOf: marker), bytes)
        XCTAssertFalse(FileManager.default.fileExists(atPath: outside.appendingPathComponent("camera").path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: outside.path), ["private-marker"])
    }

    func testInitialAlbumSymlinkIsRejectedWithoutChangingItsTarget() throws {
        try jpeg.write(to: source)
        let outside = directory.appendingPathComponent("outside", isDirectory: true)
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: root.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(at: root, withDestinationURL: outside)
        let album = PublicCameraAlbum(root: root)
        XCTAssertThrowsError(try album.prepare())
        XCTAssertThrowsError(try album.save(source))
        XCTAssertThrowsError(try album.entries())
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: outside.path).isEmpty)
    }

    func testReplacedAlbumDirectoryCannotWriteOutsideItsRoot() throws {
        try jpeg.write(to: source)
        let album = PublicCameraAlbum(root: root)
        try album.prepare()
        try FileManager.default.removeItem(at: root)
        let elsewhere = directory.appendingPathComponent("outside", isDirectory: true)
        try FileManager.default.createDirectory(at: elsewhere, withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(at: root, withDestinationURL: elsewhere)
        XCTAssertThrowsError(try album.save(source))
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: elsewhere.path).isEmpty)
    }
}
