import Foundation

public enum EmojiParser {
    /// Extracts a leading emoji (such as country flags 🇫🇮, 🇵🇱, 🇩🇪 or symbols) from a string.
    public static func parse(from raw: String, fallbackIcon: String? = nil) -> (emoji: String, name: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return (fallbackIcon ?? "", "") }

        var index = trimmed.startIndex
        var emojiScalars = String.UnicodeScalarView()

        while index < trimmed.endIndex {
            let scalar = trimmed.unicodeScalars[index]
            if isEmoji(scalar) || isEmojiJoiner(scalar) {
                emojiScalars.append(scalar)
                index = trimmed.unicodeScalars.index(after: index)
            } else {
                break
            }
        }

        if emojiScalars.isEmpty {
            return (fallbackIcon ?? "", trimmed)
        }

        let emoji = String(emojiScalars)
        let name = String(trimmed[index...]).trimmingCharacters(in: .whitespacesAndNewlines)
        return (emoji, name.isEmpty ? trimmed : name)
    }

    private static func isEmoji(_ scalar: Unicode.Scalar) -> Bool {
        let value = scalar.value
        return (0x1F1E6...0x1F1FF).contains(value) || // Regional indicator symbols (Flags)
            (0x1F300...0x1FAFF).contains(value) ||     // Main emoji blocks
            (0x2600...0x27BF).contains(value) ||       // Misc symbols & dingbats
            (0x2B00...0x2BFF).contains(value) ||       // Arrows & stars
            (0x2190...0x21FF).contains(value) ||       // Arrows
            (0x2300...0x23FF).contains(value) ||       // Technical
            scalar.properties.isEmoji
    }

    private static func isEmojiJoiner(_ scalar: Unicode.Scalar) -> Bool {
        let value = scalar.value
        return value == 0x200D || value == 0xFE0F || value == 0xFE0E || (0x1F3FB...0x1F3FF).contains(value)
    }
}
