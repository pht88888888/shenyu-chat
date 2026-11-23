package com.shenyu.chat.chat

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shenyu.chat.data.AppLanguage
import com.shenyu.chat.data.Attachment
import com.shenyu.chat.data.Block
import com.shenyu.chat.data.ChatDatabase
import com.shenyu.chat.data.openArchiveStream
import java.io.File
import com.shenyu.chat.data.Citation
import com.shenyu.chat.data.LongDocMode
import com.shenyu.chat.data.Message
import com.shenyu.chat.data.MessageError
import com.shenyu.chat.data.MessageStatus
import com.shenyu.chat.data.ProviderConfig
import com.shenyu.chat.data.ProviderProtocol
import com.shenyu.chat.data.Role
import com.shenyu.chat.data.SearchHit
import com.shenyu.chat.data.SearchStatus
import com.shenyu.chat.data.Session
import com.shenyu.chat.data.SettingsStore
import com.shenyu.chat.net.AnthropicClient
import com.shenyu.chat.net.ApiException
import com.shenyu.chat.net.StreamEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SessionHeaderUi(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean,
)

/** 传输类任务的进度：导入 / 导出 / 从链接下载都用它驱动进度条。 */
data class TransferProgress(
    val label: String,
    /** null 表示进度不可知（转圈） */
    val fraction: Float? = null,
    val detail: String = "",
)

data class ChatUiState(
    val initialized: Boolean = false,
    val sessions: List<SessionHeaderUi> = emptyList(),
    val activeId: String? = null,
    val activeTitle: String = "",
    val messages: List<Message> = emptyList(),
    val streaming: Boolean = false,
    val siderOpen: Boolean = false,
    val toast: String? = null,
    val transfer: TransferProgress? = null,
    /** 侧栏「搜索对话内容」命中的会话 id（正文关键词匹配） */
    val bodyMatchIds: Set<String> = emptySet(),
) {
    val hasSession: Boolean get() = activeId != null

    val isEmptyWelcome: Boolean get() = messages.isEmpty()
}

