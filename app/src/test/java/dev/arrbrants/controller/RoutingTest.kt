package dev.arrbrants.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Chat and device control share one reply channel: a plain-text reply is a chat
 * answer, a single JSON object is an action that starts automation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoutingTest {

    @Test
    fun plainTextIsChat() {
        assertNull(AgentProtocol.asActionOrNull("A cache hit rate is hits divided by lookups."))
        assertNull(AgentProtocol.asActionOrNull("Sure — opening the app now."))
    }

    @Test
    fun jsonObjectIsAnAction() {
        val step = AgentProtocol.asActionOrNull("""{"thought":"need the screen","action":"look"}""")
        assertTrue(step?.action is AgentProtocol.Action.Look)
    }

    @Test
    fun fencedJsonIsAnAction() {
        val step = AgentProtocol.asActionOrNull(
            "```json\n{\"action\":\"tap_text\",\"text\":\"Settings\"}\n```"
        )
        assertEquals(AgentProtocol.Action.TapText("Settings"), step?.action)
    }

    @Test
    fun malformedJsonIsNotAnAction() {
        assertNull(AgentProtocol.asActionOrNull("""{"action":"tap","x":""}"""))
    }
}
