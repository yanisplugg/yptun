import ActivityKit
import Foundation

@available(iOS 16.2, *)
public struct VpnActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        public var isConnected: Bool
        public var serverName: String
        public var flag: String
        public var connectedDate: Date?

        public init(isConnected: Bool, serverName: String, flag: String, connectedDate: Date?) {
            self.isConnected = isConnected
            self.serverName = serverName
            self.flag = flag
            self.connectedDate = connectedDate
        }
    }

    public var appName: String

    public init(appName: String = "YPtun") {
        self.appName = appName
    }
}

// MARK: - Country Flag & Name Parsing (Shared across app & widgets)

public func extractFlagAndName(from rawName: String) -> (flag: String, name: String) {
    let trimmed = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty {
        return ("🌐", "YPtun")
    }

    var emojiPrefix = ""
    for character in trimmed {
        if character.isEmojiCharacter {
            emojiPrefix.append(character)
        } else {
            break
        }
    }
    let flagCandidate = emojiPrefix.trimmingCharacters(in: .whitespaces)
    if !flagCandidate.isEmpty {
        let rest = trimmed.dropFirst(emojiPrefix.count).trimmingCharacters(in: .whitespaces)
        return (flagCandidate, rest.isEmpty ? trimmed : rest)
    }

    let uppercase = trimmed.uppercased()
    let countryMap: [(pattern: String, code: String)] = [
        ("RU", "RU"), ("RUSSIA", "RU"), ("РОССИЯ", "RU"),
        ("DE", "DE"), ("GERMANY", "DE"), ("DEUTSCHLAND", "DE"),
        ("NL", "NL"), ("NETHERLANDS", "NL"), ("HOLLAND", "NL"),
        ("US", "US"), ("USA", "US"),
        ("FI", "FI"), ("FINLAND", "FI"),
        ("TR", "TR"), ("TURKEY", "TR"),
        ("KZ", "KZ"), ("KAZAKHSTAN", "KZ"),
        ("GB", "GB"), ("UK", "GB"),
        ("FR", "FR"), ("FRANCE", "FR"),
        ("SE", "SE"), ("SWEDEN", "SE"),
        ("PL", "PL"), ("POLAND", "PL"),
        ("JP", "JP"), ("JAPAN", "JP"),
        ("SG", "SG"), ("SINGAPORE", "SG"),
        ("HK", "HK"), ("HONG KONG", "HK"),
        ("CH", "CH"), ("SWISS", "CH"),
        ("AT", "AT"), ("AUSTRIA", "AT"),
        ("CA", "CA"), ("CANADA", "CA")
    ]

    for item in countryMap {
        if uppercase.range(of: "\\b\(item.pattern)\\b", options: .regularExpression) != nil ||
           trimmed.contains(item.pattern) {
            return (emojiFlag(for: item.code), trimmed)
        }
    }

    return ("🌐", trimmed)
}

public func emojiFlag(for countryCode: String) -> String {
    let base: UInt32 = 127397
    var s = ""
    for scalar in countryCode.uppercased().unicodeScalars {
        if let converted = UnicodeScalar(base + scalar.value) {
            s.unicodeScalars.append(converted)
        }
    }
    return s.isEmpty ? "🌐" : s
}

public extension Character {
    var isEmojiCharacter: Bool {
        guard let scalar = unicodeScalars.first else { return false }
        return scalar.properties.isEmoji && (scalar.value > 0x238C || unicodeScalars.count > 1)
    }
}
