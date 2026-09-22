package io.github.nemanjan00.pm3.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The console laid out exactly as the app nests it: inside a Scaffold that has
 * a bottom navigation bar.
 *
 * This exists because the input row went missing behind that bar and was found
 * by hand, twice. The cause was a weighted height that did not survive being
 * attached to SelectionContainer, so the log took the full height and the row
 * below it had nowhere left to go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsoleContentTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(lines: List<String>) {
        rule.setContent {
            MaterialTheme {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = true,
                                onClick = {},
                                icon = { Text("D") },
                                label = { Text("Device") },
                            )
                            NavigationBarItem(
                                selected = false,
                                onClick = {},
                                icon = { Text("C") },
                                label = { Text("Console") },
                            )
                        }
                    },
                ) { padding ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .consumeWindowInsets(padding)
                    ) {
                        ConsoleContent(
                            lines = lines,
                            enabled = true,
                            input = "",
                            onInputChange = {},
                            onSend = {},
                            onCopyAll = {},
                            onCopyLine = {},
                            onClear = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `input row is visible with an empty log`() {
        show(emptyList())
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    @Test
    fun `input row is still visible with a log long enough to overflow`() {
        // The failing case: a full log must not push the input row off-screen.
        show((1..500).map { "[+] line ${'$'}it of console output" })
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    @Test
    fun `input row sits above the bottom navigation bar`() {
        show((1..500).map { "[+] line ${'$'}it" })
        val send = rule.onNodeWithContentDescription("Send").fetchSemanticsNode()
        val navItem = rule.onNodeWithText("Console").fetchSemanticsNode()
        val sendBottom = send.boundsInRoot.bottom
        val navTop = navItem.boundsInRoot.top
        assertTrue(
            "Send button (bottom=${'$'}sendBottom) overlaps the nav bar (top=${'$'}navTop)",
            sendBottom <= navTop,
        )
    }

    @Test
    fun `toolbar actions are present`() {
        show(listOf("[+] one"))
        rule.onNodeWithText("Copy all").assertIsDisplayed()
        rule.onNodeWithText("Clear").assertIsDisplayed()
    }
}
