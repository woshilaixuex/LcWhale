package com.elyric.lcwhale.dsh

/**
 * 链接状态
 */
enum class ConnectState{
    IDEA,
    CONNECTING
}

/**
 * 存储所有链接状态
 *
 * connectState 链接状态
 *
 */
class DSHState {
    var connectState = ConnectState.IDEA
}