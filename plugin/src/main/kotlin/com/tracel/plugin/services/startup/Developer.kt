package com.tracel.plugin.services.startup

import com.tracel.plugin.services.maintenance.isDevelopmentBuild

/** Developer mode. */
internal object Developer {
    const val PROPERTY: String = "tracel.developer"

    val enabled: Boolean = System.getProperty(PROPERTY)?.let { !it.equals("false", ignoreCase = true) } ?: false

    /** Stops a development build that was not asked for. */
    fun require(version: String, on: Boolean = enabled) {
        if (isDevelopmentBuild(version) && !on) throw DeveloperFlagMissing(version)
    }
}

/** A development build was started without developer mode. */
class DeveloperFlagMissing(version: String) : IllegalStateException(
    "Tracel $version is a development build. It runs only in developer mode: start the server with " +
        "-D${Developer.PROPERTY}=true, or use a release.",
)
