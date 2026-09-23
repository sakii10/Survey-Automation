package com.example.surveyautomation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityNodeParser {

    fun parse(rootNode: AccessibilityNodeInfo?): List<UiElement> {
        if (rootNode == null) return emptyList()
        val result = mutableListOf<UiElement>()
        traverseNode(rootNode, result)
        return result
    }

    private fun traverseNode(node: AccessibilityNodeInfo, list: MutableList<UiElement>) {
        val element = parseNode(node)
        list.add(element)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, list)
        }
    }

    private fun parseNode(node: AccessibilityNodeInfo): UiElement {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        return UiElement(
            className = node.className?.toString(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isEnabled = node.isEnabled,
            boundsInScreen = bounds,
        )
    }
}
