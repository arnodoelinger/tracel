package com.tracel.plugin.command.action.support

import com.tracel.api.history.HistoryReadError
import com.tracel.plugin.i18n.said

/** What a failed read says, the way a failure is told to the sender. */
internal fun HistoryReadError.describe(): String = said(
    when (this) {
        HistoryReadError.NotFound -> "nothing found"
        is HistoryReadError.Unsupported -> reason
        is HistoryReadError.Failed -> message
    },
)
