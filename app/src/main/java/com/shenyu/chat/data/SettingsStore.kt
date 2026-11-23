package com.shenyu.chat.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppLanguage(val tag: String) {
    ZH("zh-CN"),
    EN("en-US"),
}

/**
 * 长文档模式。服务端对「标题 / 增强风控 / 联网工具」这条增强通道把输入卡在 32,768 Token，
 * 关闭这些参数则按官方 1M 上下文承载。
 */
enum class LongDocMode {
    /** 自动：文档、长对话使用1M标准模型与客户端工具；短对话保持内置联网体验。 */
    AUTO,

    /** 始终使用1M标准模型与客户端工具，保留联网与思考能力。 */
    ALWAYS,

    /** 关闭：保持原行为，超长会直接报错。 */
    OFF,
}

data class SettingsState(
    /** 可切换的供应商列表；至少包含一个官方项和一个可自定义项。 */
    val providers: List<ProviderConfig> = listOf(OFFICIAL_PROVIDER),
    val activeProviderId: String = OFFICIAL_PROVIDER.id,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.ZH,
    val thinkingEnabled: Boolean = false,
    val searchEnabled: Boolean = false,
    val fontScale: Float = 1f,
    /** 朗读音色：stella=海星（女声），tide=暗潮（男声） */
    val ttsVoice: String = "stella",
    /** 回答生成完自动朗读 */
    val autoSpeak: Boolean = false,
    /** 位置信息（用于请求里的「最新信息提醒」，让模型知道当前地点）：空/CN/HK/TW/OTHER */
    val region: String = "",
    /** 长文档模式：增强通道有 32K 输入上限，超长时按此设置改用 1M 长上下文通道 */
    val longDocMode: LongDocMode = LongDocMode.AUTO,
) {
    val activeProvider: ProviderConfig
        get() = providers.firstOrNull { it.id == activeProviderId }
            ?: providers.firstOrNull()
            ?: OFFICIAL_PROVIDER

    /** 当前供应商的 Key / 地址 / 模型：网络层与语音层都只读这三个派生值。 */
    val apiKey: String get() = activeProvider.apiKey
    val apiOrigin: String get() = activeProvider.baseUrl
    val model: String get() = activeProvider.effectiveModel

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    /** 修改任意供应商的字段（Key、模型、名称、地址等）。 */
    fun updateProvider(id: String, transform: (ProviderConfig) -> ProviderConfig): SettingsState =
        copy(providers = providers.map { if (it.id == id) transform(it) else it })

    fun switchProvider(id: String): SettingsState =
        if (providers.any { it.id == id }) copy(activeProviderId = id) else this

    companion object {
        const val DEFAULT_ORIGIN = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-flash"

        const val OFFICIAL_PROVIDER_ID = "deepseek-official"

        val OFFICIAL_PROVIDER = ProviderConfig(
            id = OFFICIAL_PROVIDER_ID,
            name = "DeepSeek 官方",
            baseUrl = DEFAULT_ORIGIN,
            model = DEFAULT_MODEL,
            protocol = ProviderProtocol.DEEPSEEK,
            builtin = true,
        )
    }
}

/** 旧版本只存了单个 api_key / api_origin，首次启动新版本时迁移成供应商列表。 */
internal fun migrateLegacyProvider(origin: String?, apiKey: String?): Pair<List<ProviderConfig>, String> {
    val legacyOrigin = origin?.trim()?.trimEnd('/').orEmpty()
    val legacyKey = apiKey.orEmpty()
    val official = SettingsState.OFFICIAL_PROVIDER.copy(apiKey = if (legacyOrigin.isBlank() || legacyOrigin == SettingsState.DEFAULT_ORIGIN) legacyKey else "")
    if (legacyOrigin.isBlank() || legacyOrigin == SettingsState.DEFAULT_ORIGIN) {
        return listOf(official) to official.id
    }
    val custom = ProviderConfig(
        id = "legacy-custom",
        name = "自定义供应商",
        baseUrl = legacyOrigin,
        apiKey = legacyKey,
        protocol = ProviderProtocol.DEEPSEEK,
    )
    return listOf(official, custom) to custom.id
}

