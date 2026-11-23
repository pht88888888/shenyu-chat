package com.shenyu.chat.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shenyu.chat.BuildConfig
import com.shenyu.chat.chat.TransferProgress
import com.shenyu.chat.data.AppLanguage
import com.shenyu.chat.data.LongDocMode
import com.shenyu.chat.data.ProviderConfig
import com.shenyu.chat.data.ProviderProtocol
import com.shenyu.chat.data.SettingsState
import com.shenyu.chat.data.ThemeMode
import com.shenyu.chat.ui.base.DsIcons
import com.shenyu.chat.ui.base.DsLoadingDots
import com.shenyu.chat.ui.base.DsSelect
import com.shenyu.chat.net.ProviderEndpoints
import com.shenyu.chat.ui.theme.DsSize
import com.shenyu.chat.ui.theme.DsTheme

private enum class Tab(val label: String, val icon: ImageVector) {
    GENERAL("通用设置", DsIcons.Settings),
    DATA("数据管理", DsIcons.Database),
}

/** 成功提示用的绿色（明暗两套主题下都能读清）。 */
private val SuccessGreen = Color(0xFF12A150)

/**
 * 国家 / 地区列表，1:1 对齐官方 `services/locationData.ts` 的 `regionCodes`：
 * 港澳台用固定中文名（中国台湾 / 中国香港 / 中国澳门），其余取系统本地化国名。
 */
private val regionOptions: List<Pair<String, String>> by lazy {
    val overrides = mapOf(
        "TW" to "中国台湾",
        "HK" to "中国香港",
        "MO" to "中国澳门",
    )
    val codes = ("AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT " +
        "BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET " +
        "FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN " +
        "IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF " +
        "MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK " +
        "PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD " +
        "TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW").split(' ')
    val collator = java.text.Collator.getInstance(java.util.Locale.SIMPLIFIED_CHINESE)
    codes.mapNotNull { code ->
        val name = overrides[code]
            ?: java.util.Locale("", code).getDisplayCountry(java.util.Locale.SIMPLIFIED_CHINESE)
                .takeIf { it.isNotBlank() && it != code }
        name?.let { code to it }
    }.sortedWith(compareBy(collator) { it.second })
}

private val CellShape = RoundedCornerShape(16.dp)
private val PillShape = RoundedCornerShape(DsSize.radiusPill)

/**
 * 设置弹窗内的临时 UI 状态。挂在 SettingsScreen 上，切换分组不会丢掉正在输入的
 * API Key、链接与导入进度（官方弹窗也是这个行为）。
 */
private class SettingsUiState {
    // API Key
    var editingKey by mutableStateOf(false)
    var draft by mutableStateOf("")
    var verifying by mutableStateOf(false)
    var keyStatus by mutableStateOf<String?>(null)
    var keyOk by mutableStateOf(true)

    // 备份 / 导入
    var importingFile by mutableStateOf(false)
    var importingUrl by mutableStateOf(false)
    var exporting by mutableStateOf(false)
    var importStatus by mutableStateOf<String?>(null)
    var importOk by mutableStateOf(true)
    var exportStatus by mutableStateOf<String?>(null)
    var exportOk by mutableStateOf(true)
    var link by mutableStateOf("")
    var linkError by mutableStateOf<String?>(null)
    var linkStatus by mutableStateOf<String?>(null)

    // 接口地址
    var originStatus by mutableStateOf<String?>(null)
    var originOk by mutableStateOf(true)
}

