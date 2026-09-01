package com.elyric.lcwhale.foundation.module

import com.tencent.kuikly.core.module.Module

class DSHWebSocketModule: Module(){

    // 链接websocket的核心函数
    fun connectWS(onState: () -> Unit) {
        toNative(
            keepCallbackAlive = true,
            methodName = "connectWS",
            param = null,
            syncCall = false,
        )
    }


    override fun moduleName(): String {
        return  MODULE_NAME
    }
    companion object{
        const val MODULE_NAME = "dsh_websocket"
    }
}

internal fun dshWebSocketUrl(httpUrl: String): String = when {
    httpUrl.startsWith("https://") -> "wss://${httpUrl.removePrefix("https://")}"
    httpUrl.startsWith("http://") -> "ws://${httpUrl.removePrefix("http://")}"
    else -> httpUrl
}