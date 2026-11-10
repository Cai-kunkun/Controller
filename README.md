# Controller

Controller is an on-device automation agent for rooted Android devices. It captures
screenshots, sends them to a vision LLM through any OpenAI-compatible `chat/completions`
endpoint, and executes the returned actions through `su` (KernelSU / Magisk).

Core loop: **screenshot → LLM → action → repeat** until the task completes or the user
stops it.

**Status: 1.0.0-rc11** — release candidate ([CHANGELOG](CHANGELOG.md)).

## Features

- **One conversation, two behaviours**: plain text for chat, or a `device_action`
  tool call to drive the phone — the model chooses per message, no mode switch.
- **DSML + native tool calling**: DeepSeek V4's `<｜DSML｜tool_calls>` envelope is
  parsed (including the malformed double-bar variant), and a real `tools` schema
  can be enabled for structured `tool_calls`.
- **Streaming replies** rendered as Markdown (headings, emphasis, lists, quotes,
  code, links); `tokens/s` is measured over the decode phase.
- **Agent loop** with unlimited steps, a stuck-loop guard, per-step screenshots in the chat,
  a floating status banner and a foreground-service notification with a Stop action.
- **Actions**: `tap`, `double_tap`, `long_press`, `swipe`, `text`, `key`, `wait`,
  `tap_text` (tap a UI element by its accessibility label), `done`, `ask`.
- **Multi-action steps**: a step may be a `sequence` that chains several actions and runs
  them in order (nested up to 2 levels, 6 actions per chain), cutting the step count on
  long deterministic flows. The chain stops at the first failing action and names it in
  the next screenshot's context, and it is interruptible by the Stop button.
- **Reliable text input**: ASCII via `input text`; any language (Chinese, emoji, …) via the
  clipboard + a synthetic paste.
- **Coordinate grid** drawn on every screenshot (labeled 0-1000) so the model can ground
  taps precisely — works even in apps that hide their accessibility tree (WeChat, games, …).
- **Memory**: a `notes` scratchpad plus the full conversation history (all previous
  screenshots and replies) are sent every step — built for long multi-item tasks
  (e.g. "summarize my latest three emails").
- **Auto-compaction**: when the prompt reaches 90% of the configured context window,
  older screenshots and exchanges are replaced with a summary of the actions already
  taken (and the running notes), so long runs keep going without overrunning the model.
- **Cache & speed telemetry**: a strip above the input shows prompt-cache hits and
  misses, the hit rate, output tokens/second, and how full the context window is —
  read straight from the provider's `usage` block.
- **DeepSeek balance**: with the official `api.deepseek.com` endpoint, the remaining
  credit is shown in the stats header and under the API key field.
- **Thinking effort**: choose `reasoning_effort` (`none` / `low` / `high` / `max`) or
  leave it to the provider.
- **Chain of thought**: DeepSeek reasoning is kept with the message and shown as a
  collapsed block (tap to expand) in a smaller font.
- **Font**: bundled Google Sans Flex (SIL OFL) across the UI.
- **Declarative plugins**: JSON-defined tools (shell / HTTP / UI steps) exposed to
  the model as `plugin_<id>`; import, enable, disable and delete in Settings.
- **Custom persona**: append your own persona/instructions after the built-in
  tool-calling prompt; categorized settings and an in-app update check.
- **Attachments**: pick images or text files. Images are downscaled and sent to the
  vision model; text is inlined; other binaries are announced by name/type.
- **Encrypted API key**: stored in Keystore-backed `EncryptedSharedPreferences`,
  with automatic migration from older plaintext builds.
- **Chat UI** with conversation history, per-step screenshots, a collapsible stats
  card, and settings for any OpenAI-compatible endpoint.

## Project structure

