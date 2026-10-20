package com.tracel.api.history

import com.tracel.api.Unstable
import com.tracel.api.actor.Actor
import java.time.Instant

/** Someone who took items out of a container: how many in all, and when they last did. */
@Unstable
public data class ContainerTaker(public val actor: Actor?, public val amount: Long, public val last: Instant)
