# Controller

Controller is an on-device automation agent for rooted Android devices. It captures
screenshots, sends them to a vision LLM through any OpenAI-compatible `chat/completions`
endpoint, and executes the returned actions through `su` (KernelSU / Magisk).

Core loop: **screenshot → LLM → action → repeat** until the task completes or the user
stops it.

## Features

- **Agent loop** with unlimited steps, a stuck-loop guard, per-step screenshots in the chat,
  a floating status banner and a foreground-service notification with a Stop action.
- **Actions**: `tap`, `double_tap`, `long_press`, `swipe`, `text`, `key`, `wait`,
  `tap_text` (tap a UI element by its accessibility label), `done`, `ask`.
- **Reliable text input**: ASCII via `input text`; any language (Chinese, emoji, …) via the
  clipboard + a synthetic paste.
- **Coordinate grid** drawn on every screenshot (labeled 0-1000) so the model can ground
  taps precisely — works even in apps that hide their accessibility tree (WeChat, games, …).
- **Memory**: a `notes` scratchpad plus the full conversation history (all previous
  screenshots and replies) are sent every step — built for long multi-item tasks
  (e.g. "summarize my latest three emails").
- **Chat UI** with conversation history, per-step screenshots, and settings for any
  OpenAI-compatible endpoint.

## Requirements

- A rooted device (KernelSU / Magisk). Grant root to Controller manually in the manager.
- An OpenAI-compatible `chat/completions` endpoint with a vision model
  (e.g. `deepseek-flash`).
- Android 7.0+ (`minSdk 24`).

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` needs `sdk.dir=/path/to/Android/sdk`.

## Usage

1. Open **Settings** and set your base URL, API key and model.
2. Tap the robot icon in the toolbar to enable **auto-control** (the input hint changes).
3. Type a task (e.g. *"打开设置，搜索电池"*), send it. The app moves itself to the
   background, goes home, and starts working — watch the top banner.
4. Stop anytime from the notification or the toolbar. Every step (screenshot + action +
   reasoning) is recorded in the conversation.

## Safety

Root-powered automation can tap anywhere and type anywhere. Keep an eye on it, use the
Stop button, and review the conversation log to see exactly what was done.
## 中文简介

Controller：截屏 → 视觉大模型分析 → 通过 root 执行操作（点击/双击/长按/滑动/输入），
自动完成手机上的 UI 任务。需要 root（KernelSU/Magisk）和一个支持视觉的 OpenAI 兼容接口
（如 `deepseek-flash`）。支持中文输入、坐标网格、便签记忆，微信/游戏等隐藏无障碍树的
App 也能操作。
