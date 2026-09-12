package com.elyric.lcwhale.dsh

/**
 * 链接状态
 */
enum class ConnectState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}

/**
 * 存储所有链接状态
 *
 * connectState 链接状态
 * url          当前连接的地址
 * lastError    最近一次连接/帧级错误描述(供 UI 提示)
 */
class DSHState {
    var connectState = ConnectState.IDLE
    var url = ""
    var lastError = ""
}
