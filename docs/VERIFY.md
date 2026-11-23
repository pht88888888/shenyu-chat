# 验证记录（深语 1.0.0）

本机不启动模拟器（内存不足），因此全部为**编译期与产物级**验证；运行时行为由使用者在真机验收。
每一项都可复现，命令见下。

## 1. 环境

| 项 | 值 |
|---|---|
| JDK | 17.0.21（`/usr/lib/jvm/java-17-openjdk-amd64`） |
| Gradle | 8.13（Wrapper） |
| AGP | 8.8.2 |
| Kotlin | 2.0.21（`org.jetbrains.kotlin.plugin.compose` + `serialization`） |
| Compose BOM | 2024.09.02 |
| Android SDK | `/opt/android-sdk`（platform 35 / build-tools 35.0.0） |

## 2. 构建

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
bash gradlew :app:assembleDebug :app:assembleRelease --console=plain --max-workers=2
```

结果：`BUILD SUCCESSFUL`，release 产物 1.4 MB。

## 3. 产物检查

| 检查 | 命令 | 结果 |
|---|---|---|
| 包身份 | `aapt2 dump badging` | `com.shenyu.chat` code=1 name=1.0.0，label `深语`，launchable `com.shenyu.chat.MainActivity` |
| minSdk / targetSdk | 同上 | 24 / 34 |
| 签名 | `apksigner verify --print-certs` | `Verifies`，v2 方案，证书 `CN=Shenyu Chat`，SHA-256 `61af02c8…` |
| 体积 | `ls -lh` | 1.4 MB（R8 全量混淆 + 资源裁剪） |
| dex 类数 | `dexdump` | 3042 个类 |
| 序列化类保留 | `dexdump \| grep` | `Session$$serializer`、`Message$$serializer`、`Block$Search$$serializer` 等全部保留 |
| 协议常量 | `strings classes.dex` | `/anthropic/v1/messages`、`/anthropic/v1/files`、`x-api-key`、`deepseek_web_search`、`export_format_version` 均在 |
| 附件上传路径 | 同上 | `multipart/form-data` 存在（MultipartBody 被 R8 改名，属正常） |

`HistoryArchive`、`SpeechController` 等业务类在 dex 中显示为混淆名，属 R8 正常重命名，不是被裁剪。

## 4. 静态检查

```bash
bash gradlew :app:lintDebug
```

结果：**0 error**。剩余 warning 均为无行为影响的提示：

| id | 数量 | 说明 |
|---|---|---|
| GradleDependency | 6 | 依赖有新版本可用 |
| ModifierParameter | 3 | `modifier` 参数位置建议（风格） |
| MonochromeLauncherIcon | 2 | 建议补单色图标（Android 13 主题图标） |
| OldTargetApi | 1 | targetSdk 34（按需要固定） |
| StaticFieldLeak | 1 | 已改为持有 `applicationContext` 并加注解，复检后不再新增 |

## 5. 单元测试

`HistoryArchive` 的导入导出逻辑与 Context 解耦（`export(dir, sessions)` / `exportToBytes` / `read(input)`），可以纯 JVM 跑。

```bash
bash gradlew :app:testDebugUnitTest
```

结果：**5 个用例全部通过**（`app/build/test-results/testDebugUnitTest/TEST-com.shenyu.chat.data.HistoryArchiveTest.xml`）。

| 用例 | 断言内容 |
|---|---|
| 导出再导入内容一致 | 会话 id / 标题 / 消息数、用户正文、附件名、思考文本与耗时、搜索 queries 与结果 url、助手正文全部还原 |
| 导出包结构符合官方约定 | 第一个条目是 `manifest.json`，含 `chat_sessions/{id}.json` 与 `chat_session_index.json` |
| 缺少 manifest 直接拒绝 | 抛 `ArchiveException` |
| 版本过新直接拒绝 | `export_format_version: 9` 抛 `ArchiveException` |
| 只还原 current_message_id 指向的主链 | 有分支的历史树只取主链 1→2→3，被放弃的分支不进入会话 |

## 6. 功能与实现文件对照

| 功能 | 实现位置 | 关键点 |
|---|---|---|
| 会话列表 / 分组 / 搜索 | `ui/sider/SiderPanel.kt` | 按「今天/昨天/7 天内/30 天内/更早」分组；标题过滤；长按重命名/删除 |
| 欢迎页 | `ui/session/WelcomeScreen.kt` | 「我是 DeepSeek，很高兴见到你！」+ 推荐提问 |
| 消息流 / 操作条 | `ui/session/MessageList.kt` | 复制、重新生成、朗读、点赞点踩；自动贴底 |
| 流式生成 | `net/AnthropicClient.kt` + `chat/ChatViewModel.kt` | SSE 解析 `message_start/content_block_*/message_delta/message_stop` |
| 深度思考 | 同上 | `thinking: {type: enabled}`；`thinking_delta` 累积，折叠显示耗时 |
| 智能搜索 | 同上 | 声明 `deepseek_web_search` 工具；`server_tool_use` + `web_search_tool_result` 渲染来源 |
| Markdown | `ui/markdown/Markdown.kt`（1077 行） | 标题、列表、引用、GFM 表格、围栏代码块（横向滚动+复制）、行内样式、链接 |
| 附件 | `ui/chatInput/ChatInput.kt` + `AnthropicClient.uploadFile` | SAF 选文件 → Files API 上传 → `image`/`document` 块 |
| 语音输入 | `ui/chatInput/ChatInput.kt` | 系统 `RecognizerIntent`，识别结果填入输入框 |
| 朗读 | `ui/speech/SpeechController.kt` | 系统 TTS，长文本分段入队 |
| 设置 | `ui/settings/SettingsScreen.kt` | 主题/语言/字号/默认开关/API Key 校验/接口地址 |
| 备份 | `data/HistoryArchive.kt` | 官方 `deepseek_data-*.zip`（v3）双向读写 |

## 7. 已知未覆盖项

1. **运行时**：全部 UI 交互、SSE 实际连接、Files API 上传、TTS 发声均未在设备上跑过；需真机验收。
2. **导入兼容性**：仅按官方格式实现，未用真实导出包回归。畸形包策略：无 `manifest.json`、版本 > 3、空包均抛中文错误。
3. **R8 与反射**：未使用反射，但若后续引入需补 keep 规则。
4. **平板 / 横屏布局**：当前按手机竖屏设计，未做双栏。
5. **官方会员/额度页**：不在范围。
6. **单色启动图标**（Android 13 主题图标）未提供。

---

# 1.1.0 修复记录（2026-11-18）

针对真机反馈的五个问题逐条处理，并补上官方语音能力。

## 1. 点「测试连接」闪退

**根因**：`ChatViewModel.verifyApiKey` 在 `viewModelScope.launch`（主线程）里调用了阻塞式 OkHttp 的 `execute()`，触发 `NetworkOnMainThreadException`，协程里未捕获 → 进程直接退出。

**修复**：把调用包进 `withContext(Dispatchers.IO)`；顺带把导出历史里的 SQLite 读取与写 zip 也移到 IO。

## 2. 流式回复时滑不动

**根因**：`LaunchedEffect` 里每来一个 token 就 `animateScrollToItem`，滚动动画与手指手势互相抢占，且判定条件过宽，用户上滑后仍被拉回底部。

**修复**（`ui/session/MessageList.kt`）：
- 用 `NestedScrollConnection` 监听 `NestedScrollSource.UserInput`，手指向上拖动即 `autoFollow = false`，彻底停止自动跟随；
- `snapshotFlow { listState.canScrollForward }` 在滑回底部时自动恢复跟随；
- 跟随用 `scrollToItem + scrollBy`（无动画），不再和手势抢焦点；
- 不在底部时右下角浮出「回到最新」胶囊按钮，点击恢复跟随并跳到底部。

## 3. 语音按钮没反应

**结论**：官方网页端确实走自家语音接口，API Key 可用（见 `services/api/speech.ts`）：
- 识别 `POST {origin}/v1/tools/asr`，multipart `file`=WAV（16kHz 单声道 16bit，≤120 秒 / ≤8 MiB），返回 `{"text"}`；
- 合成 `POST {origin}/v1/tools/tts`，JSON `{text, voice_id, format:"pcm", filter_markdown:true}`，返回裸 PCM 24kHz 单声道 16bit。

**实现**：
- `net/AudioClient.kt`：ASR/TTS 客户端 + `pcmToWav()`；
- `ui/voice/VoiceRecorder.kt`：`AudioRecord` 采集 16kHz PCM，满 120 秒自动停；
- 输入区麦克风改为「录音 / 停止」切换，录音中显示红点与计时，结束后调 ASR 把文本追加到输入框；
- 朗读由系统 TTS 换成官方 TTS，`AudioTrack` 边收边播（首包即出声），播放中按钮变品牌色；
- Manifest 增加 `RECORD_AUDIO`，首次点麦克风走运行时授权（lint 已加注说明调用方负责授权）。

## 4. 图片显示路径而不是缩略图

**修复**：新增 `ui/base/ImageThumb.kt`，直接从 `content://` 两次解码（先读尺寸算 `inSampleSize` 再按目标像素解码），不引入图片库；输入框附件、用户消息气泡里图片附件都改用缩略图，非图片仍显示文件名胶囊。

