package com.secondsense.ui.main

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.secondsense.R
import com.secondsense.access.MyAccessibilityService

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val goalInput = findViewById<EditText>(R.id.editGoal)

        findViewById<Button>(R.id.buttonOpenAccessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.buttonRunMockGoal).setOnClickListener {
            val goal = goalInput.text.toString().ifBlank { getString(R.string.default_goal_text) }
            sendGoalToRunner(goal)
        }
    }

    private fun sendGoalToRunner(goal: String) {
        val intent = Intent(MyAccessibilityService.ACTION_RUN_AGENT_GOAL).apply {
            setPackage(packageName)
            putExtra(MyAccessibilityService.EXTRA_USER_GOAL, goal)
            putExtra(MyAccessibilityService.EXTRA_USER_CONFIRMED, true)
        }
        sendBroadcast(intent)

        Toast.makeText(this, getString(R.string.mock_goal_sent, goal), Toast.LENGTH_SHORT).show()
    }
}
