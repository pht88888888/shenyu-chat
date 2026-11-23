package com.shenyu.chat.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shenyu.chat.ui.base.DsIcons
import kotlinx.coroutines.delay
import com.shenyu.chat.ui.session.CitationBadge
import com.shenyu.chat.ui.theme.DsColors
import com.shenyu.chat.ui.theme.DsSize
import com.shenyu.chat.ui.theme.DsTheme

/**
 * 纯 Compose 的 Markdown 渲染器（无三方库、无 WebView）。
 *
 * 设计要点：
 * - 解析与渲染分离：解析产出纯数据（不含颜色），渲染时再注入主题色，切主题不必重新解析；
 * - 面向流式：输入可能是半截文本，未闭合围栏按代码块渲染，未闭合 `**` / `[` 按普通文字渲染；
 * - 代码块按语言做语法高亮（自实现单遍词法器，明暗两套配色，未知语言回退纯文本）；
 * - 公式降级渲染：`$...$` 行内、`$$...$$` 块级，符号映射到 Unicode 并支持上下标；
 * - `[citation:N]` 渲染成上标小角标（CitationBadge，可点，回调 onCitationClick），不影响普通链接与普通方括号；
 * - ```mermaid 代码块交给 MermaidView（系统 WebView 离线跑 mermaid），其余语言走代码块高亮；
 * - 崩溃加固：解析入口整体 try-catch 退化为纯文本，列表 / 引用嵌套与行内递归均限深，
 *   超长输入（> 200000 字符）先截断再解析并给出提示；
 * - 解析结果按 markdown 字符串缓存，token 级刷新只重算一次解析。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    lineHeight: TextUnit = 28.sp,
    color: Color = DsTheme.colors.labelPrimary,
    onLinkClick: ((String) -> Unit)? = null,
    onCodeCopy: ((String) -> Unit)? = null,
    onCitationClick: ((Int) -> Unit)? = null,
) {
    val colors = DsTheme.colors
    // 每个 token 都会换 markdown，解析只在字符串变化时重跑（内部已兜底，不会抛异常）
    val blocks = remember(markdown) { runCatching { MdDocument.parse(markdown) }.getOrNull() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (blocks == null) {
            // 兜底：解析异常直接把原文当纯文本，绝不吞内容
            Text(text = markdown, color = color, fontSize = fontSize, lineHeight = lineHeight)
        } else {
            blocks.forEach { block ->
                MdBlockView(
                    block = block,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    color = color,
                    onLinkClick = onLinkClick,
                    onCodeCopy = onCodeCopy,
                    onCitationClick = onCitationClick,
                )
            }
        }
    }
}

// ───────────────────────────────── 数据模型（纯数据，不含颜色） ─────────────────────────────────

private sealed interface MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock

    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock

    data class Code(val language: String?, val code: String) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock

    data class BulletList(val items: List<MdListItem>) : MdBlock

    data class OrderedList(val items: List<MdListItem>) : MdBlock

    data object Divider : MdBlock

    data class Table(
        val header: List<List<MdInline>>,
        val aligns: List<MdAlign>,
        val rows: List<List<List<MdInline>>>,
    ) : MdBlock

    /**
     * 块级公式 $$...$$：[tex] 是原始 TeX（交给 KaTeX 渲染），[inlines] 是自绘近似排版
     * （KaTeX 没就绪或渲染失败时的兜底）。
     */
    data class Math(val tex: String, val inlines: List<MdInline>) : MdBlock

    /** 提示条（如超长内容被截断）。 */
    data class Notice(val text: String) : MdBlock
}

private data class MdListItem(
    val inlines: List<MdInline>,
    val children: List<MdListItem> = emptyList(),
    val number: Int = 0,
)

private enum class MdAlign { Start, Center, End }

/** 公式片段里的上下标。 */
private enum class MathScript { None, Sup, Sub }

/** 一段同一样式的行内文本；链接地址写在 [link] 里。 */
private data class MdInline(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    /** 数学公式片段（等宽 + 行内代码底色）。 */
    val formula: Boolean = false,
    /** `[citation:N]` 角标（上标 + brandPrimary）。 */
    val citation: Boolean = false,
    /** 公式片段的下标/上标。 */
    val script: MathScript = MathScript.None,
)

// ───────────────────────────────── 块级解析 ─────────────────────────────────

private object MdDocument {

    private val headingRe = Regex("^ {0,3}(#{1,6})(?:[ \\t]+(.*))?$")
    private val fenceRe = Regex("^ {0,3}(`{3,}|~{3,})[ \\t]*(.*)$")
    // 标记后必须跟空白，避免流式时单独一个 `*` / `-` 被当成空列表项
    private val bulletRe = Regex("^(\\s*)([-*+])(?:[ \\t]+)(.*)$")
    private val orderedRe = Regex("^(\\s*)(\\d{1,9})[.)](?:[ \\t]+)(.*)$")
    private val tableCellRe = Regex("^:?-+:?$")
    private val headingTailRe = Regex("\\s+#+\\s*$")

    /** 输入上限：超长流式文本先截断再解析，避免解析与渲染时 OOM。 */
    private const val MAX_INPUT_CHARS = 200_000

    /** 块级 / 列表嵌套上限（引用、列表）。 */
    private const val MAX_NESTING = 8

    fun parse(source: String): List<MdBlock> {
        val truncated = source.length > MAX_INPUT_CHARS
        val limited = if (truncated) source.substring(0, MAX_INPUT_CHARS) else source
        return try {
            val normalized = limited.replace("\r\n", "\n").replace('\r', '\n')
            val blocks = if (normalized.isBlank()) emptyList() else parseBlocks(normalized.split('\n'), 0)
            if (truncated) {
                blocks + MdBlock.Notice("内容过长，仅显示前 $MAX_INPUT_CHARS 个字符")
            } else {
                blocks
            }
        } catch (t: Throwable) {
            // 解析彻底失败：降级为纯文本逐行显示，绝不崩溃、不吞内容
            limited.split('\n').map { MdBlock.Paragraph(listOf(MdInline(it))) }
        }
    }

