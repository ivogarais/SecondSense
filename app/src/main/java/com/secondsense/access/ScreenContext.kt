package com.secondsense.access

import kotlinx.serialization.Serializable

@Serializable
data class ScreenContext(
    val currentAppPackage: String? = null,
    val timestamp: String,
    val nodes: List<NodeInfo>
)

@Serializable
data class NodeInfo(
    val id: String,
    val text: String? = null,
    val contentDesc: String? = null,
    val className: String? = null,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val focusable: Boolean,
    val bounds: Bounds
)

@Serializable
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
