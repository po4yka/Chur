package dev.po4yka.chur.app.vault

import java.text.DateFormat
import java.util.Date

internal actual fun formatCaptureDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
