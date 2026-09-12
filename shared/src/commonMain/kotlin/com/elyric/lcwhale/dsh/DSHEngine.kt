package com.elyric.lcwhale.dsh

import com.elyric.lcwhale.foundation.module.DSHWebSocketModule
import com.elyric.lcwhale.foundation.module.dshWebSocketUrl
import com.tencent.kuikly.core.base.BaseObject
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList

enum class RunState { IDLE, QUEUED, RUNNING, DONE, STOPPED, FAILED }

/**
 * 一次 agent.run(一次性任务)的会话模型:把协议事件翻译成 UI 可直接渲染的字段。
 */
internal class RunSession(val prompt: String, val chunks: Boolean) : BaseObject() {

    var requestId = ""

    var taskId: String by observable("")
    var state: RunState by observable(RunState.QUEUED)
    var error: String by observable("")
    var durationMs: Long by observable(0L)
    var agentStatus: String by observable("")
    var assistantText: String by observable("")
    var reasoningText: String by observable("")
    var toolSummary: String by observable("")

    /** 已经用增量文本流过的 turn:step,用于避免与最终 assistant.message 重复拼接。 */
    private val chunkedSteps = mutableSetOf<String>()

    private val runningTools = mutableMapOf<String, String>()
    private val finishedTools = mutableListOf<String>()

    fun onEvent(data: JSONObject) {
        when (data.optString("kind")) {
            DSHProtocol.EVT_AGENT_STATUS -> {
                agentStatus = data.optString("status")
                if (state == RunState.QUEUED) state = RunState.RUNNING
            }
            DSHProtocol.EVT_ASSISTANT_MESSAGE -> onAssistantMessage(data)
            DSHProtocol.EVT_ASSISTANT_CHUNK -> onAssistantChunk(data)
            DSHProtocol.EVT_ASSISTANT_REASONING -> {
                val text = data.optString("text")
                if (text.isNotEmpty()) {
                    reasoningText = if (reasoningText.isEmpty()) text else "$reasoningText\n$text"
                }
            }
            DSHProtocol.EVT_ASSISTANT_REASONING_CHUNK -> {
                reasoningText += data.optString("text")
            }
            DSHProtocol.EVT_TOOL_CALL -> onToolCall(data)
            DSHProtocol.EVT_TOOL_RESULT -> onToolResult(data)
            DSHProtocol.EVT_AGENT_ERROR -> {
                error = data.optString("message")
                state = RunState.FAILED
            }
            else -> Unit // 未知 kind 忽略(前向兼容)
        }
    }

    private fun onAssistantMessage(data: JSONObject) {
        if (chunkedSteps.contains(stepKey(data))) return
        val text = data.optString("text")
        if (text.isNotEmpty()) {
            assistantText = if (assistantText.isEmpty()) text else "$assistantText\n$text"
        }
    }

    private fun onAssistantChunk(data: JSONObject) {
        if (state == RunState.QUEUED) state = RunState.RUNNING
        val chunk = data.optJSONObject("chunk") ?: return
        when (chunk.optString("type")) {
            DSHProtocol.CHUNK_TEXT_DELTA -> {
                chunkedSteps.add(stepKey(data))
                assistantText += chunk.optString("text")
            }
            else -> Unit // reasoning-delta / tool-call-delta / block-* / usage / finish / 未知:忽略
        }
    }

    private fun onToolCall(data: JSONObject) {
        val callId = data.optString("callId")
        if (callId.isEmpty() || runningTools.containsKey(callId)) return
        runningTools[callId] = data.optString("name")
        rebuildToolSummary()
    }

    private fun onToolResult(data: JSONObject) {
        val callId = data.optString("callId")
        val name = runningTools.remove(callId) ?: ""
        val ok = data.optBoolean("ok")
        finishedTools.add("• $name ${if (ok) "完成" else "失败"}")
        rebuildToolSummary()
        if (state == RunState.QUEUED) state = RunState.RUNNING
    }

    private fun rebuildToolSummary() {
        val lines = mutableListOf<String>()
        finishedTools.forEach { lines.add(it) }
        runningTools.values.forEach { lines.add("• $it 运行中") }
        toolSummary = lines.joinToString("\n")
    }

    private fun stepKey(data: JSONObject): String = "${data.optInt("turn")}:${data.optInt("step")}"
}

/**
 * 会话内一条消息(role: user / assistant)。
 */
