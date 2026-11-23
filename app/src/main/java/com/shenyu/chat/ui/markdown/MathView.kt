package com.shenyu.chat.ui.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shenyu.chat.ui.theme.DsTheme
import org.json.JSONObject

/**
 * 块级公式渲染：系统 WebView 离线跑打包好的 KaTeX（assets/katex/），
 * 与官方 `services/markdown/math.ts` 的 `katex.render(tex, { displayMode: true })` 同一套排版。
 *
 * 页面 `assets/math.html` 暴露 `window.renderMath(tex, colorCss, fontSizePx)`：
 * - 渲染成功 → `MathBridge.onHeight(cssPx)` 回传内容高度，Compose 侧按该高度撑开；
 * - 失败 → `MathBridge.onError(msg)` 回传，调用方回退到自绘近似排版。
 */
private const val MATH_PAGE = "file:///android_asset/math.html"
private const val MATH_BRIDGE_NAME = "MathBridge"
private const val MATH_PLACEHOLDER_HEIGHT_DP = 44
private const val MATH_TAG = "MathView"

private class MathBridge(
    private val heightSink: (Int) -> Unit,
    private val errorSink: (String) -> Unit,
) {
    @JavascriptInterface
    fun onHeight(h: Int) {
        heightSink(h)
    }

    @JavascriptInterface
    fun onError(message: String) {
        errorSink(message)
    }
}

/** 不消费触摸事件：公式是静态的，拖动应该交给外层聊天列表。 */
private class MathWebView(context: Context) : WebView(context) {
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}

/**
 * 把一段块级 TeX 渲染成公式。
 *
 * @param tex 原始 TeX（不含 `$$` 定界符）
 * @param fontSizePx 正文字号（CSS px），KaTeX 会以 em 相对缩放
 * @param onFallback 渲染失败时的回调（调用方据此回退自绘排版）
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MathView(
    tex: String,
    modifier: Modifier = Modifier,
    fontSizePx: Float = 16f,
    onFallback: ((String) -> Unit)? = null,
    onReady: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var heightPx by remember(tex) { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableStateOf(false) }

    val colorCss = remember(colors) { colors.labelPrimary.toCssHex() }

    val bridge = remember(onFallback, onReady) {
        MathBridge(
            heightSink = { h: Int ->
                mainHandler.post {
                    if (h > 0) {
                        heightPx = h
                        onReady?.invoke()
                    }
                }
            },
            errorSink = { message: String ->
                mainHandler.post {
                    Log.w(MATH_TAG, "KaTeX 渲染失败: $message")
                    onFallback?.invoke(message)
                }
            },
        )
    }

    // 兜底：2.5 秒内没拿到高度就认为 WebView 渲染不可用，回退自绘排版
    LaunchedEffect(tex, loaded) {
        if (!loaded) return@LaunchedEffect
        kotlinx.coroutines.delay(2500)
        if (heightPx == 0) onFallback?.invoke("超时未取到公式高度")
    }

    LaunchedEffect(tex, colorCss, fontSizePx, loaded) {
        if (!loaded) return@LaunchedEffect
        val view = webView ?: return@LaunchedEffect
        val payload = JSONObject.quote(tex)
        val color = JSONObject.quote(colorCss)
        view.evaluateJavascript("renderMath($payload, $color, ${fontSizePx.toInt()});", null)
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(if (heightPx > 0) heightPx.dp else MATH_PLACEHOLDER_HEIGHT_DP.dp),
        factory = { context ->
            MathWebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.loadsImagesAutomatically = true
                settings.blockNetworkLoads = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                addJavascriptInterface(bridge, MATH_BRIDGE_NAME)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loaded = true
                        val payload = JSONObject.quote(tex)
                        val color = JSONObject.quote(colorCss)
                        view?.evaluateJavascript(
                            "renderMath($payload, $color, ${fontSizePx.toInt()});",
                            null,
                        )
                    }
                }
                loadUrl(MATH_PAGE)
                webView = this
            }
        },
        onRelease = { view ->
            view.removeJavascriptInterface(MATH_BRIDGE_NAME)
            view.destroy()
        },
    )
}

private fun androidx.compose.ui.graphics.Color.toCssHex(): String {
    val argb = toArgb()
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return if (a == 0xFF) {
        String.format("#%02X%02X%02X", r, g, b)
    } else {
        String.format("rgba(%d,%d,%d,%.2f)", r, g, b, a / 255f)
    }
}