## 5. 发图片后回复中途闪退

**已做的两头加固**：
- `applyEvent` 的块序号全部 `coerceIn(0, 63)`——原先若服务端下发异常索引，`while (blocks.size <= index) blocks += Block.Text("")` 会瞬间补齐几十万个空块直接 OOM；
- Markdown 解析器整体 try-catch 降级纯文本，引用/列表/行内递归限深 8 层，>200k 字符截断（此前深层引用与超长流式内容是栈溢出候选）。

**新增排查手段**：`ShenyuApp` 注册全局 `UncaughtExceptionHandler`，堆栈写到 `filesDir/crash/crash-时间.txt`（保留最近 5 份）；设置 → 数据管理 → 崩溃日志 可直接查看并复制。下次再闪退可以拿到准确堆栈。

## 6. Markdown 支持不足

`ui/markdown/Markdown.kt` 1081 → 1830 行：
- 代码块语法高亮（14 族语言，自实现词法扫描，无新依赖，明暗两套配色，未知语言回退纯文本）；
- 数学公式降级渲染（行内 `$…$` 带货币保护、块级 `$$…$$`，`\frac`/`\sqrt`/上下标/常用符号映射）；
- `[citation:N]` 渲染为上标角标，不影响普通链接；
- 修掉列表只能一层、深层引用无深度限制两个真实缺陷。

