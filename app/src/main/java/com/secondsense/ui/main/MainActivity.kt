package com.secondsense.ui.main

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.secondsense.R
import com.secondsense.access.MyAccessibilityService
import com.secondsense.debug.AgentLogStore
import com.secondsense.llm.EnsureModelLoadedResult
import com.secondsense.llm.LocalModelProvider
import com.secondsense.llm.ModelDownloadState
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val modelController by lazy { LocalModelProvider.get(applicationContext) }

    private lateinit var goalInput: EditText
    private lateinit var modelStateText: TextView
    private lateinit var agentLogsText: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val logsRefreshRunnable = object : Runnable {
        override fun run() {
            renderAgentLogs()
            uiHandler.postDelayed(this, LOG_REFRESH_INTERVAL_MS)
        }
    }

    private val downloadStateListener: (ModelDownloadState) -> Unit = { state ->
        runOnUiThread {
            renderModelState(state)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        goalInput = findViewById(R.id.editGoal)
        modelStateText = findViewById(R.id.textModelState)
        agentLogsText = findViewById(R.id.textAgentLogs)

        modelController.addDownloadStateListener(downloadStateListener)
        renderModelState(modelController.currentDownloadState())
        renderAgentLogs()

        findViewById<Button>(R.id.buttonOpenAccessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.buttonDownloadModel).setOnClickListener {
            val started = modelController.startPreferredDownload()
            if (started) {
                toast(getString(R.string.toast_download_started))
            } else {
                toast(getString(R.string.toast_download_already_running))
            }
        }

        findViewById<Button>(R.id.buttonPauseDownload).setOnClickListener {
            val paused = modelController.pauseDownload()
            if (paused) {
                toast(getString(R.string.toast_download_paused))
            } else {
                toast(getString(R.string.toast_download_not_running))
            }
        }

        findViewById<Button>(R.id.buttonResumeDownload).setOnClickListener {
            val resumed = modelController.resumeDownload()
            if (resumed) {
                toast(getString(R.string.toast_download_resumed))
            } else {
                toast(getString(R.string.toast_download_not_paused))
            }
        }

        findViewById<Button>(R.id.buttonCancelDownload).setOnClickListener {
            val cancelled = modelController.cancelDownload(deletePartial = false)
            if (cancelled) {
                toast(getString(R.string.toast_download_cancelled))
            } else {
                toast(getString(R.string.toast_download_not_cancellable))
            }
        }

        findViewById<Button>(R.id.buttonLoadModel).setOnClickListener {
            toast(getString(R.string.toast_model_load_started))
            Thread {
                try {
                    val result = modelController.ensureModelLoaded()
                    runOnUiThread {
                        renderLoadResult(result)
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        toast(getString(R.string.toast_model_load_failed, t.message ?: "unknown error"))
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.buttonRunGoal).setOnClickListener {
            val goal = goalInput.text.toString().ifBlank { getString(R.string.default_goal_text) }
            sendGoalToRunner(goal)
        }

        findViewById<Button>(R.id.buttonRefreshLogs).setOnClickListener {
            renderAgentLogs()
        }

        findViewById<Button>(R.id.buttonClearLogs).setOnClickListener {
            AgentLogStore.clear()
            renderAgentLogs()
        }
    }

    override fun onDestroy() {
        modelController.removeDownloadStateListener(downloadStateListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        renderModelState(modelController.currentDownloadState())
        renderAgentLogs()
        uiHandler.removeCallbacks(logsRefreshRunnable)
        uiHandler.post(logsRefreshRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(logsRefreshRunnable)
        super.onPause()
    }

    private fun renderLoadResult(result: EnsureModelLoadedResult) {
        when (result) {
            is EnsureModelLoadedResult.Ready -> {
                toast(getString(R.string.toast_model_load_ready, result.variant.fileName))
            }

            is EnsureModelLoadedResult.MissingFiles -> {
                val fileNames = result.variants.joinToString { it.fileName }
                toast(getString(R.string.toast_model_load_missing, fileNames))
            }

            is EnsureModelLoadedResult.Failed -> {
                toast(getString(R.string.toast_model_load_failed, result.message))
            }
        }
        renderModelState(modelController.currentDownloadState())
    }

    private fun renderModelState(state: ModelDownloadState) {
        val text = when (state) {
            ModelDownloadState.Idle -> getString(R.string.model_state_idle)

            is ModelDownloadState.Downloading -> {
                val total = state.totalBytes
                if (total != null && total > 0L) {
                    val percent = ((state.downloadedBytes.toDouble() / total.toDouble()) * 100.0)
                        .coerceIn(0.0, 100.0)
                        .roundToInt()
                    getString(
                        R.string.model_state_downloading,
                        state.variant.fileName,
                        percent,
                        formatBytes(state.downloadedBytes),
                        formatBytes(total)
                    )
                } else {
                    getString(
                        R.string.model_state_downloading_unknown,
                        state.variant.fileName,
                        formatBytes(state.downloadedBytes)
                    )
                }
            }

            is ModelDownloadState.Paused -> {
                getString(
                    R.string.model_state_paused,
                    state.variant.fileName,
                    formatBytes(state.downloadedBytes),
                    state.totalBytes?.let(::formatBytes) ?: "unknown"
                )
            }

            is ModelDownloadState.Verifying -> {
                getString(R.string.model_state_verifying, state.variant.fileName)
            }

            is ModelDownloadState.Completed -> {
                getString(R.string.model_state_completed, state.variant.fileName)
            }

            is ModelDownloadState.Cancelled -> {
                getString(
                    R.string.model_state_cancelled,
                    state.variant.fileName,
                    state.keptPartialFile.toString()
                )
            }

            is ModelDownloadState.Failed -> {
                getString(
                    R.string.model_state_failed,
                    state.variant.fileName,
                    state.message
                )
            }
        }

        val runtimeText = modelController.currentLoadedVariant()?.let {
            getString(R.string.model_runtime_loaded, it.fileName)
        } ?: getString(R.string.model_runtime_not_loaded)

        modelStateText.text = "$text\n$runtimeText"
    }

    private fun renderAgentLogs() {
        val lines = AgentLogStore.snapshot(limit = 220)
        agentLogsText.text = if (lines.isEmpty()) {
            getString(R.string.agent_logs_empty)
        } else {
            lines.joinToString("\n")
        }
    }

    private fun sendGoalToRunner(goal: String) {
        if (!isSecondSenseAccessibilityEnabled()) {
            toast(getString(R.string.toast_accessibility_not_enabled))
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val intent = Intent(MyAccessibilityService.ACTION_RUN_AGENT_GOAL).apply {
            setPackage(packageName)
            putExtra(MyAccessibilityService.EXTRA_USER_GOAL, goal)
            putExtra(MyAccessibilityService.EXTRA_USER_CONFIRMED, true)
        }
        sendBroadcast(intent)

        AgentLogStore.append("MainActivity", "Goal broadcast sent: $goal")
        renderAgentLogs()
        toast(getString(R.string.goal_sent, goal))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun isSecondSenseAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expected = ComponentName(this, MyAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun formatBytes(value: Long): String {
        val kib = 1024.0
        val mib = kib * 1024.0
        val gib = mib * 1024.0

        return when {
            value >= gib -> String.format("%.2f GiB", value / gib)
            value >= mib -> String.format("%.2f MiB", value / mib)
            value >= kib -> String.format("%.2f KiB", value / kib)
            else -> "$value B"
        }
    }

    private companion object {
        private const val LOG_REFRESH_INTERVAL_MS = 900L
    }
}
