package com.elyric.lcwhale.pager.chat

import com.elyric.lcwhale.dsh.ChatMessage
import com.elyric.lcwhale.dsh.ChatSession
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.base.BaseObject
import com.tencent.kuikly.core.reactive.handler.observable

/** Stable UI state for the chat page. The renderer never switches vfor collections. */
internal class ChatViewModel : BaseObject() {
    val messages: ObservableList<ChatMessage> by observableList()
    var transcript: String by observable("")
    private var boundSession: ChatSession? = null
    var onChanged: (() -> Unit)? = null

    fun bind(session: ChatSession?) {
        println("[DSH_TRACE] vm.bind session=${session?.sessionId}")
        if (boundSession === session) return
        boundSession?.onMessagesChanged = null
        boundSession = session
        messages.clear()
        transcript = ""
        if (session == null) {
            onChanged?.invoke()
            return
        }
        messages.addAll(session.messages)
        rebuildTranscript()
        println("[DSH_TRACE] vm.bind copied=${messages.size}")
        onChanged?.invoke()
        session.onMessagesChanged = { sync() }
    }

    fun addUserMessage(text: String) {
        if (text.isEmpty()) return
        val alreadyPresent = messages.lastOrNull()?.let { it.role == "user" && it.text == text } == true
        if (!alreadyPresent) messages.add(ChatMessage("user").also { it.text = text })
        rebuildTranscript()
        onChanged?.invoke()
    }

    private fun sync() {
        val source = boundSession?.messages ?: return
        messages.clear()
        messages.addAll(source)
        rebuildTranscript()
        println("[DSH_TRACE] vm.sync messages=${messages.size} transcriptLen=${transcript.length}")
        onChanged?.invoke()
    }

    private fun rebuildTranscript() {
        transcript = messages.joinToString("\n\n") { message ->
            val author = if (message.role == "user") "我" else "DSH"
            "$author\n${message.text}"
        }
    }
}
