package com.shenyu.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计变量，取值来自上游前端 theme.css 的语义层（--dsw-alias-*）。
 * 明暗两套都只在这里定义一次，业务代码一律通过 DsTheme.colors 取值。
 */
@Immutable
data class DsColors(
    val bgBase: Color,
    val bgLayer2: Color,
    val bgLayer3: Color,
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val brandPrimary: Color,
    val brandText: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelCaption: Color,
    val labelDimmed: Color,
    val bubble: Color,
    val inputMajor: Color,
    val sidebarFill: Color,
    val sidebarItemHover: Color,
    val sidebarItemActive: Color,
    val interactiveHover: Color,
    val interactiveActive: Color,
    val markdownCodeBlock: Color,
    val markdownInlineCode: Color,
    val markdownCodeBanner: Color,
    val stateError: Color,
    val stateErrorSecondary: Color,
    val toastBg: Color,
    val selector: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val isDark: Boolean,
)

val LightDsColors = DsColors(
    bgBase = Color(0xFFFFFFFF),
    bgLayer2 = Color(0xFFFFFFFF),
    bgLayer3 = Color(0xFFFFFFFF),
    borderL1 = Color(0x0A000000),
    borderL2 = Color(0x1A000000),
    borderL3 = Color(0x1F000000),
    brandPrimary = Color(0xFF426EFE),
    brandText = Color(0xFF426EFE),
    labelPrimary = Color(0xFF0F0F0F),
    labelSecondary = Color(0xFF61666B),
    labelTertiary = Color(0xFF81858C),
    labelCaption = Color(0xFFADB2B8),
    labelDimmed = Color(0xFFE1E5EE),
    bubble = Color(0xFFEDF3FE),
    inputMajor = Color(0xFFFFFFFF),
    sidebarFill = Color(0xFFF9FAFB),
    sidebarItemHover = Color(0xFFF1F3F5),
    sidebarItemActive = Color(0xFFEDF3FE),
    interactiveHover = Color(0x0F263148),
    interactiveActive = Color(0x1A263148),
    markdownCodeBlock = Color(0xFFF9FAFB),
    markdownInlineCode = Color(0xFFEBEEF2),
    markdownCodeBanner = Color(0xFFF9FAFB),
    stateError = Color(0xFFEC1313),
    stateErrorSecondary = Color(0xFFF25A5A),
    toastBg = Color(0xFF353638),
    selector = Color(0xFFF1F3F5),
    userBubble = Color(0xFFEDF3FE),
    userBubbleText = Color(0xFF0F0F0F),
    isDark = false,
)

val DarkDsColors = DsColors(
    bgBase = Color(0xFF151517),
    bgLayer2 = Color(0xFF2C2C2E),
    bgLayer3 = Color(0xFF353638),
    borderL1 = Color(0x0FFFFFFF),
    borderL2 = Color(0x1FFFFFFF),
    borderL3 = Color(0x29FFFFFF),
    // 官方 values-night/colors.xml：primary = #507BF2
    brandPrimary = Color(0xFF507BF2),
    brandText = Color(0xFF679EFE),
    labelPrimary = Color(0xFFF9FAFB),
    labelSecondary = Color(0xFFCFD3D6),
    labelTertiary = Color(0xFFADB2B8),
    labelCaption = Color(0xFF81858C),
    labelDimmed = Color(0xFF43454A),
    bubble = Color(0xFF2C2C2E),
    inputMajor = Color(0xFF2C2C2E),
    sidebarFill = Color(0xFF1B1B1C),
    sidebarItemHover = Color(0xFF242426),
    sidebarItemActive = Color(0xFF242426),
    interactiveHover = Color(0x14FFFFFF),
    interactiveActive = Color(0x24FFFFFF),
    markdownCodeBlock = Color(0xFF1B1B1C),
    markdownInlineCode = Color(0xFF353638),
    markdownCodeBanner = Color(0xFF242426),
    stateError = Color(0xFFF25A5A),
    stateErrorSecondary = Color(0xFFEC1313),
    toastBg = Color(0xFF43454A),
    selector = Color(0xFF2C2C2E),
    userBubble = Color(0xFF2C2C2E),
    userBubbleText = Color(0xFFF9FAFB),
    isDark = true,
)

/** 间距 / 圆角 / 控件高度，对应前端 --ds-control-height-* 与常用 padding。 */
object DsSize {
    val controlXl = 44.dp
    val controlL = 40.dp
    val controlM = 36.dp
    val controlS = 32.dp
    val controlXs = 28.dp

    val radiusS = 8.dp
    val radiusM = 12.dp
    val radiusL = 16.dp
    val radiusXl = 24.dp
    val radiusPill = 999.dp

    val gapXs = 4.dp
    val gapS = 8.dp
    val gapM = 12.dp
    val gapL = 16.dp
    val gapXl = 24.dp
}

private val DsTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, fontWeight = FontWeight.W600),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, fontWeight = FontWeight.W600),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, fontWeight = FontWeight.W600),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, fontWeight = FontWeight.W600),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.W500),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.W500),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp),
)

val LocalDsColors = staticCompositionLocalOf { LightDsColors }

object DsTheme {
    val colors: DsColors
        @Composable @ReadOnlyComposable get() = LocalDsColors.current
}

@Composable
fun ShenyuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkDsColors else LightDsColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.brandPrimary,
            onPrimary = Color.White,
            background = colors.bgBase,
            onBackground = colors.labelPrimary,
            surface = colors.bgBase,
            onSurface = colors.labelPrimary,
            surfaceVariant = colors.selector,
            onSurfaceVariant = colors.labelSecondary,
            error = colors.stateError,
        )
    } else {
        lightColorScheme(
            primary = colors.brandPrimary,
            onPrimary = Color.White,
            background = colors.bgBase,
            onBackground = colors.labelPrimary,
            surface = colors.bgBase,
            onSurface = colors.labelPrimary,
            surfaceVariant = colors.selector,
            onSurfaceVariant = colors.labelSecondary,
            error = colors.stateError,
        )
    }
    CompositionLocalProvider(LocalDsColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = DsTypography, content = content)
    }
}
