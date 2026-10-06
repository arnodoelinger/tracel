package com.tracel.plugin.command.suggest.quantity

import com.tracel.plugin.i18n.tr
import net.kyori.adventure.text.Component

/** `4 blocks around you`; [noun] is `block` or `chunk`. */
internal fun around(amount: Long, noun: String): Component = tr("suggest.around.$noun", "count" to amount)

/** `2 days ago`; [noun] is `second` up to `week`. */
internal fun ago(amount: Long, noun: String): Component = tr("suggest.ago", "span" to span(amount, noun))

/** `Past 10 minutes`; [noun] is `second` up to `week`. */
internal fun past(amount: Long, noun: String): Component = tr("suggest.past", "span" to span(amount, noun))

/** `10 minutes`, pluralized the reader's way. */
internal fun span(amount: Long, noun: String): Component = tr("suggest.span.$noun", "count" to amount)