    private fun parseBlocks(lines: List<String>, depth: Int): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }

            // 块级公式 \[...\]（LaTeX 行间公式，模型经常用这种写法）
            if (line.trimStart().startsWith("\\[")) {
                val tex = StringBuilder()
                val head = line.trimStart().removePrefix("\\[")
                val inlineEnd = head.indexOf("\\]")
                var j: Int
                if (inlineEnd >= 0) {
                    tex.append(head.substring(0, inlineEnd))
                    j = i + 1
                } else {
                    tex.append(head)
                    j = i + 1
                    while (j < lines.size) {
                        val raw = lines[j]
                        val end = raw.indexOf("\\]")
                        if (end >= 0) {
                            if (tex.isNotEmpty()) tex.append('\n')
                            tex.append(raw.substring(0, end))
                            j++
                            break
                        }
                        if (tex.isNotEmpty()) tex.append('\n')
                        tex.append(raw)
                        j++
                    }
                }
                out += MdBlock.Math(tex.toString().trim(), MathTex.parse(tex.toString().trim()))
                i = maxOf(j, i + 1)
                continue
            }

            // 块级公式 $$...$$（单行自闭合或跨行；流式未闭合也按公式渲染）
            if (line.trimStart().startsWith("$$")) {
                val tex = StringBuilder()
                val head = line.trimStart().removePrefix("$$")
                val inlineEnd = head.indexOf("$$")
                var j: Int
                if (inlineEnd >= 0) {
                    tex.append(head.substring(0, inlineEnd))
                    j = i + 1
                } else {
                    tex.append(head)
                    j = i + 1
                    while (j < lines.size) {
                        val raw = lines[j]
                        val end = raw.indexOf("$$")
                        if (end >= 0) {
                            if (tex.isNotEmpty()) tex.append('\n')
                            tex.append(raw.substring(0, end))
                            j++
                            break
                        }
                        if (tex.isNotEmpty()) tex.append('\n')
                        tex.append(raw)
                        j++
                    }
                }
                out += MdBlock.Math(tex.toString().trim(), MathTex.parse(tex.toString().trim()))
                i = maxOf(j, i + 1)
                continue
            }

            // 围栏代码块（``` 与 ~~~）；流式时未闭合也按代码块渲染
            val fence = fenceRe.matchEntire(line)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val fenceChar = marker[0]
                val fenceLen = marker.length
                val info = fence.groupValues[2].trim()
                val body = StringBuilder()
                var j = i + 1
                while (j < lines.size) {
                    val raw = lines[j]
                    if (isClosingFence(raw, fenceChar, fenceLen)) {
                        j++
                        break
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(raw)
                    j++
                }
                out += MdBlock.Code(
                    language = info.split(' ', '\t').firstOrNull()?.takeIf { it.isNotEmpty() },
                    code = body.toString().trimEnd('\n'),
                )
                i = j
                continue
            }

            // 水平分割线
            if (isDivider(line)) {
                out += MdBlock.Divider
                i++
                continue
            }

            // ATX 标题
            val heading = headingRe.matchEntire(line)
            if (heading != null) {
                val level = heading.groupValues[1].length
                val text = heading.groupValues[2].replace(headingTailRe, "").trim()
                out += MdBlock.Heading(level, MdInlineParser.parse(text, depth + 1))
                i++
                continue
            }

            // 引用：连续 > 行 + 惰性续行，内部递归解析；超过嵌套上限按纯文本
            if (isQuoteLine(line) && depth >= MAX_NESTING) {
                val para = mutableListOf<String>()
                var j = i
                while (j < lines.size && isQuoteLine(lines[j])) {
                    para += stripQuoteMarker(lines[j]).trim()
                    j++
                }
                out += MdBlock.Paragraph(MdInlineParser.parse(joinSoftLines(para), depth + 1))
                i = maxOf(j, i + 1)
                continue
            }
            if (isQuoteLine(line)) {
                val inner = mutableListOf<String>()
                var j = i
                while (j < lines.size) {
                    val raw = lines[j]
                    if (isQuoteLine(raw)) {
                        inner += stripQuoteMarker(raw)
                        j++
                    } else if (raw.isNotBlank() && !startsOtherBlock(lines, j)) {
                        inner += raw
                        j++
                    } else {
                        break
                    }
                }
                out += MdBlock.Quote(parseBlocks(inner, depth + 1))
                i = maxOf(j, i + 1)
                continue
            }

            // GFM 表格：表头行 + 分隔行（列数必须一致，避免把 `---` 误判成表格）
            if (i + 1 < lines.size && line.contains('|')) {
                val aligns = parseAlignRow(lines[i + 1])
                if (aligns != null && aligns.size == parseRow(line).size) {
                    val columnCount = aligns.size
                    val header = parseRow(line).map { MdInlineParser.parse(it, depth + 1) }
                    val rows = mutableListOf<List<List<MdInline>>>()
                    var j = i + 2
                    while (j < lines.size) {
                        val raw = lines[j]
                        if (raw.isBlank() || !raw.contains('|') || startsOtherBlock(lines, j)) break
                        rows += fitCells(parseRow(raw), columnCount).map { MdInlineParser.parse(it, depth + 1) }
                        j++
                    }
                    out += MdBlock.Table(header, aligns, rows)
                    i = j
                    continue
                }
            }

            // 列表（无序 / 有序，含 1 级嵌套与项内续行）
            if (bulletRe.matchEntire(line) != null || orderedRe.matchEntire(line) != null) {
                val (block, next) = parseList(lines, i)
                out += block
                // 防御：解析器若没有前进，强制推进，避免死循环
                i = if (next > i) next else i + 1
                continue
            }

            // 段落：吃掉直到空行或下一个块起始
            val para = mutableListOf<String>()
            var j = i
            while (j < lines.size) {
                val raw = lines[j]
                if (raw.isBlank()) break
                if (j > i && startsOtherBlock(lines, j)) break
                para += raw.trim()
                j++
            }
            if (para.isEmpty()) {
                i++
            } else {
                out += MdBlock.Paragraph(MdInlineParser.parse(joinSoftLines(para), depth + 1))
                i = j
            }
        }
        return out
    }

    // ── 列表 ──

    /** 列表项累加器：正文按软换行拼接；children 支持多级嵌套（上限 MAX_NESTING）。 */
    private class ListItemBuilder(val number: Int, val indent: Int) {
        val lines = mutableListOf<String>()
        val children = mutableListOf<ListItemBuilder>()
    }

    private fun parseList(lines: List<String>, start: Int): Pair<MdBlock, Int> {
        val firstBullet = bulletRe.matchEntire(lines[start])
        val ordered = firstBullet == null
        val first = firstBullet ?: orderedRe.matchEntire(lines[start]) ?: return MdBlock.Paragraph(emptyList()) to start + 1
        val baseIndent = first.groupValues[1].length

        val roots = mutableListOf<ListItemBuilder>()
        // 当前祖先链（浅 → 深），按缩进判断层级
        val stack = ArrayList<ListItemBuilder>()
        var i = start

        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                // 空行后若仍是同类列表项，视为同一列表继续
                var k = i
                while (k < lines.size && lines[k].isBlank()) k++
                if (k < lines.size && sameKindMarker(lines[k], ordered)) {
                    i = k
                    continue
                }
                break
            }

            val marker = if (ordered) orderedRe.matchEntire(line) else bulletRe.matchEntire(line)
            if (marker == null) {
                // 列表项内的续行，归属最深未闭合项
                val target = stack.lastOrNull()
                if (target != null && !startsOtherBlock(lines, i)) {
                    target.lines += line.trim()
                    i++
                    continue
                }
                break
            }

            val indent = marker.groupValues[1].length
            if (indent < baseIndent) break
            val content = marker.groupValues[3]
            val number = if (ordered) marker.groupValues[2].toIntOrNull() ?: 0 else 0

            // 缩进小于"父项缩进 + 2"的都视为同级或更浅，逐层弹出
            while (stack.isNotEmpty() && indent < stack.last().indent + 2) {
                stack.removeAt(stack.size - 1)
            }
            // 嵌套过深：按纯文本并入当前项，避免无限递归
            if (stack.size >= MAX_NESTING) {
                stack.last().lines += line.trim()
                i++
                continue
            }

            val item = ListItemBuilder(number, indent).also { it.lines += content }
            if (stack.isEmpty()) roots += item else stack.last().children += item
            stack.add(item)
            i++
        }

        val items = roots.map(::toListItem)
        return (if (ordered) MdBlock.OrderedList(items) else MdBlock.BulletList(items)) to i
    }

    private fun toListItem(builder: ListItemBuilder): MdListItem = MdListItem(
        inlines = MdInlineParser.parse(joinSoftLines(builder.lines)),
        children = builder.children.map(::toListItem),
        number = builder.number,
    )

    private fun sameKindMarker(line: String, ordered: Boolean): Boolean {
        if (line.isBlank()) return false
        val match = if (ordered) orderedRe.matchEntire(line) else bulletRe.matchEntire(line)
        return match != null
    }

    // ── 表格 ──

    private fun parseAlignRow(line: String): List<MdAlign>? {
        if (!line.contains('|') && !line.contains('-')) return null
        val cells = parseRow(line)
        if (cells.isEmpty()) return null
        val aligns = ArrayList<MdAlign>(cells.size)
        for (cell in cells) {
            val token = cell.trim()
            if (!tableCellRe.matches(token)) return null
            aligns += when {
                token.startsWith(":") && token.endsWith(":") -> MdAlign.Center
                token.endsWith(":") -> MdAlign.End
                else -> MdAlign.Start
            }
        }
        return aligns
    }

    /** 按未转义的 `|` 切分一行表格，去掉首尾竖线。 */
    private fun parseRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
        val cells = mutableListOf<String>()
        val buffer = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && (s[i + 1] == '|' || s[i + 1] == '\\')) {
                buffer.append(s[i + 1])
                i += 2
                continue
            }
            if (c == '|') {
                cells += buffer.toString()
                buffer.setLength(0)
                i++
                continue
            }
            buffer.append(c)
            i++
        }
        cells += buffer.toString()
        return cells.map { it.trim() }
    }

    private fun fitCells(cells: List<String>, count: Int): List<String> {
        if (cells.size == count) return cells
        return if (cells.size > count) cells.take(count) else cells + List(count - cells.size) { "" }
    }

    // ── 行级判定 ──

    private fun isClosingFence(line: String, fenceChar: Char, fenceLen: Int): Boolean {
        val t = line.trim()
        if (t.length < fenceLen) return false
        if (t.any { it != fenceChar }) return false
        return t.length >= fenceLen
    }

    private fun isDivider(line: String): Boolean {
        val t = line.trim()
        if (t.length < 3) return false
        val c = t[0]
        if (c != '-' && c != '*' && c != '_') return false
        var count = 0
        for (ch in t) {
            when {
                ch == c -> count++
                ch == ' ' || ch == '\t' -> Unit
                else -> return false
            }
        }
        return count >= 3
    }

    private fun isQuoteLine(line: String): Boolean = line.trimStart().startsWith(">")

    private fun stripQuoteMarker(line: String): String {
        var s = line.trimStart()
        if (s.startsWith(">")) s = s.substring(1)
        if (s.startsWith(" ")) s = s.substring(1)
        return s
    }

    /** 该行是否开启一个新块（用于段落 / 续行 / 表格行的截断判断）。 */
    private fun startsOtherBlock(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        if (line.isBlank()) return true
        if (line.trimStart().startsWith("$$")) return true
        if (fenceRe.matchEntire(line) != null) return true
        if (isDivider(line)) return true
        if (headingRe.matchEntire(line) != null) return true
        if (isQuoteLine(line)) return true
        if (bulletRe.matchEntire(line) != null || orderedRe.matchEntire(line) != null) return true
        return false
    }

    /** 软换行拼接：两侧都是宽字符（中日韩）时不插空格，否则补一个空格。 */
    private fun joinSoftLines(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        if (lines.size == 1) return lines[0]
        val sb = StringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) {
                val prev = sb.lastOrNull()
                val cur = line.firstOrNull()
                if (prev == null || cur == null || !isWideChar(prev) || !isWideChar(cur)) sb.append(' ')
            }
            sb.append(line)
        }
        return sb.toString()
    }

    private fun isWideChar(c: Char): Boolean = when (c.code) {
        in 0x2E80..0x9FFF, in 0x3000..0x303F, in 0xFF00..0xFFEF, in 0xAC00..0xD7AF -> true
        else -> false
    }
}