@Composable
fun SettingsScreen(
    settings: SettingsState,
    transfer: TransferProgress?,
    onClose: () -> Unit,
    onUpdate: ((SettingsState) -> SettingsState) -> Unit,
    onDeleteAll: () -> Unit,
    onVerifyKey: (ProviderConfig, (Result<Unit>) -> Unit) -> Unit,
    onExport: ((Result<java.io.File>) -> Unit) -> Unit,
    onImport: (android.net.Uri, (Result<Int>) -> Unit) -> Unit,
    onImportUrl: (String, (Result<Int>) -> Unit) -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.GENERAL) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // 弹窗内的临时状态：切分组不丢输入
    val ui = remember {
        SettingsUiState().also {
            it.draft = settings.apiKey
            it.editingKey = settings.apiKey.isBlank()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 官方移动端：弹层离顶 64dp、顶部两角 24dp 圆角，背后露出遮罩
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(64.dp))
            Column(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(DsTheme.colors.bgBase)
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
            Header(onClose = onClose)

            // 传输进度：标题栏下方，淡入 + 展开；完成后由外部把 transfer 置空自动收起。
            var lastTransfer by remember { mutableStateOf<TransferProgress?>(null) }
            LaunchedEffect(transfer) {
                if (transfer != null) lastTransfer = transfer
            }
            AnimatedVisibility(
                visible = transfer != null,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
            ) {
                val shown = transfer ?: lastTransfer
                if (shown != null) TransferCard(shown)
            }

            Spacer(Modifier.height(DsSize.gapM))
            TabBar(selected = tab, onSelect = { tab = it })

            Crossfade(
                targetState = tab,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                animationSpec = tween(200),
                label = "settingsTab",
            ) { current ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = 24.dp),
                ) {
                    when (current) {
                        Tab.GENERAL -> GeneralContent(settings, onUpdate)
                        Tab.DATA -> DataContent(
                            ui = ui,
                            settings = settings,
                            onUpdate = onUpdate,
                            onVerifyKey = onVerifyKey,
                            onRequestDelete = { confirmDelete = true },
                            onExport = onExport,
                            onImport = onImport,
                            onImportUrl = onImportUrl,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除后，所有对话都将被清空且无法找回") },
            text = { Text("本地保存的附件及未发送的草稿也将被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteAll()
                }) { Text("删除所有对话", color = DsTheme.colors.stateError) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

/* ------------------------------------------------------------------ */
/* 标题栏 / 分组条 / 进度卡                                             */
/* ------------------------------------------------------------------ */

@Composable
private fun Header(onClose: () -> Unit) {
    val colors = DsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "设置",
            modifier = Modifier.weight(1f),
            color = colors.labelPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DsIcons.Close,
                contentDescription = "关闭",
                modifier = Modifier.size(22.dp),
                tint = colors.labelPrimary,
            )
        }
    }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val colors = DsTheme.colors
    // 官方移动端 navItem：高 40dp、最小宽 108dp、宽随内容、选中底色 interactive-bg-hover
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tab.entries.forEach { item ->
            val active = item == selected
            val background by animateColorAsState(
                targetValue = if (active) colors.interactiveHover else Color.Transparent,
                animationSpec = tween(200),
                label = "tabBackground",
            )
            Row(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .widthIn(min = 108.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .clickable { onSelect(item) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.labelPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.label,
                    color = colors.labelPrimary,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TransferCard(progress: TransferProgress) {
    val colors = DsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = DsSize.gapS)
            .clip(CellShape)
            .background(colors.brandPrimary.copy(alpha = 0.08f))
            .padding(horizontal = DsSize.gapL, vertical = DsSize.gapM),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = progress.label,
                modifier = Modifier.weight(1f),
                color = colors.labelPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress.detail.isNotBlank()) {
                Spacer(Modifier.width(DsSize.gapM))
                Text(
                    text = progress.detail,
                    color = colors.labelSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(DsSize.gapS))
        val fraction = progress.fraction
        if (fraction == null) {
            // 进度不可知：用不确定动画条
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = colors.brandPrimary,
                trackColor = colors.borderL1,
            )
        } else {
            val animated by animateFloatAsState(
                targetValue = fraction.coerceIn(0f, 1f),
                animationSpec = tween(240),
                label = "transferProgress",
            )
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth(),
                color = colors.brandPrimary,
                trackColor = colors.borderL1,
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 列表基础件                                                          */
/* ------------------------------------------------------------------ */

/** 行分隔线：0.5dp，铺满内容宽度（左右 24dp 由外层 padding 提供）。 */
@Composable
private fun CellDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(DsTheme.colors.borderL1),
    )
}

/** 平铺列表的一行：左标题（可选说明），右侧控件。 */
@Composable
private fun CellRow(
    label: String,
    description: String? = null,
    descriptionError: Boolean = false,
    tail: @Composable () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = label,
                color = colors.labelPrimary,
                fontSize = 14.sp,
                lineHeight = 22.sp,
            )
            val desc = description
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = desc,
                    color = if (descriptionError) colors.stateError else colors.labelTertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        tail()
    }
}

/** 标题在上、控件在下一行右对齐（官方的「位置信息」形态）。 */
@Composable
private fun CellBlock(label: String, tail: @Composable () -> Unit) {
    val colors = DsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = colors.labelPrimary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { tail() }
    }
}

/** 胶囊下拉选择器：浅灰底、高 36dp、下拉菜单带默认展开动画。 */
@Composable
private fun PillSelect(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    placeholder: String = "",
) {
    // 1:1 复刻官方 Select.tsx（触发器 36dp/18dp 圆角，菜单 4dp 内边距、12dp 圆角、选中打勾）
    DsSelect(
        value = value,
        options = options,
        onSelect = onSelect,
        placeholder = placeholder,
    )
}

private enum class PillStyle { OUTLINED, FILLED, DANGER }

/** 胶囊按钮：高 44dp；有按压水波纹。 */
@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.OUTLINED,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = DsTheme.colors
    val clickable = enabled && !loading
    val contentColor = when {
        style == PillStyle.DANGER -> if (enabled) colors.stateError else colors.labelTertiary
        style == PillStyle.FILLED -> if (enabled) Color.White else Color.White.copy(alpha = 0.6f)
        else -> if (enabled) colors.labelPrimary else colors.labelTertiary
    }
    val borderColor = when {
        style == PillStyle.DANGER -> colors.stateError.copy(alpha = if (enabled) 0.9f else 0.35f)
        style == PillStyle.FILLED -> Color.Transparent
        else -> colors.borderL2
    }
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(PillShape)
            .background(if (style == PillStyle.FILLED) colors.brandPrimary else Color.Transparent)
            .border(1.dp, borderColor, PillShape)
            .clickable(enabled = clickable, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            DsLoadingDots(color = contentColor)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = contentColor,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = text,
                    color = contentColor,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 胶囊输入框：浅灰底、无边框，内置占位符。 */
@Composable
private fun PillInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    corner: Dp = 22.dp,
    textSize: Int = 15,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = DsTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(corner))
            .background(colors.selector)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = colors.labelPrimary, fontSize = textSize.sp),
            cursorBrush = SolidColor(colors.brandText),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = colors.labelTertiary,
                            fontSize = textSize.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/* ------------------------------------------------------------------ */
/* 通用设置                                                           */
/* ------------------------------------------------------------------ */

@Composable
private fun GeneralContent(
    settings: SettingsState,
    onUpdate: ((SettingsState) -> SettingsState) -> Unit,
) {

    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(
            text = "主题",
            color = DsTheme.colors.labelPrimary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeCard(
                label = "浅色",
                icon = DsIcons.Sun,
                selected = settings.themeMode == ThemeMode.LIGHT,
                modifier = Modifier.weight(1f),
            ) { onUpdate { it.copy(themeMode = ThemeMode.LIGHT) } }
            ThemeCard(
                label = "深色",
                icon = DsIcons.Moon,
                selected = settings.themeMode == ThemeMode.DARK,
                modifier = Modifier.weight(1f),
            ) { onUpdate { it.copy(themeMode = ThemeMode.DARK) } }
        }
        Spacer(Modifier.height(12.dp))
        ThemeCard(
            label = "跟随系统",
            icon = DsIcons.System,
            selected = settings.themeMode == ThemeMode.SYSTEM,
            modifier = Modifier.fillMaxWidth(),
        ) { onUpdate { it.copy(themeMode = ThemeMode.SYSTEM) } }
        Spacer(Modifier.height(12.dp))
    }

    CellRow(
        label = "语言",
        tail = {
            PillSelect(
                value = settings.language.name,
                options = listOf(
                    AppLanguage.ZH.name to "简体中文",
                    AppLanguage.EN.name to "English",
                ),
                onSelect = { key ->
                    val lang = runCatching { AppLanguage.valueOf(key) }.getOrDefault(AppLanguage.ZH)
                    onUpdate { it.copy(language = lang) }
                },
            )
        },
    )
    CellDivider()

    CellRow(
        label = "朗读音色",
        tail = {
            PillSelect(
                value = settings.ttsVoice,
                options = listOf(
                    "stella" to "海星（女声）",
                    "tide" to "暗潮（男声）",
                ),
                onSelect = { key -> onUpdate { it.copy(ttsVoice = key) } },
            )
        },
    )
    CellDivider()

    // Full-context mode keeps the selected search capability using client tools.
    CellRow(
        label = "上下文模式",
        description = when (settings.longDocMode) {
            LongDocMode.AUTO -> "默认支持 1M，完整读取长文档；保留深度思考与联网搜索"
            LongDocMode.ALWAYS -> "优先使用 1M 长上下文，保留深度思考与联网搜索"
            LongDocMode.OFF -> "使用兼容模式，内置联网服务当前输入上限为 32K"
        },
        tail = {
            PillSelect(
                value = settings.longDocMode.name,
                options = listOf(
                    LongDocMode.AUTO.name to "1M（自动）",
                    LongDocMode.ALWAYS.name to "1M（优先）",
                    LongDocMode.OFF.name to "32K（兼容）",
                ),
                onSelect = { key ->
                    val mode = runCatching { LongDocMode.valueOf(key) }.getOrDefault(LongDocMode.AUTO)
                    onUpdate { it.copy(longDocMode = mode) }
                },
            )
        },
    )
    Text(
        text = "1M 为输入与输出的合计上限。长文档联网搜索、网页阅读按官方独立工具价格计费；使用标准 API 内容检查。",
        color = DsTheme.colors.labelTertiary,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    CellDivider()

    // 官方设置里的「字体大小」（默认档叫「标准」）：只影响消息正文的排印，不动按钮与导航。
    CellRow(
        label = "字体大小",
        tail = {
            PillSelect(
                value = settings.fontScale.toString(),
                options = listOf(
                    "0.9" to "小",
                    "1.0" to "标准",
                    "1.15" to "大",
                    "1.3" to "超大",
                ),
                onSelect = { key ->
                    val scale = key.toFloatOrNull() ?: 1f
                    onUpdate { it.copy(fontScale = scale) }
                },
            )
        },
    )
    CellDivider()

    CellBlock(
        label = "位置信息",
        tail = {
            PillSelect(
                value = settings.region,
                options = regionOptions,
                onSelect = { code -> onUpdate { it.copy(region = code) } },
                placeholder = "请选择国家或地区",
            )
        },
    )
    CellDivider()

    CellRow(
        label = "版本",
        tail = {
            Text(
                text = BuildConfig.VERSION_NAME,
                color = DsTheme.colors.labelTertiary,
                fontSize = 13.sp,
            )
        },
    )
}

/** 主题选择卡：图标在上、文字在下；高 88dp、圆角 16dp。 */
@Composable
private fun ThemeCard(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    val background by animateColorAsState(
        targetValue = if (selected) colors.selector else Color.Transparent,
        animationSpec = tween(200),
        label = "themeCardBackground",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) Color.Transparent else colors.borderL2,
        animationSpec = tween(200),
        label = "themeCardBorder",
    )
    Column(
        modifier = modifier
            .height(88.dp)
            .clip(CellShape)
            .background(background)
            .border(1.dp, borderColor, CellShape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.labelPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = colors.labelPrimary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            maxLines = 1,
        )
    }
}

