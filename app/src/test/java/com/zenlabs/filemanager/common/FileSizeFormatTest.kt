package com.zenlabs.filemanager.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Verifies the decimal (base-1000) size formatting behind every size label in the app.
 *
 * The locale is pinned to `en-US` because [FileSizeFormat] intentionally uses a locale-aware
 * [java.text.DecimalFormat] (`#,##0.#`), so the decimal and grouping separators must be fixed for the
 * expectations below to be meaningful on any developer machine.
 */
class FileSizeFormatTest {

    private lateinit var originalLocale: Locale

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("en-US"))
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun nonPositiveSizesRenderAsZeroBytes() {
        assertEquals("0 B", FileSizeFormat.format(0L))
        assertEquals("0 B", FileSizeFormat.format(-1L))
        assertEquals("0 B", FileSizeFormat.format(Long.MIN_VALUE))
    }

    @Test
    fun valuesBelowOneThousandStayInBytes() {
        assertEquals("1 B", FileSizeFormat.format(1L))
        assertEquals("512 B", FileSizeFormat.format(512L))
        assertEquals("999 B", FileSizeFormat.format(999L))
    }

    @Test
    fun kilobytesUseTheDecimalDivisor() {
        assertEquals("1 KB", FileSizeFormat.format(1_000L))
        assertEquals("1.5 KB", FileSizeFormat.format(1_500L))
        assertEquals("999.9 KB", FileSizeFormat.format(999_900L))
    }

    @Test
    fun megabytesGigabytesAndTerabytesUseTheirOwnUnits() {
        assertEquals("1 MB", FileSizeFormat.format(1_000_000L))
        assertEquals("1.5 MB", FileSizeFormat.format(1_500_000L))
        assertEquals("1 GB", FileSizeFormat.format(1_000_000_000L))
        assertEquals("1.5 GB", FileSizeFormat.format(1_500_000_000L))
        assertEquals("1 TB", FileSizeFormat.format(1_000_000_000_000L))
        assertEquals("2.5 TB", FileSizeFormat.format(2_500_000_000_000L))
    }

    @Test
    fun sizesNeverDisplayOneThousandOfASmallerUnit() {
        // 999,999 bytes must read as "1 MB", not "1,000 KB".
        assertEquals("1 MB", FileSizeFormat.format(999_999L))
    }

    @Test
    fun singleDecimalIsRoundedHalfUpOnTheDisplayedDigit() {
        // 1,999 bytes -> 1.999 KB -> "2 KB" (no trailing ".0").
        assertEquals("2 KB", FileSizeFormat.format(1_999L))
        // 12 MB stays integral.
        assertEquals("12 MB", FileSizeFormat.format(12_000_000L))
    }

    @Test
    fun valuesLargerThanOneTerabyteStayInTerabytesAndKeepGrouping() {
        assertEquals("9,000 TB", FileSizeFormat.format(9_000_000_000_000_000L))
        assertEquals("1,000 TB", FileSizeFormat.format(1_000_000_000_000_000L))
    }

    @Test
    fun utilsExtensionDelegatesToTheSharedFormatter() {
        assertEquals(FileSizeFormat.format(1_500_000L), 1_500_000L.toFormattedSize())
        assertEquals("1.5 MB", 1_500_000L.toFormattedSize())
    }
}
