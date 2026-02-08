package com.secondsense.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object AgentLogStore {

    private const val MAX_ENTRIES = 500
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = ReentrantLock()
    private val entries = ArrayDeque<String>()

    fun append(source: String, message: String) {
        val line = "${timestamp()} [$source] $message"
        lock.withLock {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
    }

    fun snapshot(limit: Int = 250): List<String> {
        lock.withLock {
            if (entries.isEmpty()) return emptyList()
            return entries.toList().takeLast(limit)
        }
    }

    fun clear() {
        lock.withLock {
            entries.clear()
        }
    }

    private fun timestamp(): String {
        synchronized(timestampFormat) {
            return timestampFormat.format(Date())
        }
    }
}
