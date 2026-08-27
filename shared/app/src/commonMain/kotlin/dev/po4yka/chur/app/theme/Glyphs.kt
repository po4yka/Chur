package dev.po4yka.chur.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The icon set, drawn here rather than pulled in.
 *
 * `docs/DEPENDENCY_POLICY.md` prefers no dependency for what a few lines can
 * do, and the Material extended-icon artifact is several megabytes for the
 * handful of glyphs four destinations and a viewer need. Each one is a stroked
 * 24dp path at the 2dp weight `DESIGN.md` §9 uses for hairlines and outlines.
 */
private fun glyph(name: String, build: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(build).build()

private fun androidx.compose.ui.graphics.vector.ImageVector.Builder.stroke(
    pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
) = path(
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 2f,
    strokeLineCap = StrokeCap.Round,
    pathBuilder = pathBuilder,
)

/** A plus, for a primary create action. */
val PlusGlyph: ImageVector = glyph("Plus") {
    stroke {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }
}

/** A back chevron. */
val BackGlyph: ImageVector = glyph("Back") {
    stroke {
        moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f)
    }
}

/** A gear, for settings. */
val SettingsGlyph: ImageVector = glyph("Settings") {
    stroke {
        moveTo(12f, 8f); lineTo(16f, 12f); lineTo(12f, 16f); lineTo(8f, 12f); close()
        moveTo(12f, 3f); lineTo(12f, 6f)
        moveTo(12f, 18f); lineTo(12f, 21f)
        moveTo(3f, 12f); lineTo(6f, 12f)
        moveTo(18f, 12f); lineTo(21f, 12f)
    }
}

/** A grid, for the library destination. */
val LibraryGlyph: ImageVector = glyph("Library") {
    stroke {
        moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
        moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
        moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
    }
}

/** Stacked rectangles, for the albums destination. */
val AlbumsGlyph: ImageVector = glyph("Albums") {
    stroke {
        moveTo(6f, 7f); lineTo(18f, 7f)
        moveTo(4f, 11f); lineTo(20f, 11f); lineTo(20f, 20f); lineTo(4f, 20f); close()
    }
}

/** A magnifier, for the search destination. */
val SearchGlyph: ImageVector = glyph("Search") {
    stroke {
        moveTo(11f, 4f)
        arcToRelative(7f, 7f, 0f, true, true, 0f, 14f)
        arcToRelative(7f, 7f, 0f, true, true, 0f, -14f)
        moveTo(16.5f, 16.5f); lineTo(20f, 20f)
    }
}

/** A heart outline, for the favourite action. */
val FavoriteGlyph: ImageVector = glyph("Favorite") {
    stroke {
        moveTo(12f, 20f)
        lineTo(4.5f, 12.5f)
        arcToRelative(4.5f, 4.5f, 0f, true, true, 7.5f, -5f)
        arcToRelative(4.5f, 4.5f, 0f, true, true, 7.5f, 5f)
        close()
    }
}

/** A lock, for the lock action. */
val LockGlyph: ImageVector = glyph("Lock") {
    stroke {
        moveTo(6f, 11f); lineTo(18f, 11f); lineTo(18f, 20f); lineTo(6f, 20f); close()
        moveTo(8.5f, 11f); lineTo(8.5f, 7.5f)
        arcToRelative(3.5f, 3.5f, 0f, true, true, 7f, 0f)
        lineTo(15.5f, 11f)
    }
}

/** A downward arrow into a tray, for export. */
val ExportGlyph: ImageVector = glyph("Export") {
    stroke {
        moveTo(12f, 4f); lineTo(12f, 14f)
        moveTo(8f, 10f); lineTo(12f, 14f); lineTo(16f, 10f)
        moveTo(5f, 18f); lineTo(19f, 18f)
    }
}

/** A bin, for delete. */
val DeleteGlyph: ImageVector = glyph("Delete") {
    stroke {
        moveTo(5f, 7f); lineTo(19f, 7f)
        moveTo(10f, 7f); lineTo(10f, 4f); lineTo(14f, 4f); lineTo(14f, 7f)
        moveTo(7f, 7f); lineTo(8f, 20f); lineTo(16f, 20f); lineTo(17f, 7f)
    }
}

/** A shield outline, for the integrity states of §20. */
val IntegrityGlyph: ImageVector = glyph("Integrity") {
    stroke {
        moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 12f)
        curveTo(19f, 17f, 15f, 20f, 12f, 21f)
        curveTo(9f, 20f, 5f, 17f, 5f, 12f)
        lineTo(5f, 6f)
        close()
    }
}
