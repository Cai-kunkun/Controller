package dev.arrbrants.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parsing of the sequence action (several actions chained inside one step)
 * and its coordinate mapping, which has to recurse into nested chains.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SequenceTest {

    private fun parse(reply: String): AgentProtocol.Step = AgentProtocol.parse(reply)

    private fun rejection(reply: String): String = try {
        parse(reply)
        fail("expected this reply to be rejected: $reply")
        ""
    } catch (e: Exception) {
        e.message ?: ""
    }

    @Test
    fun parsesChainedActionsInOrder() {
        val step = parse(
            """{"thought":"open network page","notes":"checking WLAN","action":"sequence",
               "steps":[{"action":"tap_text","text":"WLAN"},{"action":"wait","ms":600},
                        {"action":"tap","x":500,"y":250},{"action":"text","text":"hello"}]}"""
        )
        assertEquals("open network page", step.thought)
        assertEquals("checking WLAN", step.notes)
        val seq = step.action as AgentProtocol.Action.Sequence
        assertEquals(4, seq.steps.size)
        assertEquals(AgentProtocol.Action.TapText("WLAN"), seq.steps[0])
        assertEquals(AgentProtocol.Action.Wait(600), seq.steps[1])
        assertEquals(AgentProtocol.Action.Tap(500, 250), seq.steps[2])
        assertEquals(AgentProtocol.Action.Text("hello"), seq.steps[3])
    }

    @Test
    fun keepsOrderForMixedChain() {
        val seq = parse(
            """{"action":"sequence","steps":[{"action":"key","key":"back"},
               {"action":"swipe","x1":500,"y1":800,"x2":500,"y2":200,"duration":250},
               {"action":"long_press","x":300,"y":400}]}"""
        ).action as AgentProtocol.Action.Sequence
        assertEquals(AgentProtocol.Action.Key("back"), seq.steps[0])
        assertEquals(AgentProtocol.Action.Swipe(500, 800, 500, 200, 250), seq.steps[1])
        assertEquals(AgentProtocol.Action.LongPress(300, 400), seq.steps[2])
    }

    @Test
    fun mapsEveryNestedCoordinateToScreenPixels() {
        val step = parse(
            """{"action":"sequence","steps":[{"action":"tap","x":500,"y":250},
               {"action":"sequence","steps":[{"action":"tap","x":0,"y":1000}]}]}"""
        )
        val shot = ScreenCapture.Shot(ByteArray(0), 1280, 2772, 720, 360)
        val seq = AgentProtocol.toScreenSpace(step, shot).action as AgentProtocol.Action.Sequence
        assertEquals(AgentProtocol.Action.Tap(640, 693), seq.steps[0])
        val nested = seq.steps[1] as AgentProtocol.Action.Sequence
        assertEquals(AgentProtocol.Action.Tap(0, 2771), nested.steps[0])
    }

    @Test
    fun describeAndSignatureCoverEveryElement() {
        val step = parse(
            """{"action":"sequence","steps":[{"action":"tap_text","text":"WLAN"},
               {"action":"wait","ms":500}]}"""
        )
        val desc = AgentProtocol.describe(step.action)
        assertTrue(desc, desc.contains("sequence of 2"))
        assertTrue(desc, desc.contains("tap_text \"WLAN\""))
        assertTrue(desc, desc.contains("wait 500ms"))
        // Repeated identical chains share a fingerprint so the stuck-loop guard trips.
        val again = parse(
            """{"action":"sequence","steps":[{"action":"tap_text","text":"WLAN"},
               {"action":"wait","ms":500}]}"""
        )
        assertEquals(
            AgentProtocol.actionSignature(step.action),
            AgentProtocol.actionSignature(again.action)
        )
        // Different chains must not collide.
        val other = parse("""{"action":"sequence","steps":[{"action":"key","key":"back"}]}""")
        assertTrue(
            AgentProtocol.actionSignature(step.action) != AgentProtocol.actionSignature(other.action)
        )
    }

    @Test
    fun rejectsDoneOrAskInsideAChain() {
        assertTrue(
            rejection(
                """{"action":"sequence","steps":[{"action":"tap","x":1,"y":1},
                   {"action":"done","summary":"x"}]}"""
            ).contains("only action")
        )
        assertTrue(
            rejection(
                """{"action":"sequence","steps":[{"action":"ask","question":"?"}]}"""
            ).contains("only action")
        )
    }

    @Test
    fun rejectsMalformedChains() {
        assertTrue(rejection("""{"action":"sequence"}""").contains("steps"))
        assertTrue(rejection("""{"action":"sequence","steps":[]}""").contains("steps"))
        assertTrue(rejection("""{"action":"sequence","steps":["tap"]}""").contains("step 1"))
        assertTrue(rejection("""{"action":"sequence","steps":[{"action":"fly","x":1}]}""").contains("unknown action"))
        val tooLong = (1..(AgentProtocol.MAX_SEQUENCE_ACTIONS + 1)).joinToString(",") {
            """{"action":"wait","ms":10}"""
        }
        assertTrue(rejection("""{"action":"sequence","steps":[$tooLong]}""").contains("limit"))
    }

    @Test
    fun rejectsRunawayNesting() {
        val deep = """{"action":"sequence","steps":[{"action":"sequence","steps":[
            {"action":"sequence","steps":[{"action":"wait","ms":1}]}]}]}"""
        assertTrue(rejection(deep).contains("nested"))
    }

    @Test
    fun plainActionsStillParse() {
        assertEquals(
            AgentProtocol.Action.Tap(10, 20),
            parse("""{"action":"tap","x":10,"y":20}""").action
        )
        assertEquals(
            AgentProtocol.Action.Done("all set"),
            parse("""{"action":"done","summary":"all set"}""").action
        )
    }
}