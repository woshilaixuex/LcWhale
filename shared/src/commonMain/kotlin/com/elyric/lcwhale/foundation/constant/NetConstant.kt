package com.elyric.lcwhale.foundation.constant

object NetConstant {
    object WSConst {
        /**
         * dsh-connect 代码默认 8097,但部署常被插件根 .env 的 DSH_CONNECT_PORT 覆盖,
         * 以服务端日志 `ws server listening on <host>:<port>` 为准。本机实测为 8080。
         */
        const val DEFAULT_PORT = 8080

        /**
         * 占位默认地址,实际以页面输入为准(输入框可改,且会记住上次输入)。
         *
         * 127.0.0.1:8080 配合 `adb reverse tcp:8080 tcp:8080` 走 USB 最稳;
         * 走 WiFi 时改成 PC 的局域网地址(如本机 WLAN 192.168.17.49:8080)。
         */
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_URL = "ws://$DEFAULT_HOST:$DEFAULT_PORT"

        /** 记住上次使用的地址 */
        const val PREF_KEY_LAST_URL = "dsh_last_ws_url"

        const val PING_PAYLOAD = "{\"v\":1,\"kind\":\"ping\"}"
    }
}
