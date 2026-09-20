import SwiftUI

#if canImport(UIKit)
  import UIKit
#elseif canImport(AppKit)
  import AppKit
#endif

extension Color {
  init(argb: Int32) {
    let value = UInt32(bitPattern: argb)
    self.init(
      .sRGB,
      red: Double((value >> 16) & 0xFF) / 255,
      green: Double((value >> 8) & 0xFF) / 255,
      blue: Double(value & 0xFF) / 255,
      opacity: Double((value >> 24) & 0xFF) / 255
    )
  }

  init(light: UInt32, dark: UInt32) {
    #if canImport(UIKit)
      self.init(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(argbHex: dark) : UIColor(argbHex: light)
      })
    #elseif canImport(AppKit)
      self.init(nsColor: NSColor(name: nil) { appearance in
        let isDark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        return NSColor(argbHex: isDark ? dark : light)
      })
    #else
      self.init(argb: Int32(bitPattern: light))
    #endif
  }
}

#if canImport(UIKit)
  extension UIColor {
    convenience init(argbHex: UInt32) {
      self.init(
        red: CGFloat((argbHex >> 16) & 0xFF) / 255,
        green: CGFloat((argbHex >> 8) & 0xFF) / 255,
        blue: CGFloat(argbHex & 0xFF) / 255,
        alpha: CGFloat((argbHex >> 24) & 0xFF) / 255
      )
    }
  }
#elseif canImport(AppKit)
  extension NSColor {
    convenience init(argbHex: UInt32) {
      self.init(
        srgbRed: CGFloat((argbHex >> 16) & 0xFF) / 255,
        green: CGFloat((argbHex >> 8) & 0xFF) / 255,
        blue: CGFloat(argbHex & 0xFF) / 255,
        alpha: CGFloat((argbHex >> 24) & 0xFF) / 255
      )
    }
  }
#endif

enum WhiteboardTheme {
  static let primary = Color(light: 0xFF27_4060, dark: 0xFFAF_C6E9)
  static let secondary = Color(light: 0xFF00_6D77, dark: 0xFF7D_D8DD)
  static let secondaryContainer = Color(light: 0xFFB6_ECF0, dark: 0xFF00_4F56)
  static let tertiary = Color(light: 0xFFB5_4708, dark: 0xFFFF_B77E)
  static let tertiaryContainer = Color(light: 0xFFFF_DCC2, dark: 0xFF6E_3600)
  static let background = Color(light: 0xFFF8_F7F2, dark: 0xFF11_1416)
  static let surface = Color(light: 0xFFFF_FEFA, dark: 0xFF19_1C1E)
  static let surfaceVariant = Color(light: 0xFFE8_EBE7, dark: 0xFF30_3437)
}
