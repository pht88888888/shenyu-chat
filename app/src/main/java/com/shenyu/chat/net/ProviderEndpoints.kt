package com.shenyu.chat.net

import com.shenyu.chat.data.ProviderConfig
import com.shenyu.chat.data.ProviderProtocol
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 把用户填写的「接口地址」解析成真正要请求的 URL。
 *
 * 用户的输入可能是根地址、`/v1`，也可能已经贴了完整的 `/messages` 或
 * `/chat/completions` 地址；这里统一收口，避免每个调用点各写一套字符串拼接。
 */
object ProviderEndpoints {

    /** 补全 scheme 并去掉结尾斜杠；空输入返回空串。 */
    fun normalizeBase(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            value = "https://$value"
        }
        return value.trimEnd('/')
    }

    /** 返回 null 表示地址可用；否则是直接给用户看的中文错误。 */
    fun validationError(raw: String): String? {
        val normalized = normalizeBase(raw)
        if (normalized.isEmpty()) return "请填写接口地址"
        val url = runCatching { normalized.toHttpUrlOrNull() }.getOrNull()
            ?: return "接口地址格式不对，请填 https 开头的完整地址"
        if (url.scheme != "http" && url.scheme != "https") return "接口地址只支持 http 或 https"
        if (url.host.isBlank()) return "接口地址缺少主机名"
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return "接口地址不能带用户名或密码"
        if (url.query != null) return "接口地址不要带 ? 参数"
        if (url.fragment != null) return "接口地址不要带 # 片段"
        return null
    }

    /** 真正发起对话请求的地址。 */
    fun messagesUrl(provider: ProviderConfig): String {
        val base = normalizeBase(provider.baseUrl)
        if (base.isEmpty()) return ""
        return when (provider.protocol) {
            ProviderProtocol.DEEPSEEK ->
                if (base.endsWith("/messages")) base
                else if (base.endsWith("/v1")) "$base/messages"
                else if (base.endsWith("/anthropic")) "$base/v1/messages"
                else if (base.endsWith("/anthropic/v1")) "$base/messages"
                else "$base/anthropic/v1/messages"

            ProviderProtocol.ANTHROPIC ->
                if (base.endsWith("/messages")) base
                else if (base.endsWith("/v1")) "$base/messages"
                else "$base/v1/messages"

            ProviderProtocol.OPENAI ->
                if (base.endsWith("/chat/completions")) base
                else if (base.endsWith("/v1")) "$base/chat/completions"
                else "$base/v1/chat/completions"
        }
    }

    /** 校验 Key 用的模型列表地址（不同协议的路径来源不同）。 */
    fun modelsUrl(provider: ProviderConfig): String {
        val base = normalizeBase(provider.baseUrl)
        if (base.isEmpty()) return ""
        return when (provider.protocol) {
            ProviderProtocol.DEEPSEEK -> {
                // 官方与中转站都把模型列表挂在 /v1/models；先折回站点根路径，
                // 兼容用户直接贴 .../anthropic/v1/messages 或 .../v1/messages 的情况。
                serviceOrigin(provider) + "/v1/models"
            }

            ProviderProtocol.ANTHROPIC -> messagesUrl(provider)
                .removeSuffix("/messages") + "/models"

            ProviderProtocol.OPENAI -> messagesUrl(provider)
                .removeSuffix("/v1/chat/completions")
                .removeSuffix("/chat/completions") + "/v1/models"
        }
    }

    /** 附件上传地址（仅 DeepSeek 应用协议使用）。 */
    fun filesUrl(provider: ProviderConfig): String =
        messagesUrl(provider).removeSuffix("/messages") + "/files"

    /**
     * 语音识别、朗读、联网工具等服务都挂在站点根路径下的固定路径上。
     * 如果用户把完整的 .../messages 地址贴进了 baseUrl，这里折回站点根。
     */
    fun serviceOrigin(provider: ProviderConfig): String {
        val base = normalizeBase(provider.baseUrl)
        val suffixes = listOf(
            "/anthropic/v1/messages",
            "/anthropic/v1",
            "/anthropic",
            "/v1/chat/completions",
            "/chat/completions",
            "/v1/messages",
            "/messages",
            "/v1",
        )
        suffixes.firstOrNull { base.endsWith(it) }?.let { suffix ->
            return base.dropLast(suffix.length).ifBlank { base }
        }
        return base
    }

    /** 界面上展示的「实际请求地址」，让用户知道自己填的地址会被拼成什么。 */
    fun previewUrl(provider: ProviderConfig): String = messagesUrl(provider)

    /** 设置页副标题用：协议 + 主机名，不展示完整长路径。 */
    fun shortLabel(provider: ProviderConfig): String {
        val host = runCatching {
            normalizeBase(provider.baseUrl).toHttpUrlOrNull()?.host
        }.getOrNull()
        val target = host?.takeIf { it.isNotBlank() } ?: provider.baseUrl
        return "${provider.protocol.label} · $target"
    }
}