internal class ChatMessage(val role: String) : BaseObject() {
    var text: String by observable("")
}

/**
 * 待审批的权限请求(session.approval → approval.respond)。
 */
internal class ApprovalRequest(
    val pendingId: String,
    val toolName: String,
    val callId: String,
    val reason: String,
)

/**
 * 多轮会话模型(session.* 族):事件落到 messages 列表,UI 直接 vfor 渲染。
 */
internal class ChatSession(val sessionId: String, initialTitle: String) : BaseObject() {

    var title: String by observable(initialTitle)
    var turnState: RunState by observable(RunState.IDLE)
    var error: String by observable("")
    var reasoningText: String by observable("")
    var toolSummary: String by observable("")
    var todoText: String by observable("")
    var planText: String by observable("")
    var pendingApproval: ApprovalRequest? by observable(null)

    /** 消息列表。必须经 observableList 委托持有,vfor 才能收到增删通知。 */
    val messages: ObservableList<ChatMessage> by observableList()

    /** 已用增量流过正文的 turn:step,避免最终 assistant.message 重复拼接。 */
    private val streamedSteps = mutableSetOf<String>()

    /** 当前正在流式输出的助手消息(同一 turn 内增量追加)。 */
    private var streamingMessage: ChatMessage? = null

    private val runningTools = mutableMapOf<String, String>()
    private val finishedTools = mutableListOf<String>()

    fun beginTurn() {
        turnState = RunState.RUNNING
        error = ""
        reasoningText = ""
        toolSummary = ""
        todoText = ""
        planText = ""
        pendingApproval = null
        streamedSteps.clear()
        runningTools.clear()
        finishedTools.clear()
        streamingMessage = null
    }

    fun onEvent(data: JSONObject) {
        when (data.optString("kind")) {
            DSHProtocol.EVT_SESSION_USER_MESSAGE -> {
                appendMessage("user", data.optString("text"))
            }
            DSHProtocol.EVT_ASSISTANT_MESSAGE -> onAssistantMessage(data)
            DSHProtocol.EVT_ASSISTANT_CHUNK -> onAssistantChunk(data)
            DSHProtocol.EVT_ASSISTANT_REASONING -> {
                val text = data.optString("text")
                if (text.isNotEmpty()) reasoningText = if (reasoningText.isEmpty()) text else "$reasoningText\n$text"
            }
            DSHProtocol.EVT_ASSISTANT_REASONING_CHUNK -> {
                reasoningText += data.optString("text")
            }
            DSHProtocol.EVT_SESSION_TODO -> onTodo(data)
            DSHProtocol.EVT_SESSION_PLAN -> {
                planText = if (data.optBoolean("active")) "计划模式进行中" else ""
            }
            DSHProtocol.EVT_SESSION_APPROVAL -> onApproval(data)
            DSHProtocol.EVT_TOOL_CALL -> onToolCall(data)
            DSHProtocol.EVT_TOOL_RESULT -> onToolResult(data)
            DSHProtocol.EVT_SESSION_TURN -> {
                if (data.optString("phase") == "start" && turnState == RunState.IDLE) {
                    turnState = RunState.RUNNING
                }
            }
            DSHProtocol.EVT_AGENT_STATUS -> {
                if (data.optString("status") == "running" && turnState == RunState.IDLE) {
                    turnState = RunState.RUNNING
                }
            }
            DSHProtocol.EVT_AGENT_ERROR -> {
                error = data.optString("message")
                turnState = RunState.FAILED
            }
            else -> Unit // 未知 kind 忽略(前向兼容)
        }
    }

