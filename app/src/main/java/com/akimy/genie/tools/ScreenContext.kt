package com.akimy.genie.tools

/**
 * A lightweight snapshot of the current screen state for deterministic risk assessment.
 *
 * Extracted from the accessibility tree at tool execution time.
 * Used by [RiskAssessor] to decide if HITL biometric auth is needed.
 *
 * @param packageName The package name of the foreground app
 * @param visibleTexts All visible text strings from the accessibility tree
 * @param clickableLabels Text labels of clickable nodes on the current screen
 * @param focusedFieldClassName Class name of the currently focused input field (e.g. "android.widget.EditText")
 * @param focusedFieldIsPassword Whether the focused field is a password field
 * @param focusedFieldHint Hint text of the focused field (e.g. "Enter PIN")
 * @param focusedFieldInputType Raw inputType flags of the focused field
 */
data class ScreenContext(
    val packageName: String = "",
    val visibleTexts: List<String> = emptyList(),
    val clickableLabels: List<String> = emptyList(),
    val focusedFieldClassName: String? = null,
    val focusedFieldIsPassword: Boolean = false,
    val focusedFieldHint: String? = null,
    val focusedFieldInputType: Int? = null,
)
