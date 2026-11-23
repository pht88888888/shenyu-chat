package com.shenyu.chat.ui.session

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shenyu.chat.ui.theme.DsTheme
import java.util.Locale

/**
 * 消息里的文档附件卡片（对照 MessageFile.tsx + FileCard.tsx / .module.css）。
 *
 * 左侧 28×28 文件类型图标（按扩展名配色/形状区分），中间文件名（单行省略）+ 下方
 * 「扩展名 · 大小」小字；卡片宽 240dp、最小高 64dp、圆角 16dp、1dp 描边。
 */
@Composable
fun MessageFileCard(
    name: String,
    size: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val ext = extensionOf(name)
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        onClick != null && pressed -> colors.interactiveHover
        else -> colors.inputMajor
    }
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    val meta = listOf(ext, formatSize(size)).filter { it.isNotBlank() }.joinToString(" ")

    Row(
        modifier = modifier
            .width(240.dp)
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, colors.borderL2, shape)
            .then(clickableModifier)
            .padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(ext = ext, size = 28.dp, modifier = Modifier.padding(end = 10.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        ) {
            Text(
                text = name,
                color = colors.labelPrimary,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                color = colors.labelTertiary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 文件类型图标：按扩展名映射到官方 5 类（图片 / PDF / 表格 / PPT / 其它）配色与形状。 */
@Composable
fun FileTypeIcon(
    ext: String,
    size: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    val category = fileCategoryOf(ext)
    val icon = remember(category) { buildFileVector(category) }
    val painter = rememberVectorPainter(icon)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        // PDF 的白色 “PDF” 字样官方是矢量描出，这里用文本近似，居中偏下压在页身区域
        if (category == FileCategory.PDF) {
            Text(
                text = "PDF",
                color = Color.White,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private enum class FileCategory(val color: Color) {
    IMAGE(Color(0xFF8B76F6)),
    PDF(Color(0xFFEE5138)),
    TABLE(Color(0xFF3FB67F)),
    PPT(Color(0xFFFB8E34)),
    OTHER(Color(0xFF418CFF)),
}

private val IMAGE_EXTS = setOf("JPG", "JPEG", "PNG", "GIF", "WEBP", "BMP", "SVG", "HEIC", "TIF", "TIFF")
private val TABLE_EXTS = setOf("XLS", "XLSX", "CSV")
private val PPT_EXTS = setOf("PPT", "PPTX")

private fun fileCategoryOf(ext: String): FileCategory {
    val upper = ext.uppercase()
    return when {
        IMAGE_EXTS.contains(upper) -> FileCategory.IMAGE
        upper == "PDF" -> FileCategory.PDF
        TABLE_EXTS.contains(upper) -> FileCategory.TABLE
        PPT_EXTS.contains(upper) -> FileCategory.PPT
        else -> FileCategory.OTHER
    }
}

/** 扩展名（大写，不含点）；无扩展名返回空串。与官方 getExtension 一致。 */
private fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(dot + 1).uppercase() else ""
}

/** 字节数格式化，与官方 formatSize 保持一致：<1024 用 B，否则 KB/MB/GB，<10 保留一位小数。 */
private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024.0
        unit++
    }
    val number = if (value < 10) String.format(Locale.US, "%.1f", value) else Math.round(value).toString()
    return "$number ${units[unit]}"
}

// ---- 矢量绘制 ----

/** 官方 FileIconFrame 的页身路径（28×28 viewBox）。 */
private const val FRAME_PATH =
    "M8.48924 28H19.5108C21.6479 28 22.7165 28 23.5594 27.6509C24.6833 27.1853 25.5762 26.2924 " +
        "26.0417 25.1685C26.3909 24.3256 26.3909 23.257 26.3909 21.1199V8.79443C26.3909 8.32877 " +
        "26.3909 8.09593 26.3471 7.87507C26.2887 7.58058 26.173 7.30042 26.0067 7.05048C25.882 6.86303 " +
        "25.7177 6.69799 25.3893 6.36792L20.0611 1.01354C19.7304 0.681235 19.5651 0.515081 19.3769 " +
        "0.38885C19.126 0.220541 18.8443 0.103463 18.5481 0.0443412C18.3259 0 18.0915 0 17.6226 " +
        "0H8.48924C6.35209 0 5.28351 0 4.4406 0.349145C3.31672 0.814671 2.4238 1.70759 1.95828 " +
        "2.83147C1.60913 3.67438 1.60913 4.74296 1.60913 6.88011V21.1199C1.60913 23.257 1.60913 " +
        "24.3256 1.95828 25.1685C2.4238 26.2924 3.31672 27.1853 4.4406 27.6509C5.28351 28 6.35209 " +
        "28 8.48924 28Z"

/** 右上角折角（白色 70%）。 */
private const val CORNER_PATH =
    "M26.3909 7.37445L19.0525 0V3.77445C19.0525 4.89271 19.0525 5.45184 19.2352 5.89289C19.4788 " +
        "6.48096 19.946 6.94818 20.5341 7.19176C20.9751 7.37445 21.5342 7.37445 22.6525 " +
        "7.37445H26.3909Z"

private fun buildFileVector(category: FileCategory): ImageVector {
    val builder = ImageVector.Builder(
        name = "FileIcon_${category.name}",
        defaultWidth = 28.dp,
        defaultHeight = 28.dp,
        viewportWidth = 28f,
        viewportHeight = 28f,
    )
    builder.addPath(
        pathData = PathParser().parsePathString(FRAME_PATH).toNodes(),
        fill = SolidColor(category.color),
    )
    builder.addPath(
        pathData = PathParser().parsePathString(CORNER_PATH).toNodes(),
        fill = SolidColor(Color.White.copy(alpha = 0.7f)),
    )
    when (category) {
        FileCategory.OTHER -> {
            // 三条白色横线（末行较短）
            listOf(
                "M8.10132 12.6846H19.8948",
                "M8.10132 16.4688H19.8948",
                "M8.10132 20.252H16.0199",
            ).forEach { line ->
                builder.addPath(
                    pathData = PathParser().parsePathString(line).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 1.6f,
                    strokeLineCap = StrokeCap.Round,
                )
            }
        }

        FileCategory.TABLE -> {
            // 2×2 白色圆角小方块
            listOf(
                "M9.3879 11.6172h4.24569v4.24569H9.3879z",
                "M9.3879 16.5879h4.24569v4.24569H9.3879z",
                "M14.3665 11.6172h4.24569v4.24569H14.3665z",
                "M14.3665 16.5879h4.24569v4.24569H14.3665z",
            ).forEach { rect ->
                builder.addPath(
                    pathData = PathParser().parsePathString(rect).toNodes(),
                    fill = SolidColor(Color.White),
                )
            }
        }

        FileCategory.PPT -> {
            // 圆形 + 四分之一圆（近似官方两个白色图形）
            builder.addPath(
                pathData = PathParser().parsePathString(
                    "M12.9 13.6a3.6 3.6 0 1 1 0 7.2 3.6 3.6 0 0 1 0-7.2z",
                ).toNodes(),
                fill = SolidColor(Color.White),
            )
            builder.addPath(
                pathData = PathParser().parsePathString("M17 10.2A4.8 4.8 0 0 1 21.8 15H17z").toNodes(),
                fill = SolidColor(Color.White),
            )
        }

        FileCategory.IMAGE -> {
            // 山峰 + 太阳（近似官方图片符号）
            builder.addPath(
                pathData = PathParser().parsePathString(
                    "M8.2 20.4l3.3-4.7 2.6 3.5 2.1-2.9 3.6 4.1z",
                ).toNodes(),
                fill = SolidColor(Color.White),
            )
            builder.addPath(
                pathData = PathParser().parsePathString(
                    "M11.6 12.3a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z",
                ).toNodes(),
                fill = SolidColor(Color.White),
            )
        }

        FileCategory.PDF -> {
            // 字样在 Composable 里用 Text 叠加
        }
    }
    return builder.build()
}
