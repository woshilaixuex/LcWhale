package com.elyric.lcwhale.foundation.module

import com.tencent.kuikly.core.module.CallbackFn
import com.tencent.kuikly.core.module.Module

class DSHWebSocketModule: Module(){

    // 链接websocket的核心函数
    fun connectWS(url: String, onEvent: CallbackFn) {
        toNative(
            keepCallbackAlive = true,
            methodName = "connectWS",
            param = url,
            callback = onEvent,
            syncCall = false,
        )
    }
    // 发一帧。frameJson = 完整线协议帧({v:1,kind:"req",...}.toString())
    // ack 可选:想确认"这帧发出去了/被拒了"就传,不关心就传 null
    fun sendText(frameJson: String, ack: CallbackFn? = null) {
        toNative(
            keepCallbackAlive = false,
            methodName = "sendText",
            param = frameJson,
            callback = ack,
            syncCall = false
        )
    }
    // 关闭连接,无参数无回调
    fun closeWS() {
        toNative(keepCallbackAlive = false, methodName = "closeWS", param = null, callback = null, syncCall = false)
    }
    // 页面销毁时让出事件通道(连接保持,由下一个出现的页面重新 attach)
    fun detachWS() {
        toNative(keepCallbackAlive = false, methodName = "detachWS", param = null, callback = null, syncCall = false)
    }

    override fun moduleName(): String {
        return  MODULE_NAME
    }
    companion object{
        const val MODULE_NAME = "dsh_websocket"
    }
}

internal fun dshWebSocketUrl(httpUrl: String): String {
    val trimmed = httpUrl.trim()
    return when {
        trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
        trimmed.startsWith("https://") -> "wss://${trimmed.removePrefix("https://")}"
        trimmed.startsWith("http://") -> "ws://${trimmed.removePrefix("http://")}"
        trimmed.isEmpty() -> trimmed
        else -> "ws://$trimmed"
    }
}