package com.akimy.genie.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import java.util.LinkedList

private const val TAG = "GenieUINodeParser"

/**
 * Represents a parsed UI element from the accessibility tree.
 */
data class UINode(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val boundsInScreen: android.graphics.Rect?,
    val nodeInfo: AccessibilityNodeInfo,
)

/**
 * Utility for parsing the Android accessibility node tree.
 *
 * Extracted from BetaAssist's inline BFS traversals in:
 * - extractText()
 * - translateText()
 * - summarizeTextOnscreen()
 *
 * Made reusable as standalone functions for the agent's tools.
 */
object UINodeParser {

    /**
     * Parse all visible nodes in the accessibility tree using BFS.
     *
     * @param root The root AccessibilityNodeInfo (from getRootInActiveWindow())
     * @return A flat list of all UINode objects in the tree
     */
    fun parseNodeTree(root: AccessibilityNodeInfo?): List<UINode> {
        if (root == null) return emptyList()

        val nodes = mutableListOf<UINode>()
        val queue: LinkedList<AccessibilityNodeInfo> = LinkedList()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue

            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)

            nodes.add(UINode(
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString(),
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                boundsInScreen = bounds,
                nodeInfo = node,
            ))

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }

        return nodes
    }

    /**
     * Extract all visible text from the accessibility tree.
     * Adapted from BetaAssist's extractText() method.
     *
     * @param root The root AccessibilityNodeInfo
     * @return Concatenated text from all nodes
     */
    fun extractAllText(root: AccessibilityNodeInfo?): String {
        val nodes = parseNodeTree(root)
        val textParts = mutableListOf<String>()

        for (node in nodes) {
            node.text?.let { text ->
                if (text.isNotBlank()) textParts.add(text)
            }
            node.contentDescription?.let { desc ->
                if (desc.isNotBlank() && desc != node.text?.toString()) {
                    textParts.add("[$desc]")
                }
            }
        }

        return textParts.joinToString("\n")
    }

    /**
     * Find a node by its visible text content.
     * Searches both text and contentDescription fields.
     *
     * @param root The root node
     * @param target Text to search for (case-insensitive partial match)
     * @return The first matching node, or null
     */
    fun findNodeByText(root: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        val nodes = parseNodeTree(root)
        val lowerTarget = target.lowercase()

        // First try exact match
        for (node in nodes) {
            if (node.text?.toString()?.lowercase() == lowerTarget ||
                node.contentDescription?.toString()?.lowercase() == lowerTarget
            ) {
                return node.nodeInfo
            }
        }

        // Then try contains match
        for (node in nodes) {
            if (node.text?.toString()?.lowercase()?.contains(lowerTarget) == true ||
                node.contentDescription?.toString()?.lowercase()?.contains(lowerTarget) == true
            ) {
                return node.nodeInfo
            }
        }

        Log.d(TAG, "Node not found for target: '$target'")
        return null
    }

    /**
     * Find a clickable node matching the given target text.
     * Walks up the tree from the text node to find the nearest clickable ancestor.
     *
     * @param root The root node
     * @param target Text to search for
     * @return The clickable node, or null
     */
    fun findClickableNode(root: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        val textNode = findNodeByText(root, target) ?: return null

        // If the text node itself is clickable, return it
        if (textNode.isClickable) return textNode

        // Walk up to find clickable parent
        var parent = textNode.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }

        Log.d(TAG, "No clickable ancestor found for target: '$target'")
        return null
    }
}
