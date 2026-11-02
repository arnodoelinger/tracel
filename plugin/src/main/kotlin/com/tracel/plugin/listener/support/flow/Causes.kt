package com.tracel.plugin.listener.support.flow

import com.tracel.model.cause.CauseKind

/** [CauseKind.PLAYER_ACTION] when somebody [by] is behind it, [otherwise] when nobody is. */
internal fun causeOr(by: Any?, otherwise: CauseKind): CauseKind =
    if (by != null) CauseKind.PLAYER_ACTION else otherwise
