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

    /**
     * The connection itself — keys, addresses, endpoint — in the order the editor shows them. Shown only with
     * «Разрешить редактирование конфигураций» (the plain editor takes the whole INI text instead).
     */
    val CONNECTION: List<Knob> = listOf(
        Knob("PrivateKey", "", Kind.Text),
        Knob("Address", "10.0.0.2/32, fd00::2/128", Kind.Text),
        Knob("PublicKey", "", Kind.Text, Section.Peer),
        Knob("PresharedKey", "", Kind.Text, Section.Peer),
        Knob("Endpoint", "host:51820", Kind.Text, Section.Peer),
        Knob("AllowedIPs", "0.0.0.0/0, ::/0", Kind.Text, Section.Peer),
    )

    /** Keys that make a wg-quick INI AmneziaWG rather than plain WireGuard (lower case). */
    val OBFUSCATION_KEYS: Set<String> = setOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4",
        "i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3", "itime",
    )

    /** What a freshly chosen AmneziaWG starts from: the values that behave as plain WireGuard plus junk packets. */
    private val AWG_SEED = listOf(
        "Jc" to "4", "Jmin" to "40", "Jmax" to "70", "S1" to "0", "S2" to "0",
        "H1" to "1", "H2" to "2", "H3" to "3", "H4" to "4",
    )

    /** True when [ini] sets any obfuscation parameter (AmneziaWG); false for plain WireGuard. */
    fun isAwg(ini: String): Boolean = OBFUSCATION_KEYS.any { get(ini, it).isNotBlank() }

    /** The obfuscation parameters [ini] sets, as (key, value) — what switching to plain WireGuard takes away. */
    fun obfuscation(ini: String): List<Pair<String, String>> =
        KNOBS.filter { it.key.lowercase() in OBFUSCATION_KEYS }
            .mapNotNull { knob -> get(ini, knob.key).takeIf { it.isNotBlank() }?.let { knob.key to it } }

    /** [ini] as plain WireGuard: every obfuscation parameter removed. */
    fun toWireGuard(ini: String): String =
        OBFUSCATION_KEYS.fold(ini) { acc, key -> set(acc, key, "") }

    /** [ini] as AmneziaWG: the seed parameters added where it sets none. */
    fun toAmneziaWg(ini: String): String =
        if (isAwg(ini)) ini else AWG_SEED.fold(ini) { acc, (key, value) -> set(acc, key, value) }

    /** An empty config to fill in: the section layout and the usual defaults, no keys yet. */
    fun skeleton(awg: Boolean): String {
        val base = "[Interface]\nPrivateKey = \nAddress = 10.0.0.2/32\nDNS = 1.1.1.1\nMTU = 1280\n\n" +
            "[Peer]\nPublicKey = \nEndpoint = \nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 25"
        return if (awg) toAmneziaWg(base) else base
    }

    /** The required parameters [ini] still lacks (what the engine refuses to start without); empty when usable. */
    fun missing(ini: String): List<String> = buildList {
        if (get(ini, "PrivateKey").isBlank()) add("PrivateKey")
        if (get(ini, "Address").isBlank()) add("Address")
        if (get(ini, "PublicKey", Section.Peer).isBlank()) add("PublicKey")
        if (get(ini, "Endpoint", Section.Peer).isBlank()) add("Endpoint")
    }

    /** `host` and `port` of the peer Endpoint (port 0 when absent or unreadable), for display and de-duplication. */
    fun endpoint(ini: String): Pair<String, Int> {
        val value = get(ini, "Endpoint", Section.Peer)
        val colon = value.lastIndexOf(':')
        if (colon <= 0) return value.removeSurrounding("[", "]") to 0
        return value.substring(0, colon).removeSurrounding("[", "]") to (value.substring(colon + 1).toIntOrNull() ?: 0)
    }

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
