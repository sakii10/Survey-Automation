package com.example.surveyautomation

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object AutomationController {

    private const val TAG = "SurveyAutomation"

    enum class State {
        IDLE,
        RUNNING,
        WAITING_FOR_ATTAPOLL,
        STOPPED,
        FAILED,
        COMPLETED,
    }

    private val _statusMessage = MutableStateFlow("Idle. Press START to begin.")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _currentState = MutableStateFlow(State.IDLE)
    val currentState: StateFlow<State> = _currentState.asStateFlow()

    private var automationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    var targetPackageName: String? = null
        private set

    fun isAutomationActive(): Boolean {
        val state = _currentState.value
        return state == State.RUNNING || state == State.WAITING_FOR_ATTAPOLL
    }

    fun startAutomation(context: Context) {
        logAndNotify("START pressed - Beginning automation flow")
        _currentState.value = State.RUNNING

        automationJob?.cancel()
        automationJob = scope.launch {
            // 0. Verify Accessibility Service is enabled
            if (!isAccessibilityServiceEnabled(context)) {
                logAndNotify("ERROR: Accessibility Service is disabled. Opening Accessibility Settings...")
                openAccessibilitySettings(context)
                _currentState.value = State.FAILED
                return@launch
            }

            // 1. Wake screen if off
            logAndNotify("Checking screen interactive state...")
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wasInteractive = powerManager.isInteractive
            if (!wasInteractive) {
                logAndNotify("Screen is OFF. Attempting to wake screen...")
                wakeScreenIfNecessary(context)
            }

            if (!isAutomationActive()) return@launch

            // 2. Re-check lock state after screen wake
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val isKeyguardLocked = keyguardManager.isKeyguardLocked
            val isDeviceLocked = keyguardManager.isDeviceLocked
            val isSecure = keyguardManager.isKeyguardSecure
            val isInteractive = powerManager.isInteractive

            logAndNotify("Lock state check: isInteractive=$isInteractive, isKeyguardLocked=$isKeyguardLocked, isDeviceLocked=$isDeviceLocked, isKeyguardSecure=$isSecure")

            val isCurrentlyLocked = isKeyguardLocked || isDeviceLocked

            if (isCurrentlyLocked) {
                if (isSecure) {
                    logAndNotify("ERROR: Device is locked with PIN/Password/Biometrics. Manual unlock required.")
                } else {
                    logAndNotify("ERROR: Device is locked on swipe keyguard. Manual unlock required.")
                }
                _currentState.value = State.FAILED
                return@launch
            }

            logAndNotify("Device is UNLOCKED. Continuing automation...")

            if (!isAutomationActive()) return@launch

            // 3. Locate AttaPoll package dynamically
            logAndNotify("Searching for AttaPoll package...")
            val packageName = findAttaPollPackage(context)
            if (packageName == null) {
                logAndNotify("ERROR: AttaPoll package NOT found on device.")
                _currentState.value = State.FAILED
                return@launch
            }

            targetPackageName = packageName
            logAndNotify("AttaPoll package found: $packageName")

            if (!isAutomationActive()) return@launch

            // 4. Launch AttaPoll app
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                logAndNotify("ERROR: Could not get launch intent for $packageName")
                _currentState.value = State.FAILED
                return@launch
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(launchIntent)
                logAndNotify("AttaPoll launched successfully. Waiting for UI...")
                _currentState.value = State.WAITING_FOR_ATTAPOLL
            } catch (e: Exception) {
                logAndNotify("ERROR: Failed to launch AttaPoll: ${e.localizedMessage}")
                _currentState.value = State.FAILED
            }
        }
    }

    fun stopAutomation() {
        automationJob?.cancel()
        automationJob = null
        targetPackageName = null
        _currentState.value = State.STOPPED
        logAndNotify("Automation stopped by user.")
    }

    fun onSurveysClicked() {
        logAndNotify("`Surveys` section successfully clicked! Flow completed.")
        _currentState.value = State.COMPLETED
    }

    fun reset() {
        automationJob?.cancel()
        automationJob = null
        _currentState.value = State.IDLE
        _statusMessage.value = "Idle. Press START to begin."
        targetPackageName = null
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = ComponentName(context, SurveyAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val stringSplitter = TextUtils.SimpleStringSplitter(':')
        stringSplitter.setString(enabledServicesSetting)

        while (stringSplitter.hasNext()) {
            val componentNameString = stringSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        val componentName = ComponentName(context, SurveyAccessibilityService::class.java)
        val specificIntent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra("android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME", componentName.flattenToString())
            putExtra("preference_key", componentName.flattenToString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(specificIntent)
        } catch (_: Exception) {
            val generalIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(generalIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to open accessibility settings: ${e.localizedMessage}")
            }
        }
    }

    private fun findAttaPollPackage(context: Context): String? {
        val pm = context.packageManager

        // Search installed launcher apps matching label "AttaPoll"
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launcherActivities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        for (resolveInfo in launcherActivities) {
            val label = resolveInfo.loadLabel(pm).toString()
            if (label.contains("AttaPoll", ignoreCase = true)) {
                return resolveInfo.activityInfo.packageName
            }
        }

        // Fallback check by installed application info labels
        @Suppress("DEPRECATION")
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (appInfo in installedApps) {
            val label = pm.getApplicationLabel(appInfo).toString()
            if (label.contains("AttaPoll", ignoreCase = true)) {
                return appInfo.packageName
            }
        }

        // Fallback to standard package name if installed
        val fallbackPackage = "com.attapoll.app"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(fallbackPackage, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(fallbackPackage, 0)
            }
            return fallbackPackage
        } catch (_: Exception) {
        }

        return null
    }

    private fun wakeScreenIfNecessary(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "SurveyAutomation:WakeLock",
            )
            wakeLock.acquire(3000)
        }
    }

    fun logAndNotify(msg: String) {
        Log.d(TAG, msg)
        _statusMessage.value = msg
    }
}