/* ------------------------------------------------------------------ */
/* 数据管理                                                           */
/* ------------------------------------------------------------------ */

@Composable
private fun DataContent(
    ui: SettingsUiState,
    settings: SettingsState,
    onUpdate: ((SettingsState) -> SettingsState) -> Unit,
    onVerifyKey: (ProviderConfig, (Result<Unit>) -> Unit) -> Unit,
    onRequestDelete: () -> Unit,
    onExport: ((Result<java.io.File>) -> Unit) -> Unit,
    onImport: (android.net.Uri, (Result<Int>) -> Unit) -> Unit,
    onImportUrl: (String, (Result<Int>) -> Unit) -> Unit,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    val busy = ui.importingFile || ui.importingUrl || ui.exporting
    val provider = settings.activeProvider

    var providerListOpen by remember { mutableStateOf(false) }
    var addingProvider by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }

    // 切换供应商后，Key 输入框跟着换，避免把 A 的 Key 存到 B 上。
    LaunchedEffect(settings.activeProviderId) {
        ui.draft = provider.apiKey
        ui.editingKey = provider.apiKey.isBlank()
        ui.verifying = false
        ui.keyStatus = null
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        if (uri == null) {
            // 取消选择：恢复按钮
            ui.importingFile = false
            return@rememberLauncherForActivityResult
        }
        ui.importingFile = true
        ui.importStatus = null
        onImport(uri) { result ->
            ui.importingFile = false
            ui.importOk = result.isSuccess
            ui.importStatus = result.fold(
                onSuccess = { count -> "已导入 $count 个对话" },
                onFailure = { it.message ?: "导入失败" },
            )
        }
    }

    fun saveKey() {
        val trimmed = ui.draft.trim()
        if (trimmed.isEmpty()) {
            ui.keyOk = false
            ui.keyStatus = "请输入 API Key"
            return
        }
        onUpdate { state ->
            state.updateProvider(state.activeProviderId) { it.copy(apiKey = trimmed) }
        }
        ui.editingKey = false
        ui.keyOk = true
        ui.keyStatus = "已保存"
    }

    fun verifyKey() {
        val trimmed = ui.draft.trim()
        if (trimmed.isEmpty()) {
            ui.keyOk = false
            ui.keyStatus = "请输入 API Key"
            return
        }
        ui.verifying = true
        ui.keyStatus = null
        onVerifyKey(provider.copy(apiKey = trimmed)) { result ->
            ui.verifying = false
            ui.keyOk = result.isSuccess
            ui.keyStatus = result.fold(
                onSuccess = { "连接正常，地址与 Key 可用" },
                onFailure = { it.message ?: "校验失败" },
            )
        }
    }

    // ---- 供应商切换 ----
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(
            text = "供应商",
            color = colors.labelPrimary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(DsSize.gapS))
        ProviderSummaryCard(provider = provider, onClick = { providerListOpen = true })
        Spacer(Modifier.height(DsSize.gapS))
        Text(
            text = "每个供应商独立保存 Key 与模型；点上方卡片可切换官方或添加自定义接口。",
            color = colors.labelTertiary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }

    // ---- API Key ----
    Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp)) {
        Text(
            text = "API Key",
            color = colors.labelPrimary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${provider.label} · ${provider.effectiveModel}",
            color = colors.labelTertiary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(DsSize.gapS))
        if (provider.apiKey.isNotBlank() && !ui.editingKey) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(colors.selector)
                    .padding(start = 16.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "*******",
                    modifier = Modifier.weight(1f),
                    color = colors.labelCaption,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable {
                            ui.draft = provider.apiKey
                            ui.keyStatus = null
                            ui.editingKey = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DsIcons.Edit,
                        contentDescription = "修改 API Key",
                        modifier = Modifier.size(20.dp),
                        tint = colors.brandText,
                    )
                }
            }
        } else {
            PillInput(
                value = ui.draft,
                onValueChange = {
                    ui.draft = it.trim()
                    ui.keyStatus = null
                },
                placeholder = "请输入 API Key",
                height = 52.dp,
                corner = 26.dp,
                textSize = 16,
                keyboardType = KeyboardType.Password,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    text = if (ui.verifying) "测试中…" else "测试连接",
                    onClick = { verifyKey() },
                    enabled = !ui.verifying,
                    loading = ui.verifying,
                )
                Spacer(Modifier.weight(1f))
                PillButton(
                    text = "保存",
                    onClick = { saveKey() },
                    style = PillStyle.FILLED,
                    enabled = ui.draft.isNotBlank(),
                )
            }
            Spacer(Modifier.height(DsSize.gapS))
            Text(
                text = "保存后立即生效；测试连接只校验地址与 Key，不产生生成费用。",
                color = colors.labelTertiary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        ui.keyStatus?.let { message ->
            Spacer(Modifier.height(DsSize.gapS))
            Text(
                text = message,
                color = if (ui.keyOk) SuccessGreen else colors.stateError,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
        if (!provider.protocol.supportsDeepSeekExtensions) {
            Spacer(Modifier.height(DsSize.gapS))
            Text(
                text = "${provider.protocol.label} 通道仅支持纯文本对话：联网搜索、附件、语音与自动标题不可用。",
                color = colors.labelTertiary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }

    // ---- 导入历史对话 ----
    CellDivider()
    CellRow(
        label = "导入历史对话",
        description = ui.importStatus
            ?: "导入历史对话会覆盖全部本地对话和附件，支持 DeepSeek 导出的 ZIP 文件。",
        descriptionError = ui.importStatus != null && !ui.importOk,
        tail = {
            PillButton(
                text = "上传",
                onClick = {
                    ui.importingFile = true
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                enabled = !busy,
                loading = ui.importingFile,
            )
        },
    )

    // ---- 从链接导入（官方导出链接直接拉取） ----
    CellDivider()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = "从链接导入",
            color = colors.labelPrimary,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )
        Spacer(Modifier.height(DsSize.gapXs))
        Text(
            text = ui.linkStatus
                ?: "粘贴 DeepSeek 导出的下载链接，直接下载并导入对话。",
            color = if (ui.linkStatus != null && !ui.importOk) colors.stateError else colors.labelSecondary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PillInput(
                    value = ui.link,
                    onValueChange = {
                        ui.link = it.trim()
                        ui.linkError = null
                        ui.linkStatus = null
                    },
                    placeholder = "https://…",
                    height = 40.dp,
                    corner = 20.dp,
                    textSize = 14,
                    enabled = !busy,
                    keyboardType = KeyboardType.Uri,
                )
            }
            Spacer(Modifier.width(DsSize.gapS))
            PillButton(
                text = "导入",
                onClick = {
                    val link = ui.link.trim()
                    if (!link.startsWith("http://") && !link.startsWith("https://")) {
                        ui.linkError = "请填写 http 或 https 开头的下载链接"
                        ui.linkStatus = null
                        return@PillButton
                    }
                    ui.linkError = null
                    ui.linkStatus = null
                    ui.importingUrl = true
                    onImportUrl(link) { result ->
                        ui.importingUrl = false
                        ui.importOk = result.isSuccess
                        ui.linkStatus = result.fold(
                            onSuccess = { count -> "已导入 $count 个对话" },
                            onFailure = { it.message ?: "导入失败" },
                        )
                    }
                },
                enabled = !busy,
                loading = ui.importingUrl,
            )
        }
        ui.linkError?.let { message ->
            Spacer(Modifier.height(DsSize.gapXs))
            Text(
                text = message,
                color = colors.stateError,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }

    // ---- 导出所有历史对话 ----
    CellDivider()
    CellRow(
        label = "导出所有历史对话",
        description = ui.exportStatus
            ?: "包含所有历史对话及本地图片原件；其他附件仅保留文件信息，不包含 API Key。",
        descriptionError = ui.exportStatus != null && !ui.exportOk,
        tail = {
            PillButton(
                text = "下载",
                onClick = {
                    ui.exporting = true
                    ui.exportStatus = null
                    onExport { result ->
                        ui.exporting = false
                        ui.exportOk = result.isSuccess
                        result.fold(
                            onSuccess = { file ->
                                ui.exportStatus = "已生成 ${file.name}"
                                runCatching {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        context.packageName + ".fileprovider",
                                        file,
                                    )
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_SEND,
                                    ).apply {
                                        type = "application/zip"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        android.content.Intent.createChooser(intent, "保存或分享导出包"),
                                    )
                                }
                            },
                            onFailure = { ui.exportStatus = it.message ?: "导出失败" },
                        )
                    }
                },
                enabled = !busy,
                loading = ui.exporting,
            )
        },
    )

    // ---- 删除所有对话 ----
    CellDivider()
    CellRow(
        label = "删除所有对话",
        tail = {
            PillButton(
                text = "删除",
                onClick = onRequestDelete,
                style = PillStyle.DANGER,
                enabled = !busy,
            )
        },
    )

    // ---- 崩溃日志（自有增强：真机闪退时不用连电脑，直接看堆栈） ----
    CellDivider()
    CrashLogCell()

    // ---- 供应商弹窗 ----
    if (providerListOpen) {
        ProviderListDialog(
            settings = settings,
            onSelect = { id -> onUpdate { it.switchProvider(id) } },
            onEdit = { target ->
                editingProvider = target
                providerListOpen = false
            },
            onAdd = {
                addingProvider = true
                providerListOpen = false
            },
            onDismiss = { providerListOpen = false },
        )
    }
    if (addingProvider) {
        ProviderEditorDialog(
            existing = null,
            onSave = { saved ->
                onUpdate { state ->
                    state.copy(
                        providers = state.providers + saved,
                        activeProviderId = saved.id,
                    )
                }
                addingProvider = false
            },
            onDelete = null,
            onDismiss = { addingProvider = false },
        )
    }
    editingProvider?.let { target ->
        ProviderEditorDialog(
            existing = target,
            onSave = { saved ->
                onUpdate { state -> state.updateProvider(saved.id) { saved } }
                editingProvider = null
            },
            onDelete = {
                onUpdate { state ->
                    val remaining = state.providers.filterNot { it.id == target.id }
                    val fallback = remaining.firstOrNull() ?: SettingsState.OFFICIAL_PROVIDER
                    state.copy(
                        providers = remaining.ifEmpty { listOf(fallback) },
                        activeProviderId = if (state.activeProviderId == target.id) {
                            fallback.id
                        } else {
                            state.activeProviderId
                        },
                    )
                }
                editingProvider = null
            },
            onDismiss = { editingProvider = null },
        )
    }
}

/** 崩溃日志：真机闪退时不用连电脑，直接在这里看堆栈。 */
@Composable
private fun CrashLogCell() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val colors = DsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val crashes = remember { com.shenyu.chat.ShenyuApp.recentCrashes(context.filesDir) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "crashArrow",
    )

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clickable(enabled = crashes.isNotEmpty()) { expanded = !expanded }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = "崩溃日志",
                    color = colors.labelPrimary,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (crashes.isEmpty()) {
                        "暂时没有崩溃记录"
                    } else {
                        "最近 ${crashes.size} 条记录 · ${if (expanded) "收起" else "查看"}"
                    },
                    color = colors.labelTertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
            if (crashes.isNotEmpty()) {
                Icon(
                    imageVector = DsIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                    tint = colors.labelSecondary,
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && crashes.isNotEmpty(),
            enter = fadeIn(tween(160)) + expandVertically(tween(200)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        text = "复制",
                        color = colors.brandText,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(PillShape)
                            .clickable {
                                clipboard.setText(
                                    AnnotatedString(crashes.joinToString("\n\n") { it.second }),
                                )
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(DsSize.gapS))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.markdownCodeBlock)
                        .padding(DsSize.gapM)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = crashes.firstOrNull()?.second.orEmpty(),
                        color = colors.labelSecondary,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* 供应商                                                             */
/* ------------------------------------------------------------------ */

/** 设置页顶部的当前供应商卡片：显示名称与协议/主机名，点开切换列表。 */
@Composable
private fun ProviderSummaryCard(provider: ProviderConfig, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CellShape)
            .background(colors.selector)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = provider.label,
                color = colors.labelPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = ProviderEndpoints.shortLabel(provider),
                color = colors.labelTertiary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "切换",
            color = colors.brandText,
            fontSize = 13.sp,
            maxLines = 1,
        )
        Icon(
            imageVector = DsIcons.ChevronDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.labelSecondary,
        )
    }
}

