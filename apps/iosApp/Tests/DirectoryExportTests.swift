import Darwin
import Foundation
import XCTest
@testable import ChurScratch

final class DirectoryExportTests: XCTestCase {
    private var root: URL!
    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: root) }

    private func session(stop: @escaping () -> Void = {}) throws -> DirectoryExportSession {
        try XCTUnwrap(DirectoryExportSession(url: root, startAccess: { true }, stopAccess: stop))
    }

    func testCreatesNamedHierarchyAndNeverOverwritesExistingFiles() throws {
        try Data([9]).write(to: root.appendingPathComponent("photo.jpg"))
        let grant = try session()
        let directory = try XCTUnwrap(grant.directory)
        let file = try XCTUnwrap(directory.createFile(displayName: "photo.jpg", plaintextSize: 3))
        let bytes: [UInt8] = [1, 2, 3]
        XCTAssertEqual(bytes.withUnsafeBytes { Darwin.write(file.descriptor, $0.baseAddress, $0.count) }, 3)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("photo (2).jpg").path))
        XCTAssertTrue(file.publishChecked())
        file.close()
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("photo.jpg")), Data([9]))
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("photo (2).jpg")), Data(bytes))
        let album = try XCTUnwrap(directory.createDirectory(displayName: "Album"))
        let child = try XCTUnwrap(album.createDirectory(displayName: "Child"))
        child.close(); album.close(); directory.close()
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("Album/Child").path))
    }

    func testFailedNativeExportRemovesOnlyTheIncompleteOutput() throws {
        let grant = try session()
        let directory = try XCTUnwrap(grant.directory)
        let file = try XCTUnwrap(directory.createFile(displayName: "secret.jpg", plaintextSize: 2))
        let entries = try FileManager.default.contentsOfDirectory(atPath: root.path)
        XCTAssertEqual(entries.count, 1)
        XCTAssertTrue(entries[0].hasPrefix(".chur-partial-"))
        file.discard(); file.close(); directory.close()
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path), [])
    }

    func testSessionCancellationKeepsScopeUntilNativeWriterClosesItsLease() throws {
        var stops = 0
        let grant = try session { stops += 1 }
        let directory = try XCTUnwrap(grant.directory)
        let child = try XCTUnwrap(directory.createDirectory(displayName: "Child"))
        let file = try XCTUnwrap(child.createFile(displayName: "unfinished", plaintextSize: 10))
        child.close()
        XCTAssertEqual(stops, 0)
        grant.close()
        XCTAssertEqual(stops, 0)
        XCTAssertGreaterThanOrEqual(file.descriptor, 0)
        XCTAssertFalse(file.publishChecked())
        file.discard(); file.close()
        XCTAssertEqual(stops, 1)
        XCTAssertEqual(file.descriptor, -1)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("Child").path), [])
        directory.close(); grant.close(); file.close()
        XCTAssertEqual(stops, 1)
        XCTAssertNil(directory.createDirectory(displayName: "after-close"))
    }

    func testCancellingBeforeFileProviderGrantsFileCreationReturnsWithoutWaitingForProvider() throws {
        try assertCancellationBeforeCoordination(file: true)
    }

    func testCancellingBeforeFileProviderGrantsDirectoryCreationReturnsWithoutWaitingForProvider() throws {
        try assertCancellationBeforeCoordination(file: false)
    }

    private func assertCancellationBeforeCoordination(file: Bool) throws {
        let released = expectation(description: "scope drained")
        let grant = try session { released.fulfill() }
        let directory = try XCTUnwrap(grant.directory)
        let presenter = HeldDirectoryPresenter(root)
        NSFileCoordinator.addFilePresenter(presenter)
        defer { presenter.resume.signal(); NSFileCoordinator.removeFilePresenter(presenter) }
        let returned = expectation(description: "cancelled creation returned")
        DispatchQueue.global(qos: .userInitiated).async {
            if file { XCTAssertNil(directory.createFile(displayName: "fixture.txt", plaintextSize: 3)) }
            else { XCTAssertNil(directory.createDirectory(displayName: "Album")) }
            returned.fulfill()
        }
        XCTAssertEqual(presenter.requested.wait(timeout: .now() + 3), .success)
        // The provider still withholds its coordinated accessor. Closing the
        // session must return the application worker before it cooperates.
        grant.close()
        wait(for: [returned], timeout: 1)
        presenter.resume.signal()
        wait(for: [released], timeout: 3)
        directory.close()
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path), [])
    }

    func testUntrustedNamesStayInsideTheSelectedDirectory() throws {
        let grant = try session()
        let directory = try XCTUnwrap(grant.directory)
        for name in ["..", ".", "", "../outside", "a/b\\c:d\u{0000}"] {
            let safe = DirectoryExportNode.safeName(name)
            XCTAssertFalse(safe.contains("/")); XCTAssertFalse(safe.contains("\\"))
            XCTAssertFalse(safe.contains(":")); XCTAssertFalse(safe.contains("\u{0000}"))
            XCTAssertNotEqual(safe, "."); XCTAssertNotEqual(safe, "..")
            XCTAssertNotNil(directory.createDirectory(displayName: name))
        }
        let existing = root.appendingPathComponent("Album")
        try FileManager.default.createDirectory(at: existing, withIntermediateDirectories: false)
        XCTAssertNotNil(directory.createDirectory(displayName: "Album"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("Album (2)").path))
        directory.close()
    }

    func testLargeAnnouncedSizeNeedsNoScratchAllocationAndPublishedCopySurvivesLock() throws {
        let grant = try session()
        let directory = try XCTUnwrap(grant.directory)
        let file = try XCTUnwrap(directory.createFile(displayName: "large.mov", plaintextSize: 5 * 1024 * 1024 * 1024))
        // Reserving a descriptor does not buffer the announced original size.
        XCTAssertTrue(file.publishChecked())
        grant.close()
        file.close()
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("large.mov").path))
        directory.close()
    }

    func testUnicodeNamesHaveABoundedUTF8Component() throws {
        let name = String(repeating: "📷", count: 180)
        XCTAssertLessThanOrEqual(DirectoryExportNode.safeName(name).utf8.count, 180)
        let grant = try session()
        let directory = try XCTUnwrap(grant.directory)
        XCTAssertNotNil(directory.createDirectory(displayName: name))
        directory.close()
    }

    func testDeniedGrantAndNonDirectoryReturnNoCapability() throws {
        XCTAssertNil(DirectoryExportSession(url: root, startAccess: { false }, stopAccess: { XCTFail("No grant to release") }))
        let file = root.appendingPathComponent("file")
        try Data().write(to: file)
        var stops = 0
        XCTAssertNil(DirectoryExportSession(url: file, startAccess: { true }, stopAccess: { stops += 1 }))
        XCTAssertEqual(stops, 1)
    }
}

/// A real Foundation coordination gate, independent of filesystem speed.
private final class HeldDirectoryPresenter: NSObject, NSFilePresenter, @unchecked Sendable {
    let presentedItemURL: URL?
    let presentedItemOperationQueue = OperationQueue()
    let requested = DispatchSemaphore(value: 0)
    let resume = DispatchSemaphore(value: 0)
    init(_ url: URL) {
        presentedItemURL = url
        super.init()
        presentedItemOperationQueue.maxConcurrentOperationCount = 1
    }
    func relinquishPresentedItem(toWriter writer: @escaping @Sendable ((@Sendable () -> Void)?) -> Void) {
        requested.signal()
        _ = resume.wait(timeout: .now() + 5)
        writer(nil)
    }
}