## 7. 头像

改用官方鲸鱼标识的标准矢量路径（simple-icons deepseek，24×24 轮廓），生成 `res/drawable/ic_ds_logo_white.xml`，`DsAvatar` 不再手绘。

## 本轮验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` / `:app:assembleRelease` | BUILD SUCCESSFUL |
| `:app:lintDebug` | **0 error**，15 warning（依赖版本、modifier 位置等风格项） |
| `:app:testDebugUnitTest` | 5 个用例全部通过 |
| 产物 | `com.shenyu.chat` versionCode 2 / versionName 1.1.0，1.5 MB，签名证书同上 |
| dex 校验 | `/v1/tools/asr`、`/v1/tools/tts`、`voice_id`、`filter_markdown`、`ShenyuApp` 均在 |
| Manifest | `INTERNET` + `RECORD_AUDIO` |

## 仍未完成（1.2.0 待补）

1. 多版本回答切换（官方 BranchSwitcher）；
2. 「继续生成」按钮（截断后接着写）；
3. 搜索结果站点图标（官方 SiteThumbnails）；
4. 附件上传进度与失败重试；
5. 语音识别接口是否为所有 Key 开放未经真实账号验证。

---

# 1.2.0 修复记录（2026-11-18 下午）

## 1. 数学公式不渲染（截图：`[ f(x)=...\frac{...}{2!}... ]` 原样露出）

**根因**：模型用 LaTeX 行间写法 `\[ ... \]` 与行内 `\( ... \)`，而解析器的反斜杠转义规则把开头的 `[` / `(` 当成转义字符吃掉，于是整段公式退化成原始 TeX 文本。

**修复**（`ui/markdown/Markdown.kt`）：
- 新增块级 `\[…\]` 识别（单行自闭合与跨行两种，插在 `$$` 之前）；
- 行内解析在转义处理**之前**先匹配 `\(…\)` 与 `\[…\]`，命中后交给 `MathTex.parse` 做符号映射（分式、根号、上下标），失败也回退为可读文本；
- 保留原有 `$…$` / `$$…$$` 与货币保护。

## 2. 思考被拆成好几段、搜索过程不收起来、来源没有网站图标

**根因**：`AssistantMessageRow` 对 `Block.Thinking` / `Block.Search` 各自 `forEach` 渲染，一条回答里的多个思考块 → 多个「已深度思考（用时 N 秒）」；搜索条目一次性全摊开、结束后也不收起；命中结果只有文字没有站点图标。

**修复**：`MessageList.kt` 改为单个 `ProcessTimeline`：
- 标题一行，状态机：未完成思考 →「正在深度思考…」+ 加载点；搜索进行中 →「正在搜索…」；全部完成 →「已深度思考（用时 N 秒）· 已浏览 N 个页面」+ 最多 5 个 14dp 站点图标；
- 展开区左侧细竖线时间线，思考正文合并成整体（最高 320dp 内滚），每条搜索一行（进行中 / 失败 / N 条结果），完成态默认收起，用户手动展开过后不再自动收起；
- 新增 `ui/base/SiteIcon.kt`：按域名拉 `google.com/s2/favicons`，磁盘缓存 `cacheDir/favicons/<host>.png`，失败降级为首字母圆形色块（六色稳定映射），全程 IO + runCatching，不阻塞主线程。

