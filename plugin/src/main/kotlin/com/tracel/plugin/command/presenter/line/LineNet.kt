package com.tracel.plugin.command.presenter.line

import com.tracel.plugin.command.presenter.ItemPresenter
import net.kyori.adventure.text.Component

/** [delta] is what this line did to the side [family] counts for: positive for [ItemPresenter.Family.plus]. */
internal class LineNet(val family: ItemPresenter.Family, val delta: Long, val item: Component, val material: String)