@Composable
private fun ProviderListDialog(
    settings: SettingsState,
    onSelect: (String) -> Unit,
    onEdit: (ProviderConfig) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .padding(horizontal = 20.dp, vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.bgBase)
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "供应商",
                        modifier = Modifier.weight(1f),
                        color = colors.labelPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = DsIcons.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(20.dp),
                            tint = colors.labelPrimary,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "选择用于对话的供应商；每个供应商的 Key 与模型独立保存。",
                    color = colors.labelTertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    settings.providers.forEach { provider ->
                        ProviderListRow(
                            provider = provider,
                            active = provider.id == settings.activeProviderId,
                            onSelect = {
                                onSelect(provider.id)
                                onDismiss()
                            },
                            onEdit = if (provider.builtin) null else ({ onEdit(provider) }),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                PillButton(
                    text = "添加自定义供应商",
                    onClick = onAdd,
                    icon = DsIcons.Add,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PillButton(
                    text = "完成",
                    onClick = onDismiss,
                    style = PillStyle.FILLED,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ProviderListRow(
    provider: ProviderConfig,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    val colors = DsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) colors.interactiveHover else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .border(1.5.dp, if (active) colors.brandPrimary else colors.borderL2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(colors.brandPrimary),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = provider.label,
                color = colors.labelPrimary,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = ProviderEndpoints.shortLabel(provider),
                color = colors.labelTertiary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onEdit != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onEdit),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DsIcons.Edit,
                    contentDescription = "编辑供应商",
                    modifier = Modifier.size(18.dp),
                    tint = colors.brandText,
                )
            }
        }
    }
}

