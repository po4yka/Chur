package dev.po4yka.chur.android

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

/** Render the packaged resources through Android, independently of source PNG previews. */
@RunWith(AndroidJUnit4::class)
class LauncherIconPresentationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun packaged_layers_fit_the_safe_circle_at_every_density() {
        for (density in listOf(160, 240, 320, 480, 640)) {
            for (resource in listOf(R.mipmap.ic_launcher_foreground, R.mipmap.ic_launcher_monochrome, R.drawable.ic_splash_foreground)) {
                val size = 108 * density / 160
                val drawable = requireNotNull(context.resources.getDrawableForDensity(resource, density, context.theme)).mutate()
                val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(image))
                val pixels = IntArray(size * size)
                image.getPixels(pixels, 0, size, 0, 0, size, size)
                var maximumRadius = 0.0
                for (index in pixels.indices) {
                    if (Color.alpha(pixels[index]) <= 8) continue
                    val x = (index % size + 0.5) * 108.0 / size - 54
                    val y = (index / size + 0.5) * 108.0 / size - 54
                    maximumRadius = maxOf(maximumRadius, hypot(x, y))
                    if (resource == R.mipmap.ic_launcher_monochrome) {
                        assertEquals("Monochrome must encode shape in alpha", 0xFFFFFF, pixels[index] and 0xFFFFFF)
                    }
                }
                assertTrue("Empty icon at density $density", maximumRadius > 24)
                assertTrue("Icon exceeds 33dp safe radius: $maximumRadius at $density", maximumRadius <= 33)
                image.recycle()
            }
        }
    }

    @Test
    fun masks_preserve_artwork_and_themed_variants_render_at_small_sizes() {
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue("Manifest must select an adaptive icon", icon is AdaptiveIconDrawable)
        val adaptive = icon as AdaptiveIconDrawable
        val mono = if (Build.VERSION.SDK_INT >= 33) requireNotNull(adaptive.monochrome)
            else requireNotNull(context.getDrawable(R.mipmap.ic_launcher_monochrome))
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "icon-qa").apply { mkdirs() }
        for (size in listOf(32, 48, 192)) {
            val edge = size.toFloat()
            adaptive.setBounds(0, 0, size, size)
            val masks = linkedMapOf(
                "device" to Path(adaptive.iconMask),
                "circle" to Path().apply { addCircle(edge / 2, edge / 2, edge / 2, Path.Direction.CW) },
                "square" to Path().apply { addRect(0f, 0f, edge, edge, Path.Direction.CW) },
                "rounded-square" to Path().apply { addRoundRect(RectF(0f, 0f, edge, edge), edge * 0.26f, edge * 0.26f, Path.Direction.CW) },
                "squircle" to Path().apply {
                    moveTo(edge / 2, 0f)
                    cubicTo(edge, 0f, edge, 0f, edge, edge / 2)
                    cubicTo(edge, edge, edge, edge, edge / 2, edge)
                    cubicTo(0f, edge, 0f, edge, 0f, edge / 2)
                    cubicTo(0f, 0f, 0f, 0f, edge / 2, 0f)
                    close()
                },
                "teardrop" to Path().apply {
                    val r = edge / 2
                    addRoundRect(RectF(0f, 0f, edge, edge), floatArrayOf(r, r, r, r, 0f, 0f, r, r), Path.Direction.CW)
                },
            )
            // Sample palettes deliberately test both polarities; launcher tint selection is system-owned.
            val palettes = listOf(
                Triple("color", Color.rgb(10, 10, 10), null),
                Triple("purple-light", Color.rgb(233, 221, 255), Color.rgb(33, 26, 51)),
                Triple("purple-dark", Color.rgb(50, 43, 66), Color.rgb(220, 203, 255)),
                Triple("green-light", Color.rgb(211, 237, 215), Color.rgb(22, 51, 33)),
                Triple("green-dark", Color.rgb(26, 51, 36), Color.rgb(186, 235, 199)),
            )
            for ((name, mask) in masks) {
                for ((palette, background, tint) in palettes) {
                    val layer = (if (tint == null) adaptive.foreground else mono).mutate()
                    layer.setTintList(tint?.let { ColorStateList.valueOf(it) })
                    val image = renderLayer(layer, size)
                    val region = Region().apply { setPath(mask, Region(0, 0, size, size)) }
                    // Check the large render before clipping, so a cropped horn or tail fails.
                    if (size == 192) {
                        for (y in 0 until size) for (x in 0 until size) {
                            if (Color.alpha(image.getPixel(x, y)) > 8) {
                                assertTrue("$name clips $palette at $x,$y", region.contains(x, y))
                            }
                        }
                    }
                    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    Canvas(output).apply {
                        clipPath(mask)
                        drawColor(background)
                        drawBitmap(image, 0f, 0f, null)
                    }
                    File(directory, "$name-$palette-$size.png").outputStream().use {
                        assertTrue(output.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    image.recycle()
                    output.recycle()
                }
            }
        }
    }

    @Test
    fun window_and_system_splash_follow_the_first_frame_in_both_themes() {
        for ((mode, expected) in listOf(Configuration.UI_MODE_NIGHT_NO to 0xFFFAFAF9.toInt(), Configuration.UI_MODE_NIGHT_YES to 0xFF0A0A0A.toInt())) {
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            }
            val themed = ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.Theme_Chur)
            val window = themed.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground, android.R.attr.windowLightStatusBar, android.R.attr.windowLightNavigationBar))
            try {
                assertEquals(expected, window.getColor(0, Color.TRANSPARENT))
                assertEquals(mode == Configuration.UI_MODE_NIGHT_NO, window.getBoolean(1, false))
                assertEquals(mode == Configuration.UI_MODE_NIGHT_NO, window.getBoolean(2, false))
            } finally { window.recycle() }
            if (Build.VERSION.SDK_INT >= 31) {
                val splash = themed.obtainStyledAttributes(intArrayOf(android.R.attr.windowSplashScreenBackground, android.R.attr.windowSplashScreenAnimatedIcon, android.R.attr.windowSplashScreenIconBackgroundColor))
                try {
                    assertEquals(expected, splash.getColor(0, Color.TRANSPARENT))
                    assertEquals(R.drawable.ic_public_splash, splash.getResourceId(1, 0))
                    assertEquals(expected, splash.getColor(2, Color.TRANSPARENT))
                } finally { splash.recycle() }
            }
        }
    }

    private fun renderLayer(drawable: Drawable, size: Int): Bitmap {
        val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // AdaptiveIconDrawable expands a 108dp layer around its 72dp viewport.
        val inset = size / 4
        drawable.setBounds(-inset, -inset, size + inset, size + inset)
        drawable.draw(Canvas(image))
        return image
    }
}