// ───────────────────────────────── 数学公式（降级渲染：符号映射 + 上下标） ─────────────────────────────────

/**
 * 把 LaTeX 片段降级成可读文本：已知命令映射到 Unicode，`\frac{a}{b}` → `a/b`，
 * `^{x}` / `_{x}` 变成上标 / 下标片段。映射不到的命令原样输出，绝不丢内容。
 */
private object MathTex {

    private const val MAX_MATH_DEPTH = 4

    private val symbols = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "upsilon" to "υ",
        "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "pi" to "π",
        "xi" to "ξ", "rho" to "ρ", "sigma" to "σ", "tau" to "τ",
        "phi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "sum" to "∑", "prod" to "∏", "int" to "∫", "sqrt" to "√",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "ne" to "≠", "neq" to "≠", "approx" to "≈", "equiv" to "≡",
        "infty" to "∞", "pm" to "±", "mp" to "∓", "times" to "×",
        "div" to "÷", "cdot" to "·", "to" to "→", "rightarrow" to "→",
        "leftarrow" to "←", "Rightarrow" to "⇒", "leftrightarrow" to "↔",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "supset" to "⊃",
        "subseteq" to "⊆", "cup" to "∪", "cap" to "∩", "emptyset" to "∅",
        "forall" to "∀", "exists" to "∃", "nabla" to "∇", "partial" to "∂",
        "degree" to "°", "angle" to "∠", "perp" to "⊥", "parallel" to "∥",
        "because" to "∵", "therefore" to "∴", "ldots" to "…", "cdots" to "⋯",
    )

    fun parse(tex: String, depth: Int = 0): List<MdInline> {
        if (tex.isEmpty()) return emptyList()
        if (depth > MAX_MATH_DEPTH) return listOf(MdInline(tex, formula = true))
        return try {
            val out = mutableListOf<MdInline>()
            parseInto(tex, 0, tex.length, depth, MathScript.None, out)
            if (out.isEmpty()) emptyList() else out
        } catch (t: Throwable) {
            // 公式解析失败：原样输出
            listOf(MdInline(tex, formula = true))
        }
    }

    private fun parseInto(
        tex: String,
        start: Int,
        end: Int,
        depth: Int,
        script: MathScript,
        out: MutableList<MdInline>,
    ) {
        if (depth > MAX_MATH_DEPTH) {
            if (start < end) out += MdInline(tex.substring(start, end), formula = true, script = script)
            return
        }
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                out += MdInline(buffer.toString(), formula = true, script = script)
                buffer.setLength(0)
            }
        }

        var i = start
        while (i < end) {
            val c = tex[i]
            when {
                // 反斜杠命令
                c == '\\' -> {
                    var j = i + 1
                    while (j < end && tex[j].isLetter()) j++
                    val name = tex.substring(i + 1, j)
                    when {
                        name.isEmpty() -> {
                            // \, \; \{ 之类：输出后一个字符本身
                            if (i + 1 < end) buffer.append(tex[i + 1])
                            i += 2
                        }
                        name == "frac" -> {
                            val a = readGroup(tex, j, end)
                            val b = if (a != null) readGroup(tex, a.second, end) else null
                            if (a != null && b != null) {
                                buffer.append(text(a.first, depth + 1))
                                buffer.append('/')
                                buffer.append(text(b.first, depth + 1))
                                i = b.second
                            } else {
                                buffer.append("\\frac")
                                i = j
                            }
                        }
                        name == "sqrt" -> {
                            val g = readGroup(tex, j, end)
                            if (g != null) {
                                buffer.append('√').append('(')
                                buffer.append(text(g.first, depth + 1))
                                buffer.append(')')
                                i = g.second
                            } else {
                                buffer.append('√')
                                i = j
                            }
                        }
                        else -> {
                            val sym = symbols[name]
                            if (sym != null) buffer.append(sym) else buffer.append('\\').append(name)
                            i = j
                        }
                    }
                }
                // ^{...} / _{...}
                c == '^' || c == '_' -> {
                    val g = readGroup(tex, i + 1, end)
                    if (g != null) {
                        flush()
                        val target = if (c == '^') MathScript.Sup else MathScript.Sub
                        parse(g.first, depth + 1).forEach { out += it.copy(script = target, formula = true) }
                        i = g.second
                    } else {
                        buffer.append(c)
                        i++
                    }
                }
                // 分组花括号只用于分组，不显示
                c == '{' || c == '}' -> i++
                else -> {
                    buffer.append(c)
                    i++
                }
            }
        }
        flush()
    }

    /** 组内容的纯文本形式（用于 \frac、\sqrt 的参数）。 */
    private fun text(sub: String, depth: Int): String =
        parse(sub, depth).joinToString("") { it.text }

    /** 读一个参数：`{...}`（允许嵌套花括号）或单个字符。返回（内容, 结束下标）。 */
    private fun readGroup(tex: String, from: Int, end: Int): Pair<String, Int>? {
        if (from >= end) return null
        if (tex[from] != '{') {
            // \frac12 这种单字符参数
            if (tex[from] == '\\') {
                var j = from + 1
                while (j < end && tex[j].isLetter()) j++
                return tex.substring(from, j) to j
            }
            return tex[from].toString() to (from + 1)
        }
        var level = 0
        var i = from
        while (i < end) {
            val ch = tex[i]
            if (ch == '\\' && i + 1 < end) {
                i += 2
                continue
            }
            if (ch == '{') {
                level++
            } else if (ch == '}') {
                level--
                if (level == 0) return tex.substring(from + 1, i) to (i + 1)
            }
            i++
        }
        return null
    }
}

// ───────────────────────────────── 行内解析 ─────────────────────────────────

private object MdInlineParser {

