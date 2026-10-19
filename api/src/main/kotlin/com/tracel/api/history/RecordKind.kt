package com.tracel.api.history

import com.tracel.api.Unstable

/** Which kind of [HistoryRecord] a query reads. */
@Unstable
public enum class RecordKind {
    BLOCK,
    ENTITY,
    ITEM,
    EVENT,
}
