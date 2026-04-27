package com.akimy.genie.service

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.LinkedList

private const val TAG = "GenieUINodeParser"

data class UINode(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val packageName: String,
    val hintText: String?,
    val stateDescription: String?,
    val errorText: String?,
    val paneTitle: String?,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isEnabled: Boolean,
    val isSelected: Boolean,
    val isChecked: Boolean?,
    val isHeading: Boolean,
    val isPassword: Boolean,
    val isAccessibilityFocused: Boolean,
    val isInputFocused: Boolean,
    val isVisibleToUser: Boolean,
    val inputType: Int?,
    val rangeInfo: SemanticRangeInfo?,
    val collectionItemInfo: SemanticCollectionItemInfo?,
    val availableActions: List<String>,
    val boundsInScreen: Rect?,
    val nodeInfo: AccessibilityNodeInfo,
)

object UINodeParser {

    fun parseNodeTree(root: AccessibilityNodeInfo?): List<UINode> {
        if (root == null) return emptyList()

        val nodes = mutableListOf<UINode>()
        val queue: LinkedList<AccessibilityNodeInfo> = LinkedList()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            nodes.add(
                UINode(
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    packageName = node.packageName?.toString() ?: "",
                    hintText = node.hintText?.toString(),
                    stateDescription = node.stateDescription?.toString(),
                    errorText = node.error?.toString(),
                    paneTitle = node.paneTitle?.toString(),
                    isClickable = node.isClickable,
                    isLongClickable = node.isLongClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isEnabled = node.isEnabled,
                    isSelected = node.isSelected,
                    isChecked = checkedState(node),
                    isHeading = node.isHeading,
                    isPassword = node.isPassword,
                    isAccessibilityFocused = node.isAccessibilityFocused,
                    isInputFocused = node.isFocused,
                    isVisibleToUser = node.isVisibleToUser,
                    inputType = node.inputType,
                    rangeInfo = node.rangeInfo?.let {
                        SemanticRangeInfo(
                            current = it.current,
                            min = it.min,
                            max = it.max,
                            type = it.type,
                        )
                    },
                    collectionItemInfo = node.collectionItemInfo?.let {
                        SemanticCollectionItemInfo(
                            rowIndex = it.rowIndex,
                            rowCount = it.rowSpan.takeIf { span -> span > 0 }?.let { span ->
                                (it.rowIndex + span).coerceAtLeast(span)
                            },
                            columnIndex = it.columnIndex,
                            columnCount = it.columnSpan.takeIf { span -> span > 0 }?.let { span ->
                                (it.columnIndex + span).coerceAtLeast(span)
                            },
                        )
                    },
                    availableActions = node.actionList.mapNotNull { describeAction(it.id) }.distinct(),
                    boundsInScreen = bounds,
                    nodeInfo = node,
                )
            )

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }

        return nodes
    }

    fun extractAllText(root: AccessibilityNodeInfo?): String {
        return parseSemanticTree(root)
            .flatMap { node ->
                buildList {
                    node.text?.takeIf(String::isNotBlank)?.let(::add)
                    node.contentDescription
                        ?.takeIf { it.isNotBlank() && it != node.text }
                        ?.let { add("[$it]") }
                }
            }
            .joinToString("\n")
    }

    fun parseSemanticTree(root: AccessibilityNodeInfo?): List<SemanticNode> {
        return parseNodeTree(root).map(::toSemanticNode)
    }

    fun extractSemanticScreen(root: AccessibilityNodeInfo?): SemanticScreenSnapshot {
        if (root == null) return SemanticScreenSnapshot.empty()

        val nodes = parseSemanticTree(root)
        val focusedNode = nodes.firstOrNull { it.isAccessibilityFocused }
            ?: nodes.firstOrNull { it.isInputFocused }
        val actionableNodes = nodes.filter { it.isClickable || it.isEditable || it.isScrollable }
        val headings = nodes.filter { it.isHeading || it.role == "heading" }
        val formFields = nodes.filter { it.isEditable || it.role == "text_field" }
        val toggles = nodes.filter { it.role in TOGGLE_ROLES || it.isChecked != null }
        val lists = nodes.filter { it.role == "list" }
        val tabs = nodes.filter { it.role == "tab" }
        val dialogs = nodes.filter { it.role == "dialog" }
        val panes = nodes.mapNotNull { it.paneTitle?.takeIf(String::isNotBlank) }.distinct()
        val keyboardVisible = formFields.any { it.isInputFocused || it.isAccessibilityFocused } ||
            isKeyboardPackage(root.packageName?.toString().orEmpty())

        return SemanticScreenSnapshot(
            packageName = root.packageName?.toString() ?: "",
            focusedNode = focusedNode,
            visibleNodes = nodes.filter { it.isVisibleToUser },
            actionableNodes = actionableNodes,
            headings = headings,
            formFields = formFields,
            toggles = toggles,
            lists = lists,
            tabs = tabs,
            dialogs = dialogs,
            panes = panes,
            keyboardVisible = keyboardVisible,
            summary = buildScreenSummary(
                packageName = root.packageName?.toString() ?: "",
                focusedNode = focusedNode,
                actionableCount = actionableNodes.size,
                headingCount = headings.size,
                toggleCount = toggles.size,
                listCount = lists.size,
                tabCount = tabs.size,
                dialogLabel = dialogs.firstOrNull()?.label,
                paneTitle = panes.firstOrNull(),
                keyboardVisible = keyboardVisible,
            ),
        )
    }

    fun extractScreenContext(root: AccessibilityNodeInfo?): com.akimy.genie.tools.ScreenContext {
        val snapshot = extractSemanticScreen(root)
        return com.akimy.genie.tools.ScreenContext(
            packageName = snapshot.packageName,
            visibleTexts = snapshot.visibleNodes.mapNotNull { it.label.takeIf(String::isNotBlank) },
            clickableLabels = snapshot.actionableNodes.mapNotNull { it.label.takeIf(String::isNotBlank) },
            focusedFieldClassName = snapshot.focusedNode?.className,
            focusedFieldIsPassword = snapshot.focusedNode?.isPassword ?: false,
            focusedFieldHint = snapshot.focusedNode?.hintText,
            focusedFieldInputType = parseNodeTree(root)
                .firstOrNull { it.isAccessibilityFocused || it.isInputFocused }
                ?.inputType,
        )
    }

    fun findNodeByText(root: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        val lowerTarget = target.trim().lowercase()
        if (lowerTarget.isBlank()) return null

        val exact = parseNodeTree(root).firstOrNull { node ->
            node.isVisibleToUser && node.matchesText(lowerTarget, exact = true)
        }
        if (exact != null) return exact.nodeInfo

        val partial = parseNodeTree(root).firstOrNull { node ->
            node.isVisibleToUser && node.matchesText(lowerTarget, exact = false)
        }
        if (partial != null) return partial.nodeInfo

        Log.d(TAG, "Node not found for target: '$target'")
        return null
    }

    fun findClickableNode(root: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        val textNode = findNodeByText(root, target) ?: return null
        return findClickableAncestor(textNode)
    }

    fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable) return node

        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }

        return null
    }

    fun findAccessibilityFocusedNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { return it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }

        return parseNodeTree(root)
            .firstOrNull { it.isAccessibilityFocused || it.isInputFocused }
            ?.nodeInfo
    }

    fun findFirstNavigableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return parseNodeTree(root).firstOrNull(::isNavigable)?.nodeInfo
    }

    fun findNavigableNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        return parseNodeTree(root).filter(::isNavigable).map { it.nodeInfo }
    }

    fun findNodeByRole(root: AccessibilityNodeInfo?, roleQuery: String): AccessibilityNodeInfo? {
        val normalizedQuery = normalizeRole(roleQuery)
        val semanticNodes = parseSemanticTree(root)
        if (semanticNodes.isEmpty()) return null

        val matches = semanticNodes.filter { semantic ->
            matchesRoleQuery(semantic, normalizedQuery)
        }
        if (matches.isEmpty()) return null

        val focusedId = semanticNodes.firstOrNull {
            it.isAccessibilityFocused || it.isInputFocused
        }?.id

        val chosen = if (focusedId != null) {
            val focusedIndex = semanticNodes.indexOfFirst { it.id == focusedId }
            matches.minByOrNull { semantic ->
                val index = semanticNodes.indexOfFirst { it.id == semantic.id }
                if (index >= focusedIndex && focusedIndex >= 0) {
                    index - focusedIndex
                } else {
                    semanticNodes.size + index.coerceAtLeast(0)
                }
            }
        } else {
            matches.firstOrNull()
        } ?: return null

        return parseNodeTree(root)
            .firstOrNull { toSemanticNode(it).id == chosen.id }
            ?.nodeInfo
    }

    fun findScrollableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val focused = findAccessibilityFocusedNode(root)
        var current = focused
        while (current != null) {
            if (current.isScrollable) return current
            current = current.parent
        }

        return parseNodeTree(root)
            .firstOrNull { it.isVisibleToUser && it.isScrollable }
            ?.nodeInfo
    }

    fun describeFocusedNode(root: AccessibilityNodeInfo?): String {
        val semantic = extractSemanticScreen(root).focusedNode
            ?: return "Nothing is focused right now."
        return describeSemanticNode(semantic)
    }

    fun describeSemanticNode(node: SemanticNode): String {
        val parts = mutableListOf<String>()
        parts += node.label.ifBlank { "Unlabeled item" }
        parts += node.role.replace('_', ' ')
        if (node.isHeading) parts += "heading"
        if (node.isEditable) parts += "editable"
        if (node.isPassword) parts += "password field"
        if (node.isSelected) parts += "selected"
        if (node.isEnabled.not()) parts += "disabled"
        node.isChecked?.let { parts += if (it) "checked" else "not checked" }
        node.stateDescription?.takeIf(String::isNotBlank)?.let(parts::add)
        node.errorText?.takeIf(String::isNotBlank)?.let { parts += "error: $it" }
        node.hintText?.takeIf(String::isNotBlank)?.let { parts += "hint: $it" }
        node.collectionItemInfo?.let { info ->
            val row = info.rowIndex?.plus(1)
            val total = info.rowCount
            if (row != null && total != null) {
                parts += "item $row of $total"
            }
        }
        node.rangeInfo?.let { range ->
            val percent = if (range.max > range.min) {
                (((range.current - range.min) / (range.max - range.min)) * 100f).toInt()
            } else {
                null
            }
            percent?.let { parts += "$it percent" }
        }
        val effectiveActions = effectiveActions(node)
        if (effectiveActions.isNotEmpty()) {
            parts += "actions: ${effectiveActions.joinToString()}"
        }
        return parts.joinToString(", ")
    }

    fun describeScreen(root: AccessibilityNodeInfo?): String {
        return extractSemanticScreen(root).summary
    }

    fun describeAvailableActions(root: AccessibilityNodeInfo?): String {
        val snapshot = extractSemanticScreen(root)
        val focused = snapshot.focusedNode

        if (focused != null) {
            val lines = mutableListOf<String>()
            lines += "Current item: ${describeShortNode(focused)}"
            lines += describeNodeActions(focused)

            val nearbyActionable = snapshot.actionableNodes
                .filterNot { it.id == focused.id }
                .filter { it.label.isNotBlank() }
                .distinctBy { it.id }
                .take(4)

            if (nearbyActionable.isNotEmpty()) {
                lines += "Other actionable items: ${nearbyActionable.joinToString(" | ") { describeShortNode(it) }}"
            }
            return lines.joinToString("\n")
        }

        val actionable = snapshot.actionableNodes
            .filter { it.label.isNotBlank() }
            .distinctBy { it.id }
            .take(6)

        return if (actionable.isEmpty()) {
            "No focused item and no obvious actionable controls were detected."
        } else {
            "No item is focused right now. Actionable items visible: ${actionable.joinToString(" | ") { describeShortNode(it) }}"
        }
    }

    fun diffScreens(
        previous: SemanticScreenSnapshot,
        current: SemanticScreenSnapshot,
    ): SemanticScreenDiff {
        if (isUnavailableSnapshot(previous) || isUnavailableSnapshot(current)) {
            return SemanticScreenDiff.empty()
        }

        val changes = mutableListOf<SemanticChange>()

        if (previous.packageName != current.packageName && current.packageName.isNotBlank()) {
            changes += SemanticChange(
                category = "app",
                summary = "App changed to ${appLabel(current.packageName)}.",
            )
        }

        val previousPane = previous.panes.firstOrNull { it.isNotBlank() }
        val currentPane = current.panes.firstOrNull { it.isNotBlank() }
        if (previousPane != currentPane) {
            when {
                currentPane != null -> changes += SemanticChange(
                    category = "pane",
                    summary = "Pane changed to $currentPane.",
                )

                previousPane != null -> changes += SemanticChange(
                    category = "pane",
                    summary = "Pane dismissed.",
                )
            }
        }

        if (!previous.keyboardVisible && current.keyboardVisible) {
            changes += SemanticChange(category = "keyboard", summary = "Keyboard opened.")
        }
        if (previous.keyboardVisible && !current.keyboardVisible) {
            changes += SemanticChange(category = "keyboard", summary = "Keyboard closed.")
        }

        val previousFocused = previous.focusedNode
        val currentFocused = current.focusedNode
        if (previousFocused?.id != currentFocused?.id && currentFocused != null) {
            changes += SemanticChange(
                category = "focus",
                summary = "Focus moved to ${describeSemanticNode(currentFocused)}",
                nodeId = currentFocused.id,
            )
        }

        if (previousFocused?.id == currentFocused?.id && currentFocused != null) {
            if (previousFocused?.isEnabled != currentFocused.isEnabled) {
                changes += SemanticChange(
                    category = "state",
                    summary = "${currentFocused.label} is now ${if (currentFocused.isEnabled) "enabled" else "disabled"}.",
                    nodeId = currentFocused.id,
                )
            }
            if (previousFocused?.stateDescription != currentFocused.stateDescription &&
                !currentFocused.stateDescription.isNullOrBlank()
            ) {
                changes += SemanticChange(
                    category = "state",
                    summary = "${currentFocused.label} state changed: ${currentFocused.stateDescription}",
                    nodeId = currentFocused.id,
                )
            }
            if (previousFocused?.errorText != currentFocused.errorText &&
                !currentFocused.errorText.isNullOrBlank()
            ) {
                changes += SemanticChange(
                    category = "error",
                    summary = "Error on ${currentFocused.label}: ${currentFocused.errorText}",
                    nodeId = currentFocused.id,
                )
            }
        }

        current.toggles.forEach { toggle ->
            val previousToggle = previous.toggles.firstOrNull { it.id == toggle.id } ?: return@forEach
            if (previousToggle.isChecked != toggle.isChecked && toggle.isChecked != null) {
                changes += SemanticChange(
                    category = "toggle",
                    summary = "${toggle.label} is now ${if (toggle.isChecked) "on" else "off"}.",
                    nodeId = toggle.id,
                )
            }
        }

        current.dialogs
            .filter { dialog -> previous.dialogs.none { it.id == dialog.id } }
            .take(2)
            .forEach { dialog ->
                changes += SemanticChange(
                    category = "dialog",
                    summary = "Dialog appeared: ${dialog.label}",
                    nodeId = dialog.id,
                )
            }

        previous.dialogs
            .filter { dialog -> current.dialogs.none { it.id == dialog.id } }
            .take(2)
            .forEach { dialog ->
                changes += SemanticChange(
                    category = "dialog",
                    summary = "Dialog dismissed: ${dialog.label}",
                    nodeId = dialog.id,
                )
            }

        if (changes.isEmpty() && previous.summary != current.summary) {
            changes += SemanticChange(
                category = "screen",
                summary = "Screen structure changed.",
            )
        }

        return SemanticScreenDiff(
            previousPackageName = previous.packageName,
            currentPackageName = current.packageName,
            changes = changes,
            summary = if (changes.isEmpty()) {
                "No meaningful screen changes detected yet."
            } else {
                changes.take(6).joinToString("\n") { it.summary }
            },
        )
    }

    fun describeNearbyContext(root: AccessibilityNodeInfo?): String {
        val snapshot = extractSemanticScreen(root)
        val focused = snapshot.focusedNode ?: return snapshot.summary
        val focusIndex = snapshot.visibleNodes.indexOfFirst { it.id == focused.id }
        val nearby = if (focusIndex >= 0) {
            snapshot.visibleNodes
                .subList(
                    (focusIndex - 2).coerceAtLeast(0),
                    (focusIndex + 3).coerceAtMost(snapshot.visibleNodes.size),
                )
                .filterNot { it.id == focused.id }
        } else {
            emptyList()
        }

        val lines = mutableListOf<String>()
        lines += "Current item: ${describeSemanticNode(focused)}"
        if (nearby.isNotEmpty()) {
            lines += "Nearby: ${nearby.take(4).joinToString(" | ") { describeShortNode(it) }}"
        }
        if (snapshot.headings.isNotEmpty()) {
            lines += "Headings nearby: ${snapshot.headings.take(3).joinToString(", ") { it.label }}"
        }
        return lines.joinToString("\n")
    }

    fun describeDialog(root: AccessibilityNodeInfo?): String {
        val snapshot = extractSemanticScreen(root)
        val dialog = snapshot.dialogs.firstOrNull()
            ?: return "No dialog is currently visible."
        val related = snapshot.visibleNodes
            .filter { it.paneTitle == dialog.paneTitle || it.role == "dialog" || it.id == dialog.id }
            .take(6)

        return buildString {
            append("Dialog: ${dialog.label}")
            if (related.isNotEmpty()) {
                append("\nContents: ")
                append(related.joinToString(" | ") { describeShortNode(it) })
            }
        }
    }

    fun describeFormState(root: AccessibilityNodeInfo?): String {
        val snapshot = extractSemanticScreen(root)
        if (snapshot.formFields.isEmpty()) {
            return "No form fields are visible right now."
        }

        return snapshot.formFields
            .take(8)
            .joinToString("\n") { field ->
                buildString {
                    append(field.label)
                    append(": ")
                    append(field.role.replace('_', ' '))
                    if (field.isPassword) append(", password")
                    field.hintText?.takeIf(String::isNotBlank)?.let { append(", hint: $it") }
                    field.errorText?.takeIf(String::isNotBlank)?.let { append(", error: $it") }
                    if (field.isInputFocused || field.isAccessibilityFocused) append(", focused")
                    if (!field.isEnabled) append(", disabled")
                }
            }
    }

    fun describeLandmarks(root: AccessibilityNodeInfo?): String {
        val snapshot = extractSemanticScreen(root)
        val parts = mutableListOf<String>()
        if (snapshot.headings.isNotEmpty()) {
            parts += "Headings: ${snapshot.headings.take(4).joinToString(", ") { it.label }}"
        }
        if (snapshot.tabs.isNotEmpty()) {
            parts += "Tabs: ${snapshot.tabs.take(5).joinToString(", ") { it.label }}"
        }
        if (snapshot.toggles.isNotEmpty()) {
            parts += "Toggles: ${snapshot.toggles.take(4).joinToString(", ") { it.label }}"
        }
        if (snapshot.dialogs.isNotEmpty()) {
            parts += "Dialogs: ${snapshot.dialogs.take(2).joinToString(", ") { it.label }}"
        }
        if (parts.isEmpty()) {
            return "No major landmarks were detected on this screen."
        }
        return parts.joinToString("\n")
    }

    private fun toSemanticNode(node: UINode): SemanticNode {
        val label = buildLabel(node)
        return SemanticNode(
            id = buildNodeId(node),
            packageName = node.packageName,
            className = node.className,
            role = deriveRole(node),
            text = node.text,
            contentDescription = node.contentDescription,
            hintText = node.hintText,
            stateDescription = node.stateDescription,
            errorText = node.errorText,
            paneTitle = node.paneTitle,
            label = label,
            isVisibleToUser = node.isVisibleToUser,
            isAccessibilityFocused = node.isAccessibilityFocused,
            isInputFocused = node.isInputFocused,
            isClickable = node.isClickable,
            isLongClickable = node.isLongClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isEnabled = node.isEnabled,
            isSelected = node.isSelected,
            isChecked = node.isChecked,
            isHeading = node.isHeading,
            isPassword = node.isPassword,
            availableActions = node.availableActions,
            rangeInfo = node.rangeInfo,
            collectionItemInfo = node.collectionItemInfo,
            boundsInScreen = node.boundsInScreen,
        )
    }

    private fun buildLabel(node: UINode): String {
        return listOf(
            node.text,
            node.contentDescription,
            node.hintText,
            node.stateDescription,
            node.paneTitle,
            simpleClassName(node.className),
        ).firstOrNull { !it.isNullOrBlank() } ?: "Unlabeled item"
    }

    private fun buildNodeId(node: UINode): String {
        val bounds = node.boundsInScreen
        val boundsKey = if (bounds != null) {
            "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        } else {
            "no_bounds"
        }
        return listOf(
            node.packageName,
            node.className.orEmpty(),
            buildLabel(node),
            boundsKey,
        ).joinToString("|")
    }

    private fun deriveRole(node: UINode): String {
        if (node.isHeading) return "heading"
        if (looksLikeDialog(node)) return "dialog"
        if (node.className?.contains("EditText", ignoreCase = true) == true || node.isEditable) {
            return "text_field"
        }
        if (node.className?.contains("Switch", ignoreCase = true) == true) return "switch"
        if (node.className?.contains("ToggleButton", ignoreCase = true) == true) return "toggle"
        if (node.className?.contains("CheckBox", ignoreCase = true) == true) return "checkbox"
        if (node.className?.contains("RadioButton", ignoreCase = true) == true) return "radio_button"
        if (node.className?.contains("Button", ignoreCase = true) == true) return "button"
        if (node.className?.contains("SeekBar", ignoreCase = true) == true) return "slider"
        if (node.className?.contains("ProgressBar", ignoreCase = true) == true) return "progress_bar"
        if (node.className?.contains("Image", ignoreCase = true) == true) return "image"
        if (node.className?.contains("Tab", ignoreCase = true) == true) return "tab"
        if (node.className?.contains("RecyclerView", ignoreCase = true) == true ||
            node.className?.contains("ListView", ignoreCase = true) == true
        ) {
            return "list"
        }
        if (node.className?.contains("ScrollView", ignoreCase = true) == true) return "scroll_view"
        if (node.isScrollable) return "list"
        if (node.isClickable) return "button"
        return "text"
    }

    private fun checkedState(node: AccessibilityNodeInfo): Boolean? {
        return when {
            node.className?.contains("Switch", ignoreCase = true) == true -> node.isChecked
            node.className?.contains("ToggleButton", ignoreCase = true) == true -> node.isChecked
            node.className?.contains("CheckBox", ignoreCase = true) == true -> node.isChecked
            node.className?.contains("RadioButton", ignoreCase = true) == true -> node.isChecked
            else -> null
        }
    }

    private fun describeAction(actionId: Int): String? {
        return when (actionId) {
            AccessibilityNodeInfo.ACTION_CLICK -> "click"
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
            AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
            AccessibilityNodeInfo.ACTION_EXPAND -> "expand"
            AccessibilityNodeInfo.ACTION_COLLAPSE -> "collapse"
            AccessibilityNodeInfo.ACTION_DISMISS -> "dismiss"
            AccessibilityNodeInfo.ACTION_SELECT -> "select"
            AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "clear_selection"
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> "focus"
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> "clear_focus"
            AccessibilityNodeInfo.ACTION_FOCUS -> "input_focus"
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "clear_input_focus"
            else -> null
        }
    }

    private fun simpleClassName(className: String?): String? {
        if (className.isNullOrBlank()) return null
        return className.substringAfterLast('.')
    }

    private fun isNavigable(node: UINode): Boolean {
        if (!node.isVisibleToUser) return false
        if (!node.isEnabled && buildLabel(node) == "Unlabeled item") return false

        val role = deriveRole(node)
        return node.isAccessibilityFocused ||
            node.isInputFocused ||
            node.isClickable ||
            node.isEditable ||
            node.isScrollable ||
            node.isHeading ||
            node.availableActions.isNotEmpty() ||
            role in IMPORTANT_ROLES ||
            buildLabel(node) != "Unlabeled item"
    }

    private fun buildScreenSummary(
        packageName: String,
        focusedNode: SemanticNode?,
        actionableCount: Int,
        headingCount: Int,
        toggleCount: Int,
        listCount: Int,
        tabCount: Int,
        dialogLabel: String?,
        paneTitle: String?,
        keyboardVisible: Boolean,
    ): String {
        val parts = mutableListOf<String>()
        val appLabel = packageName.substringAfterLast('.').ifBlank { "unknown app" }
        parts += "You are in $appLabel."
        paneTitle?.takeIf(String::isNotBlank)?.let { parts += "Pane: $it." }
        dialogLabel?.takeIf(String::isNotBlank)?.let { parts += "Dialog visible: $it." }
        focusedNode?.let { parts += "Focused on ${describeSemanticNode(it)}." }
        if (keyboardVisible) parts += "Keyboard is likely open."
        parts += "$actionableCount actionable items visible."
        if (headingCount > 0) parts += "$headingCount headings."
        if (toggleCount > 0) parts += "$toggleCount toggles."
        if (listCount > 0) parts += "$listCount lists."
        if (tabCount > 0) parts += "$tabCount tabs."
        return parts.joinToString(" ")
    }

    private fun normalizeRole(role: String): String {
        return role.trim()
            .lowercase()
            .replace('-', '_')
            .replace(' ', '_')
            .let {
                when (it) {
                    "input", "textfield", "textinput", "edit_text" -> "text_field"
                    "form", "field", "form_field", "formfield" -> "text_field"
                    "landmark" -> "landmark"
                    "switches" -> "switch"
                    "buttons" -> "button"
                    "lists" -> "list"
                    "dialogs" -> "dialog"
                    else -> it
                }
            }
    }

    private fun UINode.matchesText(target: String, exact: Boolean): Boolean {
        val candidates = listOf(
            text,
            contentDescription,
            hintText,
            stateDescription,
            paneTitle,
        ).mapNotNull { it?.lowercase() }

        return if (exact) {
            candidates.any { it == target }
        } else {
            candidates.any { it.contains(target) }
        }
    }

    private fun matchesRoleQuery(node: SemanticNode, normalizedQuery: String): Boolean {
        return when (normalizedQuery) {
            "landmark" -> node.role in LANDMARK_ROLES || node.isHeading
            "form_field" -> node.role == "text_field" || node.isEditable
            "toggle" -> node.role in TOGGLE_ROLES || node.isChecked != null
            else -> normalizeRole(node.role) == normalizedQuery
        }
    }

    private fun describeNodeActions(node: SemanticNode): String {
        val actions = effectiveActions(node)
        return if (actions.isEmpty()) {
            "No native accessibility actions were exposed for the current item."
        } else {
            "You can ${actions.joinToString(", ") { humanizeAction(it) }}."
        }
    }

    private fun describeShortNode(node: SemanticNode): String {
        val stateBits = buildList {
            if (node.isHeading) add("heading")
            node.isChecked?.let { add(if (it) "checked" else "not checked") }
            if (!node.isEnabled) add("disabled")
        }
        return if (stateBits.isEmpty()) {
            "${node.label} (${node.role.replace('_', ' ')})"
        } else {
            "${node.label} (${node.role.replace('_', ' ')}, ${stateBits.joinToString()})"
        }
    }

    private fun effectiveActions(node: SemanticNode): List<String> {
        val actions = linkedSetOf<String>()
        actions += node.availableActions
        if (node.isClickable) actions += "click"
        if (node.isLongClickable) actions += "long_click"
        if (node.isEditable) actions += "set_text"
        if (node.isScrollable) {
            actions += "scroll_forward"
            actions += "scroll_backward"
        }
        if (!node.isAccessibilityFocused) actions += "focus"
        if (!node.isInputFocused && node.isEditable) actions += "input_focus"
        return actions.toList()
    }

    private fun humanizeAction(action: String): String {
        return when (action) {
            "click" -> "activate it"
            "long_click" -> "long press it"
            "set_text" -> "enter text"
            "scroll_forward" -> "scroll forward"
            "scroll_backward" -> "scroll backward"
            "expand" -> "expand it"
            "collapse" -> "collapse it"
            "dismiss" -> "dismiss it"
            "select" -> "select it"
            "clear_selection" -> "clear its selection"
            "focus" -> "move accessibility focus to it"
            "clear_focus" -> "clear accessibility focus"
            "input_focus" -> "place input focus on it"
            "clear_input_focus" -> "clear input focus"
            else -> action.replace('_', ' ')
        }
    }

    private val IMPORTANT_ROLES = setOf(
        "button",
        "text_field",
        "switch",
        "toggle",
        "checkbox",
        "radio_button",
        "heading",
        "tab",
        "list",
        "dialog",
        "slider",
        "progress_bar",
    )

    private val TOGGLE_ROLES = setOf("switch", "toggle", "checkbox", "radio_button")
    private val LANDMARK_ROLES = setOf("heading", "tab", "dialog", "list", "text_field", "switch")

    private fun looksLikeDialog(node: UINode): Boolean {
        val className = node.className.orEmpty()
        val packageName = node.packageName
        return className.contains("Dialog", ignoreCase = true) ||
            className.contains("BottomSheet", ignoreCase = true) ||
            className.contains("Popup", ignoreCase = true) ||
            packageName.contains("permissioncontroller", ignoreCase = true) ||
            packageName.contains("packageinstaller", ignoreCase = true) ||
            node.paneTitle?.isNotBlank() == true && node.availableActions.contains("dismiss")
    }

    private fun isKeyboardPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return "inputmethod" in lower || "keyboard" in lower
    }

    private fun isUnavailableSnapshot(snapshot: SemanticScreenSnapshot): Boolean {
        return snapshot.packageName.isBlank() && snapshot.visibleNodes.isEmpty()
    }

    private fun appLabel(packageName: String): String {
        return packageName.substringAfterLast('.').ifBlank { "current app" }
    }
}
