# 隐私政策 / Privacy Policy

Controller 是一个在你设备上本地运行的 Android 客户端。开发者不运营任何服务器，
应用内没有广告、统计或崩溃上报 SDK。

## 数据如何处理

- **你输入的内容**（文字、截图、附件）会发送到**你自己在设置中配置的**
  OpenAI 兼容接口（例如 DeepSeek）。请求由你的设备直接发往该服务商，不经过开发者。
- **API Key** 使用 Android Keystore 支撑的加密存储
  （`EncryptedSharedPreferences`）保存在本机，不写入明文，也不会上传。
- **会话记录、截图和附件**保存在应用私有目录
  （`/data/data/dev.arrbrants.controller`），仅本应用可访问。
- **我们不收集**设备标识、位置、联系人、使用统计或任何遥测数据。

## 权限说明

- `INTERNET`：访问你配置的模型接口。
- `SYSTEM_ALERT_WINDOW`：运行自动化时在屏幕顶部显示状态条。
- `POST_NOTIFICATIONS`：显示前台服务通知和停止按钮。
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`：在后台持续执行任务。
- **Root**：通过 `su` 执行点击、输入、滑动等操作。未授权 Root 时仍可聊天，但无法操作手机。

## 第三方

你选择的模型服务商会收到你发送的文字、截图和附件，其处理方式适用该服务商的隐私政策。
请勿在对话中发送你不希望离开设备的敏感信息。

## 删除数据

- 在应用内删除会话会同时删除对应截图和附件。
- 卸载应用会删除全部本地数据（包括加密的 API Key）。

## 联系方式

问题与建议：https://github.com/Cai-kunkun/Controller/issues

---

# Privacy Policy (English)

Controller runs locally on your device. The developer operates no servers and the app
contains no ads, analytics or crash-reporting SDKs.

- Your prompts, screenshots and attachments are sent **directly from your device** to
  the OpenAI-compatible endpoint **you configure**. The developer never receives them.
- Your API key is stored locally with Keystore-backed `EncryptedSharedPreferences`.
- Conversations, screenshots and attachments live in the app's private storage.
- No device identifiers, location, contacts, telemetry or usage data are collected.
- Root (`su`) is used only to perform the actions you ask the agent to perform.

The model provider you choose processes the content you send under its own privacy
policy. Uninstalling the app removes all local data, including the encrypted API key.
