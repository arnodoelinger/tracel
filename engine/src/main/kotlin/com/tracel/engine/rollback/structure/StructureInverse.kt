package com.tracel.engine.rollback.structure

/** Swap expected / target so apply-then-inverse is identity. */
public fun StructureStep.inverse(): StructureStep = when (this) {
    is StructureStep.SetBlock -> StructureStep.SetBlock(at, expected, target)
    is StructureStep.SpawnEntity ->
        if (expected == null) StructureStep.RemoveEntity(at, entity, shape)
        else StructureStep.SpawnEntity(at, entity, expected, shape)

    is StructureStep.RemoveEntity -> StructureStep.SpawnEntity(at, entity, shape)
}
