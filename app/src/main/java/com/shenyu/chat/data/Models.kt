package com.shenyu.chat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** 会话：一条对话，包含一条消息树（这里按线性列表保存当前分支）。 */
@Serializable
data class Session(
    val id: String = UUID.randomUUID().toString(),
    val title: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val messages: List<Message> = emptyList(),
) {
    /** 侧栏展示用标题：首条提问的前若干字。 */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: messages.firstOrNull { it.role == Role.USER }?.plainText?.take(30)?.replace('\n', ' ')
            ?: "新对话"
}

@Serializable
enum class Role { USER, ASSISTANT }

@Serializable
enum class MessageStatus {
    /** 正在生成 */
    STREAMING,

    /** 正常结束 */
    FINISHED,

    /** 用户手动停止 */
    STOPPED,

    /** 出错 */
    FAILED,
}

/** 正文里的引用标记还原出的来源。 */
@Serializable
data class Citation(
    val title: String = "",
    val url: String = "",
    val index: Int = 0,
)

/** 搜索步骤展示状态。 */
@Serializable
enum class SearchStatus { RUNNING, DONE, FAILED }

@Serializable
data class SearchHit(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val date: String = "",
    val siteName: String = "",
    val siteIcon: String = "",
)

@Serializable
sealed interface Block {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        val citations: List<Citation> = emptyList(),
    ) : Block

    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val text: String,
        val seconds: Int? = null,
        val done: Boolean = false,
    ) : Block

    @Serializable
    @SerialName("search")
    data class Search(
        val kind: String = "search",
        val queries: List<String> = emptyList(),
        val status: SearchStatus = SearchStatus.RUNNING,
        val hits: List<SearchHit> = emptyList(),
    ) : Block
}

/** 消息里出现的错误（用于红色提示 + 重试）。 */
@Serializable
data class MessageError(
    val message: String,
    val retryable: Boolean = true,
)

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val blocks: List<Block> = emptyList(),
    val status: MessageStatus = MessageStatus.FINISHED,
    val createdAt: Long = System.currentTimeMillis(),
    val error: MessageError? = null,
    /** 是否为本轮提问的用户消息（用户消息只含一个 Text 块） */
    val attachments: List<Attachment> = emptyList(),
    /**
     * 同一轮提问的其它回答版本（重新生成产生的历史回答）。
     * 当前显示的就是这条消息自己，其它放这里，`variantNumber` 从 1 开始。
     */
    val variants: List<Message> = emptyList(),
    val variantNumber: Int = 1,
    /** 「继续生成」时保留的已有正文，渲染时前置到 blocks 之前 */
    val prefixText: String = "",
    /** 用户反馈：0 未评价，1 喜欢，-1 不喜欢（对应官方 喜欢/不喜欢 按钮） */
    val feedback: Int = 0,
) {
    val plainText: String
        get() = prefixText + blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }

    val thinkingText: String
        get() = blocks.filterIsInstance<Block.Thinking>().joinToString("\n") { it.text }

    val isStreaming: Boolean get() = status == MessageStatus.STREAMING

    /** 这一轮的版本总数（含自己）。 */
    val variantTotal: Int get() = variants.size + 1
}

/** 附件（图片 / 文档），保存本地副本路径与原始名。 */
@Serializable
data class Attachment(
    val name: String,
    val mime: String,
    val size: Long,
    /** 应用私有目录下的副本；上传成功后补充 fileId */
    val localPath: String? = null,
    val fileId: String? = null,
    val remoteUrl: String? = null,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

/** 请求里的一种内容块（发给 API 的形态）。 */
sealed interface ApiBlock {
    data class Text(val text: String) : ApiBlock
    data class ImageFile(val fileId: String) : ApiBlock
    data class DocumentFile(val fileId: String, val title: String) : ApiBlock
}