@SuppressLint("StaticFieldLeak")
class ChatViewModel(
    context: Context,
    private val db: ChatDatabase,
    val settings: SettingsStore,
) : ViewModel() {

    private val appContext: Context = context.applicationContext
    private val client = AnthropicClient()
    private val audio = com.shenyu.chat.net.AudioClient()
    private val webTools = com.shenyu.chat.net.WebToolsClient()
    private val transferClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val archive = com.shenyu.chat.data.HistoryArchive()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 当前会话的内存副本；流式生成期间频繁改动，结束时整体落库。 */
    private var current: Session? = null
    private var streamJob: Job? = null

    /** 已被服务端判定输入超长的会话：后续轮次直接走长上下文通道，省一次 400 往返。 */
    private val longDocSessions = mutableSetOf<String>()

    init {
        viewModelScope.launch { reloadSessions() }
    }

    fun reloadSessions() {
        val headers = db.readSessions().map {
            SessionHeaderUi(
                id = it.id,
                title = it.title ?: "新对话",
                updatedAt = it.updatedAt,
                pinned = it.pinned,
            )
        }
        _state.value = _state.value.copy(initialized = true, sessions = headers)
    }

    fun setSiderOpen(open: Boolean) {
        _state.value = _state.value.copy(siderOpen = open)
    }

    fun toast(message: String) {
        _state.value = _state.value.copy(toast = message)
    }

    fun consumeToast() {
        if (_state.value.toast != null) {
            _state.value = _state.value.copy(toast = null)
        }
    }

    fun newSession() {
        stopGenerating()
        current = null
        _state.value = _state.value.copy(
            activeId = null,
            activeTitle = "",
            messages = emptyList(),
            streaming = false,
            siderOpen = false,
        )
    }

    fun selectSession(id: String) {
        if (_state.value.activeId == id && !_state.value.streaming) {
            _state.value = _state.value.copy(siderOpen = false)
            return
        }
        stopGenerating()
        val header = db.readSessions().firstOrNull { it.id == id } ?: return
        val messages = db.readMessages(id)
        current = Session(
            id = id,
            title = header.title,
            createdAt = header.createdAt,
            updatedAt = header.updatedAt,
            pinned = header.pinned,
            messages = messages,
        )
        _state.value = _state.value.copy(
            activeId = id,
            activeTitle = header.title ?: messages.firstOrNull { it.role == Role.USER }?.plainText?.take(30).orEmpty(),
            messages = messages,
            streaming = false,
            siderOpen = false,
        )
    }

    fun deleteSession(id: String) {
        db.deleteSession(id)
        if (_state.value.activeId == id) {
            newSession()
        }
        reloadSessions()
    }

    fun renameSession(id: String, title: String) {
        val trimmed = title.trim().take(60)
        db.updateSessionMeta(id, trimmed.ifBlank { null }, System.currentTimeMillis())
        if (_state.value.activeId == id) {
            current?.let { current = it.copy(title = trimmed) }
            _state.value = _state.value.copy(activeTitle = trimmed)
        }
        reloadSessions()
    }

    fun deleteAllSessions() {
        stopGenerating()
        db.deleteAll()
        current = null
        _state.value = _state.value.copy(
            activeId = null,
            activeTitle = "",
            messages = emptyList(),
            streaming = false,
            sessions = emptyList(),
        )
    }

    /**
     * 发送一条提问：立刻插入用户消息与空的助手消息，然后开始流式生成。
     * 若当前没有会话则新建一个。
     */
    fun send(text: String, attachments: List<Attachment> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        if (_state.value.streaming) return

        val settingsState = settings.current
        if (!settingsState.hasApiKey) {
            toast("请先在设置里填入 API Key")
            return
        }

        val session = current ?: Session().also {
            current = it
            db.upsertSession(it)
        }

        // 首条消息一发出就先把标题写进库：侧栏立刻显示可读的名字，而不是一串「新对话」
        if (session.title.isNullOrBlank()) {
            val title = trimmed.take(30).replace('\n', ' ').trim()
            if (title.isNotBlank()) {
                current = session.copy(title = title)
                db.upsertSession(current!!)
                reloadSessions()
            }
        }

        val userMessage = Message(
            role = Role.USER,
            blocks = listOf(Block.Text(trimmed)),
            status = MessageStatus.FINISHED,
            attachments = attachments,
        )
        val assistantMessage = Message(
            role = Role.ASSISTANT,
            blocks = emptyList(),
            status = MessageStatus.STREAMING,
        )
        val base = current ?: session
        val messages = base.messages + userMessage + assistantMessage
        current = base.copy(messages = messages, updatedAt = System.currentTimeMillis())
        _state.value = _state.value.copy(
            activeId = base.id,
            activeTitle = base.title ?: trimmed.take(30),
            messages = messages,
            streaming = true,
        )
        db.upsertMessage(session.id, userMessage)

        startStream(assistantId = assistantMessage.id, pending = attachments)
    }

    /** 重新生成最后一条回复：丢掉最后一条助手消息后重发。 */
    fun regenerate() {
        if (_state.value.streaming) return
        val session = current ?: return
        val messages = session.messages.toMutableList()
        var previous: Message? = null
        while (messages.isNotEmpty() && messages.last().role == Role.ASSISTANT) {
            val removed = messages.removeAt(messages.lastIndex)
            if (previous == null) previous = removed
        }
        if (messages.isEmpty()) return

        // 旧回答不丢弃：作为「上一个版本」挂到新回答上，用户可以用 ‹ 2/3 › 切回去
        val carried = buildList {
            previous?.let {
                add(it.copy(variants = emptyList(), variantNumber = 1))
                addAll(it.variants)
            }
        }.sortedBy { it.variantNumber }

        val placeholder = Message(
            role = Role.ASSISTANT,
            status = MessageStatus.STREAMING,
            createdAt = (messages.lastOrNull()?.createdAt ?: 0L) + 1,
            variants = carried,
            variantNumber = carried.size + 1,
        )
        val next = (messages + placeholder).toList()
        current = session.copy(messages = next, updatedAt = System.currentTimeMillis())
        _state.value = _state.value.copy(messages = next, streaming = true)
        startStream(assistantId = placeholder.id)
    }

    /** 继续生成：把最后一条回答当作 prefill，让模型接着往下写。 */
    fun continueGenerating() {
        if (_state.value.streaming) return
        val session = current ?: return
        val last = session.messages.lastOrNull() ?: return
        if (last.role != Role.ASSISTANT || last.plainText.isBlank()) return
        if (last.status == MessageStatus.FAILED) return
        val prefix = last.plainText
        val placeholder = last.copy(
            blocks = emptyList(),
            status = MessageStatus.STREAMING,
            error = null,
            prefixText = prefix,
        )
        val next = session.messages.dropLast(1) + placeholder
        current = session.copy(messages = next, updatedAt = System.currentTimeMillis())
        _state.value = _state.value.copy(messages = next, streaming = true)
        startStream(assistantId = placeholder.id, prefill = prefix)
    }

    /**
     * 编辑用户消息并重新提问：删掉这条提问之后的全部消息，用新文字重发。
     * 官方 UserMessage 编辑器就是这个语义。
     */
    fun editMessageAndResend(messageId: String, newText: String) {
        if (_state.value.streaming) return
        val session = current ?: return
        val index = session.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val target = session.messages[index]
        if (target.role != Role.USER) return
        val text = newText.trim()
        if (text.isBlank()) return
        // 更新提问正文，保留附件
        val edited = target.copy(blocks = listOf(Block.Text(text)))
        val kept = session.messages.subList(0, index)
        val placeholder = Message(
            role = Role.ASSISTANT,
            status = MessageStatus.STREAMING,
            createdAt = edited.createdAt + 1,
        )
        val next = kept + edited + placeholder
        current = session.copy(messages = next, updatedAt = System.currentTimeMillis())
        _state.value = _state.value.copy(messages = next, streaming = true)
        persist()
        startStream(assistantId = placeholder.id)
    }

    /**
     * 修改用户消息并创建分支（官方 message_press_edit_to_branch）：
     * 原回答不丢弃，降级成新回答的版本 1，新回答是版本 2，可在 ‹ › 之间切换。
     */
    fun editMessageAndBranch(messageId: String, newText: String) {
        if (_state.value.streaming) return
        val session = current ?: return
        val index = session.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val target = session.messages[index]
        if (target.role != Role.USER) return
        val text = newText.trim()
        if (text.isBlank()) return

        val edited = target.copy(blocks = listOf(Block.Text(text)))
        val kept = session.messages.subList(0, index)
        val oldAnswer = session.messages.getOrNull(index + 1)
            ?.takeIf { it.role == Role.ASSISTANT && it.plainText.isNotBlank() }
        // 旧回答（含它的历史版本）平铺后重新编号 1..n，新回答排在 n+1
        val branches = oldAnswer
            ?.let { listOf(it) + it.variants }
            ?.sortedBy { it.variantNumber }
            ?.mapIndexed { i, m -> m.copy(variantNumber = i + 1, variants = emptyList()) }
            .orEmpty()

        val placeholder = Message(
            role = Role.ASSISTANT,
            status = MessageStatus.STREAMING,
            createdAt = edited.createdAt + 1,
            variants = branches,
            variantNumber = branches.size + 1,
        )
        val next = kept + edited + placeholder
        current = session.copy(messages = next, updatedAt = System.currentTimeMillis())
        _state.value = _state.value.copy(messages = next, streaming = true)
        persist()
        startStream(assistantId = placeholder.id)
        toast("已创建分支")
    }

    /** 在多版本回答之间切换（BranchSwitcher 的 ‹ ›）。 */
    fun switchVariant(messageId: String, targetNumber: Int) {
        if (_state.value.streaming) return
        val session = current ?: return
        val messages = session.messages.toMutableList()
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val shown = messages[index]
        val all = (listOf(shown) + shown.variants).sortedBy { it.variantNumber }
        val target = all.firstOrNull { it.variantNumber == targetNumber } ?: return
        if (target.id == shown.id) return
        messages[index] = target.copy(
            variants = all.filter { it.id != target.id },
            variantNumber = target.variantNumber,
        )
        current = session.copy(messages = messages)
        _state.value = _state.value.copy(messages = messages)
        persist()
    }

    fun stopGenerating() {
        streamJob?.cancel()
        streamJob = null
        val session = current
        if (session != null && _state.value.streaming) {
            val messages = session.messages.map { msg ->
                if (msg.status == MessageStatus.STREAMING) msg.copy(
                    status = MessageStatus.STOPPED,
                    blocks = msg.blocks.map { block -> when (block) {
                        is Block.Thinking -> block.copy(done = true)
                        is Block.Search -> if (block.status == SearchStatus.RUNNING) block.copy(status = SearchStatus.FAILED) else block
                        else -> block
                    } },
                ) else msg
            }
            current = session.copy(messages = messages)
            _state.value = _state.value.copy(messages = messages, streaming = false)
            persist()
        }
    }

    private fun startStream(
        assistantId: String,
        pending: List<Attachment> = emptyList(),
        prefill: String? = null,
    ) {
        val settingsState = settings.current
        val provider = settingsState.activeProvider
        if (settingsState.searchEnabled && !provider.protocol.supportsDeepSeekExtensions) {
            toast("当前供应商不支持联网搜索，本条消息按普通对话发送")
        }
        streamJob = viewModelScope.launch {
            try {
                val session = current ?: return@launch
                val history = session.messages.filter { it.id != assistantId }
                // 非 DeepSeek 通道没有 Files API：新附件与历史附件都会让模型读不到内容，提前说明。
                if (!provider.supportsFiles &&
                    (pending.isNotEmpty() || history.any { m -> m.attachments.any { it.fileId != null } })
                ) {
                    throw ApiException(
                        ApiException.Kind.PROTOCOL,
                        "当前供应商（${provider.label}）不支持附件，请切换到 DeepSeek 官方供应商，或新建不含附件的对话。",
                    )
                }
                // 附件先上传拿到 file_id，再发消息；失败则不发送正文
                pending.filter { it.fileId == null }.forEach { attachment ->
                    val uploaded = uploadAttachment(attachment, provider)
                    replaceAttachment(attachment.name, uploaded)
                }
                val hasFile = (current?.messages ?: history).any { m -> m.attachments.any { it.fileId != null } }
                // 只有官方供应商才走服务端 deepseek_* 工具；自建/中转的 DeepSeek 兼容
                // 站点通常只代理 /v1/messages，不会替客户端执行工具，改走客户端工具循环。
                var enhanced = provider.builtin && provider.protocol.supportsDeepSeekExtensions &&
                    useEnhancedChannel(session.id, history, settingsState)
                while (true) {
                    val body = buildRequest(
                        history = current?.messages?.filter { it.id != assistantId } ?: history,
                        provider = provider,
                        thinking = settingsState.thinkingEnabled,
                        search = settingsState.searchEnabled,
                        prefill = prefill,
                        enhanced = enhanced,
                        withTitle = current?.title.isNullOrBlank(),
                    )
                    try {
                        if (enhanced || !provider.protocol.supportsDeepSeekExtensions) {
                            client.stream(
                                provider = provider,
                                body = body,
                                hasFile = hasFile,
                            ).collect { event -> applyEvent(assistantId, event) }
                        } else {
                            ClientToolRunner(
                                model = { request -> client.stream(
                                    provider = provider,
                                    body = request,
                                    hasFile = hasFile,
                                ) },
                                execute = { name, input -> webTools.execute(
                                    com.shenyu.chat.net.ProviderEndpoints.serviceOrigin(provider),
                                    provider.apiKey,
                                    name,
                                    input,
                                ) },
                            ).run(body) { event -> applyEvent(assistantId, event) }
                        }
                        break
                    } catch (e: ApiException) {
                        // Only retry a confirmed enhanced-path input cap, before any answer was streamed.
                        if (enhanced && settingsState.longDocMode != LongDocMode.OFF &&
                            e.status == 400 && ContextBudget.isInputTooLong(e.message) &&
                            (ContextBudget.reportedLimit(e.message) ?: Int.MAX_VALUE) <= ContextBudget.ENHANCED_INPUT_LIMIT
                        ) {
                            enhanced = false
                            longDocSessions += session.id
                            toast("正在切换长上下文，全文和联网功能均会保留")
                            continue
                        }
                        throw e
                    }
                }
                finishMessage(assistantId, MessageStatus.FINISHED, null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ApiException) {
                finishMessage(assistantId, MessageStatus.FAILED, friendlyError(e))
            } catch (e: Exception) {
                finishMessage(assistantId, MessageStatus.FAILED, e.message ?: "生成失败")
            }
        }
    }

    /**
     * 这一轮要不要走「增强通道」（标题 / 增强风控 / 联网工具）。
     * 增强通道的输入被服务端卡在 32,768 Token；普通通道实测可到 1M。
     */
    private fun useEnhancedChannel(
        sessionId: String,
        history: List<Message>,
        settingsState: com.shenyu.chat.data.SettingsState,
    ): Boolean = when (settingsState.longDocMode) {
        LongDocMode.ALWAYS -> false
        LongDocMode.OFF -> true
        LongDocMode.AUTO -> {
            // Files are expanded at the server, so keep documents on the 1M path from their first turn.
            if (sessionId in longDocSessions || history.any { m -> m.attachments.any { !it.isImage } }) {
                false
            } else {
                val estimate = ContextBudget.estimateTokens(
                    history.joinToString("\n") { it.plainText },
                )
                estimate <= ContextBudget.PROACTIVE_INPUT_BUDGET
            }
        }
    }

    /** 服务端报错翻译成用户能看懂、能动手的说明；超长类错误单独处理。 */
    private fun friendlyError(e: ApiException): String {
        val message = e.message
        if (ContextBudget.isInputTooLong(message)) {
            return ContextBudget.explainTooLong(message, settings.current.longDocMode)
        }
        return message
    }

    /** 读取本地文件并通过 Files API 上传，返回带 fileId 的附件。 */
    private suspend fun uploadAttachment(
        attachment: Attachment,
        provider: ProviderConfig,
    ): Attachment = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val uri = android.net.Uri.parse(attachment.localPath)
            ?: throw ApiException(ApiException.Kind.NETWORK, "附件路径无效")
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw ApiException(ApiException.Kind.NETWORK, "无法读取附件")
        val uploaded = client.uploadFile(
            provider = provider,
            name = attachment.name,
            mime = attachment.mime,
            input = input,
            length = attachment.size,
        )
        attachment.copy(fileId = uploaded.id, name = uploaded.name, mime = uploaded.mime)
    }

    /** 上传完成后把当前用户消息里的同名附件替换成带 fileId 的版本。 */
    private fun replaceAttachment(name: String, uploaded: Attachment) {
        val session = current ?: return
        val messages = session.messages.map { message ->
            if (message.attachments.none { it.name == name }) {
                message
            } else {
                message.copy(
                    attachments = message.attachments.map { if (it.name == name) uploaded else it },
                )
            }
        }
        current = session.copy(messages = messages)
        _state.value = _state.value.copy(messages = messages)
    }

    private fun finishMessage(assistantId: String, status: MessageStatus, error: String?) {
        val session = current ?: return
        val messages = session.messages.map { msg ->
            when {
                msg.id != assistantId -> msg
                status == MessageStatus.FINISHED && msg.blocks.isEmpty() -> msg.copy(
                    status = MessageStatus.FAILED,
                    error = MessageError("服务端没有返回内容，请重试"),
                )

                else -> msg.copy(
                    status = status,
                    error = error?.let { MessageError(it) },
                    blocks = msg.blocks.map { block -> when (block) {
                        is Block.Thinking -> block.copy(done = true)
                        is Block.Search -> if (block.status == SearchStatus.RUNNING) block.copy(status = SearchStatus.FAILED) else block
                        else -> block
                    } },
                )
            }
        }
        current = session.copy(messages = messages, updatedAt = System.currentTimeMillis())
        _state.value = _state.value.copy(messages = messages, streaming = false)
        streamJob = null
        persist()
    }

    /** 把内存里的会话写盘，并刷新侧栏列表。 */
    private fun persist() {
        val session = current ?: return
        val title = session.title ?: session.messages.firstOrNull { it.role == Role.USER }
            ?.plainText?.take(30)?.replace('\n', ' ')
        val saved = session.copy(title = title, updatedAt = System.currentTimeMillis())
        current = saved
        db.saveSession(saved)
        reloadSessions()
        if (_state.value.activeId == saved.id) {
            _state.value = _state.value.copy(activeTitle = title ?: "")
        }
    }

    private fun applyEvent(assistantId: String, event: StreamEvent) {
        val session = current ?: return
        val messages = session.messages.toMutableList()
        val index = messages.indexOfFirst { it.id == assistantId }
        if (index < 0) return
        val target = messages[index]
        val blocks = target.blocks.toMutableList()
        val now = System.currentTimeMillis()

        // 服务端下发的块序号理论上很小；一旦异常放大到几十万，补齐空块会直接把内存打爆
        fun safeIndex(raw: Int): Int = raw.coerceIn(0, 63)
        fun ensure(index: Int): Block? = blocks.getOrNull(index)

        when (event) {
            is StreamEvent.TextStart -> {
                while (blocks.size <= safeIndex(event.index)) blocks += Block.Text("")
                if (safeIndex(event.index) < blocks.size && blocks[safeIndex(event.index)] !is Block.Text) {
                    blocks[safeIndex(event.index)] = Block.Text("")
                }
                blocks[safeIndex(event.index)] = Block.Text(event.text)
            }

            is StreamEvent.ThinkingStart -> {
                while (blocks.size <= safeIndex(event.index)) blocks += Block.Text("")
                blocks[safeIndex(event.index)] = Block.Thinking(event.text, done = false)
            }

            is StreamEvent.SearchToolUse -> {
                while (blocks.size <= safeIndex(event.index)) blocks += Block.Text("")
                blocks[safeIndex(event.index)] = Block.Search(
                    kind = event.kind,
                    queries = event.queries.ifEmpty { listOfNotNull(event.url.ifBlank { null }) },
                    status = SearchStatus.RUNNING,
                )
            }

            is StreamEvent.SearchResults -> {
                val existing = ensure(safeIndex(event.index)) as? Block.Search
                if (safeIndex(event.index) < blocks.size) {
                    blocks[safeIndex(event.index)] = Block.Search(
                        kind = existing?.kind ?: event.kind,
                        queries = existing?.queries ?: emptyList(),
                        status = SearchStatus.DONE,
                        hits = event.hits.map {
                            SearchHit(
                                it.title,
                                it.url,
                                it.snippet,
                                it.date,
                                it.siteName,
                                it.siteIcon,
                            )
                        },
                    )
                }
            }

            is StreamEvent.SearchFailed -> {
                val existing = ensure(safeIndex(event.index)) as? Block.Search
                if (safeIndex(event.index) < blocks.size) {
                    blocks[safeIndex(event.index)] = Block.Search(
                        kind = existing?.kind ?: event.kind,
                        queries = existing?.queries ?: emptyList(),
                        status = SearchStatus.FAILED,
                    )
                }
            }

            is StreamEvent.TextDelta -> {
                val currentBlock = ensure(safeIndex(event.index))
                if (currentBlock is Block.Text) {
                    blocks[safeIndex(event.index)] = currentBlock.copy(text = currentBlock.text + event.text)
                } else if (currentBlock == null) {
                    while (blocks.size < safeIndex(event.index)) blocks += Block.Text("")
                    blocks += Block.Text(event.text)
                }
            }

            is StreamEvent.ThinkingDelta -> {
                val currentBlock = ensure(safeIndex(event.index))
                if (currentBlock is Block.Thinking) {
                    blocks[safeIndex(event.index)] = currentBlock.copy(text = currentBlock.text + event.text)
                }
            }

            is StreamEvent.CitationDelta -> {
                val lastIndex = blocks.indexOfLast { it is Block.Text }
                if (lastIndex >= 0) {
                    val textBlock = blocks[lastIndex] as Block.Text
                    val nextIndex = textBlock.citations.size + 1
                    if (textBlock.citations.none { it.url == event.url }) {
                        blocks[lastIndex] = textBlock.copy(
                            citations = textBlock.citations + Citation(event.title, event.url, nextIndex),
                        )
                    }
                }
            }

            is StreamEvent.Finished -> {
                val title = event.title
                if (!title.isNullOrBlank()) {
                    current = session.copy(title = title.take(60))
                    _state.value = _state.value.copy(activeTitle = title)
                }
                // 思考块收尾，记录耗时
                val startedThinking = target.blocks.any { it is Block.Thinking && !it.done }
                if (startedThinking) {
                    for (i in blocks.indices) {
                        val b = blocks[i]
                        if (b is Block.Thinking && !b.done) {
                            val seconds = ((now - target.createdAt) / 1000).toInt().coerceAtLeast(1)
                            blocks[i] = b.copy(done = true, seconds = seconds)
                        }
                    }
                }
            }

            else -> Unit
        }

        messages[index] = target.copy(blocks = blocks)
        current = session.copy(messages = messages)
        _state.value = _state.value.copy(messages = messages)
    }

    /** 组装 anthropic 兼容请求体。 */
    private fun buildRequest(
        history: List<Message>,
        provider: ProviderConfig,
        thinking: Boolean,
        search: Boolean,
        prefill: String? = null,
        enhanced: Boolean = true,
        withTitle: Boolean = true,
    ): JsonObject {
        val extensions = provider.protocol.supportsDeepSeekExtensions
        return MessagePayloads.build(
            messages = buildMessages(
                history = history,
                reminder = if (extensions) latestReminder() else null,
                prefill = prefill,
            ),
            options = MessagePayloads.Options(
                model = provider.effectiveModel,
                system = if (provider.builtin) SYSTEM_PROMPT else GENERIC_SYSTEM_PROMPT,
                thinking = thinking && provider.supportsThinking,
                search = search && extensions,
                enhanced = enhanced && extensions,
                withTitle = withTitle && extensions,
                clientTools = extensions,
                maxTokens = if (provider.protocol == ProviderProtocol.ANTHROPIC) ANTHROPIC_MAX_TOKENS else null,
                thinkingBudget = if (
                    provider.protocol == ProviderProtocol.ANTHROPIC && thinking && provider.supportsThinking
                ) {
                    ANTHROPIC_THINKING_BUDGET
                } else {
                    null
                },
            ),
        )
    }

    /**
     * 官方协议里的「最新信息提醒」：以 role=latest_reminder 插在消息流里，
     * 内容是「日期,星期,地区,Web,语言」，让模型知道今天的真实时间与地点。
     */
    private fun latestReminder(): String {
        val cal = java.util.Calendar.getInstance()
        val date = String.format(
            java.util.Locale.US,
            "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
        val weekday = WEEKDAYS[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        // 官方 createLatestReminder：固定五项，地区为空也保留占位逗号
        val region = regionName(settings.current.region)
        return listOf(date, weekday, region, "Web", "中文").joinToString(",")
    }

    /** 地区码 → 中文地区名：港澳台固定，其余取系统本地化国名（官方 getRegionName 同义）。 */
    private fun regionName(code: String): String {
        if (code.isBlank()) return ""
        return when (code) {
            "TW" -> "中国台湾"
            "HK" -> "中国香港"
            "MO" -> "中国澳门"
            else -> java.util.Locale("", code).getDisplayCountry(java.util.Locale.SIMPLIFIED_CHINESE)
                .takeIf { it.isNotBlank() } ?: code
        }
    }

    private fun buildMessages(
        history: List<Message>,
        reminder: String? = null,
        prefill: String? = null,
    ): JsonArray = buildJsonArray {
        if (!reminder.isNullOrBlank()) {
            add(
                buildJsonObject {
                    put("role", "latest_reminder")
                    put("content", reminder)
                }
            )
        }
        history.forEach { message ->
            when (message.role) {
                Role.USER -> {
                    val content = message.attachments.mapNotNull { it.fileId }
                    if (content.isEmpty()) {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", message.plainText)
                            }
                        )
                    } else {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        content.forEach { fileId ->
                                            val attachment = message.attachments.first { it.fileId == fileId }
                                            add(
                                                buildJsonObject {
                                                    put("type", if (attachment.isImage) "image" else "document")
                                                    put(
                                                        "source",
                                                        buildJsonObject {
                                                            put("type", "file")
                                                            put("file_id", fileId)
                                                        },
                                                    )
                                                }
                                            )
                                        }
                                        if (message.plainText.isNotBlank()) {
                                            add(
                                                buildJsonObject {
                                                    put("type", "text")
                                                    put("text", message.plainText)
                                                }
                                            )
                                        }
                                    },
                                )
                            }
                        )
                    }
                }

                Role.ASSISTANT -> {
                    val text = message.plainText
                    if (text.isNotBlank() && message.status != MessageStatus.FAILED) {
                        add(
                            buildJsonObject {
                                put("role", "assistant")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", text)
                                            }
                                        )
                                    },
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    /** 导出全部对话为 deepseek_data-*.zip，回调带文件路径。 */
    fun exportHistory(callback: (Result<File>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                _state.value = _state.value.copy(
                    transfer = TransferProgress("正在打包对话", null),
                )
                val sessions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    db.readSessions().mapNotNull { header ->
                    current?.takeIf { it.id == header.id } ?: Session(
                        id = header.id,
                        title = header.title,
                        createdAt = header.createdAt,
                        updatedAt = header.updatedAt,
                        pinned = header.pinned,
                        messages = db.readMessages(header.id),
                    )
                    }
                }
                _state.value = _state.value.copy(
                    transfer = TransferProgress("正在写入文件", 0.9f, "共 ${sessions.size} 个对话"),
                )
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    archive.export(File(appContext.filesDir, "exports"), sessions)
                }
            }
            _state.value = _state.value.copy(transfer = null)
            callback(result)
        }
    }

    /** 导入官方导出包，回调带新增会话条数。 */
    /**
     * 喜欢 / 不喜欢（对应官方消息操作条的 like、dislike）。
     * value=1 喜欢、-1 不喜欢、0 取消；再点一次同一按钮即取消。
     */
    fun setFeedback(messageId: String, value: Int) {
        val session = current ?: return
        val index = session.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val target = session.messages[index]
        val next = if (target.feedback == value) 0 else value
        val messages = session.messages.toMutableList().also { it[index] = target.copy(feedback = next) }
        current = session.copy(messages = messages)
        _state.value = _state.value.copy(messages = messages)
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.updateFeedback(session.id, messageId, next)
            }
        }
        when (next) {
            1 -> toast("感谢反馈")
            -1 -> toast("感谢反馈，我们会继续改进")
            else -> Unit
        }
    }

    private var bodySearchJob: Job? = null

    /** 侧栏搜索：按消息正文匹配会话（防抖由调用方用 LaunchedEffect 控制）。 */
    fun searchBody(query: String) {
        bodySearchJob?.cancel()
        if (query.isBlank()) {
            _state.value = _state.value.copy(bodyMatchIds = emptySet())
            return
        }
        bodySearchJob = viewModelScope.launch {
            val ids = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { db.searchBodySessions(query) }.getOrDefault(emptySet())
            }
            _state.value = _state.value.copy(bodyMatchIds = ids)
        }
    }

    /** 批量置顶 / 取消置顶。 */
    fun setPinned(ids: Collection<String>, pinned: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.updatePinned(ids, pinned)
            }
            reloadSessions()
            toast(if (pinned) "已置顶 ${ids.size} 个对话" else "已取消置顶")
        }
    }

    /** 批量删除。 */
    fun deleteSessions(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.deleteSessions(ids)
            }
            if (_state.value.activeId in ids) {
                current = null
                _state.value = _state.value.copy(activeId = null, activeTitle = "", messages = emptyList())
            }
            reloadSessions()
            toast("已删除 ${ids.size} 个对话")
        }
    }

    /** 从本机文件导入（带进度）。 */
    fun importHistory(uri: android.net.Uri, callback: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                _state.value = _state.value.copy(
                    transfer = TransferProgress("正在读取备份", null),
                )
                val sessions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    appContext.openArchiveStream(uri).use { input -> archive.read(input) }
                }
                writeSessions(sessions)
            }
            _state.value = _state.value.copy(transfer = null)
            callback(result)
        }
    }

    /** 直接从官方导出的下载链接导入（带下载进度）。 */
    fun importFromUrl(url: String, callback: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val link = url.trim()
                if (!link.startsWith("http://") && !link.startsWith("https://")) {
                    throw IllegalArgumentException("请填写 http 或 https 开头的下载链接")
                }
                val sessions = downloadArchive(link)
                writeSessions(sessions)
            }
            _state.value = _state.value.copy(transfer = null)
            callback(result)
        }
    }

    private suspend fun writeSessions(sessions: List<Session>): Int {
        if (sessions.isEmpty()) throw IllegalArgumentException("备份里没有可导入的对话")
        sessions.forEachIndexed { index, session ->
            _state.value = _state.value.copy(
                transfer = TransferProgress(
                    label = "正在导入对话",
                    fraction = (index + 1f) / sessions.size,
                    detail = "${index + 1} / ${sessions.size}",
                ),
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // 同 id 的旧会话先删掉，避免出现两条同名对话
                db.deleteSession(session.id)
                db.saveSession(session)
            }
        }
        reloadSessions()
        return sessions.size
    }

    private suspend fun downloadArchive(url: String): List<Session> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            _state.value = _state.value.copy(
                transfer = TransferProgress("正在连接", null),
            )
            val request = okhttp3.Request.Builder().url(url).build()
            transferClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw com.shenyu.chat.net.ApiException(
                        com.shenyu.chat.net.ApiException.Kind.HTTP,
                        "下载失败：HTTP ${response.code}",
                        response.code,
                    )
                }
                val body = response.body ?: throw com.shenyu.chat.net.ApiException(
                    com.shenyu.chat.net.ApiException.Kind.PROTOCOL,
                    "下载内容为空",
                )
                val total = body.contentLength()
                // 备份包动辄上百 MB（实测 124.7 MB），绝不能整个读进内存：
                // 之前用 ByteArrayOutputStream 攒完再 toByteArray()，在 256 MB 堆的机器上直接
                // 「Failed to allocate a 130721176 byte allocation ... until OOM」。
                // 现在边下边写进 cacheDir 的临时文件，再按流解析，内存占用只剩 64 KB 缓冲。
                val tmp = java.io.File.createTempFile("import-", ".zip", appContext.cacheDir)
                try {
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(tmp).buffered().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var read = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                out.write(buffer, 0, n)
                                read += n
                                val fraction = if (total > 0) {
                                    (read.toFloat() / total).coerceIn(0f, 1f) * 0.85f
                                } else {
                                    null
                                }
                                _state.value = _state.value.copy(
                                    transfer = TransferProgress(
                                        label = "正在下载备份",
                                        fraction = fraction,
                                        detail = if (total > 0) {
                                            "${formatBytes(read)} / ${formatBytes(total)}"
                                        } else {
                                            formatBytes(read)
                                        },
                                    ),
                                )
                            }
                        }
                    }
                    _state.value = _state.value.copy(
                        transfer = TransferProgress("正在解析备份", 0.9f),
                    )
                    tmp.inputStream().use { archive.read(it) }
                } finally {
                    tmp.delete()
                }
            }
        }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    fun verifyApiKey(provider: ProviderConfig, callback: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            // 必须切到 IO：verifyKey 内部是阻塞式 OkHttp 调用，主线程会抛 NetworkOnMainThreadException
            val target = provider.copy(apiKey = provider.apiKey.trim())
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.verifyKey(target, target.apiKey)
            }
            callback(result)
        }
    }

    fun language(): AppLanguage = settings.current.language

    /** 供 UI 层读取当前设置（朗读、语音识别要拿 Key 与接口地址）。 */
    fun settingsSnapshot(): com.shenyu.chat.data.SettingsState = settings.current

    /** 语音识别：把录音 PCM 包成 WAV 交给官方 ASR 接口，回调识别文本。 */
    fun transcribeVoice(
        pcm: ByteArray,
        callback: (Result<String>) -> Unit,
    ) {
        viewModelScope.launch {
            val current = settings.current
            val provider = current.activeProvider
            val result = runCatching {
                if (current.apiKey.isBlank()) {
                    throw com.shenyu.chat.net.ApiException(
                        com.shenyu.chat.net.ApiException.Kind.AUTH,
                        "请先在设置里填写 API Key",
                    )
                }
                if (!provider.supportsVoice) {
                    throw com.shenyu.chat.net.ApiException(
                        com.shenyu.chat.net.ApiException.Kind.PROTOCOL,
                        "当前供应商不支持语音识别，请切换到 DeepSeek 官方供应商。",
                    )
                }
                audio.transcribe(
                    origin = com.shenyu.chat.net.ProviderEndpoints.serviceOrigin(provider),
                    apiKey = provider.apiKey,
                    wav = com.shenyu.chat.net.pcmToWav(pcm),
                )
            }
            callback(result)
        }
    }

    /** 朗读用的音频客户端，UI 层直接复用同一个实例。 */
    val audioClient: com.shenyu.chat.net.AudioClient = audio

    companion object {
        private const val ANTHROPIC_MAX_TOKENS = 8192
        private const val ANTHROPIC_THINKING_BUDGET = 4096
        private val WEEKDAYS = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        const val SYSTEM_PROMPT = "该助手为DeepSeek，由深度求索公司创造。"
        const val GENERIC_SYSTEM_PROMPT = "你是一个乐于助人的 AI 助手。回答准确、简洁，使用与用户相同的语言。"

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                return ChatViewModel(
                    context = appContext,
                    db = ChatDatabase(appContext),
                    settings = SettingsStore(appContext),
                ) as T
            }
        }
    }
}
