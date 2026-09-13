package com.elyric.lcwhale.pager.workspace

import com.elyric.lcwhale.RouterNavBar
import com.elyric.lcwhale.dsh.ConnectState
import com.elyric.lcwhale.dsh.SessionSummaryUi
import com.elyric.lcwhale.dsh.WorkspaceUi
import com.elyric.lcwhale.foundation.view.WhalePager
import com.elyric.lcwhale.foundation.theme.ThemePalette
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
    private var expandedWorkspaceIds by observable(setOf<String>())
    private var unassignedExpanded by observable(false)

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
            attr {
                backgroundColor(ctx.palette.page)
            }

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
                        color(if (ctx.engine.isConnected()) ctx.palette.success else ctx.palette.textMuted)
                    }
                }
                Button {
                    attr {
                        size(72f, 34f)
                        borderRadius(6f)
                        backgroundColor(ctx.palette.accent)
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
                        color(ctx.palette.warning)
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
                        expanded = workspace.workspaceId in ctx.expandedWorkspaceIds,
                        palette = ctx.palette,
                        onClick = { session -> ctx.openSession(session) },
                        onNewSession = { ctx.openNewSession(workspace) },
                        onToggle = { ctx.toggleWorkspace(workspace.workspaceId) },
                    )
                }
                UnassignedSection(
                    sessions = ctx.unassignedSessionList,
                    expanded = ctx.unassignedExpanded,
                    palette = ctx.palette,
                    onToggle = { ctx.unassignedExpanded = !ctx.unassignedExpanded },
                    onClick = { session -> ctx.openSession(session) },
                )
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

    private fun toggleWorkspace(workspaceId: String) {
        expandedWorkspaceIds = if (workspaceId in expandedWorkspaceIds) {
            expandedWorkspaceIds - workspaceId
        } else {
            expandedWorkspaceIds + workspaceId
        }
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
    expanded: Boolean,
    palette: ThemePalette,
    onClick: (SessionSummaryUi) -> Unit,
    onNewSession: () -> Unit,
    onToggle: () -> Unit,
) {
    View {
        attr {
            margin(top = 6f, bottom = 4f)
            padding(all = 10f)
            borderRadius(8f)
            backgroundColor(palette.surface)
        }
        View {
            attr {
                flexDirectionRow()
            }
            Text {
                attr {
                    text("${if (expanded) "收起" else "展开"}  ·  ${workspace.title.ifEmpty { workspace.path }}")
                    fontSize(15f)
                    fontWeightBold()
                    flex(1f)
                    color(palette.success)
                }
                event { click { onToggle() } }
            }
            Button {
                attr {
                    size(96f, 30f)
                    borderRadius(6f)
                    backgroundColor(palette.success)
                    titleAttr {
                        text("新建会话")
                        fontSize(12f)
                        color(Color.WHITE)
                    }
                }
                event { click { onNewSession() } }
            }
        }
        View {
            attr {
                marginTop(3f)
            }
            Text {
                attr {
                    text("${sessions.size} 个会话  ·  ${workspace.path}")
                    fontSize(11f)
                    color(palette.textMuted)
                }
            }
        }
        vif({ expanded }) {
            if (sessions.isEmpty()) {
                Text {
                    attr {
                        text("暂无会话")
                        fontSize(12f)
                        marginTop(7f)
                        color(palette.textMuted)
                    }
                }
            } else {
                sessions.forEach { session -> SessionRow(session, palette) { onClick(session) } }
            }
        }
    }
}

private fun ViewContainer<*, *>.UnassignedSection(
    sessions: ObservableList<SessionSummaryUi>,
    expanded: Boolean,
    palette: ThemePalette,
    onToggle: () -> Unit,
    onClick: (SessionSummaryUi) -> Unit,
) {
    View {
        attr {
            margin(top = 8f, bottom = 4f)
            padding(all = 10f)
            borderRadius(8f)
            backgroundColor(palette.surface)
        }
        Text {
            attr {
                text("${if (expanded) "收起" else "展开"}  ·  未分组会话  ·  ${sessions.size} 个")
                fontSize(15f)
                fontWeightBold()
                color(palette.text)
            }
            event { click { onToggle() } }
        }
        vif({ expanded }) {
            if (sessions.isEmpty()) {
                Text {
                    attr {
                        text("暂无未分组会话")
                        fontSize(12f)
                        marginTop(7f)
                        color(palette.textMuted)
                    }
                }
            } else {
                sessions.forEach { session -> SessionRow(session, palette) { onClick(session) } }
            }
        }
    }
}

private fun ViewContainer<*, *>.SessionRow(session: SessionSummaryUi, palette: ThemePalette, onClick: () -> Unit) {
    View {
        attr {
            margin(top = 6f, bottom = 2f)
            padding(all = 10f)
            borderRadius(6f)
            backgroundColor(palette.surfaceMuted)
        }
        Text {
            attr {
                text(session.title.ifEmpty { session.sessionId })
                fontSize(14f)
                fontWeightBold()
                color(palette.text)
            }
        }
        Text {
            attr {
                text(if (session.lastMessage.isEmpty()) "暂无消息" else session.lastMessage)
                fontSize(12f)
                marginTop(3f)
                color(palette.textMuted)
            }
        }
        event { click { onClick() } }
    }
}
