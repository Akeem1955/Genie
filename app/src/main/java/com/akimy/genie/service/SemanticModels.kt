package com.akimy.genie.service

import android.graphics.Rect

data class SemanticRangeInfo(
    val current: Float,
    val min: Float,
    val max: Float,
    val type: Int?,
)

data class SemanticCollectionItemInfo(
    val rowIndex: Int?,
    val rowCount: Int?,
    val columnIndex: Int?,
    val columnCount: Int?,
)

data class SemanticNode(
    val id: String,
    val packageName: String,
    val className: String?,
    val role: String,
    val text: String?,
    val contentDescription: String?,
    val hintText: String?,
    val stateDescription: String?,
    val errorText: String?,
    val paneTitle: String?,
    val label: String,
    val isVisibleToUser: Boolean,
    val isAccessibilityFocused: Boolean,
    val isInputFocused: Boolean,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isEnabled: Boolean,
    val isSelected: Boolean,
    val isChecked: Boolean?,
    val isHeading: Boolean,
    val isPassword: Boolean,
    val availableActions: List<String>,
    val rangeInfo: SemanticRangeInfo?,
    val collectionItemInfo: SemanticCollectionItemInfo?,
    val boundsInScreen: Rect?,
)

data class SemanticScreenSnapshot(
    val packageName: String,
    val focusedNode: SemanticNode?,
    val visibleNodes: List<SemanticNode>,
    val actionableNodes: List<SemanticNode>,
    val headings: List<SemanticNode>,
    val formFields: List<SemanticNode>,
    val toggles: List<SemanticNode>,
    val lists: List<SemanticNode>,
    val tabs: List<SemanticNode>,
    val dialogs: List<SemanticNode>,
    val panes: List<String>,
    val keyboardVisible: Boolean,
    val summary: String,
) {
    companion object {
        fun empty(): SemanticScreenSnapshot = SemanticScreenSnapshot(
            packageName = "",
            focusedNode = null,
            visibleNodes = emptyList(),
            actionableNodes = emptyList(),
            headings = emptyList(),
            formFields = emptyList(),
            toggles = emptyList(),
            lists = emptyList(),
            tabs = emptyList(),
            dialogs = emptyList(),
            panes = emptyList(),
            keyboardVisible = false,
            summary = "Screen unavailable.",
        )
    }
}

data class SemanticChange(
    val category: String,
    val summary: String,
    val nodeId: String? = null,
)

data class SemanticScreenDiff(
    val previousPackageName: String,
    val currentPackageName: String,
    val changes: List<SemanticChange>,
    val summary: String,
) {
    val hasChanges: Boolean
        get() = changes.isNotEmpty()

    companion object {
        fun empty(): SemanticScreenDiff = SemanticScreenDiff(
            previousPackageName = "",
            currentPackageName = "",
            changes = emptyList(),
            summary = "No meaningful screen changes detected yet.",
        )
    }
}

data class AwarenessEventRecord(
    val timestampMs: Long,
    val type: String,
    val summary: String,
)
