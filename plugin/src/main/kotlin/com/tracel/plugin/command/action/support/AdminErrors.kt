package com.tracel.plugin.command.action.support

import com.tracel.api.admin.AdminError
import com.tracel.plugin.i18n.said

/** What a failed admin call says when nothing more specific is known. */
internal fun AdminError.describe(): String = said(
    when (this) {
        is AdminError.Failed -> message
        is AdminError.Denied -> "no access to $folder"
        is AdminError.NoSuchFile -> "no file $name"
        is AdminError.BadName -> "$name is not a snapshot"
        AdminError.Busy -> "busy"
        AdminError.Revoked -> "revoked"
        AdminError.Stopped -> "stopped"
        AdminError.NoSpace -> "no space left"
        AdminError.NoSource -> "no database found"
        AdminError.NoRoom -> "no room"
    },
)
