package dev.arrbrants.controller.agent

import dev.arrbrants.controller.llm.LlmClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Prompt construction and action parsing for the screenshot -> model ->
 * action loop. Coordinates travel on a 0..1000 scale relative to the
 * screenshot; [toScreenSpace] converts them into real screen pixels.
 */
object AgentProtocol {

    /** How deeply sequences may nest, and how many actions one step may chain. */
    const val MAX_SEQUENCE_DEPTH = 2
    const val MAX_SEQUENCE_ACTIONS = 6

    sealed class Action {
        data class Tap(val x: Int, val y: Int) : Action()
        data class DoubleTap(val x: Int, val y: Int) : Action()
        data class LongPress(val x: Int, val y: Int) : Action()
        data class TapText(val label: String) : Action()
        data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int) : Action()
        data class Text(val text: String) : Action()
        data class Key(val key: String) : Action()
        data class Wait(val ms: Int) : Action()
        /** Fetches the current screen without touching it, so the model can decide. */
        object Look : Action()
        /** Several actions run in order inside one step. */
        data class Sequence(val steps: List<Action>) : Action()
        data class Done(val summary: String) : Action()
        data class Ask(val question: String) : Action()
    }

    data class Step(val thought: String, val action: Action, val notes: String? = null)

    fun systemPrompt(persona: String = ""): String {
        val base = """
        You are Controller, the user's on-device assistant. You can both chat and operate the user's Android phone.

        Decide what each message needs:
        - Pure conversation (answer a question, explain, write, translate, summarize, plan, code, small talk): reply in plain text. Do NOT emit a tool call.
        - A phone task (open or use an app, tap, type, scroll, read the screen, change a setting, follow a flow in another app): operate the phone by replying with exactly ONE DSML tool call and no other text.
        - If the task depends on what is currently on screen and you have not seen it, call device_action with action="look".
        - Never just say that you will do something: to actually operate the phone you must emit the DSML tool call.

        To operate the phone, reply with exactly this structure (the delimiter is the fullwidth vertical bar ｜, U+FF5C — never ASCII |, never JSON, never markdown fences):

        <｜DSML｜tool_calls>
        <｜DSML｜invoke name="device_action">
        <｜DSML｜parameter name="thought" string="true">why you do this</｜DSML｜parameter>
        <｜DSML｜parameter name="action" string="true">tap_text</｜DSML｜parameter>
        <｜DSML｜parameter name="text" string="true">发送</｜DSML｜parameter>
        </｜DSML｜invoke>
        </｜DSML｜tool_calls>

        Always include "thought" and "action". Add the fields the action needs:
        - look: (no extra fields) capture the screen without touching it
        - tap, double_tap, long_press: "x" and "y" on the 0-1000 coordinate scale (string="false")
        - tap_text: "text" — the visible label or accessibility label of the view to tap
        - swipe: "x1","y1","x2","y2" and "duration" in ms (string="false")
        - text: "text" to type into the focused field
        - key: "key" — back | home | enter | recents
        - wait: "ms" (string="false")
        - done: "summary"
        - ask: "question"
        Numbers may use string="false"; all other values use string="true".
        You may optionally add "notes": a scratchpad carried across steps. When present it replaces the previous notes. Example:
        <｜DSML｜parameter name="notes" string="true">Read 1/3: Google security alert. Remaining: Tavily, Rotem Weiss.</｜DSML｜parameter>

        While operating you receive a screenshot after every action and keep replying with one DSML tool call. When the task is finished call action="done". If the user only needed an answer that you can now give, reply in plain text instead — that ends the operation.

        Starting screen:
        - You begin on the currently visible screen. If it shows Controller itself (the chat list, settings or input bar), press the home key first and continue.
        - Do not go to the home screen by reflex: stay in the current app when the task is about it, and only press home when you need the launcher or a fresh start.

        Coordinates use a 0-1000 scale relative to the screenshot: x=0 is the left edge, x=1000 the right edge, y=0 the top edge, y=1000 the bottom edge.

        Rules while operating the phone:
        - Every screenshot has a faint magenta grid: the lines are labeled 100..900 on the 0-1000 coordinate scale — read coordinates off this grid so taps land precisely.
        - Your full step history (all previous screenshots and your own replies) is attached to the conversation; rely on it instead of revisiting screens you have already seen.
        - Keep your notes up to date: when you read or learn something you will need later (like an email to summarize), write it into "notes" immediately — you cannot go back to earlier screenshots and nothing else is remembered.
        - One action per reply. After each step you receive a fresh screenshot.
        - If any part of the task depends on something you cannot see yet, send a single action and look at the next screenshot.
        - A small status banner titled "Controller" may appear at the very top of the screen. Ignore it; never tap it.
        - Prefer tap_text for any target with a visible label (buttons, menu items, input fields). Use tap with coordinates only for unlabeled targets or icons.
        - If a coordinate tap does not do what you expected, do not retry the same spot — switch to tap_text with the visible label.
        - If coordinate taps on a small icon keep missing, try tap_text with the label the icon likely has: icons usually have accessibility labels even without visible text (like = 点赞 / 未点赞, favorite = 收藏, comment = 评论, share = 分享, back = 返回).
        - Never repeat the same action more than three times in a row; if it is not working, change your approach (back, home, or tap_text).
        - When paging home screens or app drawers, swipe through the middle of the screen; swipes at the very bottom edge fall into the system gesture area and do nothing.
        - Dialogs that block the task (required app update, login, permission) should be accepted rather than dismissed; if unsure, use the ask action.
        - If a panel or dialog offers choices, pick the option that leads to the goal; do not close a panel you just opened unless it is clearly wrong.
        - Banners, ads and promo tiles can lead straight to a product page — that is fine as a shortcut when the product matches the target. But if a page is a marketing/campaign page without product options or an add-to-cart action, or it keeps reloading, press back immediately.
        - To search in an app, use tap_text on the search bar (match its label or placeholder text such as 「搜索」), then type the query.
        - To type text: first tap the text field itself — the wide bar just above the keyboard — never tap inside the keyboard.
        - After typing, check the next screenshot: if the field still looks empty, tap the field again and retype once before trying something else.
        - In chat apps, prefer pressing the enter key (key action) to send a message, or tap_text the send button (e.g. "发送"); these are more reliable than tapping by coordinates.
        - Never tap avatars or profile pictures: they open profile pages and lose your context.
        - Before sending a message, verify the chat title at the top of the screen matches the intended chat.
        - Prefer visible UI elements; avoid destructive or irreversible actions unless the task asks for them.
        - If an action did not work, do not repeat it blindly; try a different approach.
        - A search showing no results does not mean your text was not entered; try a different wording (e.g. the device's local language variant) before retyping.
        - Call action="done" as soon as the task is complete.
    """.trimIndent()
        if (persona.isBlank()) return base
        return base + "\n\n## User persona and extra instructions\n" + persona.trim()
    }

    fun stepPrompt(step: Int, lastResult: String?, notes: String?, advice: String?): String {
        val sb = StringBuilder("Step ").append(step).append('.')
        if (!lastResult.isNullOrBlank()) {
            sb.append("\nPrevious action result: ").append(lastResult)
        }
        if (!notes.isNullOrBlank()) {
            sb.append("\nYour notes: ").append(notes)
        }
        if (!advice.isNullOrBlank()) {
            sb.append("\nAdvice: ").append(advice)
        }
        sb.append("\nHere is the current screenshot. Reply with one DSML tool call to keep operating, ")
        sb.append("or with plain text to answer the user and stop.")
        return sb.toString()
    }

    /**
     * Returns the parsed action when the whole reply is a single JSON object
     * (optionally wrapped in a markdown fence), or null for a normal chat reply.
     * DeepSeek can also emit its native DSML tool-call markup even when asked
     * for bare JSON; that is recognised and converted as well.
     */
    fun asActionOrNull(reply: String): Step? {
        var t = reply.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        if (t.startsWith("{")) {
            return try {
                parse(t)
            } catch (e: IOException) {
                null
            }
        }
        return parseDsml(reply)
    }

    /** Converts a structured native tool call into a [Step]. */
    fun stepFromToolCalls(calls: List<LlmClient.ToolCall>): Step? {
        val call = calls.firstOrNull { it.name == "device_action" }
            ?: calls.firstOrNull()
            ?: return null
        val obj = try {
            JSONObject(call.arguments)
        } catch (e: Exception) {
            return null
        }
        return try {
            Step(
                obj.optString("thought").trim(),
                parseAction(obj, 1),
                obj.optString("notes").trim().ifBlank { null }
            )
        } catch (e: IOException) {
            null
        }
    }

    /** Serializes structured tool calls back into canonical DSML history. */
    fun toDsml(calls: List<LlmClient.ToolCall>): String {
        if (calls.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("<｜DSML｜tool_calls>\n")
        for (call in calls) {
            sb.append("<｜DSML｜invoke name=\"").append(call.name).append("\">\n")
            val obj = try {
                JSONObject(call.arguments)
            } catch (e: Exception) {
                null
            }
            if (obj != null) {
                for (key in obj.keys()) {
                    val value = obj.opt(key)
                    if (value == null || value == JSONObject.NULL) continue
                    val isString = value is String
                    sb.append("<｜DSML｜parameter name=\"").append(key)
                        .append("\" string=\"").append(isString).append("\">")
                        .append(value.toString())
                        .append("</｜DSML｜parameter>\n")
                }
            }
            sb.append("</｜DSML｜invoke>\n")
        }
        sb.append("</｜DSML｜tool_calls>")
        return sb.toString()
    }

    /** True for replies that start like a structured action (DSML or JSON). */
    fun looksLikeActionStart(reply: String): Boolean {
        val t = reply.trimStart()
        if (t.startsWith("{") || t.startsWith("```")) return true
        if (t.startsWith("<｜") || t.startsWith("<|")) {
            return t.contains("DSML", ignoreCase = true)
        }
        return false
    }

    /**
     * Strips leaked JSON/DSML from a thought before it is shown in the chat.
     * The model sometimes appends a fenced copy of its own action.
     */
    fun cleanThought(text: String): String {
        if (text.isBlank()) return ""
        var t = text
        val fence = t.indexOf("```")
        if (fence >= 0) t = t.substring(0, fence)
        val fullwidth = t.indexOf("<｜DSML", ignoreCase = true)
        if (fullwidth >= 0) t = t.substring(0, fullwidth)
        val ascii = t.indexOf("<|DSML", ignoreCase = true)
        if (ascii >= 0) t = t.substring(0, ascii)
        return t.trim()
    }

    /** True when a message contains provider tool-call markup. */
    fun containsToolMarkup(text: String): Boolean =
        text.contains("DSML", ignoreCase = true)

    /**
     * Extracts a plugin call from DSML text, e.g.
     * `<｜DSML｜invoke name="plugin_device_report"></｜DSML｜invoke>`.
     */
    fun asPluginCallOrNull(reply: String): LlmClient.ToolCall? {
        if (!containsToolMarkup(reply)) return null
        val normalized = reply.replace('\uFF5C', '|')
        for (match in DSML_INVOKE_BLOCK.findAll(normalized)) {
            val name = match.groupValues[1].trim()
            if (!name.startsWith("plugin_")) continue
            val args = JSONObject()
            for (param in DSML_PARAM.findAll(match.groupValues[2])) {
                val key = param.groupValues[1].trim()
                val value = param.groupValues[2].trim()
                if (value.contains("<|DSML|", ignoreCase = true)) continue
                args.put(key, value)
            }
            return LlmClient.ToolCall("dsml_$name", name, args.toString())
        }
        return null
    }

    private val DSML_OPEN = "<\\|{1,2}\\s*DSML\\s*\\|{1,2}"

    private val DSML_PARAM = Regex(
        "$DSML_OPEN\\s*parameter\\b[^>]*?\\bname=\"([^\"]+)\"[^>]*>" +
            "([\\s\\S]*?)</\\|{1,2}\\s*DSML\\s*\\|{1,2}\\s*parameter>",
        RegexOption.IGNORE_CASE
    )

    /** Tolerates the malformed `<｜｜DSML｜｜ invoke name="thought">...` variant. */
    private val DSML_INVOKE = Regex(
        "$DSML_OPEN\\s*invoke\\b[^>]*?\\bname=\"([^\"]+)\"[^>]*>" +
            "([\\s\\S]*?)</\\|{1,2}\\s*DSML\\s*\\|{1,2}\\s*(?:parameter|invoke)>",
        RegexOption.IGNORE_CASE
    )

    /** Whole invoke blocks, used to find plugin calls (empty bodies included). */
    private val DSML_INVOKE_BLOCK = Regex(
        "$DSML_OPEN\\s*invoke\\b[^>]*?\\bname=\"([^\"]+)\"[^>]*>" +
            "([\\s\\S]*?)</\\|{1,2}\\s*DSML\\s*\\|{1,2}\\s*invoke>",
        RegexOption.IGNORE_CASE
    )

    /**
     * Converts DeepSeek's `<｜｜DSML｜｜ parameter name="...">value</...>` blocks
     * into a regular action. Only scalar fields are supported.
     */
    private fun parseDsml(reply: String): Step? {
        if (!containsToolMarkup(reply)) return null
        val normalized = reply.replace('\uFF5C', '|')
        val fields = LinkedHashMap<String, String>()
        for (match in DSML_PARAM.findAll(normalized)) {
            val name = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            if (value.contains("<｜DSML", ignoreCase = true) ||
                value.contains("<|DSML", ignoreCase = true)
            ) {
                continue
            }
            fields[name] = value
        }
        // Legacy/malformed variant: the thought is on the invoke tag itself.
        for (match in DSML_INVOKE.findAll(normalized)) {
            val name = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            if (name in fields) continue
            if (value.contains("<|DSML|", ignoreCase = true)) continue
            fields[name] = value
        }
        val action = fields["action"]?.trim()?.lowercase().orEmpty()
        if (action.isEmpty()) return null

        val numeric = setOf("x", "y", "x1", "y1", "x2", "y2", "duration", "ms")
        val allowed = numeric + setOf("action", "text", "key", "summary", "question")
        val obj = JSONObject()
        for ((key, value) in fields) {
            if (key !in allowed) continue
            if (key in numeric) {
                val number = value.trim().toIntOrNull() ?: continue
                obj.put(key, number)
            } else {
                obj.put(key, value)
            }
        }
        return try {
            Step(
                fields["thought"].orEmpty().trim(),
                parseAction(obj, 1),
                fields["notes"]?.trim()?.ifBlank { null }
            )
        } catch (e: IOException) {
            null
        }
    }

    fun parse(reply: String): Step {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("no JSON object found")
        val obj = try {
            JSONObject(reply.substring(start, end + 1))
        } catch (e: Exception) {
            throw IOException("invalid JSON (${e.message})")
        }
        val thought = obj.optString("thought").trim()
        val notes = obj.optString("notes").trim().ifBlank { null }
        return Step(thought, parseAction(obj, 1), notes)
    }

    /** Parses one action object; [depth] guards against runaway nested sequences. */
    private fun parseAction(obj: JSONObject, depth: Int): Action {
        val name = obj.optString("action").trim().lowercase()
        val action = when (name) {
            "tap" -> Action.Tap(need(obj, "x"), need(obj, "y"))
            "double_tap" -> Action.DoubleTap(need(obj, "x"), need(obj, "y"))
            "long_press" -> Action.LongPress(need(obj, "x"), need(obj, "y"))
            "tap_text" -> Action.TapText(requireText(obj, "text"))
            "swipe" -> Action.Swipe(
                need(obj, "x1"), need(obj, "y1"), need(obj, "x2"), need(obj, "y2"),
                obj.optInt("duration", 300).coerceIn(50, 3000)
            )
            "text" -> Action.Text(requireText(obj, "text"))
            "key" -> Action.Key(requireText(obj, "key"))
            "wait" -> Action.Wait(obj.optInt("ms", 1000).coerceIn(0, 5000))
            "look" -> Action.Look
            "sequence" -> Action.Sequence(parseSequence(obj.optJSONArray("steps"), depth))
            "done" -> Action.Done(obj.optString("summary").ifBlank { "task finished" })
            "ask" -> Action.Ask(obj.optString("question").ifBlank { "need more information" })
            else -> throw IOException("unknown action '$name'")
        }
        if (depth > 1 && (action is Action.Done || action is Action.Ask)) {
            throw IOException("\"$name\" must be the only action in a reply, not part of a sequence")
        }
        return action
    }

    private fun parseSequence(arr: JSONArray?, depth: Int): List<Action> {
        if (depth > MAX_SEQUENCE_DEPTH) {
            throw IOException("sequences nested more than $MAX_SEQUENCE_DEPTH levels deep")
        }
        if (arr == null || arr.length() == 0) {
            throw IOException("sequence needs a non-empty \"steps\" array")
        }
        if (arr.length() > MAX_SEQUENCE_ACTIONS) {
            throw IOException("sequence has ${arr.length()} actions, the limit is $MAX_SEQUENCE_ACTIONS")
        }
        val out = ArrayList<Action>(arr.length())
        for (i in 0 until arr.length()) {
            val child = try {
                arr.optJSONObject(i)
            } catch (e: Exception) {
                throw IOException("sequence step ${i + 1} is not a JSON object")
            } ?: throw IOException("sequence step ${i + 1} is not a JSON object")
            out.add(parseAction(child, depth + 1))
        }
        return out
    }

    fun toScreenSpace(step: Step, shot: ScreenCapture.Shot): Step =
        Step(step.thought, map(step.action, shot), step.notes)

    /** Converts an action (and everything nested in it) to real screen pixels. */
    private fun map(action: Action, shot: ScreenCapture.Shot): Action = when (action) {
        is Action.Tap -> Action.Tap(px(action.x, shot.screenWidth), px(action.y, shot.screenHeight))
        is Action.DoubleTap -> Action.DoubleTap(px(action.x, shot.screenWidth), px(action.y, shot.screenHeight))
        is Action.LongPress -> Action.LongPress(px(action.x, shot.screenWidth), px(action.y, shot.screenHeight))
        is Action.Swipe -> Action.Swipe(
            px(action.x1, shot.screenWidth), px(action.y1, shot.screenHeight),
            px(action.x2, shot.screenWidth), px(action.y2, shot.screenHeight),
            action.durationMs
        )
        is Action.Sequence -> Action.Sequence(action.steps.map { map(it, shot) })
        else -> action
    }

    fun describe(action: Action): String = when (action) {
        is Action.Tap -> "tap (${action.x}, ${action.y})"
        is Action.DoubleTap -> "double_tap (${action.x}, ${action.y})"
        is Action.LongPress -> "long_press (${action.x}, ${action.y})"
        is Action.TapText -> "tap_text \"${action.label}\""
        is Action.Swipe -> "swipe (${action.x1}, ${action.y1}) -> (${action.x2}, ${action.y2})"
        is Action.Text -> "type \"${action.text}\""
        is Action.Key -> "key ${action.key}"
        is Action.Wait -> "wait ${action.ms}ms"
        is Action.Look -> "look at the screen"
        is Action.Sequence ->
            "sequence of ${action.steps.size} [" +
                action.steps.joinToString(", ") { describe(it) } + "]"
        is Action.Done -> "done: ${action.summary}"
        is Action.Ask -> "ask: ${action.question}"
    }

    fun keyCode(name: String): String? = when (name.trim().lowercase()) {
        "back" -> "KEYCODE_BACK"
        "home" -> "KEYCODE_HOME"
        "enter" -> "KEYCODE_ENTER"
        "recents", "app_switch", "overview" -> "KEYCODE_APP_SWITCH"
        else -> null
    }

    /** Coarse fingerprint of an action, used to detect stuck loops. */
    fun actionSignature(action: Action): String = when (action) {
        is Action.Tap -> "tap:${action.x / 150},${action.y / 300}"
        is Action.DoubleTap -> "double_tap:${action.x / 150},${action.y / 300}"
        is Action.LongPress -> "long_press:${action.x / 150},${action.y / 300}"
        is Action.TapText -> "tap_text:${action.label}"
        is Action.Swipe -> "swipe:${action.x1 / 200}:${if (action.y2 > action.y1) "down" else "up"}"
        is Action.Text -> "text:${action.text.take(20)}"
        is Action.Key -> "key:${action.key}"
        is Action.Wait -> "wait"
        is Action.Look -> "look"
        // A repeated whole chain counts as one repeated step: the sequence
        // fingerprint covers its elements, so a stuck chain still trips the guard.
        is Action.Sequence -> "seq:" + action.steps.joinToString(",") { actionSignature(it) }
        is Action.Done -> "done"
        is Action.Ask -> "ask"
    }

    private fun need(obj: JSONObject, key: String): Int {
        if (!obj.has(key)) throw IOException("missing field '$key'")
        val value = obj.optInt(key, Int.MIN_VALUE)
        if (value == Int.MIN_VALUE) throw IOException("bad field '$key'")
        return value
    }

    private fun requireText(obj: JSONObject, key: String): String {
        val value = obj.optString(key)
        if (value.isBlank()) throw IOException("missing field '$key'")
        return value
    }

    private fun px(v: Int, extent: Int): Int =
        ((v / 1000f) * extent).roundToInt().coerceIn(0, extent - 1)
}
