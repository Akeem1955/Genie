package com.akimy.genie.ui

sealed class AgentUIState {
    data object Initializing : AgentUIState()
    data object Idle : AgentUIState()
    data object Waking : AgentUIState()
    data object Listening : AgentUIState()
    data object Thinking : AgentUIState()
    data class Executing(val title: String, val subtitle: String? = null) : AgentUIState()
    data class Speaking(val text: String) : AgentUIState()
}
