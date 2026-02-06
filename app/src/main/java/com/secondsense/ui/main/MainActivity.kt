package com.secondsense.ui.main

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.secondsense.R
import com.secondsense.access.MyAccessibilityService
import com.secondsense.agent.Action
import com.secondsense.agent.AgentResponse
import com.secondsense.agent.Selector
import com.secondsense.agent.Status
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MainActivity : AppCompatActivity() {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.buttonOpenAccessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.buttonRunDebugPlan).setOnClickListener {
            sendDebugModelOutput()
        }
    }

    private fun sendDebugModelOutput() {
        val response = AgentResponse(
            status = Status.IN_PROGRESS,
            actions = listOf(
                Action.OpenApp("Instagram"),
                Action.Wait(ms = 1200),
                Action.Click(selector = Selector(textContains = "Messages"))
            )
        )

        val rawModelOutput = json.encodeToString(response)
        val intent = Intent(MyAccessibilityService.ACTION_RUN_MODEL_OUTPUT).apply {
            setPackage(packageName)
            putExtra(MyAccessibilityService.EXTRA_MODEL_OUTPUT, rawModelOutput)
            putExtra(MyAccessibilityService.EXTRA_USER_CONFIRMED, true)
        }
        sendBroadcast(intent)

        Toast.makeText(this, R.string.debug_plan_sent, Toast.LENGTH_SHORT).show()
    }
}