    /** session.history 的落盘历史,整体替换消息列表。 */
    fun replaceAllHistory(history: JSONArray?) {
        streamingMessage = null
        messages.clear()
        if (history == null) return
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val text = m.optString("text")
            if (role != "user" && role != "assistant") continue
            if (text.startsWith("<system-reminder")) continue // 宿主注入的上下文,过滤
            messages.add(ChatMessage(role).also { it.text = text })
        }
    }

    fun onTurnResult(res: DSHRequestResult) {
        streamingMessage = null
        if (!res.ok) {
            turnState = RunState.FAILED
            error = if (res.message.isNotEmpty()) "${res.code}: ${res.message}" else res.code
            return
        }
        when (res.data?.optString("status") ?: "") {
            DSHProtocol.STATUS_STOPPED -> turnState = RunState.STOPPED
            DSHProtocol.STATUS_FAILED -> {
                turnState = RunState.FAILED
                error = res.data?.optString("error") ?: ""
            }
            else -> {
                if (turnState != RunState.FAILED) turnState = RunState.DONE
            }
        }
    }

    private fun appendMessage(role: String, text: String) {
        if (text.isEmpty()) return
        messages.add(ChatMessage(role).also { it.text = text })
    }

    private fun onAssistantMessage(data: JSONObject) {
        if (streamedSteps.contains(stepKey(data))) {
            // 该 turn:step 已用增量流出正文,最终 message 不再重复
            streamingMessage = null
            return
        }
        streamingMessage = null
        appendMessage("assistant", data.optString("text"))
    }

    private fun onAssistantChunk(data: JSONObject) {
        if (turnState == RunState.IDLE) turnState = RunState.RUNNING
        val chunk = data.optJSONObject("chunk") ?: return
        when (chunk.optString("type")) {
            DSHProtocol.CHUNK_TEXT_DELTA -> {
                streamedSteps.add(stepKey(data))
                val target = streamingMessage ?: ChatMessage("assistant").also {
                    messages.add(it)
                    streamingMessage = it
                }
                target.text += chunk.optString("text")
            }
            else -> Unit // reasoning-delta / tool-call-delta / block-* / usage / finish / 未知:忽略
        }
    }

    private fun onToolCall(data: JSONObject) {
        val callId = data.optString("callId")
        if (callId.isEmpty() || runningTools.containsKey(callId)) return
        val label = data.optString("label")
        runningTools[callId] = if (label.isNotEmpty()) label else data.optString("name")
        rebuildToolSummary()
    }

    private fun onToolResult(data: JSONObject) {
        val callId = data.optString("callId")
        val name = runningTools.remove(callId) ?: ""
        val ok = data.optBoolean("ok")
        finishedTools.add("• $name ${if (ok) "完成" else "失败"}")
        rebuildToolSummary()
    }

    private fun onTodo(data: JSONObject) {
        val todos = data.optJSONArray("todos")
        if (todos == null) {
            todoText = ""
            return
        }
        val lines = mutableListOf<String>()
        for (i in 0 until todos.length()) {
            val o = todos.optJSONObject(i) ?: continue
            val mark = when (o.optString("status")) {
                "completed" -> "✓"
                "in_progress" -> "…"
                else -> "○"
            }
            lines.add("$mark ${o.optString("content")}")
        }
        todoText = lines.joinToString("\n")
    }

    private fun onApproval(data: JSONObject) {
        val pendingId = data.optString("pendingId")
        if (pendingId.isEmpty()) return
        pendingApproval = ApprovalRequest(
            pendingId = pendingId,
            toolName = data.optString("toolName"),
            callId = data.optString("callId"),
            reason = data.optString("reason"),
        )
    }

    private fun rebuildToolSummary() {
        val lines = mutableListOf<String>()
        finishedTools.forEach { lines.add(it) }
        runningTools.values.forEach { lines.add("• $it 运行中") }
        toolSummary = lines.joinToString("\n")
    }

    private fun stepKey(data: JSONObject): String = "${data.optInt("turn")}:${data.optInt("step")}"
}

/**
 * session.list 返回的会话摘要(纯数据,刷新时整体重建)。
 */
internal class SessionSummaryUi(
    val sessionId: String,
    val title: String,
    val source: String,
    val cwd: String,
    val lastMessage: String,
    val messageCount: Int,
    val live: Boolean,
    val deleted: Boolean,
)

/**
 * workspace.list 返回的工作区(纯数据)。
 */
internal class WorkspaceUi(
    val workspaceId: String,
    val path: String,
    val title: String,
    val sessionIds: List<String>,
)

/**
 * dsh-connect 会话/运行引擎(commonMain)。
 *
 * 通过 [DSHManager] 全局单例持有:连接跨页面存活,页面出现时 attach 当前模块。
 * 同时承载两条主干:agent.run(一次性)与 session.*(多轮)。
 */
internal class DSHEngine(private val client: DSHClient) {

    val state: DSHState
        get() = client.state

    /** 一次性任务列表(可观测,配合 vfor 动态增删)。 */
    val sessions: ObservableList<RunSession> by observableList()

    /** 多轮会话表:sessionId → ChatSession。 */
    private val chatSessions = mutableMapOf<String, ChatSession>()

