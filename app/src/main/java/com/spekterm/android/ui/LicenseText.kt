package com.spekterm.android.ui

/**
 * `res/raw/third_party_licenses.txt` split for the Licenses screen (`play-release` D4): `[app]`, `[libraries]`,
 * `[licenses]` and `[notices]` start sections, shown as headings; blank lines part paragraphs; each library entry is a
 * paragraph of its own. A paragraph whose lines are indented (a license's own layout) is preformatted.
 */
object LicenseText {
    enum class Section(val marker: String) { APP("[app]"), LIBRARIES("[libraries]"), LICENSES("[licenses]"), NOTICES("[notices]") }

    sealed interface Block {
        data class Heading(val section: Section) : Block

        data class Paragraph(val text: String, val preformatted: Boolean) : Block
    }

    fun blocks(text: String): List<Block> {
        val out = mutableListOf<Block>()
        var section: Section? = null
        val lines = mutableListOf<String>()
        fun flush() {
            if (lines.isEmpty()) return
            out += Block.Paragraph(lines.joinToString("\n"), lines.any { it.startsWith(" ") })
            lines.clear()
        }
        for (line in text.lines()) {
            val marker = Section.entries.firstOrNull { it.marker == line.trim() }
            when {
                marker != null -> {
                    flush()
                    section = marker
                    out += Block.Heading(marker)
                }
                line.isBlank() -> flush()
                section == Section.LIBRARIES -> {
                    flush()
                    out += Block.Paragraph(line, false)
                }
                else -> lines += line.trimEnd()
            }
        }
        flush()
        return out
    }
}
