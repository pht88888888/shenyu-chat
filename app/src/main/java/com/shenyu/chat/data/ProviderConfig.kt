package com.shenyu.chat.data

import kotlinx.serialization.Serializable

/**
 * 供应商协议。深语最初只对接 DeepSeek 自家 App/网页端的 anthropic 兼容通道，
 * 现在允许用户把同一个客户端指向别的地址，所以把「协议」显式拆出来：
 *
 * - [DEEPSEEK]：DeepSeek 应用协议（Anthropic 线格式 + latest_reminder + deepseek_* 工具 +
 *   标题/风控扩展 + Files 附件 + 官方语音接口）。功能最全，也是默认项。
 * - [ANTHROPIC]：标准 Anthropic Messages 协议。保留思考块，但不再发送
 *   DeepSeek 私有字段/工具，附件与语音也不可用，避免第三方网关直接 400。
 * - [OPENAI]：标准 OpenAI Chat Completions（/v1/chat/completions）。只支持纯文本对话，
 *   联网搜索、附件、语音、标题扩展都不可用。
 */
@Serializable
enum class ProviderProtocol {
    DEEPSEEK,
    ANTHROPIC,
    OPENAI;

    val label: String
        get() = when (this) {
            DEEPSEEK -> "DeepSeek 应用协议"
            ANTHROPIC -> "Anthropic 兼容"
            OPENAI -> "OpenAI 兼容"
        }

    /** 是否走 Anthropic `/messages` 线格式（DeepSeek 与标准 Anthropic 都是）。 */
    val usesAnthropicWire: Boolean get() = this != OPENAI

    /** DeepSeek 私有扩展：latest_reminder、deepseek_* 工具、标题、safety_check、客户端联网工具。 */
    val supportsDeepSeekExtensions: Boolean get() = this == DEEPSEEK
}

/**
 * 一个可切换的 API 供应商。每个供应商各自保存 Key 与模型名，
 * 切换时不会串用；[builtin] 的官方项不允许删除。
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    /** 接口根地址；允许直接填完整的 `/messages` 或 `/chat/completions` 地址。 */
    val baseUrl: String,
    val apiKey: String = "",
    /** 模型名；留空时按协议用默认值（见 [effectiveModel]）。 */
    val model: String = "",
    val protocol: ProviderProtocol = ProviderProtocol.DEEPSEEK,
    val builtin: Boolean = false,
) {
    val label: String get() = name.trim().ifBlank { "未命名供应商" }

    val effectiveModel: String
        get() = model.trim().ifBlank {
            if (protocol == ProviderProtocol.OPENAI) DEFAULT_OPENAI_MODEL else SettingsState.DEFAULT_MODEL
        }

    /** Files API 附件上传只对官方供应商开放；中转站的 /v1/files 通常未实现。 */
    val supportsFiles: Boolean get() = protocol == ProviderProtocol.DEEPSEEK && builtin

    /** 语音识别/朗读走 DeepSeek 官方工具接口。 */
    val supportsVoice: Boolean get() = protocol == ProviderProtocol.DEEPSEEK

    /** 思考块：Anthropic 线格式支持；OpenAI 通道不透传。 */
    val supportsThinking: Boolean get() = protocol != ProviderProtocol.OPENAI

    companion object {
        const val DEFAULT_OPENAI_MODEL = "deepseek-chat"
    }
}
