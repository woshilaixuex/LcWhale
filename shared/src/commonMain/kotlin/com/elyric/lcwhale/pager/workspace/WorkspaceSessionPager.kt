package com.elyric.lcwhale.pager.workspace

import com.elyric.lcwhale.RouterNavBar
import com.elyric.lcwhale.dsh.ConnectState
import com.elyric.lcwhale.dsh.SessionSummaryUi
import com.elyric.lcwhale.dsh.WorkspaceUi
import com.elyric.lcwhale.foundation.view.WhalePager
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button

@Page("dsh_workspace")
internal class WorkspaceSessionPager : WhalePager() {

    private var statusText by observable("未连接")
    private var notice by observable("")
    private var url = ""
    private var workspaceList: ObservableList<WorkspaceUi> by observableList()
    private var sessionList: ObservableList<SessionSummaryUi> by observableList()
    private var unassignedSessionList: ObservableList<SessionSummaryUi> by observableList()

    override fun created() {
        super.created()
        url = pagerData.params.optString("url")
        bindEngineHooks()
        statusText = stateLabel(engine.state.connectState)
        if (engine.state.connectState == ConnectState.CONNECTED) {
            refresh()
        } else if (url.isNotEmpty()) {
            engine.connect(url)
        }
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        bindEngineHooks()
        if (engine.state.connectState == ConnectState.CONNECTED) refresh()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            RouterNavBar {
                attr {
                    title = "工作区与会话"
                    backDisable = false
                }
            }

            View {
                attr {
                    padding(all = 10f)
                    flexDirectionRow()
                }
                Text {
                    attr {
                        text(ctx.statusText)
                        fontSize(13f)
                        flex(1f)
                        color(if (ctx.engine.isConnected()) Color(0xFF4CAF50) else Color(0xFF777777))
                    }
                }
                Button {
                    attr {
                        size(72f, 34f)
                        borderRadius(6f)
                        backgroundColor(Color(0xFF2196F3))
                        titleAttr {
                            text("刷新")
                            fontSize(13f)
                            color(Color.WHITE)
                        }
                    }
                    event { click { ctx.refresh() } }
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

            Scroller {
                attr {
                    flex(1f)
                    padding(all = 10f)
                }
                vfor({ ctx.workspaceList }) { workspace ->
                    WorkspaceSection(
                        workspace = workspace,
                        sessions = ctx.sessionsFor(workspace),
                        onClick = { session -> ctx.openSession(session) },
                        onNewSession = { ctx.openNewSession(workspace) },
                    )
                }
                vif({ ctx.unassignedSessionList.isNotEmpty() }) {
                    Text {
                        attr {
                            text("未归属工作区")
                            fontSize(14f)
                            fontWeightBold()
                            marginTop(12f)
                            color(Color(0xFF555555))
                        }
                    }
                    vfor({ ctx.unassignedSessionList }) { session ->
                        SessionRow(session) { ctx.openSession(session) }
                    }
                }
            }
        }
    }

    private fun bindEngineHooks() {
        engine.onConnectionChanged = { state ->
            statusText = stateLabel(state)
            if (state == ConnectState.CONNECTED) refresh()
        }
        engine.onNotice = { message -> notice = message }
    }

    private fun refresh() {
        if (engine.state.connectState != ConnectState.CONNECTED) return
        engine.listSessions { sessions ->
            sessionList.clear()
            val visibleSessions = sessions.filter { !it.deleted }
            sessionList.addAll(visibleSessions)
            rebuildUnassignedSessions()
        }
        engine.listWorkspaces { workspaces ->
            workspaceList.clear()
            workspaceList.addAll(workspaces)
            rebuildUnassignedSessions()
        }
    }

    private fun rebuildUnassignedSessions() {
        val assignedIds = workspaceList.flatMap { it.sessionIds }.toSet()
        unassignedSessionList.clear()
        unassignedSessionList.addAll(sessionList.filter { it.sessionId !in assignedIds })
    }

    private fun sessionsFor(workspace: WorkspaceUi): List<SessionSummaryUi> {
        val ids = workspace.sessionIds.toSet()
        return sessionList.filter { it.sessionId in ids }
    }

    private fun openSession(session: SessionSummaryUi) {
        val data = JSONObject()
        data.put("sessionId", session.sessionId)
        data.put("title", session.title)
        data.put("cwd", session.cwd)
        data.put("url", url)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("dsh_chat", data)
    }

    private fun openNewSession(workspace: WorkspaceUi) {
        val data = JSONObject()
        data.put("title", workspace.title)
        data.put("cwd", workspace.path)
        data.put("url", url)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("dsh_chat", data)
    }

    private fun stateLabel(state: ConnectState): String = when (state) {
        ConnectState.IDLE -> "未连接"
        ConnectState.CONNECTING -> "连接中…"
        ConnectState.CONNECTED -> "已连接"
        ConnectState.DISCONNECTED -> "已断开"
    }
}

private fun ViewContainer<*, *>.WorkspaceSection(
    workspace: WorkspaceUi,
    sessions: List<SessionSummaryUi>,
    onClick: (SessionSummaryUi) -> Unit,
    onNewSession: () -> Unit,
) {
    View {
        attr {
            marginTop(8f)
            paddingBottom(6f)
        }
        View {
            attr {
                flexDirectionRow()
            }
            Text {
                attr {
                    text(workspace.title.ifEmpty { workspace.path })
                    fontSize(15f)
                    fontWeightBold()
                    flex(1f)
                    color(Color(0xFF1B5E20))
                }
            }
            Button {
                attr {
                    size(96f, 30f)
                    borderRadius(6f)
                    backgroundColor(Color(0xFF4CAF50))
                    titleAttr {
                        text("新建会话")
                        fontSize(12f)
                        color(Color.WHITE)
                    }
                }
                event { click { onNewSession() } }
            }
        }
        Text {
            attr {
                text(workspace.path)
                fontSize(11f)
                marginTop(2f)
                color(Color(0xFF888888))
            }
        }
        if (sessions.isEmpty()) {
            Text {
                attr {
                    text("暂无会话")
                    fontSize(12f)
                    marginTop(5f)
                    color(Color(0xFF999999))
                }
            }
        } else {
            sessions.forEach { session -> SessionRow(session) { onClick(session) } }
        }
    }
}

private fun ViewContainer<*, *>.SessionRow(session: SessionSummaryUi, onClick: () -> Unit) {
    View {
        attr {
            margin(top = 6f, bottom = 2f)
            padding(all = 10f)
            borderRadius(6f)
            backgroundColor(Color(0xFFF5F7FA))
        }
        Text {
            attr {
                text(session.title.ifEmpty { session.sessionId })
                fontSize(14f)
                fontWeightBold()
                color(Color(0xFF222222))
            }
        }
        Text {
            attr {
                text(if (session.lastMessage.isEmpty()) "暂无消息" else session.lastMessage)
                fontSize(12f)
                marginTop(3f)
                color(Color(0xFF666666))
            }
        }
        event { click { onClick() } }
    }
}
