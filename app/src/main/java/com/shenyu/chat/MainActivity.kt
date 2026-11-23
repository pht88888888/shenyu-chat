package com.shenyu.chat

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shenyu.chat.chat.ChatViewModel
import com.shenyu.chat.data.ThemeMode
import com.shenyu.chat.ui.ShenyuApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM),
        )
        setContent {
            val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(this))
            val settings by viewModel.settings.state.collectAsState()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES

                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val base = LocalDensity.current
            // Native UI uses discrete DPI steps, not raw Android density. The custom
            // message font preference is applied only once, in the message region.
            val metrics = resources.displayMetrics
            val density = remember(base, metrics.densityDpi, metrics.xdpi, metrics.ydpi) {
                com.shenyu.chat.ui.theme.nativeUiDensity(
                    logicalDpi = metrics.densityDpi,
                    physicalDpi = maxOf(metrics.xdpi, metrics.ydpi),
                    systemFontScale = base.fontScale,
                )
            }
            CompositionLocalProvider(LocalDensity provides density) {
                ShenyuApp(viewModel = viewModel)
            }
        }
    }

    private companion object {
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
