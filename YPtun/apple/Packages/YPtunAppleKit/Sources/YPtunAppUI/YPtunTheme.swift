import SwiftUI

public enum YPtunColor {
    // Canvas & Surfaces (AMOLED / Deep Purple Dark Theme)
    public static let canvas = Color(hex: 0x0E0F11)
    public static let canvasPurple = Color(hex: 0x120E18)
    public static let cardSurface = Color(hex: 0x1A1722)
    public static let cardSurfaceSelected = Color(hex: 0x2B223D)
    public static let cardSurfaceHigh = Color(hex: 0x242030)
    public static let groupCard = Color(hex: 0x221B2E)
    public static let groupCardSubtle = Color(hex: 0x2A223B)

    // Accents
    public static let accent = Color(hex: 0xB388FF)           // Neon Lavender / Purple #B388FF
    public static let accentPurple = Color(hex: 0x8B5CF6)     // Vibrant Purple #8B5CF6
    public static let accentDeep = Color(hex: 0x6D28D9)       // Deep Purple #6D28D9
    public static let accentGlow = Color(hex: 0xB388FF).opacity(0.35)
    public static let ringActive = Color(hex: 0xB388FF).opacity(0.25)
    public static let ringIdle = Color(white: 0.2).opacity(0.20)

    // Status / Feedback
    public static let pingGreen = Color(hex: 0x22C55E)        // Emerald Green #22C55E
    public static let pingGreenBg = Color(hex: 0x22C55E).opacity(0.18)
    public static let offlineRed = Color(hex: 0xEF4444)       // Red #EF4444
    public static let offlineRedBg = Color(hex: 0xEF4444).opacity(0.18)

    // Typography
    public static let textPrimary = Color(hex: 0xECEDEF)
    public static let textSecondary = Color(hex: 0xA0A3A8)
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
