package com.elyric.lcwhale.foundation.theme

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.BaseObject
import com.tencent.kuikly.core.reactive.handler.observable

enum class ThemeMode(val value: String, val label: String) {
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    SYSTEM("system", "跟随系统");

    companion object {
        fun from(value: String): ThemeMode = values().firstOrNull { it.value == value } ?: SYSTEM
    }
}

data class ThemePalette(
    val page: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val text: Color,
    val textMuted: Color,
    val border: Color,
    val accent: Color,
    val userBubble: Color,
    val assistantBubble: Color,
    val input: Color,
    val error: Color,
    val warning: Color,
    val success: Color,
) {
    companion object {
        val Light = ThemePalette(
            Color(0xFFF8F9FB), Color.WHITE, Color(0xFFF1F3F5), Color(0xFF202124), Color(0xFF73777D),
            Color(0xFFE2E5E9), Color(0xFF2563EB), Color(0xFFE8F1FF), Color.WHITE, Color(0xFFF3F4F6),
            Color(0xFFDC2626), Color(0xFFD97706), Color(0xFF16A34A)
        )
        val Dark = ThemePalette(
            Color(0xFF15171A), Color(0xFF202327), Color(0xFF292D33), Color(0xFFF1F3F5), Color(0xFFAAB1BA),
            Color(0xFF3A414A), Color(0xFF78A9FF), Color(0xFF17345E), Color(0xFF252A30), Color(0xFF292D33),
            Color(0xFFFF8A8A), Color(0xFFF2B56B), Color(0xFF65D391)
        )
    }
}

const val THEME_MODE_KEY = "lcwhale_theme_mode"

object AppThemeState : BaseObject() {
    var mode: ThemeMode by observable(ThemeMode.SYSTEM)
    var systemDark: Boolean by observable(false)
    var initialized = false

    val palette: ThemePalette
        get() = if (mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && systemDark) ThemePalette.Dark else ThemePalette.Light
}
