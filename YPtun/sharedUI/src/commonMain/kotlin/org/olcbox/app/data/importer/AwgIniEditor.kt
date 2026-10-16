package org.olcbox.app.data.importer

/**
 * Reads and rewrites single parameters of an AmneziaWG wg-quick INI ([org.olcbox.app.data.model.ProxyProfile.awgConfig])
 * for the fine-tuning editor. Everything else in the text — keys, addresses, endpoint, comments, key order — is left
 * exactly as it was; only the one line asked for is replaced, appended to its section, or (blank value) removed.
 */
object AwgIniEditor {

    enum class Section(val header: String) { Interface("Interface"), Peer("Peer") }

    /** [kind] decides what the field accepts; [hint] is the usual value shown as a placeholder. */
    class Knob(val key: String, val hint: String, val kind: Kind = Kind.Number, val section: Section = Section.Interface)

    enum class Kind {
        /** Digits only. */
        Number,
        /** A value or a range ("1000000-2000000"): digits and dashes. */
        Range,
        /** Free text on one line (CPS packets `<b 0x…><r 20>`, DNS, ...). */
        Text,
    }

    /** What the editor offers, in the order it shows them. All of them are understood by the awgproxy engine. */
    val KNOBS: List<Knob> = listOf(
        Knob("Jc", "4"), Knob("Jmin", "40"), Knob("Jmax", "70"),
        Knob("S1", "0"), Knob("S2", "0"), Knob("S3", "0"), Knob("S4", "0"),
        Knob("H1", "1", Kind.Range), Knob("H2", "2", Kind.Range), Knob("H3", "3", Kind.Range), Knob("H4", "4", Kind.Range),
        Knob("I1", "<b 0x…>", Kind.Text), Knob("I2", "", Kind.Text), Knob("I3", "", Kind.Text),
        Knob("I4", "", Kind.Text), Knob("I5", "", Kind.Text),
        Knob("J1", "", Kind.Text), Knob("J2", "", Kind.Text), Knob("J3", "", Kind.Text), Knob("Itime", "120"),
        Knob("MTU", "1280"), Knob("DNS", "1.1.1.1", Kind.Text),
        Knob("PersistentKeepalive", "25", section = Section.Peer),
    )

    /** What [raw] may contain for a field of [kind] (other characters are dropped as the user types). */
    fun filter(raw: String, kind: Kind): String = when (kind) {
        Kind.Number -> raw.filter(Char::isDigit)
        Kind.Range -> raw.filter { it.isDigit() || it == '-' }
        Kind.Text -> raw.replace("\r", "").replace("\n", "")
    }

    /** The current value of [key] in [section], or "" when the INI does not set it. */
    fun get(ini: String, key: String, section: Section = Section.Interface): String {
        val lines = ini.split('\n')
        val index = find(lines, key, section)
        return if (index < 0) "" else lines[index].substringAfter('=').trim()
    }

    /** [ini] with [key] set to [value] in [section]; a blank [value] removes the line. Same text when nothing changes. */
    fun set(ini: String, key: String, value: String, section: Section = Section.Interface): String {
        val clean = value.trim().replace("\r", "").replace("\n", "")
        val lines = ini.replace("\r\n", "\n").split('\n').toMutableList()
        val index = find(lines, key, section)
        if (index >= 0) {
            if (clean.isEmpty()) lines.removeAt(index)
            else lines[index] = lines[index].substringBefore('=').trimEnd() + " = " + clean
            return lines.joinToString("\n")
        }
        if (clean.isEmpty()) return ini
        val start = lines.indexOfFirst { it.trim().equals("[${section.header}]", ignoreCase = true) }
        if (start < 0) {
            // The section is missing altogether (a Peer-less text): add it at the end.
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines += listOf("", "[${section.header}]", "$key = $clean")
            return lines.joinToString("\n")
        }
        var end = start + 1
        while (end < lines.size && !lines[end].trim().startsWith("[")) end++
        // Insert after the section's last non-blank line, so blank lines stay between sections.
        var at = end
        while (at - 1 > start && lines[at - 1].isBlank()) at--
        lines.add(at, "$key = $clean")
        return lines.joinToString("\n")
    }

    /** Index of the line that sets [key] inside [section] (case-insensitive key), or -1. */
    private fun find(lines: List<String>, key: String, section: Section): Int {
        var current: String? = null
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length - 1).trim()
                continue
            }
            if (current == null || !current.equals(section.header, ignoreCase = true)) continue
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            val eq = line.indexOf('=')
            if (eq > 0 && line.substring(0, eq).trim().equals(key, ignoreCase = true)) return i
        }
        return -1
    }
}
