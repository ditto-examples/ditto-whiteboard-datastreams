import SwiftUI

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

}