    private val urlPrefixes = listOf("https://", "http://", "www.")
    private const val URL_BREAK = " \t\n<>\"'`“”‘’、，。；：！？（）【】《》…"

    /** 行内递归层数上限（粗体里套链接再套公式……超过就按纯文本）。 */
    private const val MAX_INLINE_DEPTH = 8

    fun parse(source: String, depth: Int = 0): List<MdInline> {
        if (source.isEmpty()) return emptyList()
        return try {
            val out = mutableListOf<MdInline>()
            parseRange(source, 0, source.length, MdInline(""), null, out, depth)
            out
        } catch (t: Throwable) {
            // 行内解析失败：整段降级为纯文本，绝不吞内容
            listOf(MdInline(source))
        }
    }

    /**
     * 扫描 [start, end)，按需递归处理嵌套样式。
     * 关键约定：只有当闭合符真的存在时才吃进样式，否则把开启符当普通文字输出，
     * 这样流式文本里半截的 `**`、`[` 不会吞掉后面的内容。
     */
    private fun parseRange(
        src: String,
        start: Int,
        end: Int,
        base: MdInline,
        link: String?,
        out: MutableList<MdInline>,
        depth: Int,
    ) {
        // 递归过深：剩余部分按纯文本输出，防止栈溢出
        if (depth > MAX_INLINE_DEPTH) {
            if (start < end) out += base.copy(text = src.substring(start, end), link = link)
            return
        }
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                out += base.copy(text = buffer.toString(), link = link)
                buffer.setLength(0)
            }
        }

        var i = start
        while (i < end) {
            val c = src[i]

            // 反斜杠转义
            // LaTeX 行内公式 \(...\) / 行间公式 \[...\]：优先于转义处理，
            // 否则开头的 [ 会被当成转义吃掉，公式就变回一堆原始 TeX 文本。
            if (c == '\\' && i + 1 < end && (src[i + 1] == '(' || src[i + 1] == '[')) {
                val closing = if (src[i + 1] == '(') "\\)" else "\\]"
                val closeIdx = src.indexOf(closing, i + 2)
                if (closeIdx > i + 1) {
                    flush()
                    out += MathTex.parse(src.substring(i + 2, closeIdx).trim()).map { it.copy(link = link) }
                    i = closeIdx + 2
                    continue
                }
            }

            if (c == '\\' && i + 1 < end && isEscapable(src[i + 1])) {
                buffer.append(src[i + 1])
                i += 2
                continue
            }

            // 行内公式 $...$ / $$...$$
            if (c == '$') {
                val double = i + 1 < end && src[i + 1] == '$'
                val open = if (double) 2 else 1
                val first = if (i + open < end) src[i + open] else ' '
                // 货币保护：$ 后紧跟数字或空白时不当作公式
                if (first != '$' && !first.isDigit() && !first.isWhitespace()) {
                    var close = -1
                    var j = i + open
                    while (j + open <= end) {
                        val hit = if (double) src.startsWith("$$", j) else src[j] == '$'
                        if (hit && src[j - 1] != '\\') {
                            close = j
                            break
                        }
                        // 行内公式不跨行
                        if (!double && src[j] == '\n') break
                        j++
                    }
                    if (close > i + open) {
                        flush()
                        val runs = MathTex.parse(src.substring(i + open, close), depth + 1)
                        runs.forEach { out += it.copy(link = link) }
                        i = close + open
                        continue
                    }
                }
                buffer.append(c)
                i++
                continue
            }

            // 行内代码
            if (c == '`') {
                val run = runLength(src, i, end, '`')
                val close = findRun(src, i + run, end, '`', run)
                if (close >= 0) {
                    flush()
                    out += base.copy(
                        text = src.substring(i + run, close),
                        code = true,
                        link = link,
                    )
                    i = close + run
                } else {
                    buffer.append(c)
                    i++
                }
                continue
            }

            // 引用角标 [citation:N]
            if (c == '[') {
                val citation = matchCitation(src, i, end)
                // 紧跟 ( 时仍按普通链接处理，避免 [citation:1](url) 被截成角标
                if (citation != null && (citation.second >= end || src[citation.second] != '(')) {
                    flush()
                    out += base.copy(text = citation.first, citation = true, link = link)
                    i = citation.second
                    continue
                }
            }

            // [文字](链接)
            if (c == '[') {
                val closeBracket = findChar(src, i + 1, end, ']')
                if (closeBracket > 0 && closeBracket + 1 < end && src[closeBracket + 1] == '(') {
                    val closeParen = findChar(src, closeBracket + 2, end, ')')
                    if (closeParen > 0) {
                        val target = src.substring(closeBracket + 2, closeParen)
                            .trim()
                            .split(' ', '\t')
                            .firstOrNull()
                            .orEmpty()
                            .trim('<', '>')
                        if (target.isNotEmpty()) {
                            flush()
                            parseRange(src, i + 1, closeBracket, base, target, out, depth + 1)
                            i = closeParen + 1
                            continue
                        }
                    }
                }
                // 未闭合：按普通文字
                buffer.append(c)
                i++
                continue
            }

            // **粗体** / __ 不处理，避免误伤 snake_case
            if (c == '*' && i + 1 < end && src[i + 1] == '*') {
                val close = findDelim(src, i + 2, end, "**")
                if (close >= 0) {
                    flush()
                    parseRange(src, i + 2, close, base.copy(bold = true), link, out, depth + 1)
                    i = close + 2
                } else {
                    buffer.append("**")
                    i += 2
                }
                continue
            }

            // ~~删除线~~
            if (c == '~' && i + 1 < end && src[i + 1] == '~') {
                val close = findDelim(src, i + 2, end, "~~")
                if (close >= 0) {
                    flush()
                    parseRange(src, i + 2, close, base.copy(strike = true), link, out, depth + 1)
                    i = close + 2
                } else {
                    buffer.append("~~")
                    i += 2
                }
                continue
            }

            // *斜体*
            if (c == '*') {
                val close = findSingleAsterisk(src, i + 1, end)
                if (close > i + 1) {
                    flush()
                    parseRange(src, i + 1, close, base.copy(italic = true), link, out, depth + 1)
                    i = close + 1
                } else {
                    buffer.append(c)
                    i++
                }
                continue
            }

            // 裸 URL 自动成链
            if (link == null) {
                val prefix = urlPrefixes.firstOrNull { src.startsWith(it, i) }?.takeIf { isUrlBoundary(src, i, start) }
                if (prefix != null) {
                    var j = i + prefix.length
                    while (j < end && src[j] !in URL_BREAK) j++
                    // 去掉粘在末尾的英文标点
                    while (j > i + prefix.length && src[j - 1] in ".,;:!?)]}。，；：）】") j--
                    if (j > i + prefix.length) {
                        flush()
                        val url = src.substring(i, j)
                        out += base.copy(text = url, link = url)
                        i = j
                        continue
                    }
                }
            }

            buffer.append(c)
            i++
        }
        flush()
    }

    /** 识别 `[citation:12]`，返回（角标数字, 结束下标）。 */
    private fun matchCitation(src: String, start: Int, end: Int): Pair<String, Int>? {
        val head = "[citation:"
        if (!src.startsWith(head, start)) return null
        var j = start + head.length
        val digitsStart = j
        while (j < end && src[j].isDigit()) j++
        if (j == digitsStart) return null
        if (j >= end || src[j] != ']') return null
        return src.substring(digitsStart, j) to (j + 1)
    }

    private fun isEscapable(c: Char): Boolean = !c.isLetterOrDigit() && !c.isWhitespace()

    private fun isUrlBoundary(src: String, index: Int, start: Int): Boolean {
        if (index <= start) return true
        val prev = src[index - 1]
        // 中文正文里 URL 常紧挨汉字，汉字也视为边界
        val cjk = prev.code >= 0x2E80
        return cjk || prev.isWhitespace() || prev in "([{（【、，。；：!？*\"'<|>"
    }

    private fun runLength(src: String, from: Int, end: Int, ch: Char): Int {
        var i = from
        while (i < end && src[i] == ch) i++
        return i - from
    }

    /** 从 from 起找长度恰好（或至少）为 len 的同一字符连续段起点。 */
    private fun findRun(src: String, from: Int, end: Int, ch: Char, len: Int): Int {
        var i = from
        while (i < end) {
            if (src[i] == ch) {
                val run = runLength(src, i, end, ch)
                if (run >= len) return i
                i += run
            } else {
                i++
            }
        }
        return -1
    }

    private fun findDelim(src: String, from: Int, end: Int, delim: String): Int {
        var i = from
        while (i + delim.length <= end) {
            if (src.startsWith(delim, i) && (i == from || src[i - 1] != '\\')) return i
            i++
        }
        return -1
    }

    private fun findSingleAsterisk(src: String, from: Int, end: Int): Int {
        var i = from
        while (i < end) {
            if (src[i] == '*' && (i + 1 >= end || src[i + 1] != '*') && src[i - 1] != '\\') return i
            i++
        }
        return -1
    }

    private fun findChar(src: String, from: Int, end: Int, target: Char): Int {
        var i = from
        while (i < end) {
            if (src[i] == '\\' && i + 1 < end) {
                i += 2
                continue
            }
            if (src[i] == target) return i
            i++
        }
        return -1
    }
}

