package com.tracel.plugin.integration.update

import io.github.z4kn4fein.semver.Version

/**
 * A published `Tracel` release.
 *
 * @param page the release page, with the changes
 * @param download the jar itself, or [page] if the release has none
 */
data class Release(val version: Version, val page: String, val download: String)
