package com.gekkotron.oikos.feature.home

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import java.io.File

typealias ActivityRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/**
 * Renders the current content with Robolectric's native graphics and writes `build/screenshots/<name>.png`. The
 * clock is frozen first: `captureToImage` can time out on content that animates (the same trick as the energy flow
 * screenshots). There is no golden image to diff against; the callers assert the picture is not blank and follows the theme.
 */
fun ActivityRule.renderPng(name: String): Bitmap {
    mainClock.autoAdvance = false
    mainClock.advanceTimeBy(300)
    val view = activity.window.decorView
    val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    File("build/screenshots/$name.png").also { it.parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return bitmap
}

/** How many different colours a sparse sample of [this] holds: a blank render has one. */
fun Bitmap.distinctColors(): Int {
    val seen = HashSet<Int>()
    for (x in 0 until width step 7) for (y in 0 until height step 7) seen += getPixel(x, y)
    return seen.size
}

fun Bitmap.luminanceAt(x: Int, y: Int): Float {
    val p = getPixel(x, y)
    return (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
}
