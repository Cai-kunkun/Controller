# 酷安发布材料

## 基本信息

| 项目 | 内容 |
|---|---|
| 应用名称 | Controller |
| 副标题 | 会自己动手的手机 AI 助手 |
| 分类 | 效率 / 工具 |
| 标签 | AI、自动化、Root、效率、DeepSeek、Agent、Markdown |
| 包名 | `dev.arrbrants.controller` |
| 版本 | `1.0.0-rc8`（versionCode 31） |
| 大小 | 约 5.5 MB |
| 最低系统 | Android 7.0 |
| 开源地址 | https://github.com/Cai-kunkun/Controller |
| 隐私政策 | https://github.com/Cai-kunkun/Controller/blob/main/PRIVACY.md |
| 开发者 | ArrBrants |

## 一句话简介

聊天，或者直接让它操作手机：截屏 → 视觉大模型 → 自动点击/输入，支持 DeepSeek V4 与任意 OpenAI 兼容接口。需要 Root。

## 应用介绍

Controller 把「聊天」和「操作手机」合成了一个对话。

你只需要说一句话：

- 想聊天、问问题、写东西 → 它像普通 AI 助手一样流式回答，支持 Markdown；
- 想让它动手机（打开 App、点击、输入、滑动、读屏幕）→ 它自己判断，然后接管手机完成任务。

核心循环：**截图 → 视觉模型 → 动作 → 再截图**，每一步都会把截图、动作和理由记录在对话里。
顶部有状态条，通知栏有停止按钮，随时可以中断。

## 主要功能

- **一个对话，两种行为**：纯文本聊天，或 `device_action` 工具调用，无需切换模式
- **DSML + 原生工具调用**：兼容 DeepSeek V4 的 `<｜DSML｜tool_calls>`，也可开启标准 `tools` schema
- **流式 + Markdown**：标题、粗体、列表、引用、代码块、链接
- **设备操作**：点击、双击、长按、按文字点击、滑动、输入（含中文）、按键、等待
- **坐标网格**：截图自带 0–1000 刻度，微信/游戏等隐藏无障碍树的 App 也能点
- **长任务**：便签记忆 + 90% 上下文自动压缩 + 卡死循环保护
- **缓存统计**：命中/未命中 token、命中率、tok/s、上下文占用，可收起
- **附件**：图片会缩放后发给视觉模型，文本文件内联进对话
- **密钥安全**：API Key 使用 Android Keystore 加密保存
- **DeepSeek 余额**：官方接口下直接显示 API Key 剩余余额
- **思考强度**：可选 none / low / high / max，或跟随服务商
- **思考过程**：DeepSeek reasoning 默认折叠展示，可点开查看
- **字体**：全应用使用 Google Sans Flex（SIL OFL）
- **插件**：导入 JSON 插件，模型可按需调用 shell / HTTP / UI 步骤
- **深色 UI**：Material 3，支持动态取色

## 使用步骤

1. 安装 APK，并在 KernelSU / Magisk 中给 Controller 授予 Root
2. 打开设置，填写 Base URL、API Key、模型（例如 `https://api.deepseek.com` + `deepseek-flash`）
3. 回到对话，直接输入需求；需要操作手机时它会自动进入自动操作
4. 随时从通知栏或工具栏停止

## 环境要求

- Android 7.0+
- Root（KernelSU / Magisk）
- 一个支持视觉的 OpenAI 兼容接口（自带 Key，应用不内置任何 Key）

## 安全提示

Root 自动化可以点击和输入屏幕上的任何位置。请在使用时盯着运行过程，确认目标应用和内容无误，
并随时使用停止按钮。不要用它绕过应用的限制或进行违规操作。

## 更新日志（1.0.0-rc8）

- Agent 不再强制回桌面：先看当前屏幕，由模型决定是否需要按 Home
- 其他同 1.0.0-rc7

## 截图

放在 `docs/coolapk/screenshots/`：

1. `main.png` — Markdown 聊天
2. `drawer.png` — 会话列表
3. `settings.png` — 模型设置
4. `stats.png` — 缓存/上下文统计

## 上传清单

- [ ] `app-release.apk`（已签名，versionCode 25）
- [ ] 图标（使用系统默认图标）
- [ ] 截图 4 张（待抓取，需等设备空闲）
- [ ] 隐私政策链接
- [ ] 一句话简介 + 详细介绍
- [ ] 更新日志
- [ ] 选择分类：效率 / 工具
