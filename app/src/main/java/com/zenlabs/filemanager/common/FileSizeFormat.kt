package com.zenlabs.filemanager.common

import java.text.DecimalFormat

/**
 * Decimal (base-1000) human-readable byte formatting.
 *
 * Sizes are rendered with the SI/decimal units used by storage vendors and Android's own
 * settings screens:
 *
 *  - 1 KB = 1,000 bytes
 *  - 1 MB = 1,000,000 bytes
 *  - 1 GB = 1,000,000,000 bytes
 *  - 1 TB = 1,000,000,000,000 bytes
 *
 * The formatting is intentionally kept pure (no Android dependencies) so it can be unit tested
 * on the JVM. Every file-size label in the app routes through [FileSizeFormat.format] via the
 * `Long.toFormattedSize()` extension, which guarantees consistency across screens.
 */
object FileSizeFormat {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    /** One decimal place at most, e.g. `1.5 MB`; whole values render without a decimal, e.g. `12 KB`. */
    private const val VALUE_PATTERN = "#,##0.#"

    /**
     * Highest value, in the current unit, that still rounds to `999.9` or less when displayed with a
     * single decimal. Anything at or above this is promoted to the next unit so a size never renders
     * as `1,000 KB` when `1 MB` is meant.
     */
    private const val PROMOTE_THRESHOLD = 999.95

    /**
     * Formats [bytes] into a human-readable decimal size string, e.g. `1.5 MB`.
     * Non-positive values render as `0 B`.
     *
     * Unit selection divides repeatedly instead of using `log10`/`pow` so the boundary between two
     * units is decided by a plain comparison and never trips on floating-point rounding.
     */
    fun format(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        var value = bytes.toDouble()
        var unitIndex = 0
        while (unitIndex < UNITS.lastIndex && value >= PROMOTE_THRESHOLD) {
            value /= 1000.0
            unitIndex++
        }
        // A fresh DecimalFormat per call keeps this thread-safe (DecimalFormat is not).
        return DecimalFormat(VALUE_PATTERN).format(value) + " " + UNITS[unitIndex]
    }
}
