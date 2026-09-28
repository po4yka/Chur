package dev.po4yka.chur.android

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.vault.SourceDeletionCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the user deletes an original that the system was not asked to. */
private const val PLACE = "your Photos or Files app"

/**
 * Deletes the originals of an import when the user asks to, `DESIGN.md`
 * §15.3 and `ANDROID.md` §14.5.
 *
 * A picked item is a grant to read that one item, and the photo picker even
 * replaces its file name, so the original's row in the media library is out
 * of sight. Reviewing therefore asks for read access to the library at that
 * moment and not before, §24: the originals are then found as MediaStore
 * rows, and `createDeleteRequest` has the system show them and ask before it
 * deletes them. A refusal of either
 * request deletes nothing, and an original that is not found is left to the
 * user with the place to delete it. API 29 has no delete request, so there
 * the review asks for nothing and says where to delete.
 *
 * Both requests are dialogs that only pause the activity, so each runs
 * inside a [ChurController.beginPrompt] bracket: the background lock of
 * `MainActivity.onPause` does not close the vault under them, and a user who
 * leaves the app meanwhile is locked out at once when the activity stops,
 * `ANDROID.md` §19.3. An answer to a prompt that ended so reports nothing. A
 * lock disposes of the route, and with it of these launchers and the picked
 * items, so no outcome outlives the session. The items live in plain state
 * and never reach saved state, §14.2.
 */
@Composable
internal fun rememberOriginalDeletion(controller: ChurController): (List<Uri>) -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var found by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var prompt by remember { mutableStateOf<Long?>(null) }
    val answered = {
        val current = prompt?.let(controller::endPrompt) == true
        prompt = null
        current
    }
    val finish = { deleted: Int ->
        controller.report(SourceDeletionCopy.outcome(picked.size, found.size, deleted, PLACE))
        picked = emptyList()
        found = emptyList()
    }
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (answered()) finish(if (result.resultCode == Activity.RESULT_OK) found.size else 0)
    }
    val access = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!answered()) return@rememberLauncherForActivityResult
        scope.launch {
            found = withContext(Dispatchers.IO) { picked.mapNotNull { originalRow(resolver, it) } }
            val request = if (found.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { MediaStore.createDeleteRequest(resolver, found) }.getOrNull()
            } else {
                null
            }
            if (request == null) {
                found = emptyList()
                finish(0)
            } else {
                prompt = controller.beginPrompt()
                confirm.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }
        }
    }
    return { uris ->
        picked = uris
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finish(0)
        } else {
            prompt = controller.beginPrompt()
            access.launch(readPermissions(resolver, uris))
        }
    }
}

/**
 * The read access that finds [uris] in the media library, `ANDROID.md` §24.
 *
 * API 33 splits it by kind, and API 34 adds the partial grant, under which the
 * user chooses which photos and videos Chur may see. Before API 33 it is one
 * permission.
 */
private fun readPermissions(resolver: ContentResolver, uris: List<Uri>): Array<String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    val kinds = uris.mapNotNull { kindOf(resolver, it) }.toSet()
    return buildList {
        if ("image" in kinds) add(Manifest.permission.READ_MEDIA_IMAGES)
        if ("video" in kinds) add(Manifest.permission.READ_MEDIA_VIDEO)
        if ("audio" in kinds) add(Manifest.permission.READ_MEDIA_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && ("image" in kinds || "video" in kinds)) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
    }.toTypedArray()
}

private fun kindOf(resolver: ContentResolver, uri: Uri): String? =
    runCatching { resolver.getType(uri) }.getOrNull()?.substringBefore('/')

/** The authority of the photo picker's on-device items; any other is a cloud provider's. */
private const val LOCAL_PICKER = "com.android.providers.media.photopicker"

/**
 * The MediaStore row of a picked original, or `null` when none can be told
 * apart.
 *
 * An on-device photo picker item carries its row's ID as its last segment,
 * and the picker gives it a name made of that ID rather than the file's, so
 * the row is looked up by the ID. Any other item is looked up by the name its
 * grant reads and must match exactly one row by name and size. Where both
 * sides know the size, it must agree. A row outside the read access the user
 * granted is not visible, and an original that is not on this device, such as
 * a cloud-only item, has none. Every provider call can fail, and a failure is
 * `null`.
 */
private fun originalRow(resolver: ContentResolver, uri: Uri): Uri? = runCatching {
    val collection = when (kindOf(resolver, uri)) {
        "image" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        "video" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        "audio" -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else -> return null
    }
    val (name, size) = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.getString(0) to (if (cursor.isNull(1)) null else cursor.getLong(1))
        }
        ?: return null
    val local = uri.takeIf { it.authority == MediaStore.AUTHORITY }
        ?.takeIf { it.pathSegments.firstOrNull()?.startsWith("picker") != true || LOCAL_PICKER in it.pathSegments }
        ?.lastPathSegment
        ?.toLongOrNull()
    val (column, value) = if (local != null) {
        MediaStore.MediaColumns._ID to local.toString()
    } else {
        MediaStore.MediaColumns.DISPLAY_NAME to (name ?: return null)
    }
    val rows = resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
        "$column = ?",
        arrayOf(value),
        null,
    )?.use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to (if (cursor.isNull(1)) null else cursor.getLong(1))) }
    }.orEmpty()
    val id = rows.singleOrNull { (_, rowSize) ->
        if (local != null) size == null || rowSize == null || rowSize == size else size != null && rowSize == size
    }?.first ?: return null
    ContentUris.withAppendedId(collection, id)
}.getOrNull()
