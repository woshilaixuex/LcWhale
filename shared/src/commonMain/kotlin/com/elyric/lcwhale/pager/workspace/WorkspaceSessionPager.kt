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
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button

@Page("dsh_workspace")
internal class WorkspaceSessionPager : WhalePager() {

    private var statusText by observable("未连接")
    private var notice by observable("")
    private var url = ""
    private val viewModel = WorkspaceViewModel
    private var selectedSessionId by observable("")

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

            vif({ ctx.viewModel.loading }) {
                View {
                    attr { flex(1f); allCenter() }
                    Text {
                        attr {
                            text("正在加载工作区与会话…")
                            fontSize(13f)
                            color(ctx.palette.textMuted)
                        }
                    }
                }
            }
            vif({ !ctx.viewModel.loading }) {
                Scroller {
                    attr {
                        flex(1f)
                        padding(all = 10f)
                    }
                    vfor({ ctx.viewModel.sections }) { section ->
                        WorkspaceSection(
                            section = section,
                            selectedSessionId = { ctx.selectedSessionId },
                            palette = ctx.palette,
                            onSelect = { session -> ctx.selectedSessionId = session.sessionId },
                            onContinue = { session -> ctx.openSession(session) },
                            onNewSession = { ctx.openNewSession(section.workspace) },
                        )
                    }
                    UnassignedSection(
                        sessions = ctx.viewModel.unassignedSessions,
                        count = { ctx.viewModel.unassignedCount },
                        expanded = { ctx.viewModel.unassignedExpanded },
                        selectedSessionId = { ctx.selectedSessionId },
                        palette = ctx.palette,
                        onToggle = { ctx.viewModel.unassignedExpanded = !ctx.viewModel.unassignedExpanded },
                        onSelect = { session -> ctx.selectedSessionId = session.sessionId },
                        onContinue = { session -> ctx.openSession(session) },
                    )
                }
            }
            vif({ ctx.viewModel.loaded && ctx.viewModel.sessions.isEmpty() }) {
                Text {
                    attr {
                        text("未获取到历史会话，请确认 PC 端 dsh-connect 已加载会话能力")
                        fontSize(12f)
                        margin(all = 12f)
                        color(ctx.palette.textMuted)
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
        viewModel.refresh(engine)
        if (selectedSessionId.isNotEmpty() && viewModel.sessions.none { it.sessionId == selectedSessionId }) {
                selectedSessionId = ""
        }
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
    section: WorkspaceSectionUi,
    selectedSessionId: () -> String,
    palette: ThemePalette,
    onSelect: (SessionSummaryUi) -> Unit,
    onContinue: (SessionSummaryUi) -> Unit,
    onNewSession: () -> Unit,
) {
    val workspace = section.workspace
    val sessions = section.sessions
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
                padding(all = 2f)
            }
            Text {
                attr {
                    text(workspace.title.ifEmpty { workspace.path })
                    fontSize(15f)
                    fontWeightBold()
                    flex(1f)
                    color(palette.success)
                }
            }
            Button {
                attr {
                    size(58f, 30f)
                    borderRadius(6f)
                    marginRight(6f)
                    backgroundColor(palette.surfaceMuted)
                    titleAttr {
                        text(if (section.expanded) "收起" else "展开")
                        fontSize(12f)
                        color(palette.text)
                    }
                }
                event { click { section.expanded = !section.expanded } }
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
        vif({ section.expanded }) {
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
                sessions.forEach { session ->
                    SessionRow(
                        session = session,
                        selected = { session.sessionId == selectedSessionId() },
                        palette = palette,
                        onSelect = { onSelect(session) },
                        onContinue = { onContinue(session) },
                    )
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.UnassignedSection(
    sessions: ObservableList<SessionSummaryUi>,
    count: () -> Int,
    expanded: () -> Boolean,
    selectedSessionId: () -> String,
    palette: ThemePalette,
    onToggle: () -> Unit,
    onSelect: (SessionSummaryUi) -> Unit,
    onContinue: (SessionSummaryUi) -> Unit,
) {
    View {
        attr {
            margin(top = 8f, bottom = 4f)
            padding(all = 10f)
            borderRadius(8f)
            backgroundColor(palette.surface)
        }
        View {
            attr {
                flexDirectionRow()
                padding(all = 2f)
            }
            Text {
                attr {
                    text("未分组会话  ·  ${count()} 个")
                    fontSize(15f)
                    fontWeightBold()
                    flex(1f)
                    color(palette.text)
                }
            }
            Button {
                attr {
                    size(58f, 30f)
                    borderRadius(6f)
                    backgroundColor(palette.surfaceMuted)
                    titleAttr {
                        text(if (expanded()) "收起" else "展开")
                        fontSize(12f)
                        color(palette.text)
                    }
                }
                event { click { onToggle() } }
            }
        }
        vif({ expanded() }) {
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
                vfor({ sessions }) { session ->
                    SessionRow(
                        session = session,
                        selected = { session.sessionId == selectedSessionId() },
                        palette = palette,
                        onSelect = { onSelect(session) },
                        onContinue = { onContinue(session) },
                    )
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.SessionRow(
    session: SessionSummaryUi,
    selected: () -> Boolean,
    palette: ThemePalette,
    onSelect: () -> Unit,
    onContinue: () -> Unit,
) {
    View {
        attr {
            margin(top = 6f, bottom = 2f)
            padding(all = 10f)
            borderRadius(6f)
            backgroundColor(if (selected()) palette.userBubble else palette.surfaceMuted)
        }
        View {
            attr { flexDirectionRow() }
            Text {
                attr {
                    text(session.title.ifEmpty { session.sessionId })
                    fontSize(14f)
                    fontWeightBold()
                    flex(1f)
                    color(palette.text)
                }
            }
            vif({ selected() }) {
                Button {
                    attr {
                        size(76f, 28f)
                        borderRadius(6f)
                        backgroundColor(palette.accent)
                        titleAttr {
                            text("继续会话")
                            fontSize(12f)
                            color(Color.WHITE)
                        }
                    }
                    event { click { onContinue() } }
                }
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
        Text {
            attr {
                text("${session.messageCount} 条消息${if (session.live) "  ·  活跃" else ""}")
                fontSize(11f)
                marginTop(3f)
                color(if (selected()) palette.accent else palette.textMuted)
            }
        }
        event { click { onSelect() } }
    }
}
