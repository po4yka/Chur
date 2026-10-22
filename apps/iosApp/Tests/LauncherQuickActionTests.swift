import Foundation
import XCTest
@testable import ChurScratch

final class LauncherQuickActionTests: XCTestCase {
    func testOnlyExactContentFreeTypesAreAccepted() {
        XCTAssertEqual(LauncherQuickAction.allCases.map(\.rawValue), [
            "dev.po4yka.chur.action.public-notes", "dev.po4yka.chur.action.public-calculator", "dev.po4yka.chur.action.public-camera", "dev.po4yka.chur.action.public-pet",
            "dev.po4yka.chur.action.import", "dev.po4yka.chur.action.search",
            "dev.po4yka.chur.action.lock-and-hide", "dev.po4yka.chur.action.new-private-note",
        ])
        for unknown in ["", "search", "dev.po4yka.chur.action.export", "dev.po4yka.chur.action.search?query=secret"] {
            XCTAssertNil(LauncherQuickAction(rawValue: unknown))
        }
    }

    func testColdRequestWaitsForActivationAndDeliversOnce() {
        let dispatcher = LauncherQuickActionDispatcher()
        var performed: [LauncherQuickAction] = []
        var results: [Bool] = []
        dispatcher.receive(type: LauncherQuickAction.importItems.rawValue, active: false,
            perform: { performed.append($0); return true }, completion: { results.append($0) })
        XCTAssertTrue(performed.isEmpty)
        XCTAssertTrue(results.isEmpty)
        dispatcher.activate { performed.append($0); return true }
        dispatcher.activate { performed.append($0); return true }
        XCTAssertEqual(performed, [.importItems])
        XCTAssertEqual(results, [true])
    }

    func testWarmRejectionCompletesOnceAndIsNeverReplayed() {
        let dispatcher = LauncherQuickActionDispatcher()
        var results: [Bool] = []
        dispatcher.receive(type: LauncherQuickAction.search.rawValue, active: true,
            perform: { _ in false }, completion: { results.append($0) })
        dispatcher.activate { _ in XCTFail("Rejected command replayed"); return true }
        XCTAssertEqual(results, [false])
    }

    func testReadinessWaitCanRejectAfterInboxScanAndCannotReplay() {
        let dispatcher = LauncherQuickActionDispatcher()
        var results: [Bool] = []
        // The scene is active, but its asynchronous inbox scan is unfinished.
        dispatcher.receive(type: LauncherQuickAction.search.rawValue, active: false,
            perform: { _ in XCTFail("Navigation ran before inbox readiness"); return true },
            completion: { results.append($0) })
        XCTAssertTrue(results.isEmpty)
        // A discovered share refuses navigation; a later empty scan cannot replay it.
        dispatcher.activate { _ in false }
        dispatcher.activate { _ in XCTFail("Refused request replayed after share"); return true }
        XCTAssertEqual(results, [false])
    }

    func testLockIsImmediateWhileInactiveAndRevokesPendingNavigation() {
        let dispatcher = LauncherQuickActionDispatcher()
        var performed: [LauncherQuickAction] = []
        var results: [Bool] = []
        dispatcher.receive(type: LauncherQuickAction.newPrivateNote.rawValue, active: false,
            perform: { _ in XCTFail("Navigation ran inactive"); return true }, completion: { results.append($0) })
        dispatcher.receive(type: LauncherQuickAction.lockAndHide.rawValue, active: false,
            perform: { performed.append($0); return true }, completion: { results.append($0) })
        dispatcher.activate { _ in XCTFail("Cancelled navigation replayed"); return true }
        XCTAssertEqual(performed, [.lockAndHide])
        XCTAssertEqual(results, [false, true])
    }

    func testSecondPendingRequestAndUnknownTypesCannotReplaceFirst() {
        let dispatcher = LauncherQuickActionDispatcher()
        var performed: [LauncherQuickAction] = []
        var results: [Bool] = []
        dispatcher.receive(type: LauncherQuickAction.search.rawValue, active: false,
            perform: { _ in true }, completion: { results.append($0) })
        for type in [LauncherQuickAction.importItems.rawValue, "unknown"] {
            dispatcher.receive(type: type, active: false, perform: { _ in true }, completion: { results.append($0) })
        }
        dispatcher.activate { performed.append($0); return true }
        dispatcher.cancel()
        XCTAssertEqual(performed, [.search])
        XCTAssertEqual(results, [false, false, true])
    }

    func testDisconnectCancelsAndCompletesOnce() {
        let dispatcher = LauncherQuickActionDispatcher()
        var results: [Bool] = []
        dispatcher.receive(type: LauncherQuickAction.search.rawValue, active: false,
            perform: { _ in true }, completion: { results.append($0) })
        dispatcher.cancel()
        dispatcher.cancel()
        dispatcher.activate { _ in XCTFail("Disconnected request replayed"); return true }
        XCTAssertEqual(results, [false])
    }

    func testPrivateShortcutsAreNotStaticallyExposed() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let plist = try XCTUnwrap(try PropertyListSerialization.propertyList(
            from: Data(contentsOf: root.appendingPathComponent("Info.plist")), format: nil) as? [String: Any])
        XCTAssertNil(plist["UIApplicationShortcutItems"])
    }
}