/**
 * 用户设置。放在 SharedPreferences 里，读一次进内存，改动同时写盘并推给 UI。
 * API Key 只存本机，不参与任何导出。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shenyu_settings", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val current: SettingsState get() = _state.value

    private fun load(): SettingsState {
        val stored = prefs.getString(KEY_PROVIDERS, null)
        val (providers, activeId) = if (!stored.isNullOrBlank()) {
            runCatching {
                val list = json.decodeFromString<List<ProviderConfig>>(stored)
                if (list.isEmpty()) null else {
                    val withOfficial = if (list.any { it.id == SettingsState.OFFICIAL_PROVIDER_ID }) {
                        list
                    } else {
                        listOf(SettingsState.OFFICIAL_PROVIDER) + list
                    }
                    val preferred = prefs.getString(KEY_ACTIVE_PROVIDER, null)
                    val active = preferred?.takeIf { id -> withOfficial.any { it.id == id } }
                        ?: withOfficial.first().id
                    withOfficial to active
                }
            }.getOrNull()
        } else {
            null
        } ?: migrateLegacyProvider(
            origin = prefs.getString(KEY_ORIGIN, null),
            apiKey = prefs.getString(KEY_API_KEY, null),
        )

        return SettingsState(
            providers = providers,
            activeProviderId = activeId,
            themeMode = runCatching {
                ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
            }.getOrDefault(ThemeMode.SYSTEM),
            language = runCatching {
                AppLanguage.valueOf(prefs.getString(KEY_LANG, AppLanguage.ZH.name)!!)
            }.getOrDefault(AppLanguage.ZH),
            thinkingEnabled = prefs.getBoolean(KEY_THINKING, false),
            searchEnabled = prefs.getBoolean(KEY_SEARCH, false),
            fontScale = prefs.getFloat(KEY_FONT_SCALE, 1f).coerceIn(0.8f, 1.4f),
            ttsVoice = prefs.getString(KEY_TTS_VOICE, "stella") ?: "stella",
            autoSpeak = prefs.getBoolean(KEY_AUTO_SPEAK, false),
            region = prefs.getString(KEY_REGION, "") ?: "",
            longDocMode = runCatching {
                LongDocMode.valueOf(prefs.getString(KEY_LONG_DOC, LongDocMode.AUTO.name)!!)
            }.getOrDefault(LongDocMode.AUTO),
        )
    }

    fun update(transform: (SettingsState) -> SettingsState) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit()
            .putString(KEY_PROVIDERS, json.encodeToString(next.providers))
            .putString(KEY_ACTIVE_PROVIDER, next.activeProviderId)
            // 同时写旧字段：降级回旧版本时仍能直接用当前供应商。
            .putString(KEY_API_KEY, next.apiKey)
            .putString(KEY_ORIGIN, next.apiOrigin)
            .putString(KEY_THEME, next.themeMode.name)
            .putString(KEY_LANG, next.language.name)
            .putBoolean(KEY_THINKING, next.thinkingEnabled)
            .putBoolean(KEY_SEARCH, next.searchEnabled)
            .putFloat(KEY_FONT_SCALE, next.fontScale)
            .putString(KEY_TTS_VOICE, next.ttsVoice)
            .putBoolean(KEY_AUTO_SPEAK, next.autoSpeak)
            .putString(KEY_REGION, next.region)
            .putString(KEY_LONG_DOC, next.longDocMode.name)
            .apply()
    }

    private companion object {
        const val KEY_TTS_VOICE = "tts_voice"
        const val KEY_AUTO_SPEAK = "auto_speak"
        const val KEY_REGION = "region"
        const val KEY_LONG_DOC = "long_doc_mode"
        const val KEY_API_KEY = "api_key"
        const val KEY_ORIGIN = "api_origin"
        const val KEY_PROVIDERS = "providers"
        const val KEY_ACTIVE_PROVIDER = "active_provider"
        const val KEY_THEME = "theme_mode"
        const val KEY_LANG = "language"
        const val KEY_THINKING = "thinking"
        const val KEY_SEARCH = "search"
        const val KEY_FONT_SCALE = "font_scale"
    }
}
