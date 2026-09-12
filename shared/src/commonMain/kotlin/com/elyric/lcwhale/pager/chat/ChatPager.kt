package com.elyric.lcwhale.pager.chat

import com.elyric.lcwhale.RouterNavBar
import com.elyric.lcwhale.dsh.ChatMessage
import com.elyric.lcwhale.dsh.ChatSession
import com.elyric.lcwhale.dsh.ConnectState
import com.elyric.lcwhale.dsh.RunState
import com.elyric.lcwhale.foundation.view.WhalePager
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button

/**
 * 会话页:多轮对话,复用同一个 sessionId 以保持上下文。
 */
@Page("dsh_chat")
internal class ChatPager : WhalePager() {

    private var promptInput by observable("")
    private var connText by observable("未连接")
    private var notice by observable("")
    private var titleText by observable("会话")

    /** 会话模型(observable:就绪后触发消息列表重建)。 */
    private var chat: ChatSession? by observable(null)

    private var pendingUrl = ""
    private var requestedSessionId = ""
    private var pendingCwd = ""

    private val emptyMessages = ObservableList<ChatMessage>()

    override fun created() {
        super.created()
        requestedSessionId = pagerData.params.optString("sessionId")
        pendingUrl = pagerData.params.optString("url")
        pendingCwd = pagerData.params.optString("cwd")
        val paramTitle = pagerData.params.optString("title")
        if (paramTitle.isNotEmpty()) {
            titleText = paramTitle
        }

        bindEngineHooks()
        connText = connLabel(engine.state.connectState)

        if (engine.state.connectState == ConnectState.CONNECTED) {
            ensureSession()
        } else if (pendingUrl.isNotEmpty()) {
            engine.connect(pendingUrl)
        }
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        bindEngineHooks()
        if (engine.state.connectState == ConnectState.CONNECTED && chat == null) {
            ensureSession()
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            RouterNavBar {
                attr {
                    title = ctx.titleText
                    backDisable = false
                }
            }

            Text {
                attr {
                    text(ctx.connText)
                    fontSize(12f)
                    marginLeft(10f)
                    color(if (ctx.engine.isConnected()) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
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

            // 思考区(可折叠为纯文本展示)
            vif({ (ctx.chat?.reasoningText ?: "").isNotEmpty() }) {
                View {
                    attr {
                        marginLeft(10f)
                        marginRight(10f)
                        marginTop(6f)
                        padding(all = 8f)
                        borderRadius(6f)
                        backgroundColor(Color(0xFFF3F0F5))
                    }
                    Text {
                        attr {
                            text("思考")
                            fontSize(11f)
                            color(Color(0xFF9C27B0))
                        }
                    }
                    Text {
                        attr {
                            text(ctx.chat?.reasoningText ?: "")
                            fontSize(12f)
                            marginTop(2f)
                            color(Color(0xFF666666))
                        }
                    }
                }
            }
            vif({ (ctx.chat?.todoText ?: "").isNotEmpty() }) {
                Text {
                    attr {
                        text(ctx.chat?.todoText ?: "")
                        fontSize(12f)
                        marginLeft(10f)
                        marginTop(4f)
                        color(Color(0xFF666666))
                    }
                }
            }
            vif({ (ctx.chat?.planText ?: "").isNotEmpty() }) {
                Text {
                    attr {
                        text(ctx.chat?.planText ?: "")
                        fontSize(12f)
                        marginLeft(10f)
                        marginTop(4f)
                        color(Color(0xFF2196F3))
                    }
                }
            }
            vif({ (ctx.chat?.toolSummary ?: "").isNotEmpty() }) {
                Text {
                    attr {
                        text(ctx.chat?.toolSummary ?: "")
                        fontSize(12f)
                        marginLeft(10f)
                        marginTop(4f)
                        color(Color(0xFF666666))
                    }
                }
            }

            // 消息区
            Scroller {
                attr {
                    flex(1f)
                    padding(all = 10f)
                }
                vfor({ ctx.chat?.messages ?: ctx.emptyMessages }) { message ->
                    MessageBubble(message)
                }
            }

            // 状态行(运行状态 / 错误)
            Text {
                attr {
                    text(turnLabel(ctx.chat?.turnState ?: RunState.IDLE))
                    fontSize(12f)
                    marginLeft(10f)
                    color(Color(0xFF2196F3))
                }
            }
            vif({ (ctx.chat?.error ?: "").isNotEmpty() }) {
                Text {
                    attr {
                        text(ctx.chat?.error ?: "")
                        fontSize(12f)
                        marginLeft(10f)
                        color(Color(0xFFF44336))
                    }
                }
            }

            // 权限审批卡片
            vif({ ctx.chat?.pendingApproval != null }) {
                View {
                    attr {
                        marginLeft(10f)
                        marginRight(10f)
                        marginTop(8f)
                        padding(all = 10f)
                        borderRadius(8f)
                        backgroundColor(Color(0xFFFFF3E0))
                    }
                    Text {
                        attr {
                            text("权限申请")
                            fontSize(12f)
                            fontWeightBold()
                            color(Color(0xFFE65100))
                        }
                    }
                    Text {
                        attr {
                            text(ctx.chat?.pendingApproval?.toolName ?: "")
                            fontSize(13f)
                            marginTop(4f)
                            color(Color(0xFF222222))
                        }
                    }
                    vif({ (ctx.chat?.pendingApproval?.reason ?: "").isNotEmpty() }) {
                        Text {
                            attr {
                                text(ctx.chat?.pendingApproval?.reason ?: "")
                                fontSize(12f)
                                marginTop(2f)
                                color(Color(0xFF666666))
                            }
                        }
                    }
                    View {
                        attr {
                            marginTop(8f)
                            flexDirectionRow()
                        }
                        Button {
                            attr {
                                size(72f, 30f)
                                borderRadius(15f)
                                backgroundColor(Color(0xFF4CAF50))
                                titleAttr {
                                    text("允许")
                                    fontSize(13f)
                                    color(Color.WHITE)
                                }
                            }
                            event {
                                click { ctx.respondApproval(true) }
                            }
                        }
                        Button {
                            attr {
                                size(72f, 30f)
                                borderRadius(15f)
                                marginLeft(8f)
                                backgroundColor(Color(0xFFF44336))
                                titleAttr {
                                    text("拒绝")
                                    fontSize(13f)
                                    color(Color.WHITE)
                                }
                            }
                            event {
                                click { ctx.respondApproval(false) }
                            }
                        }
                    }
                }
            }

            // 输入栏
            View {
                attr {
                    padding(all = 10f)
                    flexDirectionRow()
                }
                Input {
                    attr {
                        flex(1f)
                        height(38f)
                        fontSize(14f)
                        color(Color(0xFF333333))
                        placeholder("输入消息")
                        placeholderColor(Color(0xFFAAAAAA))
                    }
                    event {
                        textDidChange { ctx.promptInput = it.text }
                    }
                }
                vif({ ctx.chat?.turnState == RunState.RUNNING }) {
                    Button {
                        attr {
                            size(72f, 38f)
                            borderRadius(6f)
                            marginLeft(8f)
                            backgroundColor(Color(0xFFF44336))
                            titleAttr {
                                text("停止")
                                fontSize(14f)
                                color(Color.WHITE)
                            }
                        }
                        event {
                            click { ctx.stopTurn() }
                        }
                    }
                }
                Button {
                    attr {
                        size(72f, 38f)
                        borderRadius(6f)
                        marginLeft(8f)
                        backgroundColor(Color(0xFF4CAF50))
                        titleAttr {
                            text("发送")
                            fontSize(14f)
                            color(Color.WHITE)
                        }
                    }
                    event {
                        click { ctx.send() }
                    }
                }
            }
        }
    }

    private fun bindEngineHooks() {
        engine.onConnectionChanged = { state ->
            connText = connLabel(state)
            if (state == ConnectState.CONNECTED) {
                ensureSession()
            }
        }
        engine.onNotice = { message ->
            notice = message
        }
    }

    private fun ensureSession() {
        if (chat != null || engine.state.connectState != ConnectState.CONNECTED) return
        if (requestedSessionId.isNotEmpty()) {
            engine.createSession(
                title = titleText,
                sessionId = requestedSessionId,
                cwd = pendingCwd.ifEmpty { null },
            ) { session ->
                if (session != null) adoptSession(session)
            }
        } else {
            engine.createSession(null, cwd = pendingCwd) { session ->
                if (session != null) adoptSession(session)
            }
        }
    }

    private fun adoptSession(session: ChatSession) {
        chat = session
        if (session.title.isNotEmpty()) {
            titleText = session.title
        }
        engine.loadHistory(session.sessionId)
    }

    private fun send() {
        val session = chat
        if (session == null) {
            notice = "会话尚未就绪"
            return
        }
        if (promptInput.isEmpty()) {
            notice = "请输入内容"
            return
        }
        if (!engine.send(session.sessionId, promptInput)) return
        promptInput = ""
        notice = ""
    }

    private fun stopTurn() {
        chat?.let { engine.stopSession(it.sessionId) }
    }

    private fun respondApproval(allow: Boolean) {
        val session = chat ?: return
        val approval = session.pendingApproval ?: return
        engine.respondApproval(session.sessionId, approval.pendingId, allow)
    }

    private fun connLabel(state: ConnectState): String = when (state) {
        ConnectState.IDLE -> "未连接"
        ConnectState.CONNECTING -> "连接中…"
        ConnectState.CONNECTED -> "已连接"
        ConnectState.DISCONNECTED -> "已断开"
    }
}

private fun turnLabel(state: RunState): String = when (state) {
    RunState.IDLE -> ""
    RunState.QUEUED -> "排队中"
    RunState.RUNNING -> "运行中…"
    RunState.DONE -> "完成"
    RunState.STOPPED -> "已停止"
    RunState.FAILED -> "失败"
}

private fun ViewContainer<*, *>.MessageBubble(message: ChatMessage) {
    View {
        attr {
            margin(all = 6f)
            padding(all = 10f)
            borderRadius(8f)
            backgroundColor(
                if (message.role == "user") Color(0xFFE3F2FD) else Color(0xFFF1F1F1)
            )
        }
        Text {
            attr {
                text(if (message.role == "user") "我" else "DSH")
                fontSize(11f)
                color(Color(0xFF999999))
            }
        }
        Text {
            attr {
                text(message.text)
                fontSize(14f)
                marginTop(2f)
                color(Color(0xFF222222))
            }
        }
    }
}