## 3. 对话没有标题

**修复**：`send()` 里首条消息发出时立刻把标题写成问题前 30 字并 `upsertSession + reloadSessions`，侧栏马上显示可读名字；服务端返回更合适的标题时仍会覆盖。

## 4. 侧边栏：置顶分组 + 多选批量管理

`SiderPanel.kt` 重写：`pinned` 单独成「置顶」组排最前，其余按 今天 / 昨天 / 7 天内 / 30 天内 / 更早；行尾 `⋯` 进入多选，顶部变「已选择 N 个对话」+ 全选/✕，每行勾选圈带颜色与缩放动画，底部批量「置顶 / 取消置顶」「删除」（删除二次确认），进出多选与标题切换全部有 `AnimatedVisibility` / `AnimatedContent` 过渡。ViewModel 新增 `setPinned(ids, pinned)`、`deleteSessions(ids)`，DB 新增事务版 `updatePinned` / `deleteSessions`。

## 5. 设置界面重做 + 传输进度 + 从链接导入

`SettingsScreen.kt` 整文件重写：卡片式分组、字重层级、Tab 指示条 `animateDpAsState` 滑动、内容 `Crossfade`、行内水波纹（全文件不再有 `indication = null`）、分段控件选中态 `animateColorAsState`；顶部「传输进度卡」由 `state.transfer` 驱动（`fraction == null` 走不确定动画，否则平滑过渡），导出/导入/下载三个按钮共享 busy 态，任一进行中其余禁用并显示加载点；新增「从链接导入」输入框（非法链接就地红字，不弹窗）。

ViewModel 侧新增 `TransferProgress` 状态与 `importFromUrl(url)`（OkHttp 流式下载 → 进度回调 → 解析 → 逐条写入并回报进度）、`importHistory` 分阶段进度、`exportHistory` 进度。

## 6. 语音交互改为「按住说话」

输入区新增语音模式：点麦克风切到「按住说话」，`pointerInput + detectTapGestures(onPress)` 按下开录、松手结束并转写，录音中标题变「松开 结束」；右侧图标换成键盘，可切回打字；语音模式下隐藏发送键。

## 7. 顶栏「新建对话」图标自绘

`DsIcons.NewChatCircle`：`ImageVector` 用两段描边路径（圆环 + 加号，1.7 描边、圆头），靠 `Icon(tint=)` 统一着色，替换原来的 `AddComment`。

## 本轮验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` / `:app:assembleRelease` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | 5 个用例通过 |
| `:app:lintDebug` | **0 error** |
| 产物 | versionCode 3 / versionName 1.2.0，1.5 MB，签名证书同上，`sha256 d965d7d7…` |

## 仍未做

1. 引用角标的来源面板（点击 `[citation:N]` 弹出网页列表）；
2. 回答多版本切换、继续生成；
3. 附件「拍照 / 相册 / 文件」三选一菜单与上传进度条；
4. 顶部自动朗读开关。

---

# 1.3.0 按官方 demo 逐页复刻（2026-11-18 傍晚）

参照方式：官方 demo 的 `dist/` 起静态服务，用无头 Chromium 以 390×844@2x 逐页截图（`/ref/01..05-*.png`），
再对着截图与 `src/**/*.module.css` 取值。逐项对照表见 `docs/FIDELITY.md`。

## 本轮改动

