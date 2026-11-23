# 深语（Shenyu）

纯 Android 原生（Kotlin + Jetpack Compose）的 DeepSeek 客户端。界面与交互对齐 DeepSeek 官方 App：侧栏会话列表、欢迎页、流式对话、深度思考、智能搜索、Markdown 与代码块、附件、语音输入、设置面板、深浅色主题。

- 包名：`com.shenyu.chat`
- 版本：2.0.2（versionCode 47）
- minSdk 24 / targetSdk 34 / compileSdk 35
- 体积：约 1.6 MB（R8 全量压缩）
- 图标：沿用鲸鱼图标；应用名「深语」

## 功能

| 模块 | 实现 |
|---|---|
| 消息操作 | 复制 / 喜欢 / 不喜欢 / 重新生成 / 朗读 / 继续生成 / 分享为图片 |
| 会话 | SQLite 本地存储，侧栏按「今天/昨天/7 天内/30 天内/更早」分组，长按重命名或删除，可新建 |
| 对话 | 对接 `/anthropic/v1/messages` SSE 流式；逐字渲染，可中途停止，失败显示原因并可重试 |
| 深度思考 | 开关控制 `thinking: enabled/disabled`，思考块可折叠并显示耗时 |
| 智能搜索 | 开关控制是否声明 `deepseek_web_search` 工具，搜索结果可展开查看来源 |
| 图标 | 160 个矢量图标取自官方 App 2.8.0（`tools/gen_official_icons.py` 生成 `OfficialIcons.kt`） |
| 渲染 | 自研 Compose Markdown 渲染器：标题、列表、引用、GFM 表格、围栏代码块（横向滚动 + 复制）、行内样式、链接 |
| 附件 | 官方供应商：系统文件选择器选图/文档 → Files API 上传 → 消息里按 `image`/`document` 块发送；中转站通常不支持 |
| 语音 | 按住说话调系统识别；语音态与键盘态输入卡片严格等高（41dp + 1dp 分隔线 = 42dp） |
| 语音通话 | 侧栏底部「语音通话」进入全屏通话页 |
| 顶栏 | 会话菜单（自绘两道杠）/ 标题 / 自动朗读喇叭 / 新建对话（自绘扁气泡+左下尖角） |
| 分享 | 回答可生成为带水印的分享卡片图片 |
| 设置 | 主题（浅色/深色/跟随系统）、语言、字号、对话默认开关、多供应商（官方 / 自定义地址，独立 Key 与模型，可切换） |
| 备份 | 导出/导入与官方同格式的 `deepseek_data-日期.zip`（export_format_version 3），可双向互导 |
| 安全 | API Key 只存本机 SharedPreferences，导出包里不含 Key |

## 自定义供应商

设置 → 数据管理顶部可以切换或新增供应商。每个供应商独立保存接口地址、API Key 与模型，互不串用：

| 协议 | 请求地址 | 能力 |
|---|---|---|
| DeepSeek 应用协议 | 官方默认拼 `/anthropic/v1/messages`；中转站填 `/v1` 时拼 `/v1/messages` | 联网搜索、语音、标题；官方供应商还带附件与增强工具 |
| Anthropic 兼容 | 默认拼 `/v1/messages` | 标准 Messages：保留思考与纯文本，不发送 DeepSeek 私有字段 |
| OpenAI 兼容 | 默认拼 `/v1/chat/completions` | 纯文本对话；联网、附件、语音、自动标题不可用 |

接口地址可以填站点根、`/v1`，或直接贴完整的 `/messages`、`/chat/completions` 地址；设置页会显示「实际请求」地址。Anthropic 与 OpenAI 兼容协议必须填写模型名（例如 `claude-3-5-sonnet-latest`、`gpt-4o-mini`）。旧版本保存的单个 `api_key` / `api_origin` 会在升级后自动迁移成供应商列表。

### DeepSeek 兼容中转站

有些中转站（例如 New API 系）同时提供 DeepSeek 的 `/v1/messages` 与 `/v1/tools/*`，但不支持 Anthropic 官方协议里的服务端工具。此时把供应商协议选为「DeepSeek 应用协议」，接口地址填到 `/v1`（例如 `https://api.example.com/v1`），模型名照抄站点价目表：

- 对话走 `/v1/messages`；
- 智能搜索改由 App 调用 `/v1/tools/search`、`/v1/tools/fetch` 后把结果回传给模型；
- 语音识别/朗读走 `/v1/tools/asr`、`/v1/tools/tts`；朗读兼容中转站只返回 WAV 的情况；
- 附件仍依赖 `/v1/files` Files API，站点没有实现时会明确报错。