    /** 已发出 agent.run、res 未回、taskId 尚未绑定的会话(按提交顺序)。 */
    private val awaiting = mutableListOf<RunSession>()

    /** taskId → 会话。 */
    private val byTaskId = mutableMapOf<String, RunSession>()

    /** 连接状态变化。 */
    var onConnectionChanged: ((ConnectState) -> Unit)? = null

    /** 告警/提示(帧级错误、发送失败、未知帧等)。 */
    var onNotice: ((String) -> Unit)? = null

    init {
        client.addTopicListener { push, data -> onTopic(push, data) }
        client.onFrameError = { code, message -> onNotice?.invoke("帧级错误 $code:$message") }
        client.onWarning = { message -> onNotice?.invoke(message) }
        client.onStateChanged = { next -> onConnectionChanged?.invoke(next) }
    }

    /** 页面出现:换上当前页面的模块;已连接时只切事件通道,不重连。 */
    fun attach(module: DSHWebSocketModule) {
        client.attach(module)
    }

    /** 页面销毁:让出事件通道,连接保持。 */
    fun detach() {
        client.detach()
    }

    fun connect(rawUrl: String) {
        client.connect(dshWebSocketUrl(rawUrl))
    }

    fun close() {
        client.close()
    }

    fun isConnected(): Boolean = client.state.connectState == ConnectState.CONNECTED

    // ── 一次性任务(agent.run,无状态) ─────────────────────────────────────────

    fun runAgent(prompt: String, cwd: String? = null, chunks: Boolean = true): RunSession? {
        if (prompt.isEmpty()) {
            onNotice?.invoke("prompt 不能为空")
            return null
        }
        if (!isConnected()) {
            onNotice?.invoke("未连接,无法提交任务")
            return null
        }
        val session = RunSession(prompt, chunks)
        sessions.add(session)
        awaiting.add(session)
        val payload = JSONObject()
        payload.put("prompt", prompt)
        if (!cwd.isNullOrEmpty()) {
            payload.put("cwd", cwd)
        }
        payload.put("chunks", chunks)
        session.requestId = client.sendRequest(DSHProtocol.CODE_AGENT_RUN, payload) { res ->
            awaiting.remove(session)
            finishRunSession(session, res)
        }
        return session
    }

    fun stopRun(session: RunSession) {
        if (session.taskId.isEmpty()) {
            onNotice?.invoke("任务还没有 taskId,无法停止")
            return
        }
        val payload = JSONObject()
        payload.put("taskId", session.taskId)
        client.sendRequest(DSHProtocol.CODE_AGENT_STOP, payload) { res ->
            if (!res.ok) {
                onNotice?.invoke("停止失败:${res.code} ${res.message}")
            }
        }
    }

    private fun finishRunSession(session: RunSession, res: DSHRequestResult) {
        if (!res.ok) {
            session.state = RunState.FAILED
            session.error = if (res.message.isNotEmpty()) "${res.code}: ${res.message}" else res.code
            return
        }
        val data = res.data
        val taskId = data?.optString("taskId") ?: ""
        if (taskId.isNotEmpty()) {
            session.taskId = taskId
            byTaskId[taskId] = session
        }
        session.durationMs = data?.optLong("durationMs") ?: 0L
        when (data?.optString("status") ?: "") {
            DSHProtocol.STATUS_STOPPED -> session.state = RunState.STOPPED
            DSHProtocol.STATUS_FAILED -> {
                session.state = RunState.FAILED
                session.error = data?.optString("error") ?: ""
            }
            else -> {
                if (session.state != RunState.FAILED) session.state = RunState.DONE
            }
        }
    }

    // ── 多轮会话(session.*,有状态) ───────────────────────────────────────────

    /** 取已有会话模型(没有则创建空壳,等 history 填充)。 */
    fun ensureChatSession(sessionId: String, title: String): ChatSession =
        chatSessions.getOrPut(sessionId) { ChatSession(sessionId, title) }

    fun chatSession(sessionId: String): ChatSession? = chatSessions[sessionId]