// ───────────────────────────────── 代码块高亮（自实现，无三方依赖） ─────────────────────────────────

private enum class HighlightKind { Comment, String, Number, Keyword, Function }

/** 高亮配色：明暗两套固定值，与代码块底色（markdownCodeBlock）对比度足够。 */
private class CodePalette(
    val comment: Color,
    val string: Color,
    val number: Color,
    val keyword: Color,
    val function: Color,
) {
    fun colorOf(kind: HighlightKind): Color = when (kind) {
        HighlightKind.Comment -> comment
        HighlightKind.String -> string
        HighlightKind.Number -> number
        HighlightKind.Keyword -> keyword
        HighlightKind.Function -> function
    }

    companion object {
        fun of(colors: DsColors): CodePalette = if (colors.isDark) {
            CodePalette(
                comment = Color(0xFF7C848F),
                string = Color(0xFF9ECE6A),
                number = Color(0xFFFF9E64),
                keyword = Color(0xFFBB9AF7),
                function = Color(0xFF7AA2F7),
            )
        } else {
            CodePalette(
                comment = Color(0xFF8A9099),
                string = Color(0xFF2E7D32),
                number = Color(0xFFB45309),
                keyword = Color(0xFF7C3AED),
                function = Color(0xFF1D4ED8),
            )
        }
    }
}

/** 一门语言的词法要素（注释 / 字符串 / 关键字 + 标签名开关）。 */
private class LangSpec(
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val quotes: List<Char> = emptyList(),
    val multiQuotes: List<String> = emptyList(),
    val keywords: Set<String> = emptySet(),
    val caseInsensitiveKeywords: Boolean = false,
    val tagNames: Boolean = false,
    val numberLiterals: Boolean = true,
)

/**
 * 逐字符的单遍扫描词法器：
 * 注释 > 字符串 > 数字 > 标识符（关键字 / 函数名），O(n) 不回溯，
 * 流式每帧重算也只有毫秒级开销；未知语言直接返回原文（不高亮）。
 */
private object CodeHighlighter {

    /** 超过这个长度不做高亮，避免超长代码块拖慢渲染。 */
    private const val MAX_HIGHLIGHT_CHARS = 30_000

    private val kotlinKeywords = setOf(
        "abstract", "actual", "annotation", "as", "break", "by", "catch", "class", "companion",
        "const", "constructor", "continue", "crossinline", "data", "delegate", "do", "dynamic",
        "else", "enum", "expect", "external", "false", "field", "file", "final", "finally", "for",
        "fun", "get", "if", "import", "in", "infix", "init", "inline", "inner", "interface",
        "internal", "is", "lateinit", "noinline", "null", "object", "open", "operator", "out",
        "override", "package", "param", "private", "property", "protected", "public", "receiver",
        "reified", "return", "sealed", "set", "setparam", "super", "suspend", "tailrec", "this",
        "throw", "true", "try", "typealias", "typeof", "val", "var", "vararg", "when", "where",
        "while", "it", "also", "let", "run", "apply", "with", "Unit", "String", "Int", "Long",
        "Double", "Float", "Boolean", "List", "Map", "Set", "Any", "Nothing",
    )

