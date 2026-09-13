package com.elyric.lcwhale.pager.chat

import com.elyric.lcwhale.RouterNavBar
import com.elyric.lcwhale.dsh.ChatMessage
import com.elyric.lcwhale.dsh.ChatSession
import com.elyric.lcwhale.dsh.ConnectState
import com.elyric.lcwhale.dsh.RunState
import com.elyric.lcwhale.base.bridgeModule
import com.elyric.lcwhale.base.setTimeout
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
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.SelectableOption
import com.tencent.kuikly.core.views.SelectionType
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
    private var renderScheduled = false
    private val displaySources = mutableMapOf<ChatMessage, ChatMessage>()
    private val streamTargets = mutableMapOf<ChatMessage, String>()
    private val streamRunning = mutableSetOf<ChatMessage>()
    private val selectionRefs = mutableMapOf<ChatMessage, ViewRef<DivView>>()
    private var selectedMessage by observable<ChatMessage?>(null)
    private var selectedText by observable("")

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
        // Protocol events can arrive several times per frame while the assistant
        // is streaming. Coalesce them into one layout pass to keep scrolling and
        // Markdown measurement responsive.
        viewModel.onChanged = { scheduleRenderedMessages() }
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
                                    else -> ctx.palette.page
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
                        View {
                            ref { ctx.selectionRefs[message] = it }
                            attr {
                                selectable(SelectableOption.ENABLE)
                                selectionColor(ctx.palette.accent)
                            }
                            event {
                                longPress {
                                    if (it.state == "start") {
                                        ctx.selectionRefs[message]?.view?.createSelection(it.x, it.y, SelectionType.WORD)
                                    }
                                }
                                selectEnd {
                                    ctx.selectionRefs[message]?.view?.getSelection { result ->
                                        val value = result.content.joinToString("").trim()
                                        if (value.isNotEmpty()) {
                                            ctx.selectedMessage = message
                                            ctx.selectedText = value
                                        }
                                    }
                                }
                                selectCancel {
                                    if (ctx.selectedMessage === message) {
                                        ctx.selectedMessage = null
                                        ctx.selectedText = ""
                                    }
                                }
                            }
                            if (message.role == "assistant") {
                                AiMarkdownContent(message.text, ctx.palette) { code ->
                                    ctx.bridgeModule.copyToPasteboard(code)
                                }
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
                        View {
                            attr { flexDirectionRow(); marginTop(6f) }
                            Button {
                                attr {
                                    height(24f); padding(left = 8f, right = 8f); borderRadius(5f)
                                    backgroundColor(ctx.palette.surfaceMuted)
                                    titleAttr { text("复制"); fontSize(11f); color(ctx.palette.textMuted) }
                                }
                                event { click { ctx.bridgeModule.copyToPasteboard(message.text) } }
                            }
                            vif({ ctx.selectedMessage === message && ctx.selectedText.isNotEmpty() }) {
                                Button {
                                    attr {
                                        height(24f); padding(left = 8f, right = 8f); marginLeft(6f); borderRadius(5f)
                                        backgroundColor(ctx.palette.accent)
                                        titleAttr { text("复制选中"); fontSize(11f); color(Color.WHITE) }
                                    }
                                    event { click { ctx.bridgeModule.copyToPasteboard(ctx.selectedText) } }
                                }
                            }
                        }
                    }
                }
            }
            View {
                attr { flexDirectionRow(); marginLeft(12f); marginRight(12f); marginBottom(4f) }
                View { attr { flex(1f) } }
                Button {
                    attr {
                        height(26f); padding(left = 9f, right = 9f); borderRadius(5f)
                        backgroundColor(ctx.palette.surfaceMuted)
                        titleAttr { text("导出会话"); fontSize(11f); color(ctx.palette.textMuted) }
                    }
                    event { click { ctx.bridgeModule.copyToPasteboard(ctx.viewModel.transcript) } }
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

            ChatInputBar(
                value = ctx.promptInput,
                running = ctx.chat?.turnState == RunState.RUNNING,
                onChange = { value -> ctx.promptInput = value },
                onStop = { ctx.stopTurn() },
                onSend = { ctx.send() },
                onKeyboardDismiss = { ctx.bridgeModule.closeKeyboard() },
                palette = ctx.palette,
            )
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
        val sourceMessages = viewModel.messages.toList()
        while (renderedMessages.size > sourceMessages.size) renderedMessages.removeAt(renderedMessages.lastIndex)
        sourceMessages.forEachIndexed { index, source ->
            val display = if (index < renderedMessages.size && displaySources[source] === renderedMessages[index]) {
                renderedMessages[index]
            } else {
                val created = ChatMessage(source.role)
                displaySources[source] = created
                if (index < renderedMessages.size) renderedMessages[index] = created else renderedMessages.add(created)
                created
            }
            val shouldAnimate = source.role == "assistant" || source.role == "reasoning"
            if (shouldAnimate && display.text.isEmpty() && source.text.isNotEmpty()) {
                streamTargets[source] = source.text
                animateMessage(source, display)
            } else if (shouldAnimate && source.text.startsWith(display.text) && source.text.length > display.text.length) {
                streamTargets[source] = source.text
                animateMessage(source, display)
            } else if (display.text != source.text) {
                display.text = source.text
                streamTargets.remove(source)
            }
        }
        messageRefreshToken += 1
        println("[DSH_TRACE] pager.render messages=${renderedMessages.size} token=$messageRefreshToken")
        // Content size is reported after layout; the callback above performs the
        // final positioning once the new message heights are known.
        scrollMessagesToLatest()
    }

    /** Consume streamed text one character at a time without blocking protocol callbacks. */
    private fun animateMessage(source: ChatMessage, display: ChatMessage) {
        if (!streamRunning.add(source)) return
        setTimeout(18) {
            val target = streamTargets[source] ?: source.text
            if (display.text.length < target.length && target.startsWith(display.text)) {
                display.text += target[display.text.length]
                streamRunning.remove(source)
                animateMessage(source, display)
                messageRefreshToken += 1
            } else {
                display.text = target
                streamTargets.remove(source)
                streamRunning.remove(source)
                messageRefreshToken += 1
            }
        }
    }

    private fun scheduleRenderedMessages() {
        if (renderScheduled) return
        renderScheduled = true
        setTimeout(16) {
            renderScheduled = false
            syncRenderedMessages()
        }
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
