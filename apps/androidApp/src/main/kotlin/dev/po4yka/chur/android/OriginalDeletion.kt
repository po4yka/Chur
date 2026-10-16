package dev.po4yka.chur.android

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Process
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
 * replaces its file name. Reviewing asks for read access to the library at
 * that moment and not before, §24: an original is found only by its trusted
 * MediaStore identity, and `createDeleteRequest` has the system show it and ask before it
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
 * Only a MediaStore item URI or the local photo picker's URI in this user
 * profile identifies a row. Names and sizes cannot link different providers:
 * an unrelated local file can have the same metadata as an imported source.
 * Direct item URIs retain their volume. Where both sides know the size, it
 * must agree. A missing or inaccessible row is left for manual deletion.
 * Every provider call can fail, and a failure is `null`.
 */
internal fun originalRow(resolver: ContentResolver, uri: Uri): Uri? = runCatching {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != MediaStore.AUTHORITY) return null
    val segments = uri.pathSegments
    val picker = segments.firstOrNull() in setOf("picker", "picker_get_content")
    val volume = if (picker) {
        if (segments.size != 5 || segments[2] != LOCAL_PICKER || segments[3] != "media") return null
        // Android assigns each user a range of 100,000 UIDs. A picker URI can
        // name a different profile whose numeric media IDs are not ours.
        if (segments[1].toIntOrNull() != Process.myUid() / 100_000) return null
        MediaStore.VOLUME_EXTERNAL
    } else {
        if (segments.size != 4 || segments[2] != "media") return null
        segments[0]
    }
    val id = segments.lastOrNull()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val kind = kindOf(resolver, uri)
    if (!picker && segments[1] != when (kind) {
            "image" -> "images"
            "video" -> "video"
            "audio" -> "audio"
            else -> return null
        }
    ) return null
    val collection = when (kind) {
        "image" -> MediaStore.Images.Media.getContentUri(volume)
        "video" -> MediaStore.Video.Media.getContentUri(volume)
        "audio" -> MediaStore.Audio.Media.getContentUri(volume)
        else -> return null
    }
    val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            if (cursor.isNull(0)) null else cursor.getLong(0)
        }
    val rows = resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
        "${MediaStore.MediaColumns._ID} = ?",
        arrayOf(id.toString()),
        null,
    )?.use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to (if (cursor.isNull(1)) null else cursor.getLong(1))) }
    }.orEmpty()
    val row = rows.singleOrNull() ?: return null
    if (row.first != id || (size != null && row.second != null && row.second != size)) return null
    ContentUris.withAppendedId(collection, id)
}.getOrNull()
