package com.elyric.lcwhale.pager.home

import com.elyric.lcwhale.RouterNavBar
import com.elyric.lcwhale.dsh.ConnectState
import com.elyric.lcwhale.foundation.constant.NetConstant
import com.elyric.lcwhale.foundation.view.WhalePager
import com.elyric.lcwhale.foundation.theme.AppThemeState
import com.elyric.lcwhale.foundation.theme.ThemeMode
import com.elyric.lcwhale.pager.workspace.WorkspaceViewModel
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button

/**
 * 首页：负责建立 dsh 连接并预加载工作区、会话数据；具体列表在对应页面展示。
 */
@Page("home_pager")
internal class HomePager : WhalePager() {

    private var urlInput by observable(NetConstant.WSConst.DEFAULT_URL)
    private var statusText by observable("未连接")
    private var notice by observable("")
    private lateinit var urlRef: ViewRef<InputView>

    override fun created() {
        super.created()
        bindEngineHooks()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        bindEngineHooks()
        if (engine.state.connectState == ConnectState.CONNECTED) {
            WorkspaceViewModel.refresh(engine)
        }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        val saved = acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
            .getItem(NetConstant.WSConst.PREF_KEY_LAST_URL)
        if (saved.isNotEmpty()) {
            urlInput = saved
            urlRef.view?.setText(saved)
        }
        statusText = stateLabel(engine.state.connectState)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                backgroundColor(ctx.palette.page)
            }

            RouterNavBar {
                attr {
                    title = "dsh 连接"
                    backDisable = true
                }
            }

            View {
                attr {
                    flex(1f)
                    allCenter()
                }
                View {
                    attr {
                        width(pagerData.pageViewWidth * 0.86f)
                        margin(all = 20f)
                        padding(all = 22f)
                        borderRadius(12f)
                        backgroundColor(ctx.palette.surface)
                    }
                    Text {
                        attr {
                            text("连接到 DSH")
                            fontSize(22f)
                            fontWeightBold()
                            color(ctx.palette.text)
                            marginBottom(8f)
                        }
                    }
                    Text {
                        attr {
                            text("输入 WebSocket 地址以开始使用")
                            fontSize(13f)
                            color(ctx.palette.textMuted)
                            marginBottom(18f)
                        }
                    }
                    View {
                        attr { flexDirectionRow() }
                        Input {
                            ref { ctx.urlRef = it }
                            attr {
                                flex(1f)
                                height(42f)
                                fontSize(14f)
                                color(ctx.palette.text)
                                placeholder("ws://host:port")
                                placeholderColor(ctx.palette.textMuted)
                            }
                            event { textDidChange { ctx.urlInput = it.text } }
                        }
                        Button {
                            attr {
                                size(78f, 42f)
                                borderRadius(6f)
                                marginLeft(8f)
                                backgroundColor(if (ctx.isConnected()) ctx.palette.surfaceMuted else ctx.palette.accent)
                                titleAttr {
                                    text(if (ctx.isConnected()) "断开" else "连接")
                                    fontSize(14f)
                                    color(Color.WHITE)
                                }
                            }
                            event { click { ctx.toggleConnect() } }
                        }
                    }
                    Text {
                        attr {
                            text(ctx.statusText)
                            fontSize(13f)
                            marginTop(12f)
                            color(if (ctx.isConnected()) ctx.palette.success else ctx.palette.textMuted)
                        }
                    }
                    View {
                        attr {
                            flexDirectionRow()
                            allCenter()
                            marginTop(20f)
                        }
                        Button {
                            attr {
                                size(108f, 38f)
                                borderRadius(6f)
                                backgroundColor(ctx.palette.success)
                                titleAttr { text("新建会话"); fontSize(14f); color(Color.WHITE) }
                            }
                            event { click { ctx.openChat() } }
                        }
                        Button {
                            attr {
                                size(108f, 38f)
                                borderRadius(6f)
                                marginLeft(8f)
                                backgroundColor(ctx.palette.accent)
                                titleAttr { text("进入工作区"); fontSize(14f); color(Color.WHITE) }
                            }
                            event { click { ctx.openWorkspacePage() } }
                        }
                    }
                    vif({ ctx.notice.isNotEmpty() }) {
                        Text {
                            attr {
                                text(ctx.notice)
                                fontSize(12f)
                                marginTop(14f)
                                color(ctx.palette.warning)
                            }
                        }
                    }
                    View {
                        attr { flexDirectionRow(); allCenter(); marginTop(18f) }
                        Text { attr { text("主题"); fontSize(13f); color(ctx.palette.textMuted); marginRight(8f) } }
                        ThemeButton(ThemeMode.LIGHT, ctx.palette, AppThemeState.mode == ThemeMode.LIGHT) { ctx.setThemeMode(ThemeMode.LIGHT) }
                        ThemeButton(ThemeMode.DARK, ctx.palette, AppThemeState.mode == ThemeMode.DARK) { ctx.setThemeMode(ThemeMode.DARK) }
                        ThemeButton(ThemeMode.SYSTEM, ctx.palette, AppThemeState.mode == ThemeMode.SYSTEM) { ctx.setThemeMode(ThemeMode.SYSTEM) }
                    }
                }
            }
        }
    }

    private fun bindEngineHooks() {
        engine.onConnectionChanged = { state ->
            statusText = stateLabel(state)
            if (state == ConnectState.CONNECTED) {
                refreshAll()
            }
        }
        engine.onNotice = { message ->
            notice = message
        }
    }

    private fun isConnected(): Boolean {
        val state = engine.state.connectState
        return state == ConnectState.CONNECTED || state == ConnectState.CONNECTING
    }

    private fun toggleConnect() {
        if (isConnected()) {
            engine.close()
            WorkspaceViewModel.clear()
        } else {
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .setItem(NetConstant.WSConst.PREF_KEY_LAST_URL, urlInput)
            engine.connect(urlInput)
        }
    }

    private fun refreshAll() {
        WorkspaceViewModel.refresh(engine)
    }

    private fun openChat() {
        openChatPage("", "", "")
    }

    private fun openChatPage(sessionId: String, title: String, cwd: String) {
        val pageData = JSONObject()
        pageData.put("sessionId", sessionId)
        pageData.put("title", title)
        pageData.put("cwd", cwd)
        pageData.put("url", urlInput)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("dsh_chat", pageData)
    }

    private fun openWorkspacePage() {
        val pageData = JSONObject()
        pageData.put("url", urlInput)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("dsh_workspace", pageData)
    }

    private fun stateLabel(state: ConnectState): String = when (state) {
        ConnectState.IDLE -> "未连接"
        ConnectState.CONNECTING -> "连接中…"
        ConnectState.CONNECTED -> "已连接"
        ConnectState.DISCONNECTED -> "已断开"
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.ThemeButton(
    mode: ThemeMode,
    palette: com.elyric.lcwhale.foundation.theme.ThemePalette,
    selected: Boolean,
    onClick: () -> Unit
) {
    Button {
        attr {
            size(72f, 30f)
            borderRadius(15f)
            marginLeft(4f)
            backgroundColor(if (selected) palette.accent else palette.surfaceMuted)
            titleAttr {
                text(mode.label)
                fontSize(12f)
                color(if (selected) Color.WHITE else palette.text)
            }
        }
        event { click { onClick() } }
    }
}
