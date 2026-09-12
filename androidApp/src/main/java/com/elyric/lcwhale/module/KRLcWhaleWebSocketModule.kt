package com.elyric.lcwhale.module

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.ArrayDeque

/**
 * dsh-connect 的 Android 薄传输层。
 *
 * 只负责:建连、写文本帧、把入站文本帧与连接状态归一成事件回传 JS。
 * 不解析线协议 —— 帧语义、req↔res 关联、订阅等全部在 commonMain(JS 侧)完成。
 *
 * 关键点:Kuikly 的 Module 是「页面级」的(每个 Activity 一个实例),而 dsh 连接要跨页面存活,
 * 因此真正的连接状态放在 [Transport](进程内单例),各页面模块实例只是对它的薄门面。
 * 同一 URL 的 connect 幂等:已连接时只把事件回调切到当前页面,不重连。
 *
 * 上行事件信封(每次 callback.invoke 一个 map):
 *   {type:"open"} / {type:"message", text} / {type:"error", code, message} / {type:"closed", code, message}
 */
class KRLcWhaleWebSocketModule : KuiklyRenderBaseModule() {

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "connectWS" -> {
                Transport.connect(params, callback, this)
                null
            }

            "sendText" -> {
                Transport.sendText(params, callback)
                null
            }

            "closeWS" -> {
                Transport.close()
                null
            }

            "detachWS" -> {
                Transport.detach(this)
                null
            }

            else -> callback?.invoke(mapOf("code" to "method.not.found", "message" to "方法不存在:$method"))
        }
    }

    companion object {
        const val MODULE_NAME = "dsh_websocket"
    }
}

/**
 * 进程内共享的连接状态。所有状态只在主线程读写。
 */
private object Transport {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    /** 当前持有事件回调的模块实例(页面销毁时据此判断是否让出回调)。 */
    @Volatile
    private var upstream: KuiklyRenderCallback? = null
    private var upstreamOwner: KRLcWhaleWebSocketModule? = null

    private var webSocket: WebSocket? = null
    private var currentUrl: String = ""
    private val outbox = ArrayDeque<String>()

    private var lastInboundAt = 0L
    private var pingSentAt = 0L
    private var awaitingPong = false

    /** 应用层保活:空闲时发 JSON ping,若 pong 未回归则判链路不可用。 */
    private val pingTask = object : Runnable {
        override fun run() {
            val ws = webSocket ?: return
            val now = System.currentTimeMillis()
            if (awaitingPong && now - pingSentAt > PING_TIMEOUT_MS) {
                emit(mapOf("type" to "error", "code" to "ping.timeout", "message" to "pong 未响应"))
                ws.cancel()
                return
            }
            if (now - lastInboundAt >= PING_IDLE_MS) {
                pingSentAt = now
                awaitingPong = true
                ws.send(DSH_PING_PAYLOAD)
            }
            mainHandler.postDelayed(this, PING_TICK_MS)
        }
    }

    fun connect(url: String?, callback: KuiklyRenderCallback?, owner: KRLcWhaleWebSocketModule) {
        if (url.isNullOrEmpty()) {
            callback?.invoke(mapOf("type" to "error", "code" to "bad.request", "message" to "url 为空"))
            return
        }
        // 幂等:已连到同一 URL → 只把事件通道切到当前页面
        if (webSocket != null && url == currentUrl) {
            upstream = callback
            upstreamOwner = owner
            return
        }
        Log.i(TAG, "connect $url")
        upstream = callback
        upstreamOwner = owner
        currentUrl = url
        webSocket?.cancel()
        webSocket = null
        outbox.clear()
        lastInboundAt = System.currentTimeMillis()
        awaitingPong = false

        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            emit(mapOf("type" to "error", "code" to "bad.request", "message" to "非法 url:$url"))
            return
        }
        webSocket = httpClient.newWebSocket(request, listener)
        mainHandler.removeCallbacks(pingTask)
        mainHandler.postDelayed(pingTask, PING_TICK_MS)
    }

    fun sendText(text: String?, callback: KuiklyRenderCallback?) {
        if (text == null) {
            callback?.invoke(mapOf("ok" to false, "code" to "bad.request"))
            return
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) {
            callback?.invoke(mapOf("ok" to false, "code" to "bad.size"))
            return
        }
        val ws = webSocket
        if (ws == null) {
            if (outbox.size >= MAX_OUTBOX) {
                outbox.removeFirst()
            }
            outbox.addLast(text)
            callback?.invoke(mapOf("ok" to true))
            return
        }
        val sent = ws.send(text)
        callback?.invoke(mapOf("ok" to sent, "code" to if (sent) "" else "send.failed"))
    }

    fun close() {
        mainHandler.removeCallbacks(pingTask)
        awaitingPong = false
        outbox.clear()
        webSocket?.close(1000, "client closing")
        webSocket = null
        currentUrl = ""
        upstream = null
        upstreamOwner = null
    }

    fun detach(owner: KRLcWhaleWebSocketModule) {
        // 只有当前持有回调的页面销毁时才让出,避免误清其它页面的回调
        if (upstreamOwner === owner) {
            upstream = null
            upstreamOwner = null
        }
    }

    // ── OkHttp 回调(非主线程,统一 post 回主线程) ─────────────────────────────

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            runOnMain {
                this@Transport.webSocket = webSocket
                lastInboundAt = System.currentTimeMillis()
                awaitingPong = false
                while (outbox.isNotEmpty()) {
                    webSocket.send(outbox.removeFirst())
                }
                emit(mapOf("type" to "open"))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runOnMain {
                lastInboundAt = System.currentTimeMillis()
                awaitingPong = false
                emit(mapOf("type" to "message", "text" to text))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            runOnMain {
                if (this@Transport.webSocket === webSocket) {
                    this@Transport.webSocket = null
                }
                stopPing()
                outbox.clear()
                emit(mapOf("type" to "closed", "code" to code, "message" to reason))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            runOnMain {
                if (this@Transport.webSocket === webSocket) {
                    this@Transport.webSocket = null
                }
                stopPing()
                outbox.clear()
                emit(
                    mapOf(
                        "type" to "error",
                        "code" to "ws.failure",
                        "message" to (t.message ?: t.javaClass.simpleName),
                    )
                )
            }
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    private fun stopPing() {
        mainHandler.removeCallbacks(pingTask)
        awaitingPong = false
    }

    private fun emit(event: Map<String, Any?>) {
        val callback = upstream ?: return
        Log.i(TAG, "↑ ${event["type"]} ${event["code"] ?: ""} ${event["message"] ?: ""}")
        runOnMain { callback.invoke(event) }
    }

    private const val TAG = "DSHWebSocket"
    private const val MAX_FRAME_BYTES = 256 * 1024
    private const val MAX_OUTBOX = 64
    private const val DSH_PING_PAYLOAD = "{\"v\":1,\"kind\":\"ping\"}"
    private const val PING_IDLE_MS = 30_000L
    private const val PING_TICK_MS = 5_000L
    private const val PING_TIMEOUT_MS = 10_000L
}
