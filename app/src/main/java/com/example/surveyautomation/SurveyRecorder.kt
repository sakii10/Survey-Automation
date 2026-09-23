package com.example.surveyautomation

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object SurveyRecorder {

    private const val TAG = "SurveyRecorder"
    private const val PREFS_NAME = "survey_recorder_prefs"
    private const val KEY_IS_RECORDING = "is_recording_enabled"
    private const val FILE_NAME = "survey_recordings.json"

    private val _recordingState = MutableStateFlow(false)
    val recordingState: StateFlow<Boolean> = _recordingState.asStateFlow()

    private val _recordedScreensCount = MutableStateFlow(0)
    val recordedScreensCount: StateFlow<Int> = _recordedScreensCount.asStateFlow()

    private val _recordedInteractionsCount = MutableStateFlow(0)
    val recordedInteractionsCount: StateFlow<Int> = _recordedInteractionsCount.asStateFlow()

    private var lastRecordedFingerprint: String? = null

    fun init(context: Context) {
        val prefs = getPrefs(context)
        _recordingState.value = prefs.getBoolean(KEY_IS_RECORDING, false)
        updateCountsFromFile(context)
    }

    fun setRecordingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_RECORDING, enabled).apply()
        _recordingState.value = enabled
        Log.d(TAG, "Recording mode set to: $enabled")
    }

    fun isRecordingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_RECORDING, false)
    }

    @Synchronized
    fun recordScreenSnapshot(
        context: Context,
        packageName: String,
        eventTypeStr: String,
        uiElements: List<UiElement>,
    ) {
        if (!isRecordingEnabled(context)) return
        if (!isPackageRelevant(packageName)) return
        if (uiElements.isEmpty()) return

        val fingerprint = computeUiFingerprint(uiElements)
        if (fingerprint == lastRecordedFingerprint) {
            return
        }
        lastRecordedFingerprint = fingerprint

        val snapshotObj = JSONObject().apply {
            put("recordType", "SCREEN_SNAPSHOT")
            put("timestamp", System.currentTimeMillis())
            put("packageName", packageName)
            put("eventType", eventTypeStr)
            put("elementCount", uiElements.size)
            val elementsArray = JSONArray()
            uiElements.forEach { element ->
                elementsArray.put(element.toJson())
            }
            put("uiElements", elementsArray)
        }

        appendRecordToFile(context, snapshotObj)
        _recordedScreensCount.value += 1
    }

    @Synchronized
    fun recordUserInteraction(
        context: Context,
        event: AccessibilityEvent,
    ) {
        if (!isRecordingEnabled(context)) return

        val packageName = event.packageName?.toString() ?: "Unknown"
        if (!isPackageRelevant(packageName)) return

        val sourceNode: AccessibilityNodeInfo? = try {
            event.source
        } catch (_: Exception) {
            null
        }

        val bounds = Rect()
        sourceNode?.getBoundsInScreen(bounds)

        val nodeText = sourceNode?.text?.toString() ?: if (event.text.isNotEmpty()) event.text.joinToString(" ") else null

        val interactionObj = JSONObject().apply {
            put("recordType", "USER_INTERACTION")
            put("timestamp", System.currentTimeMillis())
            put("packageName", packageName)
            put("eventType", AccessibilityEvent.eventTypeToString(event.eventType))
            put("interaction", JSONObject().apply {
                put("className", sourceNode?.className?.toString() ?: event.className?.toString() ?: JSONObject.NULL)
                put("text", nodeText ?: JSONObject.NULL)
                put("contentDescription", sourceNode?.contentDescription?.toString() ?: event.contentDescription?.toString() ?: JSONObject.NULL)
                put("viewIdResourceName", sourceNode?.viewIdResourceName ?: JSONObject.NULL)
                put("isClickable", sourceNode?.isClickable ?: true)
                put("bounds", JSONObject().apply {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                })
            })
        }

        appendRecordToFile(context, interactionObj)
        _recordedInteractionsCount.value += 1
    }

    @Synchronized
    fun clearRecordings(context: Context) {
        val file = getRecordingFile(context)
        if (file.exists()) {
            file.delete()
        }
        lastRecordedFingerprint = null
        _recordedScreensCount.value = 0
        _recordedInteractionsCount.value = 0
        Log.d(TAG, "Recordings cleared.")
    }

    fun exportRecordingFile(context: Context) {
        val file = getRecordingFile(context)
        if (!file.exists() || file.length() == 0L) {
            AutomationController.logAndNotify("No recording file found to export.")
            return
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            val fileUri = FileProvider.getUriForFile(context, authority, file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "Survey Automation Recording")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Export Survey Recordings").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.localizedMessage}")
            AutomationController.logAndNotify("Export failed: ${e.localizedMessage}")
        }
    }

    fun getRecordingFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    private fun isPackageRelevant(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val pkg = packageName.lowercase()
        val isSystemUi = pkg.contains("systemui") ||
                pkg.contains("launcher") ||
                pkg.contains("keyguard") ||
                pkg.contains("inputmethod")

        return !isSystemUi
    }

    private fun computeUiFingerprint(elements: List<UiElement>): String {
        val sb = StringBuilder()
        elements.forEach { el ->
            sb.append(el.className).append('|')
                .append(el.text).append('|')
                .append(el.contentDescription).append('|')
                .append(el.viewIdResourceName).append('|')
                .append(el.boundsInScreen.left).append(',')
                .append(el.boundsInScreen.top).append(',')
                .append(el.boundsInScreen.right).append(',')
                .append(el.boundsInScreen.bottom).append(';')
        }
        return sb.toString()
    }

    private fun appendRecordToFile(context: Context, newRecordObj: JSONObject) {
        try {
            val file = getRecordingFile(context)
            var rootObj = JSONObject()
            var recordsArray = JSONArray()

            if (file.exists() && file.length() > 0) {
                try {
                    val content = file.readText()
                    if (content.isNotBlank()) {
                        rootObj = JSONObject(content)
                        recordsArray = rootObj.optJSONArray("records") ?: JSONArray()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Corrupted recording file, re-creating: ${e.localizedMessage}")
                    recordsArray = JSONArray()
                }
            } else {
                rootObj.put("recordingStartedAt", System.currentTimeMillis())
            }

            recordsArray.put(newRecordObj)
            rootObj.put("records", recordsArray)
            rootObj.put("screenCount", _recordedScreensCount.value)
            rootObj.put("interactionCount", _recordedInteractionsCount.value)
            rootObj.put("lastUpdatedAt", System.currentTimeMillis())

            FileOutputStream(file, false).use { out ->
                out.write(rootObj.toString(2).toByteArray())
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append record to file: ${e.localizedMessage}")
        }
    }

    private fun updateCountsFromFile(context: Context) {
        val file = getRecordingFile(context)
        if (!file.exists() || file.length() == 0L) {
            _recordedScreensCount.value = 0
            _recordedInteractionsCount.value = 0
            return
        }

        try {
            val content = file.readText()
            if (content.isNotBlank()) {
                val rootObj = JSONObject(content)
                val recordsArray = rootObj.optJSONArray("records") ?: JSONArray()
                var screens = 0
                var interactions = 0
                for (i in 0 until recordsArray.length()) {
                    val item = recordsArray.optJSONObject(i) ?: continue
                    when (item.optString("recordType")) {
                        "SCREEN_SNAPSHOT" -> screens++
                        "USER_INTERACTION" -> interactions++
                    }
                }
                _recordedScreensCount.value = screens
                _recordedInteractionsCount.value = interactions
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read counts from file: ${e.localizedMessage}")
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
