package com.secondsense.agent

interface AgentModelClient {
    fun generate(prompt: String): String
}
