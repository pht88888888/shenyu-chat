package com.shenyu.chat.ui.base

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Terminal geometry of the official 2.8.0 auto_play_on/off Lottie assets (25 × 24).
 * Top-bar only: these are not the differently shaped ic_mute drawable resources.
 *
 * The four asset-1 subpaths share a NonZero fill; drawing them independently would
 * incorrectly fill the speaker's interior. Their flattened translation is (1.96,1.97):
 * root parent (12,12.01), precomp (-11,-11), layer (11,11), group anchor (-10.04,-10.04).
 * The transparent rectangle layer is intentionally omitted.
 *
 * Off's tt=2 inverted-alpha matte is the width-3.2 butt-capped straight path. A
 * viewport contour plus oppositely wound matte rectangle implements that exact
 * terminal cutout without a dependency or raster approximation. The black slash
 * is a separate width-1.8 butt-capped cubic, retaining its original control points.
 * On's terminal trim is empty, so neither matte nor slash is present.
 *
 * Icon supplies the tint, as for OfficialIcons. Geometry is source-derived;
 * Compose clip/stroke anti-aliasing is not claimed identical to Lottie's renderer.
 * Rendering the source's 25:24 aspect ratio in a square 24dp slot is intentional.
 */
object AutoTtsIcons {
    val On: ImageVector by lazy { terminal(off = false) }
    val Off: ImageVector by lazy { terminal(off = true) }

    private fun terminal(off: Boolean): ImageVector = ImageVector.Builder(
        name = if (off) "auto_play_off_terminal" else "auto_play_on_terminal",
        defaultWidth = 25.dp,
        defaultHeight = 24.dp,
        viewportWidth = 25f,
        viewportHeight = 24f,
    ).apply {
        if (off) addGroup(
            name = "invertedAlphaMatte",
            clipPathData = PathParser().parsePathString(MATTE_COMPLEMENT).toNodes(),
        )
        addPath(
            pathData = PathParser().parsePathString(SPEAKER_AND_WAVES).toNodes(),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.NonZero,
        )
        if (off) {
            clearGroup()
            addPath(
                pathData = PathParser().parsePathString(OFF_SLASH).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 4f,
            )
        }
    }.build()

    private const val SPEAKER_AND_WAVES = "M3.76,14.82C3.76,15.215 3.763,15.439 3.78,15.6C3.784,15.641 3.786,15.662 3.79,15.68C3.808,15.684 3.838,15.695 3.88,15.7C4.041,15.717 4.265,15.72 4.66,15.72C5.162,15.72 5.668,15.72 6.17,15.72C6.246,15.77 6.314,15.82 6.39,15.87C6.39,15.87 11.7904,19.466 11.7904,19.466C11.834878,19.495653 11.877158,19.523702 11.917241,19.550145C11.917473,19.502248 11.917589,19.45152 11.917589,19.397963C11.917589,19.397963 11.917589,4.733604 11.917589,4.733604C11.917589,4.676254 11.917463,4.621958 11.917213,4.570719C11.876072,4.601262 11.832572,4.63373 11.786712,4.668125C11.786712,4.668125 11.786681,4.668148 11.786681,4.668148C11.786681,4.668148 7.2,8.1 7.2,8.1C7.12,8.16 7.04,8.23 6.96,8.29C6.192,8.29 5.428,8.29 4.66,8.29C4.265,8.29 4.041,8.283 3.88,8.3C3.838,8.305 3.808,8.316 3.79,8.32C3.786,8.338 3.785,8.368 3.78,8.41C3.763,8.571 3.76,8.785 3.76,9.18C3.76,11.057 3.76,12.943 3.76,14.82C3.76,14.82 3.76,14.82 3.76,14.82C3.76,14.82 3.76,14.82 3.76,14.82ZM5.62,17.52C5.3,17.52 4.98,17.52 4.66,17.52C4.305,17.52 3.97,17.52 3.69,17.49C3.393,17.458 3.05,17.375 2.74,17.15C2.579,17.033 2.437,16.891 2.32,16.73C2.095,16.42 2.022,16.087 1.99,15.79C1.96,15.51 1.96,15.175 1.96,14.82C1.96,12.943 1.96,11.057 1.96,9.18C1.96,8.825 1.96,8.49 1.99,8.21C2.022,7.913 2.095,7.58 2.32,7.27C2.437,7.109 2.579,6.967 2.74,6.85C2.779,6.824 2.821,6.796 2.86,6.77C3.136,6.604 3.43,6.538 3.69,6.51C3.97,6.48 4.305,6.48 4.66,6.48C5.228,6.48 5.802,6.48 6.37,6.48C6.37,6.48 10.706679,3.22815 10.706679,3.22815C10.706679,3.22815 10.706725,3.228116 10.706725,3.228116C11.537791,2.604828 12.175165,2.347996 12.618846,2.45762C12.829445,2.50965 13.01874,2.604299 13.18673,2.741565C13.540635,3.030742 13.717588,3.694755 13.717588,4.733604C13.717588,4.733604 13.717588,19.397963 13.717588,19.397963C13.717589,20.385451 13.548855,21.023446 13.211385,21.311946C13.050716,21.449447 12.868515,21.54692 12.664781,21.604367C12.237951,21.725088 11.613661,21.511523 10.791914,20.963673C10.791914,20.963673 5.62,17.52 5.62,17.52ZM16.21,7.94C18.453,10.183 18.453,13.817 16.21,16.06C15.786,15.635 15.364,15.215 14.94,14.79C16.48,13.25 16.48,10.75 14.94,9.21C15.364,8.786 15.786,8.364 16.21,7.94C16.21,7.94 16.21,7.94 16.21,7.94C16.21,7.94 16.21,7.94 16.21,7.94ZM19.15,5C23.014,8.865 23.014,15.135 19.15,19C18.726,18.576 18.295,18.154 17.87,17.73C21.032,14.568 21.032,9.442 17.87,6.28C18.295,5.856 18.726,5.424 19.15,5C19.15,5 19.15,5 19.15,5C19.15,5 19.15,5 19.15,5Z"
    private const val MATTE_COMPLEMENT = "M0,0H25V24H0ZM2.1686291501,4.0513708499L19.31862915,21.20137085L21.58137085,18.93862915L4.4313708499,1.7886291501Z"
    private const val OFF_SLASH = "M2.23,4.01C7.947,9.713 13.663,15.417 19.38,21.12"
}
