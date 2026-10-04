package com.tracel.platform

/**
 * Every version `Tracel` keeps.
 *
 * Bumping one means editing this file and, where it says so, adding what the bump needs.
 */
public object Versions {
    /**
     * The layout of what `Tracel` writes.
     *
     * A reader that does not know a version refuses it rather than guess.
     */
    public object Format {
        /**
         * Which database it is.
         *
         * Not expected to ever change; another major means export and import.
         *
         * Bumping it means a new database.
         */
        public const val DB_MAJOR: Int = 1

        /**
         * The database layout within [DB_MAJOR].
         *
         * Bump it together with a migration in `StoreFormat.MIGRATIONS`.
         */
        public const val DB_MINOR: Int = 0

        /**
         * The first byte of every record in the logs.
         *
         * A new one needs a decoder for the old.
         */
        public const val RECORD: Byte = 1

        /** Segment file version. */
        public const val SEGMENT: Int = 1

        /** The manifest version that lists segments and logs. */
        public const val MANIFEST: Int = 1

        /** Export version. */
        public const val EXPORT: Int = 1

        /**
         * Config version.
         *
         * Bump it together with a step in `FileVersions.CONFIG_STEPS` and `config.yml` file.
         */
        public const val CONFIG: Int = 1

        /** Preset version. */
        public const val PRESETS: Int = 1
    }

    /** The game. */
    public object Minecraft {
        /**
         * Releases `Tracel` has been tested on, as (major, minor). Patch versions are covered.
         *
         * The oldest is also the `api-version` in `paper-plugin.yml`, the Paper API the plugin is
         * built against, and the version `runServer` starts.
         */
        public val SUPPORTED: List<Pair<Int, Int>> = listOf(
            26 to 1,
            26 to 2,
            26 to 3,
        )
    }
}
