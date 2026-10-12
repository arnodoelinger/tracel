package com.tracel.plugin.integration.update

import io.github.z4kn4fein.semver.Version

/**
 * A published `Tracel` release.
 *
 * @param page the release page on Modrinth, with the changes
 * @param download where the .jar is taken from, which is [page] too: Modrinth has the download button there
 */
data class Release(val version: Version, val page: String, val download: String)
