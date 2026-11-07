# Changelog

All notable changes to Controller are documented here.

## [1.0.0-rc5] - 2026-11-07

- **Chain of thought**: DeepSeek `reasoning_content` is captured for chat and
  agent steps, shown as a collapsed "Thinking" block with a smaller font.
- **Font**: the whole app uses Google Sans Flex (SIL OFL, bundled in `res/font`).

## [1.0.0-rc4] - 2026-11-07

- Thinking-effort dropdown: add horizontal padding, 16sp text, and English
  option names (Default / Off / Low / High / Max).

## [1.0.0-rc3] - 2026-11-07

- **Thinking effort**: Settings now exposes `reasoning_effort` for DeepSeek V4
  (`default`, `none`, `low`, `high`, `max`). Default sends nothing and follows
  the provider; other OpenAI-compatible endpoints can ignore it.

## [1.0.0-rc2] - 2026-11-07

- **DeepSeek balance**: when the official `api.deepseek.com` endpoint is
  configured, the remaining credit (`GET /user/balance`) is shown in the
  stats header and under the API key field in Settings.

## [1.0.0-rc1] - 2026-11-07

First release candidate.

### Highlights

- **One conversation for chat and phone control.** The model decides per
  message: plain text for chat, a `device_action` call to operate the phone.
  No mode switch.
- **Streaming replies** with Markdown rendering (headings, emphasis, lists,
  quotes, code, links) and a real decode-phase tokens/second figure.
- **DeepSeek V4 DSML and native tool calling.** The canonical
  `<｜DSML｜tool_calls>` envelope is parsed, the malformed double-bar variant is
  tolerated, and a real `tools` schema can be enabled for structured
  `tool_calls`. Both are echoed back to the model in its native format.
- **Device control:** screenshots with a 0–1000 coordinate grid, tap,
  double-tap, long-press, `tap_text`, swipe, text input (ASCII and clipboard
  paste), key events, waits, and stuck-loop protection.
- **Auto-compaction** when the prompt reaches 90% of the configured context
  window, keeping notes, an action summary and the latest steps.
- **Cache and context telemetry:** cache hit/miss tokens, hit rate, tokens/s
  and context usage in a collapsible card.
- **Attachments:** pick images or files; images are downscaled and sent to the
  vision model, text files are inlined, other binaries are announced by
  name/type.
- **Encrypted API key:** Keystore-backed `EncryptedSharedPreferences` with
  migration from older plaintext builds, and a masked input field.

### Known limitations

- Requires root (KernelSU / Magisk).
- Binary attachments are not uploaded; only their metadata reaches the model.
- Root-powered automation can tap and type anywhere. Watch the run and use Stop.
- Release signing keys are intentionally kept local, so CI only builds debug.
- Validated against DeepSeek V4.1 Flash through an OpenAI-compatible endpoint;
  other providers may differ in tool-call behaviour.
