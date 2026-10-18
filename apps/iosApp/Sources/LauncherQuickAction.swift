/// Fixed, content-free Home Screen commands. Never decode shortcut userInfo.
enum LauncherQuickAction: String, CaseIterable {
    case importItems = "dev.po4yka.chur.action.import"
    case search = "dev.po4yka.chur.action.search"
    case lockAndHide = "dev.po4yka.chur.action.lock-and-hide"
    case newPrivateNote = "dev.po4yka.chur.action.new-private-note"
}

/// Scene connection can precede activation. Keep one ephemeral request and
/// finish each UIKit callback exactly once, including rejection/disconnection.
final class LauncherQuickActionDispatcher {
    private var pending: (LauncherQuickAction, (Bool) -> Void)?

    func receive(type: String, active: Bool, perform: (LauncherQuickAction) -> Bool,
                 completion: @escaping (Bool) -> Void) {
        guard let action = LauncherQuickAction(rawValue: type) else { completion(false); return }
        if action == .lockAndHide {
            cancel()
            completion(perform(action))
            return
        }
        guard pending == nil else { completion(false); return }
        pending = (action, completion)
        if active { activate(perform: perform) }
    }

    func activate(perform: (LauncherQuickAction) -> Bool) {
        guard let (action, completion) = pending else { return }
        pending = nil
        completion(perform(action))
    }

    func cancel() {
        let completion = pending?.1
        pending = nil
        completion?(false)
    }
}
