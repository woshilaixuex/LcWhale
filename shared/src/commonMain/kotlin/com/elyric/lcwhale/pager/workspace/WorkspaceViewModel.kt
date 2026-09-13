package com.elyric.lcwhale.pager.workspace

import com.elyric.lcwhale.dsh.DSHEngine
import com.elyric.lcwhale.dsh.SessionSummaryUi
import com.elyric.lcwhale.dsh.WorkspaceUi
import com.tencent.kuikly.core.base.BaseObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList

internal class WorkspaceSectionUi(
    val workspace: WorkspaceUi,
    val sessions: List<SessionSummaryUi>,
    initiallyExpanded: Boolean,
) : BaseObject() {
    var expanded by observable(initiallyExpanded)
}

/** Process-wide observable workspace state, shared across page instances. */
internal object WorkspaceViewModel : BaseObject() {
    private val workspaces: ObservableList<WorkspaceUi> by observableList()
    val sessions: ObservableList<SessionSummaryUi> by observableList()
    val sections: ObservableList<WorkspaceSectionUi> by observableList()
    val unassignedSessions: ObservableList<SessionSummaryUi> by observableList()
    var loading by observable(false)
    var loaded by observable(false)
    var unassignedExpanded by observable(false)
    var unassignedCount by observable(0)

    private var refreshGeneration = 0
    private var pendingRequests = 0

    fun refresh(engine: DSHEngine) {
        if (!engine.isConnected()) return
        val generation = ++refreshGeneration
        pendingRequests = 2
        loading = true
        engine.listSessions { list ->
            if (generation != refreshGeneration) return@listSessions
            sessions.clear()
            sessions.addAll(list.filter { !it.deleted })
            rebuildSections()
            finishRequest(generation)
        }
        engine.listWorkspaces { list ->
            if (generation != refreshGeneration) return@listWorkspaces
            workspaces.clear()
            workspaces.addAll(list)
            rebuildSections()
            finishRequest(generation)
        }
    }

    fun clear() {
        refreshGeneration += 1
        pendingRequests = 0
        loading = false
        loaded = false
        workspaces.clear()
        sessions.clear()
        sections.clear()
        unassignedSessions.clear()
        unassignedCount = 0
        unassignedExpanded = false
    }

    private fun rebuildSections() {
        val expandedIds = sections.filter { it.expanded }.map { it.workspace.workspaceId }.toSet()
        sections.clear()
        workspaces.forEach { workspace ->
            val ids = workspace.sessionIds.toSet()
            sections.add(
                WorkspaceSectionUi(
                    workspace = workspace,
                    sessions = sessions.filter { it.sessionId in ids },
                    initiallyExpanded = workspace.workspaceId in expandedIds,
                )
            )
        }
        val assignedIds = workspaces.flatMap { it.sessionIds }.toSet()
        unassignedSessions.clear()
        if (assignedIds.isEmpty()) {
            unassignedSessions.addAll(sessions)
        } else {
            unassignedSessions.addAll(sessions.filter { it.sessionId !in assignedIds })
        }
        unassignedCount = unassignedSessions.size
        println(
            "[DSH_TRACE] workspace.vm sessions=${sessions.size} sections=${sections.size} " +
                "assigned=${assignedIds.size} unassigned=$unassignedCount"
        )
    }

    private fun finishRequest(generation: Int) {
        if (generation != refreshGeneration) return
        pendingRequests -= 1
        if (pendingRequests <= 0) {
            loading = false
            loaded = true
        }
    }
}
