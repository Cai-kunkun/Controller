package dev.arrbrants.controller.agent

import dev.arrbrants.controller.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler

/**
 * Executes parsed actions through root. Holds no conversation/agent state;
 * the loop only provides a stop check and a progress sink.
 */
class ActionExecutor(
    private val context: Context,
    private val mainHandler: Handler,
    private val isStopRequested: () -> Boolean,
    private val onProgress: (label: String, text: String) -> Unit
) {

    /** Runs one action; returns an error string, or null on success. */
    fun execute(action: AgentProtocol.Action, executeLabel: String = ""): String? = try {
        when (action) {
            is AgentProtocol.Action.Tap -> run("input tap ${action.x} ${action.y}")
            is AgentProtocol.Action.DoubleTap -> run(
                "input tap ${action.x} ${action.y}; input tap ${action.x} ${action.y}"
            )
            is AgentProtocol.Action.LongPress -> run(
                "input swipe ${action.x} ${action.y} ${action.x} ${action.y} 800"
            )
            is AgentProtocol.Action.TapText -> tapByText(action.label)
            is AgentProtocol.Action.Swipe -> run(
                "input swipe ${action.x1} ${action.y1} ${action.x2} ${action.y2} ${action.durationMs}"
            )
            is AgentProtocol.Action.Text -> typeText(action.text)
            is AgentProtocol.Action.Key -> {
                val code = AgentProtocol.keyCode(action.key)
                if (code == null) "unknown key '${action.key}'" else run("input keyevent $code")
            }
            is AgentProtocol.Action.Wait -> {
                Thread.sleep(action.ms.toLong())
                null
            }
            is AgentProtocol.Action.Look -> null
            is AgentProtocol.Action.Sequence -> runSequence(action, executeLabel)
            else -> null
        }
    } catch (e: Exception) {
        e.message ?: "command failed"
    }

    /**
     * Runs the chained actions of one step in order, leaving a short gap between
     * them so the UI can react. Stops at the first failing element and reports
     * which one it was; a stop request also breaks out mid-chain.
     */
    private fun runSequence(action: AgentProtocol.Action.Sequence, stepLabel: String): String? {
        var failure: String? = null
        val total = action.steps.size
        for ((index, sub) in action.steps.withIndex()) {
            if (isStopRequested()) {
                failure = "stopped after ${index} of $total actions"
                break
            }
            if (index > 0) Thread.sleep(SEQUENCE_GAP_MS)
            val subDesc = AgentProtocol.describe(sub)
            val progress = context.getString(R.string.agent_sequence, index + 1, total, subDesc)
            onProgress(stepLabel, progress)
            val error = execute(sub)
            if (error != null) {
                failure = "action ${index + 1} of $total ($subDesc) failed: $error"
                break
            }
        }
        return failure
    }

    private fun run(command: String): String? {
        val result = Shell.exec(command)
        return if (result.ok) null
        else result.stderr.ifBlank { result.stdout }.ifBlank { "exit ${result.code}" }
    }

    /**
     * Finds a view by its label through `uiautomator dump` and taps its center.
     * Far more reliable than coordinate guesses for labeled buttons.
     */
    private fun tapByText(label: String): String? {
        val file = "/data/local/tmp/controller_ui.xml"
        val dump = Shell.exec(
            "uiautomator dump $file >/dev/null 2>&1; cat $file 2>/dev/null; rm -f $file",
            20000
        )
        if (dump.stdout.isBlank() || dump.stdout.length < 500) {
            return "no UI labels available (this app hides its accessibility tree); " +
                "use tap with coordinates read from the grid"
        }
        val center = UiDump.findCenter(dump.stdout, label)
            ?: return "no view labeled \"$label\""
        return run("input tap ${center.first} ${center.second}")
    }

    /**
     * Types into the focused field. `input text` handles ASCII reliably;
     * anything else (Chinese, emoji, …) goes through the clipboard plus a
     * synthetic KEYCODE_PASTE, which `input text` cannot express.
     */
    private fun typeText(text: String): String? {
        if (text.all { it.code in 0x20..0x7e }) {
            return run("input text ${RootShell.shellQuote(text)}")
        }
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("controller", text))
        mainHandler.postDelayed({ clipboard.clearPrimaryClip() }, 3000)
        Thread.sleep(250)
        return run("input keyevent KEYCODE_PASTE")
    }

    private companion object {
        /** Pause between the actions of one sequence, so the UI can react. */
        const val SEQUENCE_GAP_MS = 450L
    }
}
