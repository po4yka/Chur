package dev.po4yka.chur.android

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * A `.exports` URI for a test fixture in `cache/export-scratch/`. The provider serves
 * only a leased file, so the fixture takes the lease that a share would take.
 */
internal fun leasedExportUri(context: Context, file: File): Uri {
    ExportLeases.grant(file)
    return FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
}