    private val javaKeywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "permits", "private", "protected",
        "public", "record", "return", "sealed", "short", "static", "strictfp", "super", "switch",
        "synchronized", "this", "throw", "throws", "transient", "true", "false", "null", "try",
        "var", "void", "volatile", "while", "yield",
    )

    private val jsKeywords = setOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "false", "finally", "for",
        "function", "get", "if", "import", "in", "instanceof", "let", "new", "null", "of",
        "return", "set", "static", "super", "switch", "this", "throw", "true", "try", "typeof",
        "undefined", "var", "void", "while", "with", "yield",
    )

    private val tsKeywords = jsKeywords + setOf(
        "abstract", "any", "as", "declare", "enum", "implements", "infer", "interface", "is",
        "keyof", "namespace", "never", "number", "private", "protected", "public", "readonly",
        "satisfies", "string", "type", "unknown", "boolean",
    )

    private val pythonKeywords = setOf(
        "and", "as", "assert", "async", "await", "break", "case", "class", "continue", "def",
        "del", "elif", "else", "except", "False", "finally", "for", "from", "global", "if",
        "import", "in", "is", "lambda", "match", "None", "nonlocal", "not", "or", "pass",
        "raise", "return", "self", "True", "try", "while", "with", "yield",
    )

    private val goKeywords = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
        "false", "for", "func", "go", "goto", "if", "import", "interface", "iota", "map", "nil",
        "package", "range", "return", "select", "struct", "switch", "true", "type", "var",
    )

    private val rustKeywords = setOf(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
        "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
        "move", "mut", "None", "Ok", "Err", "pub", "ref", "return", "Self", "self", "Some",
        "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while",
    )

    private val sqlKeywords = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
        "create", "table", "alter", "drop", "index", "view", "join", "inner", "left", "right",
        "full", "outer", "on", "group", "by", "order", "having", "limit", "offset", "union",
        "all", "distinct", "as", "and", "or", "not", "null", "is", "in", "like", "between",
        "exists", "case", "when", "then", "else", "end", "primary", "key", "foreign",
        "references", "default", "unique", "count", "sum", "avg", "min", "max", "int", "varchar",
        "text", "date", "timestamp", "boolean", "begin", "commit", "rollback", "with", "explain",
        "asc", "desc", "top", "returning",
    )

    private val yamlKeywords = setOf("true", "false", "null", "yes", "no", "on", "off")

    private val bashKeywords = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case",
        "esac", "function", "in", "return", "export", "local", "readonly", "declare", "echo",
        "printf", "cd", "exit", "set", "unset", "source", "alias", "select", "shift", "trap",
        "true", "false",
    )

    private val jsonKeywords = setOf("true", "false", "null")

    private val cppKeywords = setOf(
        "auto", "bool", "break", "case", "catch", "char", "class", "const", "constexpr",
        "continue", "default", "delete", "do", "double", "else", "enum", "explicit", "extern",
        "false", "float", "for", "friend", "goto", "if", "inline", "int", "long", "namespace",
        "new", "nullptr", "operator", "private", "protected", "public", "return", "short",
        "signed", "sizeof", "static", "struct", "switch", "template", "this", "throw", "true",
        "try", "typedef", "typename", "union", "unsigned", "using", "virtual", "void",
        "volatile", "while", "include", "define", "ifdef", "ifndef", "endif", "pragma",
    )

    private val TRIPLE_QUOTE = "\"\"\""
    private val TRIPLE_SINGLE = "'''"

    private val specs: Map<String, LangSpec> = mapOf(
        "kotlin" to LangSpec(listOf("//"), listOf("/*" to "*/"), listOf('"', '\''), keywords = kotlinKeywords),
        "java" to LangSpec(listOf("//"), listOf("/*" to "*/"), listOf('"', '\''), keywords = javaKeywords),
        "javascript" to LangSpec(listOf("//"), listOf("/*" to "*/"), listOf('"', '\'', '`'), keywords = jsKeywords),
        "typescript" to LangSpec(listOf("//"), listOf("/*" to "*/"), listOf('"', '\'', '`'), keywords = tsKeywords),
        "python" to LangSpec(
            listOf("#"), emptyList(), listOf('"', '\''),
            multiQuotes = listOf(TRIPLE_QUOTE, TRIPLE_SINGLE), keywords = pythonKeywords,
        ),
        "go" to LangSpec(listOf("//"), listOf("/*" to "*/"), listOf('"', '\'', '`'), keywords = goKeywords),
        "rust" to LangSpec(listOf("//"), listOf("/*" to "*/"), listOf('"', '\''), keywords = rustKeywords),
        "sql" to LangSpec(
            listOf("--"), listOf("/*" to "*/"), listOf('\'', '"'),
            keywords = sqlKeywords, caseInsensitiveKeywords = true,
        ),
        "json" to LangSpec(emptyList(), emptyList(), listOf('"'), keywords = jsonKeywords),
        "yaml" to LangSpec(listOf("#"), emptyList(), listOf('"', '\''), keywords = yamlKeywords),
        "bash" to LangSpec(listOf("#"), emptyList(), listOf('"', '\'', '`'), keywords = bashKeywords),
        "xml" to LangSpec(
            emptyList(), listOf("<!--" to "-->"), listOf('"', '\''),
            tagNames = true, numberLiterals = false,
        ),
        "css" to LangSpec(emptyList(), listOf("/*" to "*/"), listOf('"', '\'')),
        "cpp" to LangSpec(listOf("//"), listOf("/*" to "*/"), listOf('"', '\''), keywords = cppKeywords),
    )

    private val aliases: Map<String, String> = mapOf(
        "kt" to "kotlin", "kts" to "kotlin",
        "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript",
        "ts" to "typescript", "tsx" to "typescript",
        "py" to "python", "python3" to "python",
        "golang" to "go",
        "rs" to "rust",
        "mysql" to "sql", "postgres" to "sql", "postgresql" to "sql", "sqlite" to "sql",
        "jsonc" to "json",
        "yml" to "yaml",
        "sh" to "bash", "shell" to "bash", "zsh" to "bash", "console" to "bash", "shellscript" to "bash",
        "html" to "xml", "htm" to "xml", "xhtml" to "xml", "svg" to "xml", "vue" to "xml",
        "scss" to "css", "less" to "css",
        "c" to "cpp", "h" to "cpp", "h++" to "cpp", "cc" to "cpp", "hpp" to "cpp", "cxx" to "cpp",
    )

    /** 语言名 → 词法配置；未知语言（或纯文本）返回 null，表示不高亮。 */
    private fun specFor(lang: String?): LangSpec? {
        val name = lang?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        specs[name]?.let { return it }
        val canonical = aliases[name] ?: return null
        return specs[canonical]
    }

    private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    fun highlight(code: String, lang: String?, palette: CodePalette): AnnotatedString {
        val spec = specFor(lang) ?: return AnnotatedString(code)
        if (code.isEmpty() || code.length > MAX_HIGHLIGHT_CHARS) return AnnotatedString(code)

        val builder = AnnotatedString.Builder()
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isNotEmpty()) {
                builder.append(plain.toString())
                plain.setLength(0)
            }
        }
        fun emit(text: String, kind: HighlightKind) {
            if (text.isEmpty()) return
            flushPlain()
            val start = builder.length
            builder.append(text)
            builder.addStyle(SpanStyle(color = palette.colorOf(kind)), start, builder.length)
        }

        val n = code.length
        var i = 0
        while (i < n) {
            val c = code[i]

            // 1. 块注释
            var stop = -1
            for ((open, close) in spec.blockComments) {
                if (code.startsWith(open, i)) {
                    val at = code.indexOf(close, i + open.length)
                    stop = if (at < 0) n else at + close.length
                    break
                }
            }
            if (stop >= 0) {
                emit(code.substring(i, stop), HighlightKind.Comment)
                i = stop
                continue
            }

            // 2. 行注释
            var lineEnd = -1
            for (token in spec.lineComments) {
                if (code.startsWith(token, i)) {
                    val at = code.indexOf('\n', i)
                    lineEnd = if (at < 0) n else at
                    break
                }
            }
            if (lineEnd >= 0) {
                emit(code.substring(i, lineEnd), HighlightKind.Comment)
                i = maxOf(lineEnd, i + 1)
                continue
            }

            // 3. 三引号字符串（python 文档字符串等，可跨行）
            var multiEnd = -1
            for (q in spec.multiQuotes) {
                if (code.startsWith(q, i)) {
                    val at = code.indexOf(q, i + q.length)
                    multiEnd = if (at < 0) n else at + q.length
                    break
                }
            }
            if (multiEnd >= 0) {
                emit(code.substring(i, multiEnd), HighlightKind.String)
                i = multiEnd
                continue
            }

            // 4. 普通字符串（单行；反引号模板串可跨行）
            if (c in spec.quotes) {
                var j = i + 1
                while (j < n) {
                    val ch = code[j]
                    if (ch == '\\' && j + 1 < n) {
                        j += 2
                        continue
                    }
                    if (ch == c) {
                        j++
                        break
                    }
                    if (ch == '\n' && c != '`') break
                    j++
                }
                emit(code.substring(i, j), HighlightKind.String)
                i = maxOf(j, i + 1)
                continue
            }

            // 5. 数字
            if (spec.numberLiterals && c.isDigit() && (i == 0 || !isIdentPart(code[i - 1]))) {
                var j = i
                while (j < n) {
                    val ch = code[j]
                    if (ch.isLetterOrDigit() || ch == '_') {
                        j++
                        continue
                    }
                    if (ch == '.' && j + 1 < n && code[j + 1].isDigit()) {
                        j += 2
                        continue
                    }
                    break
                }
                emit(code.substring(i, j), HighlightKind.Number)
                i = maxOf(j, i + 1)
                continue
            }

            // 6. 标签名（html / xml）
            if (spec.tagNames && c == '<') {
                var j = i + 1
                if (j < n && code[j] == '/') j++
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '-' || code[j] == '_' || code[j] == ':')) j++
                if (j > i + 1) {
                    emit(code.substring(i, j), HighlightKind.Keyword)
                    i = j
                    continue
                }
            }

            // 7. 标识符：关键字 / 函数调用名
            if (c.isLetter() || c == '_' || c == '$') {
                var j = i
                while (j < n && isIdentPart(code[j])) j++
                val word = code.substring(i, j)
                var k = j
                while (k < n && (code[k] == ' ' || code[k] == '\t')) k++
                val isCall = k < n && code[k] == '('
                val isKeyword = if (spec.caseInsensitiveKeywords) {
                    spec.keywords.contains(word.lowercase())
                } else {
                    spec.keywords.contains(word)
                }
                when {
                    isKeyword -> emit(word, HighlightKind.Keyword)
                    isCall -> emit(word, HighlightKind.Function)
                    else -> plain.append(word)
                }
                i = j
                continue
            }

            plain.append(c)
            i++
        }
        flushPlain()
        return builder.toAnnotatedString()
    }
}

// ───────────────────────────────── 渲染 ─────────────────────────────────

private const val LINK_TAG = "shenyu-md-link"

