# Changelog

All notable changes to Controller are documented here.

## [1.0.0-rc14] - 2026-11-11

- Composer chips: a **Deep thinking** toggle, a thinking-level chip that opens
  a dropdown (PopupMenu), and a model chip that fetches `/models` with the API
  key and switches the model.
- The thinking-level setting is removed from Settings; Generation keeps only
  native tool calling.
- The stats card lives in the drawer, collapsed by default and expandable.

## [1.0.0-rc13] - 2026-11-11

- Main screen follows the DeepSeek app layout: minimal top bar, centered logo
  with a start phrase, and a large composer with the input above a row of
  chips / attach / send.
- The thinking-effort chip shows the current level and opens Generation
  settings; stats moved into the drawer; settings moved into the drawer header.

## [1.0.0-rc12] - 2026-11-10

- Settings split into a category list plus separate, auto-saving activities
  (Provider / Generation / Context / Persona / Plugins / About); the Save
  button is gone.
- Persona editor is a full-screen scrolling field with natural fling.
- Send button uses a 16dp rounded square matching the input dock.
- The chat toolbar shows the selected thinking effort, and every request sends
  the saved `reasoning_effort`.

## [1.0.0-rc11] - 2026-11-10

- Streaming thoughts are shown expanded while they arrive and collapse as soon
  as the answer content starts.
- Chat auto-scroll follows the bottom only while the user is at the bottom; a
  new chat hides the empty-state hint immediately.
- The persona field scrolls internally instead of dragging the whole settings
  page.

## [1.0.0-rc10] - 2026-11-10

- Release builds use **R8 full mode**: minification, obfuscation, optimization
  and resource shrinking (`proguard-android-optimize.txt`, APK ~3.7 MB).

## [1.0.0-rc9] - 2026-11-10

- **Update check**: Settings → About → Check for updates (GitHub releases,
  semver-aware, opens the release page).
- **Categorized settings**: Provider / Context / Generation / Persona / About
  cards instead of one long form.
- **Custom persona**: free-text persona/extra instructions appended after the
  built-in device_action prompt, so the tool protocol is preserved.

## [1.0.0-rc8] - 2026-11-07

- **Agent chooses the starting screen**: the service no longer forces `HOME`
  when a task starts. It captures the current screen first; the prompt tells it
  to press home only when needed (or when it sees Controller itself).

## [1.0.0-rc7] - 2026-11-07

- Fix plugin calls that arrive as **DSML text** instead of structured
  `tool_calls` (including empty `invoke name="plugin_..."` bodies): detect them
  in chat routing and execute them in the agent loop.

## [1.0.0-rc6] - 2026-11-07

- **Declarative plugins**: import JSON plugins in Settings, enable/disable/delete,
  and expose them to the model as `plugin_<id>` tools. Steps are whitelisted
  (`shell`, `http`, UI `action`), imported plugins start disabled, and enabling
  shell/HTTP steps asks for confirmation.

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
