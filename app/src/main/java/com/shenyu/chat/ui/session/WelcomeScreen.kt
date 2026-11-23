package com.shenyu.chat.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shenyu.chat.ui.base.DsAvatar
import com.shenyu.chat.ui.theme.DsTheme
import kotlinx.coroutines.delay

/**
 * 空会话欢迎页（对齐官方 session/Welcome.tsx）：
 * 品牌鲸鱼标识 + 主标题。文案取自 i18n/zh.ts：
 * - welcomeMessageChat250808：今天有什么可以帮到你？
 * - contentCaveat：内容由 AI 生成，请仔细甄别
 *
 * 官方 Welcome 无推荐问题卡片，故只做「吉祥物 + 标题」，并附 AI 免责说明。
 * 三段内容依次淡入上移。
 *
 * @param onPick 保留以兼容 App.kt 调用；当前官方无推荐卡片，暂未使用。
 */
@Composable
fun WelcomeScreen(onPick: (String) -> Unit) {
    var showTitle by remember { mutableStateOf(false) }
    // 不在重组时重新抽欢迎语，否则开关和侧栏动画会导致标题跳变。
    val greeting = remember { greetingText() }

    LaunchedEffect(Unit) {
        showTitle = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .offset(y = 2.5f.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        // 官方首页：蓝色鲸鱼标识 + 「你好，我能帮什么忙吗？」
        StaggeredItem(visible = showTitle) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        com.shenyu.chat.R.drawable.chat_welcome_logo
                    ),
                    contentDescription = null,
                    modifier = Modifier.width(43.dp).height(32.dp),
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    // 官方欢迎语随时段变化（实测 10:53 「早上好，今天有什么计划？」、05:57 「你好，我能帮什么忙吗？」）
                    text = greeting,
                    color = DsTheme.colors.labelPrimary,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 官方欢迎语随时段变化：上午用「早上好」，其余时段用通用问候。 */
private fun greetingText(): String {
    val hour = runCatching { java.time.LocalTime.now().hour }.getOrDefault(12)
    return if (hour in 7..10) "早上好，今天有什么计划？" else "你好，有什么我能帮你的吗？"
}

/** 依次淡入 + 上移的入场动画容器。 */
@Composable
private fun StaggeredItem(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 320)) +
            slideInVertically(
                animationSpec = tween(durationMillis = 320),
                initialOffsetY = { it / 4 },
            ),
    ) {
        content()
    }
}