@Composable
private fun MdBlockView(
    block: MdBlock,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    onLinkClick: ((String) -> Unit)?,
    onCodeCopy: ((String) -> Unit)?,
    onCitationClick: ((Int) -> Unit)?,
) {
    val colors = DsTheme.colors
    val fontScale = LocalDensity.current.fontScale
    when (block) {
        is MdBlock.Paragraph -> RichText(
            content = remember(block.inlines, colors, fontSize, color, onCitationClick, fontScale) {
                buildInline(block.inlines, colors, fontSize, color, onCitationClick, fontScale)
            },
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            onLinkClick = onLinkClick,
        )

        is MdBlock.Heading -> {
            // 官方 theme.css：h1 24/34、h2 22/32、h3 20/30、h4 16/28（600），其余同 h4
            val (baseSize, baseLine) = when (block.level) {
                1 -> 24f to 34f
                2 -> 22f to 32f
                3 -> 20f to 30f
                else -> 16f to 28f
            }
            val ratio = if (fontSize.value <= 0f) 1f else fontSize.value / 16f
            val headingSize = (baseSize * ratio).sp
            RichText(
                content = remember(block.inlines, colors, headingSize, color, onCitationClick, fontScale) {
                    buildInline(block.inlines, colors, headingSize, color, onCitationClick, fontScale)
                },
                color = color,
                fontSize = headingSize,
                lineHeight = (baseLine * ratio).sp,
                fontWeight = if (block.level >= 4) FontWeight.SemiBold else FontWeight.Bold,
                onLinkClick = onLinkClick,
            )
        }

        is MdBlock.Code ->
            if (block.language?.trim()?.lowercase() == "mermaid") {
                MermaidBlockView(block.code)
            } else {
                CodeBlockView(block, color, onCodeCopy)
            }

        is MdBlock.Quote -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val barWidth = 3.dp.toPx()
                    drawRoundRect(
                        color = colors.borderL2,
                        topLeft = Offset(0f, 0f),
                        size = Size(barWidth, size.height),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                    )
                }
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            block.blocks.forEach { child ->
                MdBlockView(
                    block = child,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    color = colors.labelSecondary,
                    onLinkClick = onLinkClick,
                    onCodeCopy = onCodeCopy,
                    onCitationClick = onCitationClick,
                )
            }
        }

        is MdBlock.BulletList -> ListView(
            items = block.items,
            ordered = false,
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
            onLinkClick = onLinkClick,
            onCodeCopy = onCodeCopy,
            onCitationClick = onCitationClick,
        )

        is MdBlock.OrderedList -> ListView(
            items = block.items,
            ordered = true,
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
            onLinkClick = onLinkClick,
            onCodeCopy = onCodeCopy,
            onCitationClick = onCitationClick,
        )

        is MdBlock.Math -> MathBlockView(block, fontSize, lineHeight, color, onCitationClick)

        is MdBlock.Notice -> Text(
            text = block.text,
            color = colors.labelTertiary,
            fontSize = (fontSize.value * 0.85f).sp,
            lineHeight = (lineHeight.value * 0.9f).sp,
        )

        MdBlock.Divider -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.borderL2),
        )

        is MdBlock.Table -> TableView(block, fontSize, lineHeight, color, onLinkClick, onCitationClick)
    }
}

/** 块级公式：居中、等宽、上下留白，过宽时可横向滚动。 */
@Composable
private fun MathBlockView(
    block: MdBlock.Math,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    onCitationClick: ((Int) -> Unit)?,
) {
    val colors = DsTheme.colors
    val mathFontSize = (fontSize.value * 1.05f).sp
    val fontScale = LocalDensity.current.fontScale
    val content = remember(block.inlines, colors, mathFontSize, color, onCitationClick, fontScale) {
        buildInline(block.inlines, colors, mathFontSize, color, onCitationClick, fontScale)
    }
    // 首选 KaTeX（与官方同一套排版）；KaTeX 不可用时回退到下面的自绘近似
    var useFallback by remember(block.tex) { mutableStateOf(false) }
    var katexReady by remember(block.tex) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
        // KaTeX 就绪前先画自绘近似（不留空白），就绪后由 KaTeX 接管
        if (!katexReady || useFallback) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = content.annotated,
                    color = color,
                    fontFamily = FontFamily.Serif,
                    fontSize = mathFontSize,
                    lineHeight = (lineHeight.value * 1.05f).sp,
                    softWrap = false,
                )
            }
        }
        if (!useFallback) {
            MathView(
                tex = block.tex,
                fontSizePx = fontSize.value,
                modifier = Modifier.alpha(if (katexReady) 1f else 0f),
                onFallback = { useFallback = true },
                onReady = { katexReady = true },
            )
        }
    }
}

/**
 * ```mermaid 代码块：交给 [MermaidView] 用系统 WebView 离线渲染。
 * 外层与官方 .md-mermaid-diagram 对齐：圆角 8dp、markdownCodeBlock 底色、上下留白。
 */
@Composable
private fun MermaidBlockView(code: String) {
    val colors = DsTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DsSize.radiusS))
            .background(colors.markdownCodeBlock)
            .padding(vertical = 8.dp),
    ) {
        MermaidView(code = code, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CodeBlockView(
    block: MdBlock.Code,
    color: Color,
    onCodeCopy: ((String) -> Unit)?,
) {
    val colors = DsTheme.colors
    // 高亮结果按（代码, 语言, 配色）缓存，流式每帧不会重算整块
    val palette = remember(colors) { CodePalette.of(colors) }
    val highlighted = remember(block.code, block.language, palette) {
        runCatching { CodeHighlighter.highlight(block.code, block.language, palette) }
            .getOrElse { AnnotatedString(block.code) }
    }
    var copied by remember(block.code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500L)
            copied = false
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.markdownCodeBlock),
    ) {
        // 顶栏（官方 .md-code-block-banner）：语言名 12/18 等宽字体、复制按钮 28dp/圆角 8/13sp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.markdownCodeBanner)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language.orEmpty().ifEmpty { "text" },
                color = colors.labelPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = onCodeCopy != null) {
                        if (copied) return@clickable
                        onCodeCopy?.invoke(block.code)
                        copied = true
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = if (copied) DsIcons.Check else DsIcons.Copy,
                    contentDescription = null,
                    tint = colors.labelSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = if (copied) "已复制" else "复制",
                    color = colors.labelSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
        }
        // 正文：官方 pre 为 white-space: pre-wrap + word-break: break-all，padding 16、字号 13/22
        Text(
            text = highlighted,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 22.sp,
            softWrap = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@Composable
private fun ListView(
    items: List<MdListItem>,
    ordered: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    onLinkClick: ((String) -> Unit)?,
    onCodeCopy: ((String) -> Unit)?,
    onCitationClick: ((Int) -> Unit)?,
    depth: Int = 0,
) {
    val colors = DsTheme.colors
    val fontScale = LocalDensity.current.fontScale
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (ordered) "${item.number.coerceAtLeast(1)}." else "•",
                    color = colors.labelSecondary,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    modifier = Modifier
                        .width(if (ordered) 26.dp else 16.dp)
                        .padding(end = 4.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RichText(
                        content = remember(item.inlines, colors, fontSize, color, onCitationClick, fontScale) {
                            buildInline(item.inlines, colors, fontSize, color, onCitationClick, fontScale)
                        },
                        color = color,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        onLinkClick = onLinkClick,
                    )
                    if (item.children.isNotEmpty() && depth < 8) {
                        ListView(
                            items = item.children,
                            ordered = ordered,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            color = color,
                            onLinkClick = onLinkClick,
                            onCodeCopy = onCodeCopy,
                            onCitationClick = onCitationClick,
                            depth = depth + 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableView(
    block: MdBlock.Table,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    onLinkClick: ((String) -> Unit)?,
    onCitationClick: ((Int) -> Unit)?,
) {
    // 官方 markdown.css：表格无外框、无表头底色；表头下边框 border-l3，行下边框 border-l2；
    // 单元格 padding 10/16，首列左内边距 0、末列右内边距 0；字号 15/25。
    val colors = DsTheme.colors
    // 官方有独立的表格预览页（ui.pages.table.TablePreviewActivity）：点表格整屏查看。
    var preview by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { preview = true },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            block.header.forEachIndexed { index, cell ->
                TableCell(
                    inlines = cell,
                    align = block.aligns.getOrElse(index) { MdAlign.Start },
                    canAlign = true,
                    isFirst = index == 0,
                    isLast = index == block.header.lastIndex,
                    color = color,
                    header = true,
                    onLinkClick = onLinkClick,
                    onCitationClick = onCitationClick,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.borderL3),
        )
        block.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                block.aligns.forEachIndexed { index, align ->
                    TableCell(
                        inlines = row.getOrNull(index).orEmpty(),
                        align = align,
                        canAlign = index < row.size,
                        isFirst = index == 0,
                        isLast = index == block.aligns.lastIndex,
                        color = color,
                        header = false,
                        onLinkClick = onLinkClick,
                        onCitationClick = onCitationClick,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.borderL2),
            )
        }
    }

    if (preview) {
        TablePreviewDialog(
            block = block,
            onDismiss = { preview = false },
        )
    }
}

/** 表格整屏预览（对照官方 TablePreviewActivity）：全屏白底、可横向滚动、右上角关闭。 */
@Composable
private fun TablePreviewDialog(
    block: MdBlock.Table,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .background(colors.bgBase),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = "表格",
                        color = colors.labelPrimary,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.IconButton(onClick = onDismiss) {
                        androidx.compose.material3.Icon(
                            imageVector = DsIcons.Close,
                            contentDescription = "关闭",
                            tint = colors.labelSecondary,
                        )
                    }
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    TableViewContent(
                        block = block,
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        color = colors.labelPrimary,
                        onLinkClick = null,
                        onCitationClick = null,
                    )
                }
            }
        }
    }
}

