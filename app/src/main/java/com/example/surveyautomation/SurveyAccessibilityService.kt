package com.example.surveyautomation

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class SurveyAccessibilityService : AccessibilityService() {

    private var lastLoggedUiTree: String? = null
    private var lastProcessedTime: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType

        // Record User Interaction when view is clicked or selected
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            if (SurveyRecorder.isRecordingEnabled(this)) {
                SurveyRecorder.recordUserInteraction(this, event)
            }
        }

        if ((eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) &&
            (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if ((eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) &&
            (currentTime - lastProcessedTime < MIN_EVENT_INTERVAL_MS)
        ) {
            return
        }

        val rootNode = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }

        if (rootNode == null) {
            if (AutomationController.isAutomationActive() &&
                AutomationController.currentState.value == AutomationController.State.WAITING_FOR_ATTAPOLL
            ) {
                AutomationController.logAndNotify("Active window tree is unavailable (rootInActiveWindow is null)")
            }
            return
        }

        val activePackageName = rootNode.packageName?.toString() ?: event.packageName?.toString() ?: "Unknown"

        val uiElements = try {
            AccessibilityNodeParser.parse(rootNode)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse UI nodes: ${e.localizedMessage}")
            emptyList()
        }

        // Record screen snapshot if recording is enabled
        if (SurveyRecorder.isRecordingEnabled(this) && uiElements.isNotEmpty()) {
            val eventTypeStr = AccessibilityEvent.eventTypeToString(eventType)
            SurveyRecorder.recordScreenSnapshot(this, activePackageName, eventTypeStr, uiElements)
        }

        // Handle automation flow when active and waiting for AttaPoll
        if (AutomationController.isAutomationActive() &&
            AutomationController.currentState.value == AutomationController.State.WAITING_FOR_ATTAPOLL
        ) {
            AutomationController.logAndNotify("Current active package: $activePackageName")

            val targetPkg = AutomationController.targetPackageName ?: "attapoll"
            val isAttaPollActive = activePackageName.contains(targetPkg, ignoreCase = true) ||
                    activePackageName.contains("attapoll", ignoreCase = true)

            if (isAttaPollActive) {
                AutomationController.logAndNotify("AttaPoll UI active. Searching for `Surveys` node...")

                val surveysNode = findSurveysNode(rootNode)
                if (surveysNode != null) {
                    if (!AutomationController.isAutomationActive()) {
                        Log.d(TAG, "Automation was stopped before click. Aborting click.")
                        return
                    }

                    val matchedText = surveysNode.text?.toString() ?: surveysNode.contentDescription?.toString()
                    AutomationController.logAndNotify("`Surveys` node found (text/desc: '$matchedText')")

                    val clickSuccess = clickNodeOrParent(surveysNode)
                    if (clickSuccess) {
                        AutomationController.logAndNotify("Click performed successfully on `Surveys` node.")
                        AutomationController.onSurveysClicked()
                    } else {
                        AutomationController.logAndNotify("ERROR: Failed to perform ACTION_CLICK on `Surveys` node.")
                    }
                }
            }
        }

        // Log UI Tree for Logcat debugging
        logUiTreeIfChanged(uiElements, activePackageName, currentTime)
    }

    private fun findSurveysNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !AutomationController.isAutomationActive()) return null

        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()

        val matchesText = (text != null) && text.contains("Surveys", ignoreCase = true)
        val matchesDesc = (contentDesc != null) && contentDesc.contains("Surveys", ignoreCase = true)

        if (matchesText || matchesDesc) {
            return node
        }

        for (i in 0 until node.childCount) {
            if (!AutomationController.isAutomationActive()) return null
            val child = node.getChild(i) ?: continue
            val found = findSurveysNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (!AutomationController.isAutomationActive()) return false
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        if (!AutomationController.isAutomationActive()) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun logUiTreeIfChanged(
        uiElements: List<UiElement>,
        packageName: String,
        currentTime: Long,
    ) {
        val jsonOutput = JSONObject().apply {
            put("packageName", packageName)
            put("elementCount", uiElements.size)
            val elementsArray = JSONArray()
            uiElements.forEach { element ->
                elementsArray.put(element.toJson())
            }
            put("uiElements", elementsArray)
        }

        val formattedTree = jsonOutput.toString(2)

        if (formattedTree == lastLoggedUiTree) {
            return
        }

        lastLoggedUiTree = formattedTree
        lastProcessedTime = currentTime

        Log.d(TAG, "--- Active Window UI Tree ---")
        Log.d(TAG, "Active Package Name: $packageName")
        logLargeString(formattedTree)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    private fun logLargeString(message: String) {
        val maxLogSize = 3000
        for (i in 0..(message.length / maxLogSize)) {
            val start = i * maxLogSize
            var end = (i + 1) * maxLogSize
            if (end > message.length) {
                end = message.length
            }
            Log.d(TAG, message.substring(start, end))
        }
    }

    companion object {
        private const val TAG = "SurveyAccessibilitySvc"
        private const val MIN_EVENT_INTERVAL_MS = 500L
    }
}
