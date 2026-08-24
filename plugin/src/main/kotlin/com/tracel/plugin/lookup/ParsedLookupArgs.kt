package com.tracel.plugin.lookup

/** Raw, un-resolved lookup flags — folded together one token at a time by [parseLookupArgs]. */
data class ParsedLookupArgs(
    val users: Set<String> = emptySet(),
    val excludedUsers: Set<String> = emptySet(),
    val item: String? = null,
    val actions: Set<String> = emptySet(),
    val since: Long? = null,
    val until: Long? = null,
    val scope: LookupScope? = null,
    val horizontalOnly: Boolean = false,
    val count: Boolean = false,
    val countOnly: Boolean = false,
    val limit: Int? = null,
    val offset: Int = 0,
    val errors: List<String> = emptyList(),
)
