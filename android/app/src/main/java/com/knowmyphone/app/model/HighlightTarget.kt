package com.knowmyphone.app.model

data class HighlightTarget(
    val elementId: String,
    val label: String,
    val bounds: Bounds? = null
)
