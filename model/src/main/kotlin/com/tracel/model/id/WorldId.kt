package com.tracel.model.id

import java.util.UUID

/** Identifies a Minecraft world. */
@JvmInline
public value class WorldId(public val uuid: UUID)