| 模块 | 改动 |
|---|---|
| 消息区 | 用户气泡 22dp/10·16dp/容器−88dp；助手操作条 28dp 热区+16dp 图标+10dp 间距、复制 2s「已复制」；条目间距对齐；新增 `ReadingBars`（朗读波形）、`MessageFileCard`（文件卡片 240×64、5 类配色）、`CitationBadge` |
| 多版本回答 | `Message.variants/variantNumber/variantTotal`；`regenerate()` 保留旧回答；`switchVariant()`；UI 为 `‹ 2 / 3 ›`（28dp 行、14dp chevron） |
| 来源面板 | 新增 `ui/search/{SearchPanel,SourceCard,SiteLogo}.kt`：底部抽屉、遮罩淡入、280ms 滑入、卡片字段与官方一致、点击 `ACTION_VIEW` 打开浏览器；入口是时间线摘要「已浏览 N 个页面 + 图标」 |
| 输入区 | 24dp 圆角 / 1dp 边框 / 60dp 起 / 34dp 按钮；按钮顺序按截图改为 语音→附件→发送；新增 `Spectrum` 波形、`DraftAttachments` 草稿卡（图片 64dp 圆角 + 删除钮、文件卡 240dp、横滚） |
| 欢迎页 | 去掉头像，只留「今天有什么可以帮到你？」20sp/28sp + 免责说明；三段落次序淡入 |
| 顶栏 | 空会话只显示菜单；会话页 = 菜单 + 标题 + 自动朗读开关 + 新建（自绘圆环加号）；按钮 44dp 触区 + 圆形按压底 |
| 侧栏 | 品牌名「深语」22sp 粗体 brand 色 + 自绘收起按钮；整宽白底描边胶囊「⊕ 开启新对话」；底部「⚙ 设置」 |
| 设置 | 平铺列表（拆掉卡片）：22sp 标题 + ✕、两枚药丸 Tab、0.5dp 分隔线、主题三卡（88dp）、语言/朗读音色/位置信息/字号 药丸下拉、API Key 大胶囊（掩码 + 铅笔）、导入/导出/删除、从链接导入、接口地址（可改 origin）、崩溃日志 |
| 自动朗读 | `SettingsState.autoSpeak`，流式结束时自动朗读最后一条回答（`wasStreaming` 边沿触发，切会话不误读） |
| 音色 | `SettingsState.ttsVoice`（stella 海星 / tide 暗潮），`SpeechController` 透传 `voice_id` |

## 验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` / `:app:assembleRelease` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | 5 个用例通过 |
| `:app:lintDebug` | **0 error** |
| 产物 | versionCode 4 / versionName 1.3.0，1.5 MB，`sha256 e2956310…` |

## 仍未做

Mermaid 图、引用角标跳转、草稿保存、消息编辑重发、位置信息透传、英文文案。

---

# 1.4.0 功能补齐（2026-11-18 夜）

对着官方 `src/` 逐文件过了一遍，补掉本轮发现的功能缺口。

| 模块 | 改动 |
|---|---|
| 时间线 | `buildReplyParts(blocks)` 按 blocks **出现顺序**构建步骤：相邻 thinking 合并、每个搜索单独成步（kind→搜索/深度搜索/图片搜索/阅读网页）、工具之间的文字记为 preamble（3 行折叠）且不再进正文；正文只取最后一个工具块之后的 text |
| Mermaid | `assets/mermaid.min.js`（11.10.1，2.6MB）+ `assets/mermaid.html` + `ui/markdown/MermaidView.kt`：离线 WebView 渲染，高度经 `@JavascriptInterface` 回传，72dp 首帧占位、150ms 流式防抖、失败保留上一张 |
| 引用角标 | `MarkdownText(onCitationClick)` → 打开来源面板并滚动定位 + 高亮 |
| 来源卡片 | `SearchHit` 增加 `siteName/siteIcon`，卡片图标优先用服务端 `site_icon`，名称用 `site_name` |
| 继续生成 | `continueGenerating()`：最后一条回答作为 prefill，正文接在同一气泡（`Message.prefixText`），操作条加「继续生成」描边按钮 |
| 消息编辑重发 | 长按用户气泡进编辑态，改完重发会删掉这条提问之后的所有消息 |
| 草稿 | `drafts: Map<sessionId, text>`，切会话回来还在，发送后清空 |
| 最新信息提醒 | 请求 messages 里插入 `role=latest_reminder`（日期,星期,地区,Web,中文），地区取设置里的位置信息 |
| 自动朗读 | 顶栏开关 + `autoSpeak`，流式结束时读一遍；发送/切会话先停朗读 |
| 朗读音色 | `ttsVoice`（stella 海星 / tide 暗潮）透传到 TTS 请求 |
| 附件 | 改多选，单文件 ≤64MB、最多 50 个，超限就地红字提示 |
| 免责说明 | 输入框下方统一渲染「内容由 AI 生成，请仔细甄别」 |

## 验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` / `:app:assembleRelease` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | 通过 |
| `:app:lintDebug` | **0 error**（Mermaid 的 WebView 加了 `@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")`） |
| 产物 | versionCode 5 / versionName 1.4.0，2.4 MB（含 mermaid），`sha256 f795e44b…` |

## 仍未做

英文文案、KaTeX 级公式排版、桌面端专属交互、搜索 query 轮播。

---

# 1.5.0 排版与细节对齐（2026-11-18 深夜）

按 `theme.css` 与各组件 `*.module.css` 的**实际数值**校正了一批样式。

