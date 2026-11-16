import SwiftUI

public final class YPtunThemeManager: ObservableObject, @unchecked Sendable {
    public static let shared = YPtunThemeManager()

    private let lock = NSLock()
    private var _accentHex: UInt

    @Published public var accentHex: UInt {
        didSet {
            lock.lock()
            _accentHex = accentHex
            lock.unlock()
            UserDefaults.standard.set(String(format: "%06X", accentHex), forKey: "yptun_accent_hex")
        }
    }

    // Default to Lavender / Purple (0xB388FF) matching user's configuration
    public static let defaultAccentHex: UInt = 0xB388FF

    public static let accentPresets: [(name: String, hex: UInt)] = [
        ("Фиолетовый", 0xB388FF),
        ("Синий", 0x3B8EF7),
        ("Бирюзовый", 0x4DD0E1),
        ("Мятный", 0x7CF2C0),
        ("Розовый", 0xF7AFCD),
        ("Малиновый", 0xFF6FA5),
        ("Янтарный", 0xFFFFD479),
        ("Коралловый", 0xFFFF8A80)
    ]

    public init() {
        let val: UInt
        if let stored = UserDefaults.standard.string(forKey: "yptun_accent_hex"),
           let parsed = UInt(stored, radix: 16) {
            val = parsed
        } else {
            val = Self.defaultAccentHex
        }
        self._accentHex = val
        self.accentHex = val
    }

    public var currentHex: UInt {
        lock.lock()
        defer { lock.unlock() }
        return _accentHex
    }

    public var accent: Color {
        Color(hex: currentHex)
    }

    public var accentContainer: Color {
        Color(hex: currentHex).opacity(0.18)
    }

    public var accentGlow: Color {
        Color(hex: currentHex).opacity(0.35)
    }
}

public enum YPtunColor {
    // Pure Black Canvas & Surfaces matching Android Material 3 Compose
    public static let canvas = Color(hex: 0x000000)
    public static let surface = Color(hex: 0x000000)
    public static let cardSurface = Color(hex: 0x161719)               // surfaceContainer #161719
    public static let cardSurfaceHigh = Color(hex: 0x222427)           // surfaceContainerHigh #222427
    public static let cardSurfaceHighest = Color(hex: 0x2A2C2E)        // surfaceContainerHighest #2A2C2E
    public static var cardSurfaceSelected: Color { YPtunThemeManager.shared.accent.opacity(0.16) }
    public static let groupCard = Color(hex: 0x181522)                 // subscription container background
    public static var groupBorder: Color { YPtunThemeManager.shared.accent.opacity(0.35) }

    // Dynamic Accent
    public static var accent: Color { YPtunThemeManager.shared.accent }
    public static var accentContainer: Color { YPtunThemeManager.shared.accentContainer }
    public static let onPrimaryContainer = Color(hex: 0xECEDEF)
    public static var accentSecondary: Color { YPtunThemeManager.shared.accent.opacity(0.7) }
    public static var accentPurple: Color { Color(hex: 0xB388FF) }
    public static var accentGlow: Color { YPtunThemeManager.shared.accentGlow }
    public static var ringActive: Color { YPtunThemeManager.shared.accent.opacity(0.25) }
    public static let ringIdle = Color(hex: 0x161719)
    public static let outline = Color(hex: 0x3C3E42)

    // Status / Feedback
    public static let pingGreen = Color(hex: 0x22C55E)                 // Emerald Green #22C55E
    public static let pingGreenBg = Color(hex: 0x22C55E).opacity(0.18)
    public static let offlineRed = Color(hex: 0xFFB4AB)                // error #FFB4AB / #EF4444
    public static let offlineRedBg = Color(hex: 0xEF4444).opacity(0.18)

    // Typography
    public static let textPrimary = Color(hex: 0xECEDEF)              // onSurface #ECEDEF
    public static let textSecondary = Color(hex: 0xA0A3A8)            // onSurfaceVariant #A0A3A8
    public static let textMuted = Color(hex: 0x6E7178)
}

public extension Color {
    init(hex: UInt, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}

public enum YPtunRadius {
    public static let card: CGFloat = 20
    public static let pill: CGFloat = 16
    public static let badge: CGFloat = 10
}
