package com.example.surveyautomation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.surveyautomation.ui.theme.SurveyAutomationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SurveyRecorder.init(applicationContext)
        setContent {
            SurveyAutomationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isRefreshing by remember { mutableStateOf(false) }
    var isServiceEnabled by remember { mutableStateOf(AutomationController.isAccessibilityServiceEnabled(context)) }

    val statusMessage by AutomationController.statusMessage.collectAsState()
    val currentState by AutomationController.currentState.collectAsState()

    val isRecording by SurveyRecorder.recordingState.collectAsState()
    val recordedScreens by SurveyRecorder.recordedScreensCount.collectAsState()
    val recordedInteractions by SurveyRecorder.recordedInteractionsCount.collectAsState()

    // Auto-detect Accessibility Service status when user returns to app (ON_RESUME)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceEnabled = AutomationController.isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val onRefresh: () -> Unit = {
        isRefreshing = true
        val enabledNow = AutomationController.isAccessibilityServiceEnabled(context)
        isServiceEnabled = enabledNow

        if (!enabledNow) {
            AutomationController.logAndNotify("Accessibility service is disabled. Opening Accessibility Settings...")
            AutomationController.openAccessibilitySettings(context)
        } else {
            AutomationController.logAndNotify("Accessibility service status refreshed: ENABLED")
        }
        isRefreshing = false
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Survey Automation",
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Pull down to refresh status",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Accessibility Service Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isServiceEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isServiceEnabled) {
                            "Accessibility Service: ENABLED"
                        } else {
                            "Accessibility Service: DISABLED"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!isServiceEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Please enable 'SurveyAutomation' in Accessibility Settings to allow recording & automation.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                AutomationController.openAccessibilitySettings(context)
                            },
                        ) {
                            Text("Open Accessibility Settings")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Recording Mode Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "Recording Mode (Debug)",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = if (isRecording) "Status: RECORDING ON" else "Status: OFF",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isRecording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                        }
                        Switch(
                            checked = isRecording,
                            onCheckedChange = { enabled ->
                                SurveyRecorder.setRecordingEnabled(context, enabled)
                            },
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Screens Recorded: $recordedScreens",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Interactions: $recordedInteractions",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { SurveyRecorder.exportRecordingFile(context) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Export JSON")
                        }
                        OutlinedButton(
                            onClick = { SurveyRecorder.clearRecordings(context) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Automation Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Automation Status:",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            val isRunning = AutomationController.isAutomationActive()

            if (isRunning) {
                Button(
                    onClick = {
                        AutomationController.stopAutomation()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(
                        text = "STOP",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            } else {
                Button(
                    onClick = {
                        val enabledNow = AutomationController.isAccessibilityServiceEnabled(context)
                        isServiceEnabled = enabledNow
                        if (!enabledNow) {
                            AutomationController.logAndNotify("Accessibility service is disabled. Opening Settings...")
                            AutomationController.openAccessibilitySettings(context)
                        } else {
                            AutomationController.startAutomation(context)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(
                        text = if (!isServiceEnabled) {
                            "ENABLE ACCESSIBILITY SERVICE"
                        } else {
                            "START"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }

            if ((currentState == AutomationController.State.COMPLETED) ||
                (currentState == AutomationController.State.FAILED) ||
                (currentState == AutomationController.State.STOPPED)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { AutomationController.reset() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("RESET")
                }
            }
        }
    }
}