| 位置 | 改动 |
|---|---|
| Markdown 正文 | 15/24 → **16/28**；标题改固定值 h1 24/34、h2 22/32、h3 20/30、h4 16/28(600) |
| 用户气泡 | 15/24 → **16/28**；编辑框同步 16 |
| 输入框 | 正文 15/22 → **16/24** |
| 设置下拉 | 弃用 material3 `DropdownMenu`，新写 `DsSelect` 1:1 对齐 `Select.tsx`（36dp/18dp 触发器、菜单 4dp 内边距/12dp 圆角/最大 280×320/12dp 投影/选中打勾/打开时正文变浅） |
| 侧栏 | 品牌名 22→20sp；新建胶囊 48→40dp、15→14sp；会话行 40→44dp、圆角 12→16dp、字号 14→16sp |
| 复制按钮 | 由「图标+已复制文字 2s」改为纯图标对勾 **1s**（对齐 `CopyButton.tsx`） |

验证：编译 / 单测 / lint（0 error）通过；产物 versionCode 6 / 1.5.0，`sha256 d59b7035…`。

---

# 1.6.0 用 Robolectric 真机渲染逐页对照（2026-11-18 深夜）

这一轮先解决了「看不见自己界面」的问题：加了 `app/src/test/java/com/shenyu/chat/ui/ScreenshotTest.kt`，
用 Robolectric 4.13 原生图形 + `decorView.draw(Canvas)` 把 Compose 界面真实渲染成 PNG
（设置页/来源面板在 Dialog 的独立 Window，通过 `ShadowDialog.getLatestDialog()` 取窗绘制）。
产物在 `/tmp/shenyu-shots/`，已发布到 `http://38.55.156.105:8899/shots/`。

有了真实渲染，逐页对官方截图改了一批「结构级」差异：

| 位置 | 改动 |
|---|---|
| 助手消息 | 去掉头像（官方无头像）→ 正文左对齐；底部行合成一行 `‹ 1/2 › 复制 朗读 重新生成 继续生成`（官方 `.bottom`，左移 6dp、gap 10dp、pt 12/pb 10）；删掉官方没有的点赞/点踩；非最后一条的操作条默认隐藏、点正文切换 |
| 摘要行 | 新增正文后的「N 个网页」胶囊（官方 `.summary`：1dp 描边、bgBase 底、圆角 100、mt 16dp、padding 4 10 4 4）；时间线标题里的「· 已浏览 N 个页面」删除（官方无） |
| 时间线文案 | 「已深度思考」→「已思考（用时 N 秒）」（messageThinkDuration）、「正在深度思考…」→「正在思考」、搜索行 `正在搜索 / 图像搜索中... / 已阅读 N 个网页 / 未搜索到相关内容 / 联网搜索暂不可用`；标题行前置 16dp 思考图标 |
| Markdown 表格 | 去掉外框/圆角/表头底色/竖线，改为官方 markdown.css 的行下边框（表头 border-l3、行 border-l2）、单元格 padding 10/16（首列左 0、末列右 0）、字号 15/25 |
| 设置弹层 | 离顶 64dp + 顶部 24dp 圆角（官方 `height: calc(100% - 64px)` + `border-radius 24 24 0 0`）；标题 20sp；Tab 改内容宽度（min 108dp、高 40dp、圆角 12dp、选中 `interactive-bg-hover`）；位置信息改「请选择国家或地区」placeholder + 全量国家地区列表（240+，港澳台固定中文名，其余取系统本地化国名） |
| 侧栏 | 去掉官方没有的「搜索对话」输入框；宽度 300dp；分组标题 14sp/22sp；设置行 16sp |
| 输入区 | 左右边距 16dp；「深度思考」默认关闭（官方默认开→关，chip 才不是蓝色选中态）；未选中 chip 图标用 labelPrimary |
| 顶栏 | 侧栏图标改自绘两条横线（官方是两横，Material 三线汉堡差别明显） |
| 请求协议 | `latest_reminder` 的地区名改系统本地化国名（港澳台固定），五项固定拼接，地区为空也保留占位逗号（官方 `createLatestReminder`） |

## 验证

- `:app:testDebugUnitTest`（含 7 张渲染截图）、`:app:lintDebug` 0 error、`:app:assembleRelease` 通过。
- 产物 versionCode 7 / 1.6.0，`sha256 92c24994…`。

---

# 1.6.1（2026-11-18 傍晚）公式排版 / 并行搜索折叠 / 滚动体感

用户拿真机截图指出三个问题，这轮逐条修：

