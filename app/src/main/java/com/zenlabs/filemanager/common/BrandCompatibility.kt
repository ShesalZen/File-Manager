package com.zenlabs.filemanager.common

/**
 * Stable names and legacy identifiers used by the ZenLabs rebrand.
 *
 * Legacy values are intentionally retained so data created by upstream Prism releases remains
 * readable. New files and directories must use the ZenLabs names.
 */
object BrandCompatibility {
    const val ZENLABS_APP_DIRECTORY = ".zenlabs"
    const val LEGACY_PRISM_APP_DIRECTORY = ".prism"

    const val ZENLABS_PREFERENCES_EXTENSION = "zenlabsprefs"
    const val LEGACY_PRISM_PREFERENCES_EXTENSION = "prismprefs"
    const val ZENLABS_PREFERENCES_FILE_NAME = "preferences.zenLabsPrefs"

    /** Keep the upstream datastore key so preference backups remain portable across rebrands. */
    const val SAVE_LOCATION_HISTORY_PREFERENCE_KEY = "saveToPrismHistoryJson"
}
