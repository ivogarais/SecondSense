package com.secondsense.llm

import android.content.Context

object LocalModelProvider {

    @Volatile
    private var instance: LocalModelController? = null

    fun get(context: Context): LocalModelController {
        instance?.let { return it }

        synchronized(this) {
            instance?.let { return it }
            val created = LocalModelController(context.applicationContext)
            instance = created
            return created
        }
    }
}