/** 表格本体的纯渲染（预览与正文共用）。 */
@Composable
private fun TableViewContent(
    block: MdBlock.Table,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    onLinkClick: ((String) -> Unit)?,
    onCitationClick: ((Int) -> Unit)?,
) {
    val colors = DsTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            block.header.forEachIndexed { index, cell ->
                TableCell(
                    inlines = cell,
                    align = block.aligns.getOrElse(index) { MdAlign.Start },
                    canAlign = true,
                    isFirst = index == 0,
                    isLast = index == block.header.lastIndex,
                    color = color,
                    header = true,
                    onLinkClick = onLinkClick,
                    onCitationClick = onCitationClick,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.borderL3),
        )
        block.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                block.aligns.forEachIndexed { index, align ->
                    TableCell(
                        inlines = row.getOrNull(index).orEmpty(),
                        align = align,
                        canAlign = index < row.size,
                        isFirst = index == 0,
                        isLast = index == block.aligns.lastIndex,
                        color = color,
                        header = false,
                        onLinkClick = onLinkClick,
                        onCitationClick = onCitationClick,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.borderL2),
            )
        }
    }
}

@Composable
private fun RowScope.TableCell(
    inlines: List<MdInline>,
    align: MdAlign,
    canAlign: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    color: Color,
    header: Boolean,
    onLinkClick: ((String) -> Unit)?,
    onCitationClick: ((Int) -> Unit)?,
) {
    val colors = DsTheme.colors
    val cellSize = 15.sp
    val cellLine = 25.sp
    val fontScale = LocalDensity.current.fontScale
    RichText(
        content = remember(inlines, colors, cellSize, color, onCitationClick, fontScale) {
            buildInline(inlines, colors, cellSize, color, onCitationClick, fontScale)
        },
        color = color,
        fontSize = cellSize,
        lineHeight = cellLine,
        fontWeight = if (header) FontWeight.SemiBold else null,
        textAlign = if (!canAlign) TextAlign.Start else when (align) {
            MdAlign.Start -> TextAlign.Start
            MdAlign.Center -> TextAlign.Center
            MdAlign.End -> TextAlign.End
        },
        modifier = Modifier
            .weight(1f)
            .padding(
                start = if (isFirst) 0.dp else 16.dp,
                end = if (isLast) 0.dp else 16.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
        onLinkClick = onLinkClick,
    )
}

/** 一行文本的渲染产物：AnnotatedString + 其中内联组件的占位映射（引用角标用）。 */
private class MdContent(
    val annotated: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
)

/**
 * 行内节点 → AnnotatedString，并挂上链接的字符串注解供点击命中；
 * `[citation:N]` 不画成文字，而是插入一个内联组件占位，由 [CitationBadge] 真正绘制。
 */
private fun buildInline(
    inlines: List<MdInline>,
    colors: DsColors,
    textSize: TextUnit,
    baseColor: Color,
    onCitationClick: ((Int) -> Unit)?,
    fontScale: Float,
): MdContent {
    val builder = AnnotatedString.Builder()
    val inlineContent = mutableMapOf<String, InlineTextContent>()
    var citationSeq = 0
    inlines.forEach { run ->
        // 引用角标：内联组件占位（官方用 .ds-markdown-cite 的 span，此处等价于一个小徽标）
        if (run.citation) {
            val index = run.text.toIntOrNull() ?: 0
            val tag = "citation${citationSeq++}"
            builder.appendInlineContent(tag, run.text)
            // Placeholder 只接受 sp/em，这里按 fontScale 折算，使占位框恒为 14dp
            inlineContent[tag] = InlineTextContent(
                placeholder = Placeholder(
                    width = citationBadgeWidth(run.text.length, fontScale),
                    height = (CITATION_BADGE_HEIGHT_DP / fontScale).sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
                ),
                children = { _ ->
                    CitationBadge(
                        index = index,
                        modifier = if (onCitationClick != null) {
                            Modifier.clickable { onCitationClick(index) }
                        } else {
                            Modifier
                        },
                    )
                },
            )
            return@forEach
        }
        val start = builder.length
        builder.append(run.text)
        val end = builder.length
        if (end == start) return@forEach
        val decoration = when {
            run.strike && run.link != null -> TextDecoration.combine(
                listOf(TextDecoration.LineThrough, TextDecoration.Underline),
            )
            run.strike -> TextDecoration.LineThrough
            run.link != null -> TextDecoration.Underline
            else -> null
        }
        // 行内代码走等宽 + 行内代码底色（官方 padding 0 5px、radius 6、0.875em）；
        // 行内公式是 KaTeX 的数学排版，不套代码样式（官方无底色、非等宽）
        val mono = run.code
        val spanColor = if (run.link != null) colors.brandText else baseColor
        val spanSize = when {
            run.code -> (textSize.value * 0.875f).sp
            run.formula -> (textSize.value * 1.05f).sp
            else -> TextUnit.Unspecified
        }
        val baseline = when (run.script) {
            MathScript.Sup -> BaselineShift.Superscript
            MathScript.Sub -> BaselineShift.Subscript
            MathScript.None -> null
        }
        builder.addStyle(
            SpanStyle(
                color = spanColor,
                fontWeight = if (run.bold) FontWeight.SemiBold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                textDecoration = decoration,
                fontFamily = when {
                    run.code -> FontFamily.Monospace
                    run.formula -> FontFamily.Serif
                    else -> null
                },
                fontSize = spanSize,
                background = if (run.code) colors.markdownInlineCode else Color.Unspecified,
                baselineShift = baseline,
            ),
            start,
            end,
        )
        run.link?.let { builder.addStringAnnotation(LINK_TAG, it, start, end) }
    }
    return MdContent(builder.toAnnotatedString(), inlineContent)
}

private const val CITATION_BADGE_HEIGHT_DP = 14f

/**
 * 角标占位宽度：官方是 padding 0 4px + 最小宽 14px + 9px 等宽字体，
 * 这里按位数估算（1 位 14dp，之后每位 +5.5dp），保证多位角标不被裁掉；
 * 同样按 fontScale 折算，使实际占用宽度就是这么多 dp。
 */
private fun citationBadgeWidth(digits: Int, fontScale: Float): TextUnit =
    (maxOf(14f, 8f + 5.5f * digits.coerceAtLeast(1)) / fontScale).sp

/** 统一文本出口：负责链接点击命中与内联组件（无回调时连手势都不挂）。 */
@Composable
private fun RichText(
    content: MdContent,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val annotated = content.annotated
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val clickModifier = if (onLinkClick == null) {
        Modifier
    } else {
        Modifier.pointerInput(annotated, onLinkClick) {
            detectTapGestures { offset ->
                val result = layout ?: return@detectTapGestures
                val index = result.getOffsetForPosition(offset).coerceIn(0, annotated.length)
                val end = (index + 1).coerceAtMost(annotated.length)
                if (index < end) {
                    val url = annotated.getStringAnnotations(LINK_TAG, index, end).firstOrNull()?.item
                    if (url != null) onLinkClick(url)
                }
            }
        }
    }
    Text(
        text = annotated,
        modifier = modifier.then(clickModifier),
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        textAlign = textAlign,
        inlineContent = content.inlineContent,
        onTextLayout = { layout = it },
    )
}
