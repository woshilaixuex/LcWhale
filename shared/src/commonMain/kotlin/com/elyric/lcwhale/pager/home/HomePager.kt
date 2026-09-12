package com.elyric.lcwhale.pager.home

import com.elyric.lcwhale.RouterNavBar
import com.elyric.lcwhale.dsh.ConnectState
import com.elyric.lcwhale.dsh.SessionSummaryUi
import com.elyric.lcwhale.dsh.WorkspaceUi
import com.elyric.lcwhale.foundation.constant.NetConstant
import com.elyric.lcwhale.foundation.view.WhalePager
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button

/**
 * 连接页:管理到 dsh 的连接,展示连接信息与已有会话列表。
 */
@Page("home_pager")
internal class HomePager : WhalePager() {

    private var urlInput by observable(NetConstant.WSConst.DEFAULT_URL)
    private var statusText by observable("未连接")
    private var notice by observable("")
    private var sessionList: ObservableList<SessionSummaryUi> by observableList()
    private var workspaceList: ObservableList<WorkspaceUi> by observableList()

    private lateinit var urlRef: ViewRef<InputView>

    override fun created() {
        super.created()
        bindEngineHooks()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        bindEngineHooks()
        refreshSessions()
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
            RouterNavBar {
                attr {
                    title = "dsh 连接"
                    backDisable = false
                }
            }

            // 连接栏
            View {
                attr {
                    padding(all = 10f)
                    flexDirectionRow()
                }
                Input {
                    ref { ctx.urlRef = it }
                    attr {
                        flex(1f)
                        height(38f)
                        fontSize(14f)
                        color(Color(0xFF333333))
                        placeholder("ws://host:port")
                        placeholderColor(Color(0xFFAAAAAA))
                    }
                    event {
                        textDidChange { ctx.urlInput = it.text }
                    }
                }
                Button {
                    attr {
                        size(72f, 38f)
                        borderRadius(6f)
                        marginLeft(8f)
                        backgroundColor(
                            if (ctx.isConnected()) Color(0xFF9E9E9E) else Color(0xFF2196F3)
                        )
                        titleAttr {
                            text(if (ctx.isConnected()) "断开" else "连接")
                            fontSize(14f)
                            color(Color.WHITE)
                        }
                    }
                    event {
                        click { ctx.toggleConnect() }
                    }
                }
            }

            Text {
                attr {
                    text(ctx.statusText)
                    fontSize(13f)
                    marginLeft(10f)
                    color(if (ctx.isConnected()) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                }
            }

            // 操作栏
            View {
                attr {
                    padding(all = 10f)
                    flexDirectionRow()
                }
                Button {
                    attr {
                        size(110f, 38f)
                        borderRadius(6f)
                        backgroundColor(Color(0xFF4CAF50))
                        titleAttr {
                            text("新建会话")
                            fontSize(14f)
                            color(Color.WHITE)
                        }
                    }
                    event {
                        click { ctx.openChat(null) }
                    }
                }
                Button {
                    attr {
                        size(80f, 38f)
                        borderRadius(6f)
                        marginLeft(8f)
                        backgroundColor(Color(0xFF2196F3))
                        titleAttr {
                            text("刷新")
                            fontSize(14f)
                            color(Color.WHITE)
                        }
                    }
                    event {
                        click { ctx.refreshAll() }
                    }
                }
                Button {
                    attr {
                        size(110f, 38f)
                        borderRadius(6f)
                        marginLeft(8f)
                        backgroundColor(Color(0xFF795548))
                        titleAttr {
                            text("进入工作区")
                            fontSize(14f)
                            color(Color.WHITE)
                        }
                    }
                    event {
                        click { ctx.openWorkspacePage() }
                    }
                }
            }

            vif({ ctx.notice.isNotEmpty() }) {
                Text {
                    attr {
                        text(ctx.notice)
                        fontSize(12f)
                        marginLeft(10f)
                        color(Color(0xFFFF9800))
                    }
                }
            }

            Text {
                attr {
                    text("工作区")
                    fontSize(13f)
                    fontWeightBold()
                    marginLeft(10f)
                    marginTop(6f)
                    color(Color(0xFF666666))
                }
            }

            Scroller {
                attr {
                    height(140f)
                    padding(all = 10f)
                }
                vfor({ ctx.workspaceList }) { ws ->
                    WorkspaceRow(ws) { ctx.openChatInWorkspace(ws) }
                }
            }

            Text {
                attr {
                    text("会话列表")
                    fontSize(13f)
                    fontWeightBold()
                    marginLeft(10f)
                    marginTop(6f)
                    color(Color(0xFF666666))
                }
            }

            Scroller {
                attr {
                    flex(1f)
                    padding(all = 10f)
                }
                vfor({ ctx.sessionList }) { summary ->
                    SessionRow(summary) { ctx.openChat(summary) }
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
            sessionList.clear()
            workspaceList.clear()
        } else {
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .setItem(NetConstant.WSConst.PREF_KEY_LAST_URL, urlInput)
            engine.connect(urlInput)
        }
    }

    private fun refreshAll() {
        refreshSessions()
        refreshWorkspaces()
    }

    private fun refreshSessions() {
        if (engine.state.connectState != ConnectState.CONNECTED) return
        engine.listSessions { list ->
            sessionList.clear()
            sessionList.addAll(list)
        }
    }

    private fun refreshWorkspaces() {
        if (engine.state.connectState != ConnectState.CONNECTED) return
        engine.listWorkspaces { list ->
            workspaceList.clear()
            workspaceList.addAll(list)
        }
    }

    private fun openChat(summary: SessionSummaryUi?) {
        openChatPage(summary?.sessionId ?: "", summary?.title ?: "", summary?.cwd ?: "")
    }

    private fun openChatInWorkspace(workspace: WorkspaceUi) {
        // 在工作区目录下新建会话
        openChatPage("", workspace.title, workspace.path)
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

private fun ViewContainer<*, *>.SessionRow(summary: SessionSummaryUi, onClick: () -> Unit) {
    View {
        attr {
            margin(all = 6f)
            padding(all = 10f)
            borderRadius(8f)
            backgroundColor(Color(0xFFF7F7F7))
        }
        Text {
            attr {
                text(summary.title.ifEmpty { summary.sessionId })
                fontSize(14f)
                fontWeightBold()
                color(Color(0xFF222222))
            }
        }
        Text {
            attr {
                val source = if (summary.source == "host") "宿主" else "本客户端"
                val live = if (summary.live) " · 常驻" else ""
                text("$source · ${summary.messageCount} 条$live")
                fontSize(12f)
                marginTop(4f)
                color(Color(0xFF2196F3))
            }
        }
        if (summary.cwd.isNotEmpty()) {
            Text {
                attr {
                    text(summary.cwd)
                    fontSize(11f)
                    marginTop(2f)
                    color(Color(0xFF999999))
                }
            }
        }
        if (summary.lastMessage.isNotEmpty()) {
            Text {
                attr {
                    text(summary.lastMessage)
                    fontSize(12f)
                    marginTop(4f)
                    color(Color(0xFF666666))
                }
            }
        }
        event {
            click { onClick() }
        }
    }
}

private fun ViewContainer<*, *>.WorkspaceRow(workspace: WorkspaceUi, onClick: () -> Unit) {
    View {
        attr {
            margin(all = 6f)
            padding(all = 10f)
            borderRadius(8f)
            backgroundColor(Color(0xFFF0F7F4))
        }
        Text {
            attr {
                text(workspace.title.ifEmpty { workspace.workspaceId })
                fontSize(13f)
                fontWeightBold()
                color(Color(0xFF1B5E20))
            }
        }
        Text {
            attr {
                text(workspace.path)
                fontSize(11f)
                marginTop(2f)
                color(Color(0xFF999999))
            }
        }
        Text {
            attr {
                text("${workspace.sessionIds.size} 个会话 · 点此新建会话")
                fontSize(11f)
                marginTop(4f)
                color(Color(0xFF4CAF50))
            }
        }
        event {
            click { onClick() }
        }
    }
}
