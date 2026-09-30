package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.SettingsKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floating button's content rules (see [FloatingButtonContentPolicy]).
 *
 * The user-facing requirement is "a custom picture must NOT show the text", and
 * a cleared text field must never leave an empty circle.
 */
class FloatingButtonContentTest {

    @Test
    fun aPictureWinsAndDropsTheText() {
        val content = FloatingButtonContentPolicy.resolve(
            text = "换", imageUri = "content://media/picked/42"
        )
        assertEquals(
            FloatingButtonContent.Image("content://media/picked/42"),
            content
        )
    }

    @Test
    fun noPictureFallsBackToTheText() {
        assertEquals(
            FloatingButtonContent.Text("换"),
            FloatingButtonContentPolicy.resolve(text = "换", imageUri = "")
        )
        assertEquals(
            FloatingButtonContent.Text("换"),
            FloatingButtonContentPolicy.resolve(text = "换", imageUri = null)
        )
        // A whitespace-only URI is not a picture either.
        assertEquals(
            FloatingButtonContent.Text("换"),
            FloatingButtonContentPolicy.resolve(text = "换", imageUri = "   ")
        )
    }

    @Test
    fun aBlankTextBecomesTheDefaultLabel() {
        assertEquals(FloatingButtonContentPolicy.DEFAULT_TEXT, FloatingButtonContentPolicy.clampText(""))
        assertEquals(FloatingButtonContentPolicy.DEFAULT_TEXT, FloatingButtonContentPolicy.clampText("   "))
        assertEquals(FloatingButtonContentPolicy.DEFAULT_TEXT, FloatingButtonContentPolicy.clampText(null))
        // The data-layer default and the policy default are the same label: a
        // fresh install must render what Settings shows as the default.
        assertEquals(
            SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT,
            FloatingButtonContentPolicy.DEFAULT_TEXT
        )
    }

    @Test
    fun theLabelIsTrimmedAndShortenedByCodePoints() {
        assertEquals("换", FloatingButtonContentPolicy.clampText("  换  "))
        assertEquals("换壁纸切", FloatingButtonContentPolicy.clampText("换壁纸切换"))
        // An emoji is two UTF-16 units: cutting it at the limit must not leave a
        // half surrogate (which renders as a replacement box).
        val emoji = "\uD83D\uDDBC" // 🖼
        val clamped = FloatingButtonContentPolicy.clampText("ab$emoji" + "cd")
        assertEquals(4, clamped.codePointCount(0, clamped.length))
        assertTrue("no half surrogate: $clamped", !clamped.endsWith("\uD83D"))
    }

    @Test
    fun longLabelsShrink() {
        assertEquals(12f, FloatingButtonContentPolicy.textSizeSp("换"))
        assertEquals(12f, FloatingButtonContentPolicy.textSizeSp("切换"))
        assertEquals(9f, FloatingButtonContentPolicy.textSizeSp("切换壁纸"))
    }
}