/**
 * 添加 / 编辑自定义供应商。内置官方项不允许编辑，所以这里只处理自定义项。
 * [existing] 为 null 表示新建。
 */
@Composable
private fun ProviderEditorDialog(
    existing: ProviderConfig?,
    onSave: (ProviderConfig) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    var name by remember { mutableStateOf(existing?.name ?: "自定义供应商") }
    var protocol by remember { mutableStateOf(existing?.protocol ?: ProviderProtocol.DEEPSEEK) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var model by remember { mutableStateOf(existing?.model.orEmpty()) }
    var apiKey by remember { mutableStateOf(existing?.apiKey.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        val urlError = ProviderEndpoints.validationError(baseUrl)
        if (urlError != null) {
            error = urlError
            return
        }
        if (protocol != ProviderProtocol.DEEPSEEK && model.isBlank()) {
            error = if (protocol == ProviderProtocol.ANTHROPIC) {
                "Anthropic 兼容供应商需要填写模型名（如 claude-3-5-sonnet-latest）"
            } else {
                "OpenAI 兼容供应商需要填写模型名（如 gpt-4o-mini、deepseek-chat）"
            }
            return
        }
        val saved = (existing ?: ProviderConfig(
            id = "provider-" + java.util.UUID.randomUUID().toString().take(8),
            name = "",
            baseUrl = "",
        )).copy(
            name = name.trim().ifBlank { "自定义供应商" },
            protocol = protocol,
            baseUrl = ProviderEndpoints.normalizeBase(baseUrl),
            model = model.trim(),
            apiKey = apiKey.trim(),
        )
        onSave(saved)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .heightIn(max = 680.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.bgBase)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (existing == null) "添加自定义供应商" else "编辑供应商",
                        modifier = Modifier.weight(1f),
                        color = colors.labelPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = DsIcons.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(20.dp),
                            tint = colors.labelPrimary,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.fillMaxWidth(),
                ) {
                    Text("名称", color = colors.labelPrimary, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    PillInput(
                        value = name,
                        onValueChange = { name = it; error = null },
                        placeholder = "自定义供应商",
                        height = 44.dp,
                        corner = 22.dp,
                        textSize = 14,
                    )
                    Spacer(Modifier.height(14.dp))

                    Text("协议", color = colors.labelPrimary, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    PillSelect(
                        value = protocol.name,
                        options = ProviderProtocol.entries.map { it.name to it.label },
                        onSelect = { key ->
                            protocol = runCatching { ProviderProtocol.valueOf(key) }
                                .getOrDefault(ProviderProtocol.DEEPSEEK)
                            error = null
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when (protocol) {
                            ProviderProtocol.DEEPSEEK ->
                                "完整功能：联网搜索、附件、语音、标题生成；地址一般填 DeepSeek 站点根路径。"

                            ProviderProtocol.ANTHROPIC ->
                                "标准 Anthropic Messages：保留思考与纯文本对话，不发送 DeepSeek 私有扩展。"

                            ProviderProtocol.OPENAI ->
                                "标准 OpenAI Chat Completions：仅纯文本对话，必须填写模型名。"
                        },
                        color = colors.labelTertiary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(14.dp))

                    Text("接口地址", color = colors.labelPrimary, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    PillInput(
                        value = baseUrl,
                        onValueChange = { baseUrl = it.trim(); error = null },
                        placeholder = when (protocol) {
                            ProviderProtocol.DEEPSEEK -> "https://api.deepseek.com"
                            ProviderProtocol.ANTHROPIC -> "https://api.anthropic.com/v1"
                            ProviderProtocol.OPENAI -> "https://api.openai.com/v1"
                        },
                        height = 44.dp,
                        corner = 22.dp,
                        textSize = 14,
                        keyboardType = KeyboardType.Uri,
                    )
                    if (baseUrl.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        val preview = runCatching {
                            ProviderEndpoints.previewUrl(
                                ProviderConfig(
                                    id = "preview",
                                    name = "",
                                    baseUrl = baseUrl,
                                    protocol = protocol,
                                ),
                            )
                        }.getOrNull()
                        if (preview != null) {
                            Text(
                                text = "实际请求：$preview",
                                color = if (error == null) colors.labelTertiary else colors.stateError,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    Text("模型", color = colors.labelPrimary, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    PillInput(
                        value = model,
                        onValueChange = { model = it.trim(); error = null },
                        placeholder = if (protocol == ProviderProtocol.OPENAI) {
                            "必填，如 deepseek-chat 或 gpt-4o-mini"
                        } else {
                            "留空使用 deepseek-flash"
                        },
                        height = 44.dp,
                        corner = 22.dp,
                        textSize = 14,
                    )
                    Spacer(Modifier.height(14.dp))

                    Text("API Key", color = colors.labelPrimary, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    PillInput(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim(); error = null },
                        placeholder = "可留空，之后在设置页填写",
                        height = 44.dp,
                        corner = 22.dp,
                        textSize = 14,
                        keyboardType = KeyboardType.Password,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Key 只保存在本机；每个供应商各自独立。",
                        color = colors.labelTertiary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )

                    error?.let { message ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = message,
                            color = colors.stateError,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }

                    if (confirmDelete && onDelete != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "删除后该供应商的 Key 与模型会一起移除，确定删除？",
                            color = colors.stateError,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            PillButton(
                                text = "取消删除",
                                onClick = { confirmDelete = false },
                            )
                            Spacer(Modifier.width(8.dp))
                            PillButton(
                                text = "确认删除",
                                onClick = onDelete,
                                style = PillStyle.DANGER,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onDelete != null) {
                        PillButton(
                            text = "删除",
                            onClick = { confirmDelete = true },
                            style = PillStyle.DANGER,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    PillButton(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    PillButton(
                        text = "保存",
                        onClick = { save() },
                        style = PillStyle.FILLED,
                    )
                }
            }
        }
    }
}