```
app/src/main/java/dev/arrbrants/controller/
├── MainActivity.kt          chat UI, streaming, attachments, stats panel
├── SettingsActivity.kt      provider / context / tool settings
├── PluginsActivity.kt       plugin import / enable / delete
├── agent/                   on-device automation runtime
│   ├── AgentService.kt      foreground service + agent loop
│   ├── AgentProtocol.kt     action protocol, DSML/JSON parsing, prompt
│   ├── ActionExecutor.kt    root taps, typing, sequences
│   ├── PluginExecutor.kt    runs shell / HTTP / UI plugin steps
│   ├── AgentOverlay.kt      floating status banner
│   ├── RootShell.kt         su command helper
│   ├── ScreenCapture.kt     screencap + coordinate grid
│   ├── ShotThumbs.kt        thumbnail cache
│   └── UiDump.kt            uiautomator label lookup
├── data/                    models + persistence
│   ├── AppPrefs.kt          typed preference access
│   ├── SecurePrefs.kt       Keystore-backed API key
│   ├── ConversationStore.kt conversations/messages JSON store
│   ├── ChatMessage.kt, Attachment.kt
│   ├── AttachmentStore.kt   picked files → prompt parts
│   ├── Plugin.kt, PluginStore.kt
│   └── UsageStats.kt        cache/context counters
├── llm/                     provider I/O
│   ├── LlmClient.kt         chat completions, SSE, tools, usage
│   ├── MessageMapper.kt     ChatMessage → prompt messages
│   └── TokenBudget.kt       token estimates + formatting
└── ui/                      recycler adapters
    ├── ChatAdapter.kt
    ├── ConversationAdapter.kt
    └── PluginAdapter.kt
```

## Requirements

- A rooted device (KernelSU / Magisk). Grant root to Controller manually in the manager.
- An OpenAI-compatible `chat/completions` endpoint with a vision model
  (e.g. `deepseek-flash`).
- Android 7.0+ (`minSdk 24`).

## Install

Download the signed APK from the
[latest release](https://github.com/Cai-kunkun/Controller/releases):

```bash
adb install -r app-release.apk
```

Root (KernelSU / Magisk) is required; grant Controller root in the manager.

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` needs `sdk.dir=/path/to/Android/sdk`.

### Release build

Create `keystore.properties` in the repo root (gitignored):

```properties
storeFile=/absolute/path/controller-release.jks
storePassword=…
keyAlias=controller
keyPassword=…
```

Then:

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties` the release APK is produced unsigned.

## Usage

1. Open **Settings** and set your base URL, API key and model.
2. Just type a message. The model decides what it is:
   - a question or chat → the answer streams into the bubble;
   - something that needs the phone (open an app, tap, type, read the screen) →
     the app moves itself to the background and starts operating, showing every
     step (screenshot + action + reasoning) in the conversation.
3. Stop anytime from the notification or the toolbar.

The model never needs a mode switch: to act it replies with a DSML tool call (or
a native `tool_calls` entry when native tool calling is enabled), and to chat it
replies in plain text. A `look` action asks for the current screen without
touching anything, so it can decide with full information.

## Known limitations

- Root (KernelSU / Magisk) is required.
- Binary attachments are announced by name/type; their content is not uploaded.
- The agent can tap and type anywhere. Watch the run and use Stop.
- Validated against DeepSeek V4.1 Flash through an OpenAI-compatible endpoint;
  other providers may differ in tool-call behaviour.

## Safety

Root-powered automation can tap anywhere and type anywhere. Keep an eye on it, use the
Stop button, and review the conversation log to see exactly what was done.
## 中文简介

Controller：截屏 → 视觉大模型分析 → 通过 root 执行操作（点击/双击/长按/滑动/输入），
自动完成手机上的 UI 任务。聊天和自动操作合并成同一个对话：模型自己判断这条消息是聊天
（流式文字回答）还是要动手机（返回 JSON 动作）。需要 root（KernelSU/Magisk）和一个支持
视觉的 OpenAI 兼容接口（如 `deepseek-flash`）。支持中文输入、坐标网格、便签记忆，微信/
游戏等隐藏无障碍树的 App 也能操作。单步还可以用 `sequence` 串起多个动作按顺序执行，
压缩长流程的步数。
