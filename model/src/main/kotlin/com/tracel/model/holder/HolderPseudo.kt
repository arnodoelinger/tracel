package com.tracel.model.holder

/** Whether this holder only stands for the world outside the ledger: a source or a sink. */
public fun HolderId.isPseudo(): Boolean = this is HolderId.Source || this is HolderId.Sink
