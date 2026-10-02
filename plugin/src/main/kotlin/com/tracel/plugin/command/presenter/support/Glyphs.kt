package com.tracel.plugin.command.presenter.support

/**
 * How wide text is in the default chat font, in pixels, for keeping a line on one row.
 *
 * Chat is 320 wide unless the player changed it; glyphs are 6 wide but for a few, and a space is 4.
 *
 * Names the client translates are measured by their English spelling, which is close enough for a cut
 * that only has to stay clear of the edge.
 */
internal object Glyphs {
    const val LINE: Int = 306

    private const val ELLIPSIS = "..."

    private val NARROW = mapOf(
        ' ' to 4, '!' to 2, '"' to 5, '\'' to 3, '(' to 5, ')' to 5, '*' to 5, ',' to 2, '.' to 2, ':' to 2, ';' to 2,
        '<' to 5, '>' to 5, 'I' to 4, '[' to 4, ']' to 4, '`' to 3, 'f' to 5, 'i' to 2, 'k' to 5, 'l' to 3, 't' to 4,
        '{' to 5, '}' to 5, '|' to 2, '~' to 7, '@' to 7,
    )

    /** How many pixels [text] takes in the default chat font. */
    fun width(text: String): Int = text.sumOf { NARROW[it] ?: 6.toInt() }

    /** [text] cut to fit [room] pixels, ending in `...` when something had to go. */
    fun clip(text: String, room: Int): String {
        if (width(text) <= room) return text
        var cut = text
        while (cut.isNotEmpty() && width(cut) + width(ELLIPSIS) > room) cut = cut.dropLast(1)
        return cut.trimEnd() + ELLIPSIS
    }
}
