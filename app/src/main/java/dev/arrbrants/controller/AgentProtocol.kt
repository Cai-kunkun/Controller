package dev.arrbrants.controller

import org.json.JSONObject
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Prompt construction and action parsing for the screenshot -> model ->
 * action loop. Coordinates travel on a 0..1000 scale relative to the
 * screenshot; [toScreenSpace] converts them into real screen pixels.
 */
object AgentProtocol {

    sealed class Action {
        data class Tap(val x: Int, val y: Int) : Action()
        data class DoubleTap(val x: Int, val y: Int) : Action()
        data class LongPress(val x: Int, val y: Int) : Action()
        data class TapText(val label: String) : Action()
        data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int) : Action()
        data class Text(val text: String) : Action()
        data class Key(val key: String) : Action()
        data class Wait(val ms: Int) : Action()
        data class Done(val summary: String) : Action()
        data class Ask(val question: String) : Action()
    }

    data class Step(val thought: String, val action: Action, val notes: String? = null)

    fun systemPrompt(task: String): String = """
        You are an Android device automation agent. Each turn you receive a screenshot of the phone screen and must reply with exactly ONE action, as a single JSON object. No markdown fences, no extra text.

        Coordinates use a 0-1000 scale relative to the screenshot: x=0 is the left edge, x=1000 the right edge, y=0 the top edge, y=1000 the bottom edge.

        Actions:
        {"thought":"why you do this","action":"tap","x":500,"y":250}
        {"thought":"...","action":"long_press","x":500,"y":250}   // press and hold; e.g. long-press the WeChat Moments camera icon to post text-only
        {"thought":"...","action":"double_tap","x":500,"y":400}   // two quick taps; e.g. on Douyin, double-tapping the video likes it
        {"thought":"...","action":"tap_text","text":"发送"}      // taps the view whose label contains this text — much more reliable than guessing coordinates for buttons, menu items, tabs
        {"thought":"...","action":"swipe","x1":500,"y1":800,"x2":500,"y2":200,"duration":300}   // scroll down: swipe from lower to upper
        {"thought":"...","action":"text","text":"hello"}      // types into the focused input; any language
        {"thought":"...","action":"key","key":"back"}         // back | home | enter | recents
        {"thought":"...","action":"wait","ms":1500}           // wait for the UI to settle
        {"thought":"...","action":"done","summary":"what was accomplished"}
        {"thought":"...","action":"ask","question":"..."}     // only when truly blocked or information is missing

        Every reply may also include a "notes" field: a scratchpad that is carried across steps and shown back to you on the next step. Use it to remember anything you still need — especially content you read (e.g. email texts you are collecting for a summary) and which items you have already processed. When present it replaces the previous notes. Example:
        {"thought":"...","action":"tap_text","text":"Next email","notes":"Read 1/3: Google security alert - new sign-in on Pixel, 2 days ago. Remaining: Tavily, Rotem Weiss."}

        Rules:
        - Every screenshot has a faint magenta grid: the lines are labeled 100..900 on the 0-1000 coordinate scale — read coordinates off this grid so taps land precisely.
        - Your full step history (all previous screenshots and your own replies) is attached to the conversation; rely on it instead of revisiting screens you have already seen.
        - Keep your notes up to date: when you read or learn something you will need later (like an email to summarize), write it into "notes" immediately — you cannot go back to earlier screenshots and nothing else is remembered.
        - One action per reply. After each action you receive a fresh screenshot.
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
        - Reply "done" as soon as the task is complete.

        The user's task: $task
    """.trimIndent()

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
        sb.append("\nHere is the current screenshot. Reply with exactly one JSON action.")
        return sb.toString()
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
        val action = obj.optString("action").trim().lowercase()
        val parsed = when (action) {
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
            "done" -> Action.Done(obj.optString("summary").ifBlank { "task finished" })
            "ask" -> Action.Ask(obj.optString("question").ifBlank { "need more information" })
            else -> throw IOException("unknown action '$action'")
        }
        return Step(thought, parsed, notes)
    }

    fun toScreenSpace(step: Step, shot: ScreenCapture.Shot): Step = Step(
        step.thought,
        when (val a = step.action) {
            is Action.Tap -> Action.Tap(px(a.x, shot.screenWidth), px(a.y, shot.screenHeight))
            is Action.DoubleTap -> Action.DoubleTap(px(a.x, shot.screenWidth), px(a.y, shot.screenHeight))
            is Action.LongPress -> Action.LongPress(px(a.x, shot.screenWidth), px(a.y, shot.screenHeight))
            is Action.Swipe -> Action.Swipe(
                px(a.x1, shot.screenWidth), px(a.y1, shot.screenHeight),
                px(a.x2, shot.screenWidth), px(a.y2, shot.screenHeight),
                a.durationMs
            )
            else -> a
        }
    )

    fun describe(action: Action): String = when (action) {
        is Action.Tap -> "tap (${action.x}, ${action.y})"
        is Action.DoubleTap -> "double_tap (${action.x}, ${action.y})"
        is Action.LongPress -> "long_press (${action.x}, ${action.y})"
        is Action.TapText -> "tap_text \"${action.label}\""
        is Action.Swipe -> "swipe (${action.x1}, ${action.y1}) -> (${action.x2}, ${action.y2})"
        is Action.Text -> "type \"${action.text}\""
        is Action.Key -> "key ${action.key}"
        is Action.Wait -> "wait ${action.ms}ms"
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
