import SwiftUI

public enum YPtunColor {
    // Pure Black Canvas & Surfaces matching Android Material 3 Compose (0xFF000000, 0xFF161719, 0xFF222427)
    public static let canvas = Color(hex: 0x000000)
    public static let surface = Color(hex: 0x000000)
    public static let cardSurface = Color(hex: 0x161719)               // surfaceContainer #161719
    public static let cardSurfaceHigh = Color(hex: 0x222427)           // surfaceContainerHigh #222427
    public static let cardSurfaceHighest = Color(hex: 0x2A2C2E)        // surfaceContainerHighest #2A2C2E
    public static let cardSurfaceSelected = Color(hex: 0x12395F)       // primaryContainer #12395F
    public static let groupCard = Color(hex: 0x21384E)                 // secondaryContainer #21384E
    public static let groupCardSubtle = Color(hex: 0x1B3A5B)           // tertiaryContainer #1B3A5B

    // Electric Blue Primary Accent (matching Android 0xFF3B8EF7)
    public static let accent = Color(hex: 0x3B8EF7)                    // primary #3B8EF7
    public static let accentContainer = Color(hex: 0x12395F)           // primaryContainer #12395F
    public static let onPrimaryContainer = Color(hex: 0xD3E4FF)        // onPrimaryContainer #D3E4FF
    public static let accentSecondary = Color(hex: 0x9FC0E8)           // secondary #9FC0E8
    public static let accentDeep = Color(hex: 0x1E5BA3)
    public static let accentPurple = Color(hex: 0x3B8EF7)
    public static let accentGlow = Color(hex: 0x3B8EF7).opacity(0.35)
    public static let ringActive = Color(hex: 0x12395F)                // ringColor when active
    public static let ringIdle = Color(hex: 0x161719)                  // ringColor when idle
    public static let outline = Color(hex: 0x3C3E42)                   // outline #3C3E42

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
    public static let card: CGFloat = 24
    public static let pill: CGFloat = 16
    public static let badge: CGFloat = 10
}
