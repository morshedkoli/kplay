package com.kdrive.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.kdrive.tv.ui.components.FocusBox
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the D-pad can actually move around the interface.
 *
 * These exist because two shipped builds looked right and were unusable with a
 * remote: focus either never landed anywhere, or landed and could not move on.
 * Both failures are invisible in a screenshot and obvious in a test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class FocusTraversalTest {

    @get:Rule
    val rule = createComposeRule()

    /** The regression that made the whole app inert: nothing claimed focus. */
    @Test
    fun `first item takes focus on launch`() {
        rule.setContent {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            Column {
                FocusBox(onClick = {}, focusRequester = first, modifier = Modifier.size(100.dp)) {
                    Text("one")
                }
                FocusBox(onClick = {}, modifier = Modifier.size(100.dp)) { Text("two") }
            }
        }

        rule.onNodeWithText("one").assertIsFocused()
    }

    /**
     * The episode-list bug. A vertical stack has to hand focus downwards; when
     * FocusBox carried both focusable() and clickable() it created two nested
     * focus targets and the search stopped dead here.
     */
    @Test
    fun `down moves through a vertical stack`() {
        rule.setContent {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            Column {
                FocusBox(onClick = {}, focusRequester = first, modifier = Modifier.height(60.dp).fillMaxWidth()) {
                    Text("row1")
                }
                FocusBox(onClick = {}, modifier = Modifier.height(60.dp).fillMaxWidth()) { Text("row2") }
                FocusBox(onClick = {}, modifier = Modifier.height(60.dp).fillMaxWidth()) { Text("row3") }
            }
        }

        rule.onNodeWithText("row1").assertIsFocused()

        rule.onNodeWithText("row1").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("row2").assertIsFocused()

        rule.onNodeWithText("row2").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("row3").assertIsFocused()
    }

    /** Rows of posters have to pass focus sideways. */
    @Test
    fun `right moves along a horizontal row`() {
        rule.setContent {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusBox(onClick = {}, focusRequester = first, modifier = Modifier.size(80.dp)) {
                    Text("a")
                }
                FocusBox(onClick = {}, modifier = Modifier.size(80.dp)) { Text("b") }
            }
        }

        rule.onNodeWithText("a").assertIsFocused()
        rule.onNodeWithText("a").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText("b").assertIsFocused()
    }

    /**
     * The rail sits left of the content. Focus has to be able to cross from
     * content into it and back — the version that overlaid the two could not,
     * because focus search is geometric and they shared coordinates.
     */
    @Test
    fun `focus crosses between a left rail and content`() {
        rule.setContent {
            val content = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { content.requestFocus() } }
            Row {
                FocusBox(onClick = {}, modifier = Modifier.size(72.dp)) { Text("rail") }
                FocusBox(onClick = {}, focusRequester = content, modifier = Modifier.size(200.dp)) {
                    Text("poster")
                }
            }
        }

        rule.onNodeWithText("poster").assertIsFocused()
        rule.onNodeWithText("rail").assertIsNotFocused()

        rule.onNodeWithText("poster").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithText("rail").assertIsFocused()

        rule.onNodeWithText("rail").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText("poster").assertIsFocused()
    }

    /**
     * A long episode list must stay traversable past the fold. This is the
     * scrolling-Column arrangement the detail screen now uses; the LazyColumn
     * it replaced could not do this, because off-screen rows were never
     * composed and focus search found nothing below.
     */
    @Test
    fun `focus reaches items below the fold in a scrolling column`() {
        rule.setContent {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            Column(Modifier.height(200.dp).verticalScroll(rememberScrollState())) {
                repeat(12) { index ->
                    FocusBox(
                        onClick = {},
                        focusRequester = if (index == 0) first else null,
                        modifier = Modifier.height(60.dp).fillMaxWidth(),
                    ) { Text("ep$index") }
                }
            }
        }

        rule.onNodeWithText("ep0").assertIsFocused()
        repeat(11) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        }
        rule.onNodeWithText("ep11").assertIsFocused()
    }
}
