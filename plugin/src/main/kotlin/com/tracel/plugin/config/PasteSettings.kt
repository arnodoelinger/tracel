package com.tracel.plugin.config

/** Where a lookup export goes. */
data class PasteSettings(
    val url: String = DEFAULT_PASTE_URL,
    val expire: String = DEFAULT_PASTE_EXPIRE,
    val burn: Boolean = false,
)
