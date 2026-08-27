@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import dev.po4yka.chur.notes.FileNoteStore
import dev.po4yka.chur.notes.NoteStore
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

/**
 * The iOS entry point.
 *
 * The Xcode project in `apps/iosApp` creates one [ChurController], presents
 * this controller, and drives the two transitions no Kotlin code can see: the
 * privacy cover on `sceneWillResignActive`, and the picker and share sheet.
 * Everything else is the shared controller, so the two hosts differ in binding
 * and nowhere else.
 *
 * The function takes no `@Composable` parameter, and that is a constraint
 * rather than a style: Kotlin/Native cannot map a composable lambda to
 * Objective-C, so a signature with one is silently absent from the framework
 * header instead of being an error.
 */
fun ChurViewController(controller: ChurController, gate: GateResult): UIViewController =
    ComposeUIViewController {
        val route by controller.route.collectAsState()
        val state by controller.vaultState.collectAsState()
        ChurApp(gate = gate, route = route) {
            IosRoutes(controller = controller, route = route, vaultState = state)
        }
    }

/**
 * The storage root, `docs/ARCHITECTURE.md` §14.4.
 *
 * The documents directory is app-private. The Xcode project marks it excluded
 * from iCloud and iTunes backup, which `PLAINTEXT_LIFECYCLE.md` §5 requires of
 * every directory Chur writes into, and this creates it so the exclusion has
 * something to apply to at first launch.
 */
fun churStorageRoot(): String {
    val documents = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true,
    ).first() as String
    val root = "$documents/chur"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = root,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return root
}

/**
 * Where the public shell keeps its notes.
 *
 * It returns the bound store rather than the path, because a path in the
 * framework header is an invitation for the host to open the file itself, and
 * the file's format belongs to `:shared:feature-notes`.
 *
 * The file sits beside the vault root rather than inside it. Nothing forbids a
 * public file in that directory, but a directory that holds only the vault is
 * one an inspection can reason about, and `PLAINTEXT_LIFECYCLE.md` §1 draws the
 * line between the two stores exactly here.
 */
fun churNoteStore(): NoteStore {
    val documents = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true,
    ).first() as String
    return FileNoteStore("$documents/notes.json")
}