1. **公式太丑** → 改为 **KaTeX 离线渲染**：把官方 demo 用的 katex 0.16.22（`katex.min.js` +
   `katex.min.css` + 20 个 woff2，共 600 KB）打进 `assets/katex/`，新增 `assets/math.html`
   与 `ui/markdown/MathView.kt`（系统 WebView，`MathBridge.onHeight` 回传高度、`onError` 回退）。
   块级公式走 KaTeX（与官方 `katex.render(tex, {displayMode:true})` 同一套排版，过宽自动等比缩小）；
   KaTeX 未就绪/失败时先画自绘近似（不留空白），2.5 秒超时也回退。
   行内公式去掉了「等宽 + 代码底色」，改衬线斜体 + 上下标。
2. **并行搜索刷一长串行** → 时间线新增分组：连续的同类搜索步骤合并成**一行**
   （`groupTimelineItems` / `SearchGroup`），文案按官方 i18n：进行中「搜索 <query>」（多 query 每 2 秒轮播）、
   完成「已阅读 N 个网页」/「搜索到 N 个网页」（深度搜索）/「浏览 N 个页面」（打开网页），
   行尾挂最多 3 个结果站点图标；任意搜索步骤还在跑时标题显示「正在搜索」并保持展开。
3. **滑动体验差** → 去掉每条消息与时间线上的 `animateContentSize`（流式每帧动画 = 掉帧）；
   自动跟随改成「只在贴底时按实际剩余距离补齐」，不再每个 token `scrollBy(120000f)`；
   「点正文切换操作条」的手势只装在非最后一条上，最后一条不再多一层手势检测。
   另外**生成过程中**时间线标题左侧会显示助手小头像（用户指出官方在生成时显示头像）。

---

# 1.6.2（2026-11-18 夜）滚动被抢修复 + 顶栏/Toast 对齐官方

用户反馈「往上滑它又一直往下跳」，定位到 `MessageList` 里自动跟随的方向判断写反了：

| 问题 | 原因 | 修复 |
| --- | --- | --- |
| 上滑被强行拉回底部 | `onPreScroll` 里判的是 `available.y < -1f`（手指向上＝往新内容走）才关闭自动跟随，方向反了 | 改成 `available.y > 0.5f`（手指往下拖＝想看旧内容）立即关闭自动跟随 |
| 恢复跟随太灵敏 | `snapshotFlow { canScrollForward }` 一旦为 false 就恢复，手势中途会来回横跳 | 改为 `canScrollForward to isScrollInProgress`，必须「到底且手指离开」才恢复 |
| 拖动中被写位置 | 流式补位移在用户拖动时照跑 | 补位移与整表定位都加了 `!listState.isScrollInProgress` 守卫 |

同轮把两处官方基准补齐：

- **顶栏 = 官方 `MobileHeader`**：高 60dp、padding 12/10/4；标题 14sp/22sp SemiBold、左右各 12dp；只保留「菜单 + 标题 + 新建对话」三项（去掉官方没有的自动朗读按钮）。
- **Toast = 官方 `base/Toast.module.css`**：从底部胶囊改成**顶部居中**（距顶 40dp、左右 14dp），圆角 12dp、内边距 12/18、14sp/22sp、细描边 + 阴影，出现时从上方 8dp 滑入淡入（200ms）。

---

# 1.6.3（2026-11-18 深夜）截图流水线修正 + 设置页对齐官方

**截图流水线两个真 bug（此前一直没发现）**

| 现象 | 原因 | 修复 |
| --- | --- | --- |
| `01-app-welcome-light` / `02-…-dark` / `06-settings-general` 三张图字节完全相同 | `capture()` 无脑取 `ShadowDialog.getLatestDialog()`，上一支用例残留的 Dialog 被后续用例误截 | `capture(name, dialog = false)`，只有设置页/来源面板显式传 `dialog = true` |
| `01` 与 `02` 永远一样 | `ShenyuApp` 自己按 `settings.themeMode` 决定明暗，测试的 `dark` 参数被忽略 | 深色用例先 `settings.update { it.copy(themeMode = DARK) }` |

**设置页对齐官方（`settings/SettingsDialog.tsx`）**

- 通用设置官方只有四项：主题 / 语言 / 朗读音色 / 位置信息 —— **删掉官方没有的「字号」行**（`fontScale` 设置项保留在数据层，不再在 UI 暴露）。
- 「数据管理」标签图标换成官方 `IconDataOutline16`（数据库圆柱 + 齿轮，两条 path 原样搬进 `DsIcons.Database`），之前用的是列表图标。

---

# 1.6.4（2026-11-18 深夜二）输入框几何对齐官方（量化验证）

