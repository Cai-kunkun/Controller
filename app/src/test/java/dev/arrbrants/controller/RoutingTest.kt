package dev.arrbrants.controller

import dev.arrbrants.controller.agent.AgentProtocol
import dev.arrbrants.controller.llm.LlmClient
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

    @Test
    fun deepSeekDsmlIsParsedAsAction() {
        // Exactly what deepseek-flash returned on the device instead of JSON.
        val raw = "\n\n<｜｜DSML｜｜ calls>\n" +
            "<｜｜DSML｜｜ invoke name=\"thought\" string=\"true\">" +
            "I need to find and open the Threads app. Let me check the current screen first." +
            "</｜｜DSML｜｜ parameter>\n" +
            "<｜｜DSML｜｜ parameter name=\"action\" string=\"true\">look" +
            "</｜｜DSML｜｜ parameter>\n" +
            "</｜｜DSML｜｜ invoke>\n</｜｜DSML｜｜ calls>"

        val step = AgentProtocol.asActionOrNull(raw)
        assertTrue(step?.action is AgentProtocol.Action.Look)
        assertTrue(step?.thought.orEmpty().startsWith("I need to find and open the Threads app"))
        assertTrue(AgentProtocol.looksLikeActionStart(raw))
    }

    @Test
    fun dsmlWithArgumentsBecomesTapText() {
        val raw = "<｜｜DSML｜｜ parameter name=\"action\" string=\"true\">tap_text" +
            "</｜｜DSML｜｜ parameter>" +
            "<｜｜DSML｜｜ parameter name=\"text\" string=\"true\">设置" +
            "</｜｜DSML｜｜ parameter>"

        assertEquals(
            AgentProtocol.Action.TapText("设置"),
            AgentProtocol.asActionOrNull(raw)?.action
        )
    }

    @Test
    fun canonicalSingleBarDsmlIsParsed() {
        val raw = "<｜DSML｜tool_calls>\n" +
            "<｜DSML｜invoke name=\"device_action\">\n" +
            "<｜DSML｜parameter name=\"thought\" string=\"true\">need the screen</｜DSML｜parameter>\n" +
            "<｜DSML｜parameter name=\"action\" string=\"true\">look</｜DSML｜parameter>\n" +
            "</｜DSML｜invoke>\n" +
            "</｜DSML｜tool_calls>"

        val step = AgentProtocol.asActionOrNull(raw)
        assertTrue(step?.action is AgentProtocol.Action.Look)
        assertEquals("need the screen", step?.thought)
    }

    @Test
    fun nativeToolCallBecomesAction() {
        val calls = listOf(
            LlmClient.ToolCall(
                "call_1",
                "device_action",
                """{"thought":"need the screen","action":"look"}"""
            )
        )
        assertTrue(AgentProtocol.stepFromToolCalls(calls)?.action is AgentProtocol.Action.Look)
    }

    @Test
    fun nativeToolCallRoundTripsThroughDsml() {
        val calls = listOf(
            LlmClient.ToolCall(
                "call_1",
                "device_action",
                """{"thought":"tap it","action":"tap","x":5,"y":6}"""
            )
        )
        val dsml = AgentProtocol.toDsml(calls)
        assertTrue(dsml.startsWith("<｜DSML｜tool_calls>"))
        assertTrue(dsml.contains("name=\"device_action\""))
        assertEquals(
            AgentProtocol.Action.Tap(5, 6),
            AgentProtocol.asActionOrNull(dsml)?.action
        )
    }
}