    /** session.create:发起连接自动订阅 session:<id>;可复用宿主已有会话、可指定工作区 cwd。 */
    fun createSession(
        title: String?,
        sessionId: String? = null,
        cwd: String? = null,
        onResult: (ChatSession?) -> Unit,
    ) {
        if (!isConnected()) {
            onNotice?.invoke("未连接,无法创建会话")
            onResult(null)
            return
        }
        val payload = JSONObject()
        if (!sessionId.isNullOrEmpty()) {
            payload.put("sessionId", sessionId)
        }
        if (!title.isNullOrEmpty()) {
            payload.put("title", title)
        }
        if (!cwd.isNullOrEmpty()) {
            payload.put("cwd", cwd)
        }
        client.sendRequest(DSHProtocol.CODE_SESSION_CREATE, payload) { res ->
            if (!res.ok) {
                onNotice?.invoke("创建会话失败:${res.code} ${res.message}")
                onResult(null)
                return@sendRequest
            }
            val data = res.data
            val id = data?.optString("sessionId") ?: ""
            if (id.isEmpty()) {
                onNotice?.invoke("创建会话失败:响应缺少 sessionId")
                onResult(null)
                return@sendRequest
            }
            val session = ChatSession(id, data?.optString("title") ?: "")
            chatSessions[id] = session
            onResult(session)
        }
    }

