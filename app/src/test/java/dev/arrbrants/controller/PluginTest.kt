package dev.arrbrants.controller

import dev.arrbrants.controller.data.PluginStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginTest {

    private val valid = """
        {
          "id": "battery_webhook",
          "name": "Battery webhook",
          "description": "POST battery status",
          "parameters": {
            "type": "object",
            "properties": { "url": { "type": "string" } },
            "required": ["url"]
          },
          "steps": [
            { "type": "shell", "command": "dumpsys battery" },
            { "type": "http", "method": "POST", "url": "{{url}}",
              "headers": { "X-Test": "1" }, "body": "{{result}}" },
            { "type": "action", "action": "wait", "ms": 100 }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesAValidPlugin() {
        val plugin = PluginStore.parse(valid)
        assertNotNull(plugin)
        assertEquals("battery_webhook", plugin!!.id)
        assertEquals(3, plugin.steps.size)
        assertFalse(plugin.enabled)
        assertEquals("plugin_battery_webhook", plugin.toolName)
    }

    @Test
    fun rejectsBadIdsAndUnknownSteps() {
        assertNull(PluginStore.parse(valid.replace("\"battery_webhook\"", "\"Bad-Id\"")))
        assertNull(PluginStore.parse(valid.replace("\"shell\"", "\"exec\"")))
    }

    @Test
    fun rejectsIncompleteActions() {
        assertNull(
            PluginStore.parse(
                """{"id":"a","steps":[{"type":"action","action":"tap","x":1}]}"""
            )
        )
        assertNull(
            PluginStore.parse("""{"id":"a","steps":[{"type":"action","action":"wait"}]}""")
        )
    }

    @Test
    fun buildsToolJsonWithParameters() {
        val plugin = PluginStore.parse(valid)!!
        val tool = plugin.toolJson()
        assertEquals("function", tool.optString("type"))
        val fn = tool.getJSONObject("function")
        assertEquals("plugin_battery_webhook", fn.optString("name"))
        assertTrue(fn.getJSONObject("parameters").optString("type") == "object")
    }

    @Test
    fun limitsStepCount() {
        val steps = (1..21).joinToString(",") {
            """{"type":"action","action":"wait","ms":1}"""
        }
        assertNull(
            PluginStore.parse("""{"id":"a","steps":[$steps]}""")
        )
    }
}
