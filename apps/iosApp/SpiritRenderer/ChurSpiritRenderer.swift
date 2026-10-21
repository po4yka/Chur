import ChurApp
import UIKit

/// Install before constructing the Compose root. No vault objects cross this API.
enum ChurSpiritRendererInstaller {
    static func install() {
        IosSpiritRendererHost.shared.factory = {
            guard let model = Bundle.main.url(forResource: "chur_interactive", withExtension: "glb") else {
                return nil
            }
            return NativeSpiritRenderer(model: model)
        }
    }
}

private final class NativeSpiritRenderer: NSObject, IosSpiritRenderer {
    private let surface: ChurSpiritView
    var view: UIView { surface }

    init(model: URL) {
        surface = ChurSpiritView(modelURL: model,
            fallbackImage: UIImage(named: "chur_interactive_fallback"))
        super.init()
    }
    func update(clip: String, clipSerial: Int32, motionSerial: Int32, reducedMotion: Bool, dragX: Float, dragY: Float) {
        surface.updateClip(clip, serial: clipSerial, motionSerial: motionSerial, reducedMotion: reducedMotion, dragX: dragX, dragY: dragY)
    }
    func dispose() { surface.dispose() }
}
