package com.tracel.model.world.entity

/** What an entity looks like. */
public data class EntityShape(
    public val type: EntityTypeKey,
    public val x: Double,
    public val y: Double,
    public val z: Double,
    public val yaw: Float = 0f,
    public val pitch: Float = 0f,
    public val extras: EntityExtras? = null,
)
