package com.knowyourphone.app.model

data class UiSnapshot(
    val screen: ScreenInfo,
    val nodes: List<UiNode>
)

data class ScreenInfo(
    val packageName: String,
    val timestamp: Long
)

data class UiNode(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val bounds: Bounds,
    val resourceId: String? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val focused: Boolean? = null,
    val scrollable: Boolean? = null,
    val depth: Int? = null,
    val childCount: Int? = null,
    val inputType: Int? = null,
    val isPassword: Boolean? = null
)

data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
