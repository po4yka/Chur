package dev.po4yka.chur.app.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.po4yka.chur.app.MediaImporter

/**
 * The copy of the source-deletion step, `DESIGN.md` §15.3.
 *
 * The step follows an import that committed and says what the import did not
 * do: the original is still where the user picked it. The operating system
 * deletes it, so nothing here says that an original is erased, and the copies
 * it does not reach are named, §27. The two labels are the section's own.
 */
object SourceDeletionCopy {
    const val KEEP: String = "Keep original"
    const val REVIEW: String = "Review source deletion"

    fun title(count: Int): String =
        if (count == 1) "Original still on this device" else "Originals still on this device"

    fun body(count: Int): String =
        (if (count == 1) "Chur has a protected copy. " else "Chur has protected copies. ") +
            "Your system handles deletion, and copies can stay in Recently deleted, backups, or other apps."

    /**
     * What a review ended in: of [picked] originals, the host found [found]
     * where the system can delete them, and the system deleted [deleted] after
     * it asked the user. [place] is where the user deletes the rest.
     */
    fun outcome(picked: Int, found: Int, deleted: Int, place: String): String {
        val rest = picked - deleted
        return when {
            found == 0 && picked == 1 -> "Chur could not reach the original. Delete it in $place."
            found == 0 -> "Chur could not reach the originals. Delete them in $place."
            deleted == 0 -> if (picked == 1) "Kept the original." else "Kept the originals."
            rest == 0 -> "Deleted ${originals(deleted)}."
            rest == 1 -> "Deleted ${originals(deleted)}. Delete the last one in $place."
            else -> "Deleted ${originals(deleted)}. Delete the other $rest in $place."
        }
    }

    private fun originals(count: Int): String = if (count == 1) "1 original" else "$count originals"
}

/**
 * The choice that follows an import, `DESIGN.md` §15.3 and `ANDROID.md` §14.5.
 *
 * A dialog, the §26 surface for a short decision whose consequence is outside
 * Chur. Keeping is the default: the scrim and Back keep, and keeping asks for
 * nothing. Reviewing is where the host asks for access to the library, and
 * the system then shows the items and asks before it deletes them.
 */
@Composable
fun SourceDeletionDialog(count: Int, onKeep: () -> Unit, onReview: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(SourceDeletionCopy.title(count)) },
        text = { Text(SourceDeletionCopy.body(count)) },
        confirmButton = { TextButton(onClick = onReview) { Text(SourceDeletionCopy.REVIEW) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(SourceDeletionCopy.KEEP) } },
    )
}

/**
 * The picked items whose originals the step may offer to delete.
 *
 * `SECURITY_INVARIANTS.md` SEC-022 keeps a source until its encrypted import
 * is durably committed, and [MediaImporter.Outcome.Imported] is the only
 * outcome that says so. [outcomes] are in pick order and end where the pick
 * stopped, so an item past their end was never imported.
 */
fun <T> importedOriginals(picked: List<T>, outcomes: List<MediaImporter.Outcome>): List<T> =
    picked.filterIndexed { index, _ -> outcomes.getOrNull(index) is MediaImporter.Outcome.Imported }
