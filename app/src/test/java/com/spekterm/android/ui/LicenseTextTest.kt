package com.spekterm.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `LicenseText` (`play-release` D4), and the shipped file read through it. */
class LicenseTextTest {
    @Test
    fun sectionsBecomeHeadingsAndLibrariesOneParagraphEach() {
        val blocks = LicenseText.blocks("[app]\nspekterm\n\nMIT License\n[libraries]\na.* — A — Apache\nb — B — MIT\n[licenses]\nApache\n   1. Definitions.\n")
        assertEquals(
            listOf(
                LicenseText.Block.Heading(LicenseText.Section.APP),
                LicenseText.Block.Paragraph("spekterm", false),
                LicenseText.Block.Paragraph("MIT License", false),
                LicenseText.Block.Heading(LicenseText.Section.LIBRARIES),
                LicenseText.Block.Paragraph("a.* — A — Apache", false),
                LicenseText.Block.Paragraph("b — B — MIT", false),
                LicenseText.Block.Heading(LicenseText.Section.LICENSES),
                LicenseText.Block.Paragraph("Apache\n   1. Definitions.", true),
            ),
            blocks,
        )
    }

    @Test
    fun theShippedFileHasEverySectionInOrder() {
        val file = listOf(File("src/main/res/raw/third_party_licenses.txt"), File("app/src/main/res/raw/third_party_licenses.txt")).first { it.isFile }
        val headings = LicenseText.blocks(file.readText()).filterIsInstance<LicenseText.Block.Heading>().map { it.section }
        assertEquals(LicenseText.Section.entries, headings)
        assertTrue("the app's own MIT license comes first", file.readText().substringBefore("[libraries]").contains("Copyright (c) 2026 Kewang"))
    }
}
