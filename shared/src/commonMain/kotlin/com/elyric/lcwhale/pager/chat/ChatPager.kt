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
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.ScrollerView
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
    private var renderedMessages: ObservableList<ChatMessage> by observableList()
    private var messageRefreshToken by observable(0)
    private var messageContentHeight by observable(0f)
    private var messageScrollerRef: ViewRef<ScrollerView<*, *>>? = null

    /** 会话模型(observable:就绪后触发消息列表重建)。 */
    private var chat: ChatSession? by observable(null)

    private var pendingUrl = ""
    private var requestedSessionId = ""
    private var pendingCwd = ""

    private val viewModel = ChatViewModel()

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
        viewModel.onChanged = { syncRenderedMessages() }
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
            attr {
                backgroundColor(ctx.palette.page)
            }

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
                    color(if (ctx.engine.isConnected()) ctx.palette.success else ctx.palette.textMuted)
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
                ref { ctx.messageScrollerRef = it }
                attr { flex(1f); padding(all = 10f) }
                event {
                    contentSizeChanged { _, height ->
                        ctx.messageContentHeight = height
                        ctx.scrollMessagesToLatest()
                    }
                }
                // One scroll container owns the complete turn: reasoning, tools,
                // user input and streamed assistant output stay together.
                vif({ ctx.chat?.turnState == RunState.RUNNING }) {
                    Text { attr { text("AI 正在思考…"); fontSize(12f); marginLeft(2f); color(ctx.palette.textMuted) } }
                }
                vfor({ ctx.renderedMessages }) { message ->
                    View {
                        attr {
                            margin(all = 6f)
                            padding(all = 10f)
                            borderRadius(8f)
                            backgroundColor(
                                when (message.role) {
                                    "user" -> ctx.palette.userBubble
                                    "reasoning", "tool", "system", "context" -> ctx.palette.surfaceMuted
                                    else -> ctx.palette.assistantBubble
                                }
                            )
                        }
                        Text {
                            attr {
                                text(
                                    when (message.role) {
                                        "user" -> "我"
                                        "reasoning" -> "思考过程"
                                        "tool" -> "工具调用"
                                        "system" -> "系统"
                                        "context" -> "上下文"
                                        else -> "DSH"
                                    }
                                )
                                fontSize(11f)
                                color(if (message.role == "tool") ctx.palette.accent else ctx.palette.textMuted)
                            }
                        }
                        if (message.role == "assistant") {
                            AiMarkdownContent(message.text, ctx.palette)
                        } else {
                            Text {
                                attr {
                                    text(message.text)
                                    fontSize(if (message.role == "reasoning" || message.role == "tool") 12f else 14f)
                                    marginTop(2f)
                                    color(if (message.role == "reasoning" || message.role == "tool") ctx.palette.textMuted else ctx.palette.text)
                                }
                            }
                        }
                    }
                }
            }
            Text { attr { text(turnLabel(ctx.chat?.turnState ?: RunState.IDLE)); fontSize(12f); marginLeft(12f); color(ctx.palette.accent) } }
            vif({ (ctx.chat?.error ?: "").isNotEmpty() }) {
                Text { attr { text(ctx.chat?.error ?: ""); fontSize(12f); marginLeft(12f); color(ctx.palette.error) } }
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
                        backgroundColor(ctx.palette.surfaceMuted)
                    }
                    Text {
                        attr {
                            text("权限申请")
                            fontSize(12f)
                            fontWeightBold()
                            color(ctx.palette.warning)
                        }
                    }
                    Text {
                        attr {
                            text(ctx.chat?.pendingApproval?.toolName ?: "")
                            fontSize(13f)
                            marginTop(4f)
                            color(ctx.palette.text)
                        }
                    }
                    vif({ (ctx.chat?.pendingApproval?.reason ?: "").isNotEmpty() }) {
                        Text {
                            attr {
                                text(ctx.chat?.pendingApproval?.reason ?: "")
                                fontSize(12f)
                                marginTop(2f)
                                color(ctx.palette.textMuted)
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
                                backgroundColor(ctx.palette.success)
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
                                backgroundColor(ctx.palette.error)
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

            ChatInputBar(ctx.promptInput, ctx.chat?.turnState == RunState.RUNNING, { value -> ctx.promptInput = value }, { ctx.stopTurn() }, { ctx.send() }, ctx.palette)
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
        viewModel.bind(session)
        syncRenderedMessages()
        if (session.title.isNotEmpty()) {
            titleText = session.title
        }
        engine.loadHistory(session.sessionId)
    }

    private fun send(): Boolean {
        val session = chat
        if (session == null) {
            notice = "会话尚未就绪"
            return false
        }
        if (promptInput.isEmpty()) {
            notice = "请输入内容"
            return false
        }
        val outgoing = promptInput
        if (!engine.send(session.sessionId, outgoing)) return false
        // Keep the UI responsive even if the host echoes session.user-message late.
        viewModel.addUserMessage(outgoing)
        promptInput = ""
        notice = ""
        return true
    }

    private fun stopTurn() {
        chat?.let { engine.stopSession(it.sessionId) }
    }

    private fun syncRenderedMessages() {
        renderedMessages.clear()
        renderedMessages.addAll(viewModel.messages)
        messageRefreshToken += 1
        println("[DSH_TRACE] pager.render messages=${renderedMessages.size} token=$messageRefreshToken")
        // Content size is reported after layout; the callback above performs the
        // final positioning once the new message heights are known.
        scrollMessagesToLatest()
    }

    private fun scrollMessagesToLatest() {
        val scroller = messageScrollerRef?.view ?: return
        val viewportHeight = scroller.frame.height
        if (viewportHeight <= 0f || messageContentHeight <= 0f) return
        val bottomOffset = (messageContentHeight - viewportHeight).coerceAtLeast(0f)
        scroller.setContentOffset(0f, bottomOffset, animated = false)
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
