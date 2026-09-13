package com.elyric.lcwhale.dsh

import com.elyric.lcwhale.foundation.module.DSHWebSocketModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 一条 res 的归一化结果。
 */
internal class DSHRequestResult(
    val ok: Boolean,
    val data: JSONObject?,
    val code: String,
    val message: String,
)

/**
 * dsh-connect 线协议引擎(commonMain)。
 *
 * 只做协议层的事:帧编解码、req↔res 按 id 派发、evt 按 push 路由、错误上报。
 * 不关心业务语义,由 [DSHEngine] 消费。
 *
 * 模块实例是页面级的(Kuikly 每页一个 Module),连接却要跨页面存活,
 * 所以 [attach] 在页面出现时换上当前页面的模块 —— 原生侧对同一 URL 幂等,
 * 只切换事件回调,不会重连。
 */
internal class DSHClient {

    val state = DSHState()

    /** 当前页面提供的传输模块。 */
    private var module: DSHWebSocketModule? = null

    private var seq = 0

    /** reqId/subId → 等待者。res 可乱序返回,必须按 id 派发。 */
    private val pending = mutableMapOf<String, (DSHRequestResult) -> Unit>()

    /** 推送订阅者:(push 推送码, evt.data)。 */
    private val topicListeners = mutableListOf<(String, JSONObject) -> Unit>()

    /** 帧级 err(如 bad.frame/bad.size);连接不断,仅上报。 */
    var onFrameError: ((code: String, message: String) -> Unit)? = null

    /** 前向兼容的告警(未知帧/未知 kind/发送失败等)。 */
    var onWarning: ((message: String) -> Unit)? = null

    var onStateChanged: ((ConnectState) -> Unit)? = null

    /**
     * 页面出现时调用:换上当前页面的模块。
     * 若连接已建立,原生侧只把事件通道切到本页,不重连。
     */
    fun attach(module: DSHWebSocketModule) {
        this.module = module
        val current = state.connectState
        if (current == ConnectState.CONNECTING || current == ConnectState.CONNECTED) {
            module.connectWS(state.url) { envelope -> handleEnvelope(envelope) }
        }
    }

    /** 页面销毁时调用:让出事件通道,连接保持。 */
    fun detach(owner: DSHWebSocketModule) {
        // A page can be destroyed after the next page has already attached.
        // Never clear the newer page's transport in that case.
        if (module === owner) {
            owner.detachWS()
            module = null
        }
    }

    fun connect(url: String) {
        val m = module ?: return
        val current = state.connectState
        if (current == ConnectState.CONNECTING || current == ConnectState.CONNECTED) {
            return
        }
        state.url = url
        state.lastError = ""
        setState(ConnectState.CONNECTING)
        m.connectWS(url) { envelope -> handleEnvelope(envelope) }
    }

    fun close() {
        module?.closeWS()
        failAllPending(DSHProtocol.ERR_CONNECTION_CLOSED, "连接已关闭")
        setState(ConnectState.DISCONNECTED)
    }

    fun addTopicListener(listener: (push: String, data: JSONObject) -> Unit) {
        topicListeners.add(listener)
    }

    fun removeTopicListener(listener: (push: String, data: JSONObject) -> Unit) {
        topicListeners.remove(listener)
    }

    /**
     * 发一个 req,返回本次请求 id。res 到达时(无论成败)回调 [onRes]。
     * 不设超时:agent.run / session.send 可能跑很久,交给上层/用户决定。
     */
    fun sendRequest(code: String, payload: JSONObject?, onRes: (DSHRequestResult) -> Unit): String {
        val id = "r${++seq}"
        println("[DSH_TRACE] send req id=$id code=$code payload=${payload?.toString()?.take(240)}")
        pending[id] = onRes
        if (!sendFrame(DSHProtocol.req(id, code, payload))) {
            pending.remove(id)
            onRes(DSHRequestResult(false, null, DSHProtocol.ERR_CONNECTION_CLOSED, "页面未 attach,无法发送"))
        }
        return id
    }

