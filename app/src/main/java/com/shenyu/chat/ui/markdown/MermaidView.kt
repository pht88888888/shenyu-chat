package com.shenyu.chat.ui.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.shenyu.chat.ui.theme.DsTheme
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Mermaid 图渲染（对照官方 `components/markdown/MermaidDiagram.tsx`）。
 *
 * 官方用 mermaid npm 包把 ```mermaid 代码块编译成 SVG，并有「图表为空 / 图表生成中… /
 * 图表渲染失败」三种状态。这里用**系统 WebView 离线**跑同一份 mermaid（assets/mermaid.min.js），
 * 页面 assets/mermaid.html 暴露 `window.renderDiagram(code, dark, fontSizePx)`：
 * - 渲染成功 → 通过 `MermaidBridge.onHeight(cssPx)` 回传内容高度，Compose 侧用该高度撑开 WebView；
 * - 渲染失败 → 通过 `MermaidBridge.onError(msg)` 回传，未拿到过高度时显示灰色提示行，
 *   已显示过图形时保留上一张有效图（与官方流式行为一致）。
 *
 * 注意：WebView 宽度（dp）与页面 CSS px 一一对应（viewport = device-width, initial-scale = 1），
 * 所以回传的高度可以直接当 dp 用。
 */
private const val MERMAID_PAGE = "file:///android_asset/mermaid.html"
private const val MERMAID_BRIDGE_NAME = "MermaidBridge"
private const val MERMAID_FONT_SIZE_PX = 14
private const val MERMAID_PLACEHOLDER_HEIGHT_DP = 72
private const val MERMAID_RENDER_DEBOUNCE_MS = 150L
private const val MERMAID_TAG = "MermaidView"

/** JS → Kotlin 的桥；方法在 WebView 的 JS 线程调用，调用方负责切主线程。 */
private class MermaidBridge(
    private val onHeightCallback: (Int) -> Unit,
    private val onErrorCallback: (String) -> Unit,
) {
    @JavascriptInterface
    fun onHeight(h: Int) {
        onHeightCallback(h)
    }

    @JavascriptInterface
    fun onError(message: String) {
        onErrorCallback(message)
    }
}

/**
 * 不消费触摸事件的 WebView：图是静态的，拖动时应该让外层聊天列表滚动，
 * 否则手指落在图上会卡住滚动。
 */
private class MermaidWebView(context: Context) : WebView(context) {
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}

/**
 * 把 ```mermaid 代码渲染成图。
 *
 * @param code mermaid 源文本（流式时可能是半截，交给 mermaid 自行判断，失败则保留上一张图）。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MermaidView(code: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val dark = colors.isDark
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // 0 表示还没拿到高度（首帧占位），>0 即渲染出的内容高度（dp）
    var heightPx by remember { mutableIntStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val bridge = remember {
        MermaidBridge(
            onHeightCallback = { h ->
                mainHandler.post {
                    if (h > 0) {
                        heightPx = h
                        errorText = null
                    }
                }
            },
            onErrorCallback = { message ->
                mainHandler.post {
                    Log.w(MERMAID_TAG, "渲染失败: $message")
                    errorText = message.ifBlank { "渲染失败" }
                }
            },
        )
    }

    // 换图/换主题时清掉上一张的错误状态，让新内容重新有机会渲染出来
    LaunchedEffect(code, dark) {
        errorText = null
    }

    // 空代码：官方 mermaidEmpty
    if (code.isBlank()) {
        MermaidStatus(text = "图表为空", modifier = modifier)
        return
    }

    Box(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (heightPx > 0) heightPx.dp else MERMAID_PLACEHOLDER_HEIGHT_DP.dp),
            factory = { context ->
                MermaidWebView(context).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    isLongClickable = false
                    isHapticFeedbackEnabled = false
                    // 圆角裁剪（外层 clip）在硬件层下对 WebView 无效，必须走软件层
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = true
                        allowContentAccess = false
                        domStorageEnabled = true
                        loadsImagesAutomatically = true
                        textZoom = 100
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        // 完全离线：不发起任何网络请求（mermaid 走本地 assets）
                        blockNetworkLoads = true
                    }
                    addJavascriptInterface(bridge, MERMAID_BRIDGE_NAME)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            pageLoaded = true
                        }
                    }
                    loadUrl(MERMAID_PAGE)
                    webView = this
                }
            },
            update = { /* 内容通过下面的 LaunchedEffect 推给页面 */ },
        )

        if (heightPx == 0) {
            // 首帧占位文案：失败优先，其次加载中
            Text(
                text = if (errorText != null) "图表渲染失败" else "图表生成中…",
                color = colors.labelSecondary,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }

    // 页面就绪 + 代码/主题变化 → 防抖后把最新代码推给页面（流式时合并连续片段）
    LaunchedEffect(code, dark, pageLoaded, webView) {
        val view = webView
        if (view == null || !pageLoaded || code.isBlank()) return@LaunchedEffect
        delay(MERMAID_RENDER_DEBOUNCE_MS)
        val script = buildString {
            append("window.renderDiagram(")
            append(JSONObject.quote(code))
            append(',')
            append(if (dark) "true" else "false")
            append(',')
            append(MERMAID_FONT_SIZE_PX)
            append(')')
        }
        view.evaluateJavascript(script, null)
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                webView = null
                view.stopLoading()
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
            }
        }
    }
}

/** 状态提示行（对照官方 .md-mermaid-status：居中、最小高度、label-secondary、14sp）。 */
@Composable
private fun MermaidStatus(text: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MERMAID_PLACEHOLDER_HEIGHT_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = colors.labelSecondary, fontSize = 14.sp)
    }
}