不再靠肉眼，直接量像素比例（截图 390×844，按高度分数比对官方 ref）：

| 元素 | 官方 ref | 深语 1.6.3 | 深语 1.6.4 |
| --- | --- | --- | --- |
| 欢迎页标题行 | 0.445–0.466 | 0.432–0.453 | **0.440–0.462** |
| 输入框上边框 | 0.841 | 0.822 | **0.838** |
| 「内容由 AI 生成」行 | 0.922 | 0.921 | 0.921 |
| 输入框整体高度 | 105.5dp | 117.4dp | **≈106dp** |

原因：官方移动端 `--line-height:24px / --input-padding-top:12px`，
textarea `min-height: calc(24px*1.25 + 12px) = 42dp`（`@media not all and (min-width:440px)`），
`.functionRow { margin-top:4px; padding:12px }`；我们之前写成 textarea 最小 60dp、下内边距 8dp，
多出 12dp。改法：textarea `heightIn(min = 42.dp)` + `padding(top=12)`、功能行 `padding(top=16, 其余12)`。

顺带把侧栏截图改成**按 300dp 宽渲染**（`Box(Modifier.width(300.dp).fillMaxHeight())`），
之前整屏 390dp 渲染，无法和官方 300dp 侧栏做几何比对；顺带修掉 `Modifier.fillMaxSize().width()` 顺序导致宽度被忽略的问题。

---

# 1.6.5（2026-11-18）恢复「从链接导入」

用户明确要求加回「从链接导入」（官方 demo 的数据页只有四行：API Key / 导入 / 导出 / 删除，
这一项是我们自己的增强）。落点、样式与既有行完全一致：

- 位置：数据管理页「导入历史对话」之下、导出之上。
- 结构：标题 16sp + 说明 13sp/20sp + 一行「链接输入框（40dp 胶囊，占满剩余宽度）+ 导入按钮（描边胶囊）」+ 行内红字报错。
- 校验：非 http/https 开头就地红字，不发请求；进行中按钮转圈且整页其余按钮禁用（`busy`）；
  成功 `已导入 N 个对话`，失败显示原因。
- 复用既有 `ChatViewModel.importFromUrl(url, callback)` 与 `SettingsUiState.{link, linkError, linkStatus, importingUrl}`，
  只恢复 UI，不新增业务逻辑。

仓库：`https://github.com/pht88888888/shenyu-chat`（private，main 分支）。

---

# 1.6.6（2026-11-18）从链接导入 OOM 修复（真机反馈）

真机报错：`Failed to allocate a 130721176 byte allocation with 25165824 free bytes and 119MB until OOM, target footprint 167833056, growth limit 268435456`。

**根因**：`ChatViewModel.downloadArchive()` 用 `ByteArrayOutputStream` 把整个包攒在内存里再 `toByteArray()`。
124.7 MB 的备份要连做「攒 + 复制」两份，256 MB 堆直接炸；本地文件导入走的是文件流，所以一直正常。

**修复**

| 位置 | 改动 |
| --- | --- |
| `ChatViewModel.downloadArchive` | 边下边写 `cacheDir` 临时文件（64 KB 缓冲），下完用 `FileInputStream` 流式解析，`finally` 删临时文件；下载进度占总进度 0–85%，解析 90% |
| `HistoryArchive.read` | 每个 `chat_sessions/*.json` 就地解析成 `Session`，不再先攒 `List<Pair<String, JsonObject>>`——真实备份的 JSON 树峰值同样会翻几倍 |

**验证**：新增 JVM 回归用例「从文件流读大包不炸内存」——200 个会话、每会话约 200 KB 正文写进临时 zip 再从文件流读回，条目数与消息数一致。`HistoryArchiveTest` 6/6 通过。

---

# 1.6.7（2026-11-18）教程重出 + 崩溃日志挂回

- **使用教程**：`docs/tutorial/tutorial.html` 版本号升到 1.6.7、APK 名同步；5.1 步骤改成和界面文案一致的
  「导入历史对话 → 上传」，并补一句「百兆级包直接用从链接导入，边下边存不占内存」；5.2 的按钮名改成
  「导出所有历史对话 → 下载」；6.2 新增一行「导入大备份提示下载失败／内存不足」的处理办法。
  用 headless Chromium 重新打印，8 页。
- **崩溃日志挂回**：`CrashLogCell()` 之前是定义了但没挂到界面（教程里却写了），现在重新挂到数据管理页最底部
  （删除全部对话之下，带分隔线），默认折叠，展开看最近几次堆栈并可复制。

