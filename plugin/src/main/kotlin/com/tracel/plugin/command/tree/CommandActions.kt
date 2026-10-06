package com.tracel.plugin.command.tree

import com.tracel.plugin.command.action.*
import com.tracel.plugin.command.action.support.NothingWeCanDo
import com.tracel.plugin.command.highlight.Highlights
import com.tracel.plugin.command.preset.PresetStore
import com.tracel.plugin.command.preset.Presets
import com.tracel.plugin.services.TracelServices

/** What the command tree runs: one action per command, built once when the tree is registered. */
internal class CommandActions(val services: TracelServices) {
    private val nothing = NothingWeCanDo(services)
    val undo = UndoAction(services, nothing)
    private val highlights = Highlights(services.plugin)
    val rollback = RollbackAction(services, highlights)
    val lookup: LookupAction = services.lookup
    val inspect = InspectAction(services)
    val purge = PurgeAction(services)
    val status = StatusAction(services)
    val export = ExportAction(services)
    val import = ImportAction(services)
    val coreProtect = CoreProtectImportAction(services)
    val store = PresetStore(services.plugin.dataFolder.toPath().resolve("presets.toml")) { version ->
        services.plugin.logger.info("Updated presets.toml to version $version.")
    }.also { Presets.store = it }
    val presets = PresetAction(store, nothing)
    val player = PlayerAction(services)
}
