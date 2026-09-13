package com.elyric.lcwhale.pager.chat

import com.elyric.lcwhale.dsh.ChatMessage
import com.elyric.lcwhale.dsh.ChatSession
import com.elyric.lcwhale.dsh.RunState
import com.elyric.lcwhale.foundation.theme.ThemePalette
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

internal fun ViewContainer<*, *>.ChatMessagePanel(
    chat: ChatSession?,
    messages: ObservableList<ChatMessage>,
    refreshToken: Int,
    palette: ThemePalette,
) {
    // Pager increments this token after every model update; reading it makes the
    // component body rebuild before generating the stable message tree.
    if (refreshToken < 0) return
    vif({ chat?.turnState == RunState.RUNNING }) {
        Text { attr { text("AI 正在思考…"); fontSize(12f); marginLeft(12f); color(palette.textMuted) } }
    }
    vif({ (chat?.reasoningText ?: "").isNotEmpty() }) {
        Text {
            attr {
                text(chat?.reasoningText ?: "")
                fontSize(12f)
                marginLeft(12f)
                marginRight(12f)
                marginTop(4f)
                color(palette.textMuted)
            }
        }
    }
    vif({ (chat?.todoText ?: "").isNotEmpty() }) { Text { attr { text(chat?.todoText ?: ""); fontSize(12f); marginLeft(12f); color(palette.textMuted) } } }
    vif({ (chat?.toolSummary ?: "").isNotEmpty() }) {
        View {
            attr { marginLeft(12f); marginRight(12f); marginTop(4f); padding(all = 8f); borderRadius(6f); backgroundColor(palette.surfaceMuted) }
            Text { attr { text("工具状态"); fontSize(11f); color(palette.accent) } }
            Text { attr { text(chat?.toolSummary ?: ""); fontSize(11f); marginTop(2f); color(palette.textMuted) } }
        }
    }
    Scroller {
        attr { flex(1f); padding(all = 10f) }
        messages.forEach { message -> MessageBubble(message, palette) }
    }
    Text { attr { text(turnLabel(chat?.turnState ?: RunState.IDLE)); fontSize(12f); marginLeft(12f); color(palette.accent) } }
    vif({ (chat?.error ?: "").isNotEmpty() }) { Text { attr { text(chat?.error ?: ""); fontSize(12f); marginLeft(12f); color(palette.error) } } }
}

private fun turnLabel(state: RunState): String = when (state) {
    RunState.IDLE -> ""
    RunState.QUEUED -> "排队中"
    RunState.RUNNING -> "运行中…"
    RunState.DONE -> "完成"
    RunState.STOPPED -> "已停止"
    RunState.FAILED -> "失败"
}

private fun ViewContainer<*, *>.MessageBubble(message: ChatMessage, palette: ThemePalette) {
    View {
        attr { margin(all = 6f); padding(all = 10f); borderRadius(8f); backgroundColor(if (message.role == "user") palette.userBubble else palette.page) }
        Text { attr { text(if (message.role == "user") "我" else "DSH"); fontSize(11f); color(palette.textMuted) } }
        if (message.role == "assistant") {
            AiMarkdownContent(message.text, palette)
        } else {
            Text { attr { text(message.text); fontSize(14f); marginTop(2f); color(palette.text) } }
        }
    }
}
