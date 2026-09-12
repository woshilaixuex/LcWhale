package com.elyric.lcwhale.dsh

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * dsh-connect 线协议 v1 常量与帧构造。
 *
 * 权威规格:.agent/skills/dsh-connect-client/references/protocol.md
 * 拼写严格照表,字段缺失一律按可选处理。
 */
internal object DSHProtocol {

    const val V = 1
    const val MAX_FRAME_BYTES = 256 * 1024
    const val MAX_FIELD_LEN = 128

    // 帧 kind
    const val KIND_REQ = "req"
    const val KIND_SUB = "sub"
    const val KIND_PING = "ping"
    const val KIND_RES = "res"
    const val KIND_EVT = "evt"
    const val KIND_ERR = "err"
    const val KIND_PONG = "pong"

    // 一次性任务接收码
    const val CODE_AGENT_RUN = "agent.run"
    const val CODE_AGENT_STOP = "agent.stop"

    // 会话族接收码(多轮,有状态)
    const val CODE_SESSION_CREATE = "session.create"
    const val CODE_SESSION_LIST = "session.list"
    const val CODE_SESSION_GET = "session.get"
    const val CODE_SESSION_HISTORY = "session.history"
    const val CODE_SESSION_SEND = "session.send"
    const val CODE_SESSION_STOP = "session.stop"
    const val CODE_SESSION_DELETE = "session.delete"

    // 工作区 / 权限申请
    const val CODE_WORKSPACE_LIST = "workspace.list"
    const val CODE_APPROVAL_RESPOND = "approval.respond"

    // 终态 status
    const val STATUS_DONE = "done"
    const val STATUS_STOPPED = "stopped"
    const val STATUS_FAILED = "failed"

    // evt.data.kind(两类主题共用)
    const val EVT_AGENT_STATUS = "agent.status"
    const val EVT_ASSISTANT_MESSAGE = "assistant.message"
    const val EVT_ASSISTANT_CHUNK = "assistant.chunk"
    const val EVT_TOOL_CALL = "tool.call"
    const val EVT_TOOL_RESULT = "tool.result"
    const val EVT_AGENT_ERROR = "agent.error"

    // 推理 / 执行结构
    const val EVT_SESSION_TURN = "session.turn"
    const val EVT_SESSION_TODO = "session.todo"
    const val EVT_SESSION_PLAN = "session.plan"
    const val EVT_ASSISTANT_REASONING = "assistant.reasoning"
    const val EVT_ASSISTANT_REASONING_CHUNK = "assistant.reasoning-chunk"

    // session:<id> 主题额外的事件:用户输入回显 / 权限请求
    const val EVT_SESSION_USER_MESSAGE = "session.user-message"
    const val EVT_SESSION_APPROVAL = "session.approval"

    // assistant.chunk.chunk.type
    const val CHUNK_TEXT_DELTA = "text-delta"
    const val CHUNK_REASONING_DELTA = "reasoning-delta"
    const val CHUNK_TOOL_CALL_DELTA = "tool-call-delta"
    const val CHUNK_BLOCK_START = "block-start"
    const val CHUNK_BLOCK_END = "block-end"
    const val CHUNK_USAGE = "usage"
    const val CHUNK_FINISH = "finish"

    // 帧级错误码(出现在 err)
    const val ERR_BAD_FRAME = "bad.frame"
    const val ERR_BAD_SIZE = "bad.size"

    // 请求级错误码(出现在 res.ok=false)
    const val ERR_UNKNOWN_CODE = "unknown.code"
    const val ERR_BAD_REQUEST = "bad.request"
    const val ERR_INTERNAL = "internal"
    const val ERR_HOST_UNAVAILABLE = "host.unavailable"
    const val ERR_AGENT_FAILED = "agent.failed"
    const val ERR_AGENT_NOT_FOUND = "agent.not.found"
    const val ERR_FORBIDDEN = "forbidden"
    const val ERR_SESSION_NOT_FOUND = "session.not.found"
    const val ERR_SESSION_BUSY = "session.busy"
    const val ERR_SESSION_RESUME_FAILED = "session.resume-failed"
    const val ERR_APPROVAL_NOT_FOUND = "approval.not.found"

    // 客户端本地错误码(不属服务端规格,用于连接/发送失败)
    const val ERR_CONNECTION_CLOSED = "connection.closed"
    const val ERR_SEND_FAILED = "send.failed"

    // 推送码前缀
    const val TOPIC_TASK_PREFIX = "task:"
    const val TOPIC_SESSION_PREFIX = "session:"

    fun req(id: String, code: String, payload: JSONObject? = null): JSONObject {
        val frame = JSONObject()
        frame.put("v", V)
        frame.put("kind", KIND_REQ)
        frame.put("id", id)
        frame.put("code", code)
        if (payload != null) {
            frame.put("payload", payload)
        }
        return frame
    }

    fun sub(id: String?, add: List<String>? = null, remove: List<String>? = null): JSONObject {
        val frame = JSONObject()
        frame.put("v", V)
        frame.put("kind", KIND_SUB)
        if (id != null) {
            frame.put("id", id)
        }
        if (add != null && add.isNotEmpty()) {
            val array = JSONArray()
            add.forEach { array.put(it) }
            frame.put("add", array)
        }
        if (remove != null && remove.isNotEmpty()) {
            val array = JSONArray()
            remove.forEach { array.put(it) }
            frame.put("remove", array)
        }
        return frame
    }

    fun ping(): JSONObject {
        val frame = JSONObject()
        frame.put("v", V)
        frame.put("kind", KIND_PING)
        return frame
    }

    fun taskTopic(taskId: String): String = "$TOPIC_TASK_PREFIX$taskId"

    fun sessionTopic(sessionId: String): String = "$TOPIC_SESSION_PREFIX$sessionId"
}