    /** session.list。 */
    fun listSessions(includeDeleted: Boolean = false, onResult: (List<SessionSummaryUi>) -> Unit) {
        if (!isConnected()) {
            onNotice?.invoke("未连接,无法获取会话列表")
            onResult(emptyList())
            return
        }
        val payload = JSONObject()
        if (includeDeleted) {
            payload.put("includeDeleted", true)
        }
        client.sendRequest(DSHProtocol.CODE_SESSION_LIST, payload) { res ->
            if (!res.ok) {
                onNotice?.invoke("会话列表失败:${res.code} ${res.message}")
                onResult(emptyList())
                return@sendRequest
            }
            val array = res.data?.optJSONArray("sessions")
            val result = mutableListOf<SessionSummaryUi>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    result.add(
                        SessionSummaryUi(
                            sessionId = o.optString("sessionId"),
                            title = o.optString("title"),
                            source = o.optString("source").ifEmpty { "host" },
                            cwd = o.optString("cwd"),
                            lastMessage = o.optString("lastMessage"),
                            messageCount = o.optInt("messageCount"),
                            live = o.optBoolean("live"),
                            deleted = o.optBoolean("deleted"),
                        )
                    )
                }
            }
            onResult(result)
        }
    }

    /** session.history:整体替换会话内消息。 */
    fun loadHistory(sessionId: String, limit: Int = 200, onDone: (() -> Unit)? = null) {
        val session = chatSessions[sessionId]
        if (session == null || !isConnected()) {
            onDone?.invoke()
            return
        }
        val payload = JSONObject()
        payload.put("sessionId", sessionId)
        if (limit > 0) {
            payload.put("limit", limit)
        }
        client.sendRequest(DSHProtocol.CODE_SESSION_HISTORY, payload) { res ->
            if (!res.ok) {
                onNotice?.invoke("历史加载失败:${res.code} ${res.message}")
                onDone?.invoke()
                return@sendRequest
            }
            session.replaceAllHistory(res.data?.optJSONArray("messages"))
            onDone?.invoke()
        }
    }

    /**
     * session.send:发一轮消息。用户输入由服务端以 session.user-message 回显,
     * 助手回复经 assistant.chunk / assistant.message 流入会话模型。
     */
    fun send(sessionId: String, prompt: String, chunks: Boolean = true): Boolean {
        val session = chatSessions[sessionId]
        if (session == null) {
            onNotice?.invoke("会话不存在")
            return false
        }
        if (prompt.isEmpty()) {
            onNotice?.invoke("消息不能为空")
            return false
        }
        if (!isConnected()) {
            onNotice?.invoke("未连接,无法发送")
            return false
        }
        if (session.turnState == RunState.RUNNING) {
            onNotice?.invoke("当前轮还在进行中,请先停止或等待")
            return false
        }
        session.beginTurn()
        val payload = JSONObject()
        payload.put("sessionId", sessionId)
        payload.put("prompt", prompt)
        payload.put("chunks", chunks)
        client.sendRequest(DSHProtocol.CODE_SESSION_SEND, payload) { res ->
            session.onTurnResult(res)
        }
        return true
    }

    /** session.stop:只打断当前轮,保留会话。 */
    fun stopSession(sessionId: String) {
        if (!isConnected()) return
        val payload = JSONObject()
        payload.put("sessionId", sessionId)
        client.sendRequest(DSHProtocol.CODE_SESSION_STOP, payload) { res ->
            if (!res.ok) {
                onNotice?.invoke("停止失败:${res.code} ${res.message}")
            }
        }
    }

    /** session.delete:软删。 */
    fun deleteSession(sessionId: String, onResult: ((Boolean) -> Unit)? = null) {
        if (!isConnected()) {
            onResult?.invoke(false)
            return
        }
        val payload = JSONObject()
        payload.put("sessionId", sessionId)
        client.sendRequest(DSHProtocol.CODE_SESSION_DELETE, payload) { res ->
            if (res.ok) {
                chatSessions.remove(sessionId)
            } else {
                onNotice?.invoke("删除失败:${res.code} ${res.message}")
            }
            onResult?.invoke(res.ok)
        }
    }

    /** workspace.list:拉工作区列表。 */
    fun listWorkspaces(includeDeleted: Boolean = false, onResult: (List<WorkspaceUi>) -> Unit) {
        if (!isConnected()) {
            onNotice?.invoke("未连接,无法获取工作区")
            onResult(emptyList())
            return
        }
        val payload = JSONObject()
        if (includeDeleted) {
            payload.put("includeDeleted", true)
        }
        client.sendRequest(DSHProtocol.CODE_WORKSPACE_LIST, payload) { res ->
            if (!res.ok) {
                onNotice?.invoke("工作区列表失败:${res.code} ${res.message}")
                onResult(emptyList())
                return@sendRequest
            }
            val array = res.data?.optJSONArray("workspaces")
            val result = mutableListOf<WorkspaceUi>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val sessionIds = mutableListOf<String>()
                    val ids = o.optJSONArray("sessionIds")
                    if (ids != null) {
                        for (j in 0 until ids.length()) {
                            val id = ids.optString(j) ?: ""
                            if (id.isNotEmpty()) sessionIds.add(id)
                        }
                    }
                    result.add(
                        WorkspaceUi(
                            workspaceId = o.optString("workspaceId"),
                            path = o.optString("path"),
                            title = o.optString("title").ifEmpty { o.optString("path") },
                            sessionIds = sessionIds,
                        )
                    )
                }
            }
            onResult(result)
        }
    }

    /** approval.respond:回复权限请求。 */
    fun respondApproval(sessionId: String, pendingId: String, allow: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        if (!isConnected()) {
            onResult?.invoke(false)
            return
        }
        val payload = JSONObject()
        payload.put("sessionId", sessionId)
        payload.put("pendingId", pendingId)
        payload.put("allow", allow)
        client.sendRequest(DSHProtocol.CODE_APPROVAL_RESPOND, payload) { res ->
            if (res.ok) {
                chatSessions[sessionId]?.pendingApproval = null
            } else if (res.code != DSHProtocol.ERR_APPROVAL_NOT_FOUND) {
                // approval.not.found = 已被别人回复/超时,忽略即可
                onNotice?.invoke("权限回复失败:${res.code} ${res.message}")
            }
            onResult?.invoke(res.ok)
        }
    }

    // ── 事件路由 ─────────────────────────────────────────────────────────────

    private fun onTopic(push: String, data: JSONObject) {
        when {
            push.startsWith(DSHProtocol.TOPIC_SESSION_PREFIX) -> {
                val sessionId = push.removePrefix(DSHProtocol.TOPIC_SESSION_PREFIX)
                chatSessions[sessionId]?.onEvent(data)
            }
            push.startsWith(DSHProtocol.TOPIC_TASK_PREFIX) -> {
                onTaskTopic(push, data)
            }
        }
    }

    private fun onTaskTopic(push: String, data: JSONObject) {
        val taskId = push.removePrefix(DSHProtocol.TOPIC_TASK_PREFIX)
        var session = byTaskId[taskId]
        if (session == null) {
            // evt 先于 res 到达:按提交顺序把新主题绑定到最早未绑定的 run
            val candidate = awaiting.firstOrNull { it.taskId.isEmpty() } ?: return
            candidate.taskId = taskId
            byTaskId[taskId] = candidate
            session = candidate
        }
        session.onEvent(data)
    }
}

/**
 * 全局引擎单例:连接与协议状态跨页面存活(页面只负责 attach 各自的传输模块)。
 */
internal object DSHManager {
    val engine: DSHEngine by lazy { DSHEngine(DSHClient()) }
}