## 构建

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
bash gradlew :app:assembleRelease --console=plain
```

产物：`app/build/outputs/apk/release/app-release.apk`

签名材料不入库：需要出正式包时，把 keystore 放到 `signing/shenyu.jks`，并在本机 `local.properties` 配置 `shenyu.storePassword`、`shenyu.keyAlias`、`shenyu.keyPassword`（该文件已被忽略）。未配置时 release 产物不签名，仍可正常编译。
**后续升级必须复用同一个 keystore**，否则无法覆盖安装。

## 代码结构

```
app/src/main/java/com/shenyu/chat/
├── MainActivity.kt              入口，edge-to-edge、字号缩放
├── chat/ChatViewModel.kt        会话状态、流式生成、附件上传、导入导出
├── data/
│   ├── Models.kt                Session / Message / Block / Attachment
│   ├── ChatDatabase.kt          SQLite（会话 + 消息，blocks 以 JSON 存）
│   ├── SettingsStore.kt         SharedPreferences + StateFlow
│   └── HistoryArchive.kt        官方导出包格式的读写
├── net/AnthropicClient.kt       SSE 流式、Files 上传、Key 校验、错误映射
└── ui/
    ├── App.kt                   主体骨架（抽屉 + 顶栏 + 输入区）
    ├── theme/Theme.kt           设计变量（取自官方前端的主题 token）
    ├── base/                    Icons.kt、Components.kt
    ├── markdown/Markdown.kt     Markdown 解析与渲染
    ├── session/                 欢迎页、消息列表
    ├── chatInput/ChatInput.kt   输入区（附件 / 语音 / 开关 / 发送）
    ├── sider/SiderPanel.kt      会话抽屉
    └── settings/SettingsScreen.kt 设置（通用 / 数据管理）
```

## 像素级对齐记录

2026-11-19 这一轮按实机截图逐项对齐，关键数值（360dp 宽 = 1080px，1dp = 3px）：

| 部位 | 数值 |
|---|---|
| 输入卡片 | 圆角 20dp、1dp `#EBEBEB` 描边、投影 4dp（浓度砍到原来的三成）、左右外边距 14dp |
| 卡片内部 | 键盘态单行 42dp；语音态 = 41dp 文字区 + 1dp `#F0F0F0` 分隔线，两者等高 |
| 分隔线 | 左右各内缩 15dp，不顶到卡片边 |
| 按住说话 | 20sp Regular，垂直居中于 41dp 区域 |
| 功能胶囊 | 高 27dp、内边距 14dp、图标 17dp、字号 13sp、间距 8dp；选中态底 `#EDF3FE` / 边 `#C2D6FC` / 字 `#4D6BFE` |
| 圆圈按钮 | 图标 24dp、触摸热区 40dp、颜色 `#333333`、间距 4dp |
| 语音/发送 | 同一位置互换：空输入显示语音，有文字换成蓝色发送箭头 |
| 顶栏 | 图标 20–22dp，两个右侧图标间距 18dp，静音用官方 `ic_mute_audio_outline_24` |
| 欢迎页 | 鲸鱼 33dp + 12dp 间距，问候语从池内随机（含「你好，有什么我能帮你的吗？」） |

## 与官方 App 的差异（如实说明）

1. 没有账号体系，直接用 API Key 调用；官方 App 的会员/额度页面不在本工程范围。
2. 官方 App 的「文件解析、图片理解」依赖 Files API，已按接口实现；服务端不支持时会在消息里显示原始报错。
3. 语音识别与朗读使用 DeepSeek 的 `/v1/tools/asr`、`/v1/tools/tts`；中转站是否可用取决于其是否代理这两个接口。
4. 附件依赖 Files API；中转站通常不实现 `/v1/files`，此时只能纯文本对话。
5. 会话搜索（侧栏搜索框）尚未接入。

## 验证状态

本机不启动模拟器，全部为编译级验证：

- `:app:compileDebugKotlin`、`:app:assembleRelease` 通过（AGP 8.8.2 / Kotlin 2.0.21 / Gradle 8.13）
- `apksigner verify` 通过，v2 签名，证书 `CN=Shenyu Chat`
- R8 后 `Session$$serializer`、`Message$$serializer`、`Block$Text` 均保留（序列化不会被裁剪）
- 运行时行为需在真机验收：首次进入 → 填 Key → 发消息 → 流式输出 → 导出/导入
