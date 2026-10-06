package com.tracel.plugin.command.args.scope

internal sealed interface ScopeValue {
    data class Radius(val scope: LookupScope) : ScopeValue
    data class World(val name: String) : ScopeValue
}
