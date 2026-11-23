package com.shenyu.chat.chat

import com.shenyu.chat.data.LongDocMode

/**
 * 长文档 / 长上下文策略。
 *
 * 背景（2026-11-19 对官方 api.deepseek.com、deepseek-flash 实测，见 qa/context-1m-20261119/）：
 * - 普通 Messages 请求：输入 540,115 Token 仍返回 200；官方价目表列上下文 1M。
 * - 请求一旦带上 `title`、`safety_check` 或联网工具（`deepseek_web_search` / `deepseek_read_url`），
 *   服务端把输入卡在 32,768 Token，回包 `user input too long, N tokens exceeds max 32768 tokens`。
 * 深语原先每轮都带这三类参数，于是任何超过 3.2 万 Token 的提问（含附件解析结果）必然 400。
 *
 * 因为附件是按 `file_id` 引用的，文档正文在服务端展开、客户端看不到，本地估算不可能准确，
 * 所以真正的判定依据是服务端 400 报错：收到即记下该会话并改用普通通道重发一次。
 * 本地估算只用于纯文本长对话的提前降级，省掉一次往返。
 */
object ContextBudget {

    /** 增强通道（标题 / 增强风控 / 联网工具）的实测输入上限；服务端回包里的 max 优先。 */
    const val ENHANCED_INPUT_LIMIT = 32_768

    /** 本地估算的主动降级阈值：比 32,768 留一点余量，估算不必很准。 */
    const val PROACTIVE_INPUT_BUDGET = 30_000

    /** 简化估算的放大系数：宁可早降级，也不让用户白等一次 400。 */
    private const val ESTIMATE_MARGIN = 1.15

    /**
     * 粗估 Token 数。中文按 1 字 ≈ 0.6 Token、其它字符按 1 字符 ≈ 0.35 Token，
     * 再乘一点余量；只用于「是否提前降级」，不用于计费或显示精确值。
     */
    fun estimateTokens(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjk(ch)) cjk++ else other++
        }
        return ((cjk * 0.6 + other * 0.35) * ESTIMATE_MARGIN).toInt()
    }

    private fun isCjk(ch: Char): Boolean = when (ch.code) {
        in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF,
        in 0x3000..0x303F, in 0xFF00..0xFFEF,
        -> true

        else -> false
    }

    /** 服务端「输入超长」判定：`user input too long, N tokens exceeds max M tokens`。 */
    fun isInputTooLong(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lower = message.lowercase()
        return (lower.contains("input too long") || lower.contains("input is too long")) &&
            lower.contains("token")
    }

    /** 从报错里取服务端给的上限，取不到返回 null。 */
    fun reportedLimit(message: String?): Int? {
        if (message.isNullOrBlank()) return null
        val match = Regex("""exceeds max (\d+) tokens""", RegexOption.IGNORE_CASE)
            .find(message) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** 从报错里取这次被拒的输入 Token 数，取不到返回 null。 */
    fun reportedInputTokens(message: String?): Int? {
        if (message.isNullOrBlank()) return null
        val match = Regex("""(\d+) tokens exceeds""", RegexOption.IGNORE_CASE)
            .find(message) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun formatTokens(tokens: Int): String =
        if (tokens >= 10_000) "约 %.1f 万".format(tokens / 10_000.0) else "$tokens"

    /**
     * 把服务端原文换成用户能看懂、能动手的说明。
     * @param mode 当前设置；OFF 时提示去开启长文档模式，其余说明已自动降级。
     */
    fun explainTooLong(message: String?, mode: LongDocMode): String {
        val input = reportedInputTokens(message)
        val limit = reportedLimit(message) ?: ENHANCED_INPUT_LIMIT
        val head = if (input != null) {
            "这条内容${formatTokens(input)} Token，超过了当前模式 ${formatTokens(limit)} Token 的输入上限。"
        } else {
            "这条内容超过了当前模式 ${formatTokens(limit)} Token 的输入上限。"
        }
        return when (mode) {
            LongDocMode.OFF ->
                "$head 可到「设置 → 上下文模式」选择「1M（自动）」或「1M（优先）」，完整读取文档并保留联网功能。"

            else ->
                "$head 即便改用长上下文通道仍未通过，请拆分文档或减少附件后重试。"
        }
    }
}
