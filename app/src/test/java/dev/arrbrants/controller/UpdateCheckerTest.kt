package dev.arrbrants.controller

import dev.arrbrants.controller.agent.AgentProtocol
import dev.arrbrants.controller.data.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckerTest {

    @Test
    fun comparesVersions() {
        assertTrue(UpdateChecker.isNewer("1.0.0", "1.0.0-rc8"))
        assertFalse(UpdateChecker.isNewer("1.0.0-rc8", "1.0.0-rc8"))
        assertTrue(UpdateChecker.isNewer("1.0.0-rc9", "1.0.0-rc8"))
        assertFalse(UpdateChecker.isNewer("1.0.0-rc8", "1.0.0-rc9"))
        assertFalse(UpdateChecker.isNewer("0.9.0", "1.0.0-rc8"))
        assertTrue(UpdateChecker.isNewer("1.0.1-rc1", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("v1.1.0", "1.0.0"))
    }

    @Test
    fun personaIsAppendedToTheBasePrompt() {
        val prompt = AgentProtocol.systemPrompt("你是简洁的中文助手。")
        assertTrue(prompt.contains("device_action"))
        assertTrue(prompt.contains("User persona and extra instructions"))
        assertTrue(prompt.contains("你是简洁的中文助手。"))
    }

    @Test
    fun emptyPersonaKeepsTheBasePrompt() {
        val prompt = AgentProtocol.systemPrompt("")
        assertFalse(prompt.contains("User persona and extra instructions"))
        assertTrue(prompt.contains("device_action"))
    }
}