    fun subscribe(add: List<String>? = null, remove: List<String>? = null, onAck: ((Boolean) -> Unit)? = null) {
        val id = if (onAck != null) "s${++seq}" else null
        if (id != null) {
            pending[id] = { res -> onAck?.invoke(res.ok) }
        }
        sendFrame(DSHProtocol.sub(id, add, remove))
    }

    fun sendPing() {
        sendFrame(DSHProtocol.ping())
    }

    private fun sendFrame(frame: JSONObject): Boolean {
        val m = module ?: run {
            warn("页面未 attach,无法发送")
            return false
        }
        val text = frame.toString()
        if (text.encodeToByteArray().size > DSHProtocol.MAX_FRAME_BYTES) {
            warn("帧超过 ${DSHProtocol.MAX_FRAME_BYTES} 字节上限,已丢弃")
            return false
        }
        m.sendText(text) { ack ->
            if (ack?.optBoolean("ok") == false) {
                warn("发送失败:${ack.optString("code", DSHProtocol.ERR_SEND_FAILED)}")
            }
        }
        return true
    }

    // ── 原生上行事件 ─────────────────────────────────────────────────────────

    private fun handleEnvelope(envelope: JSONObject?) {
        val type = envelope?.optString("type") ?: return
        when (type) {
            "open" -> setState(ConnectState.CONNECTED)
            "message" -> {
                val text = envelope.optString("text")
                if (text.isNotEmpty()) {
                    handleText(text)
                }
            }
            "error" -> {
                val code = envelope.optString("code")
                val message = envelope.optString("message")
                state.lastError = if (code.isEmpty()) message else "$code: $message"
                failAllPending(code.ifEmpty { DSHProtocol.ERR_CONNECTION_CLOSED }, message)
                setState(ConnectState.DISCONNECTED)
            }
            "closed" -> {
                failAllPending(DSHProtocol.ERR_CONNECTION_CLOSED, "连接已断开")
                setState(ConnectState.DISCONNECTED)
            }
            else -> warn("未知桥事件 type=$type")
        }
    }

    // ── 线协议帧 ─────────────────────────────────────────────────────────────

    private fun handleText(text: String) {
        val frame = runCatching { JSONObject(text) }.getOrNull()
        if (frame == null) {
            warn("${DSHProtocol.ERR_BAD_FRAME}:无法解析的文本帧,已忽略")
            return
        }
        when (frame.optString("kind")) {
            DSHProtocol.KIND_RES -> dispatchRes(frame)
            DSHProtocol.KIND_EVT -> dispatchEvt(frame)
            DSHProtocol.KIND_ERR -> onFrameError?.invoke(
                frame.optString("code"),
                frame.optString("message"),
            )
            DSHProtocol.KIND_PONG -> Unit // 保活应答
            else -> warn("未知帧 kind=${frame.optString("kind")},已忽略(前向兼容)")
        }
    }

    private fun dispatchRes(frame: JSONObject) {
        val id = frame.optString("id")
        println("[DSH_TRACE] recv res id=$id ok=${frame.optBoolean("ok")} code=${frame.optString("code")}")
        val waiter = pending.remove(id) ?: return
        waiter(
            DSHRequestResult(
                ok = frame.optBoolean("ok"),
                data = frame.optJSONObject("data"),
                code = frame.optString("code"),
                message = frame.optString("message"),
            )
        )
    }

    private fun dispatchEvt(frame: JSONObject) {
        val push = frame.optString("push")
        val data = frame.optJSONObject("data") ?: return
        val kind = data.optString("kind")
        println("[DSH_TRACE] recv evt push=$push kind=$kind textLen=${data.optString("text").length}")
        topicListeners.toList().forEach { it(push, data) }
    }

    private fun failAllPending(code: String, message: String) {
        if (pending.isEmpty()) return
        val waiters = pending.values.toList()
        pending.clear()
        waiters.forEach {
            it(DSHRequestResult(false, null, code, message))
        }
    }

    private fun setState(next: ConnectState) {
        if (state.connectState == next) return
        state.connectState = next
        onStateChanged?.invoke(next)
    }

    private fun warn(message: String) {
        onWarning?.invoke(message)
    }
}
