package com.elyric.lcwhale.foundation.view

import com.elyric.lcwhale.base.BasePager
import com.elyric.lcwhale.dsh.DSHManager
import com.elyric.lcwhale.dsh.DSHEngine
import com.elyric.lcwhale.foundation.module.DSHWebSocketModule
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.elyric.lcwhale.foundation.theme.*

/**
 * 持有链接的Pager，业务Pager全部继承这个类
 *
 * 连接与协议状态在全局引擎([DSHManager])里跨页面存活;
 * 本类只负责把当前页面的传输模块 attach 给引擎(原生侧同 URL 幂等,只切事件回调不重连)。
 */
internal abstract class WhalePager : BasePager() {

    private var themeRevision by observable(0)

    protected val palette: ThemePalette
        get() {
            themeRevision
            return AppThemeState.palette
        }

    protected fun currentThemeMode(): ThemeMode = ThemeMode.from(
        acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME).getItem(THEME_MODE_KEY)
    )

    protected fun setThemeMode(mode: ThemeMode) {
        AppThemeState.updateMode(mode)
        themeRevision += 1
        acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME).setItem(THEME_MODE_KEY, mode.value)
    }

    private val webSocketModule = DSHWebSocketModule()

    val engine: DSHEngine
        get() = DSHManager.engine

    override fun createExternalModules(): Map<String, Module>? {
        val modules = super.createExternalModules()?.toMutableMap() ?: hashMapOf<String, Module>()
        modules[DSHWebSocketModule.MODULE_NAME] = webSocketModule
        return modules
    }

    override fun created() {
        super.created()
        AppThemeState.updateSystemDark(super.isNightMode())
        if (!AppThemeState.initialized) {
            AppThemeState.updateMode(currentThemeMode())
            AppThemeState.initialized = true
        }
        engine.attach(webSocketModule)
    }

    override fun themeDidChanged(data: com.tencent.kuikly.core.nvi.serialization.json.JSONObject) {
        super.themeDidChanged(data)
        AppThemeState.updateSystemDark(data.optBoolean(IS_NIGHT_MODE_KEY))
        themeRevision += 1
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        // 页面重新可见时收回事件通道(可能被其它页面占用)
        engine.attach(webSocketModule)
    }

    override fun pageWillDestroy() {
        super.pageWillDestroy()
        // 只让出事件通道,连接保持;连接的关闭由连接页的断开按钮显式控制
        engine.detach(webSocketModule)
    }

    override fun body(): ViewBuilder {
        return {}
    }
}
