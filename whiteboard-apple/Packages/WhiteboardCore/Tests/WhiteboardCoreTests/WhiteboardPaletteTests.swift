import Foundation
import Testing
@testable import WhiteboardCore

@Suite("WhiteboardPaletteTest")
struct WhiteboardPaletteTest {
  @Test func everyDrawingColorContrastsAgainstTheFixedPaper() {
    let paper = Int32(bitPattern: 0xFFFFFEFC)
    for (index, color) in whiteboardPalette.enumerated() {
      #expect(
        contrastRatio(color, paper) >= 3.0,
        "Palette color \(index) must remain visible on the board paper"
      )
    }
  }

  @Test func unapprovedColorsNormalizeToTheDefault() {
    for color in whiteboardPalette {
      #expect(isApprovedWhiteboardColor(color))
      #expect(normalizeWhiteboardColor(color) == color)
    }
    #expect(!isApprovedWhiteboardColor(0))
    #expect(normalizeWhiteboardColor(0) == defaultWhiteboardColor)
    #expect(normalizeWhiteboardColor(Int32(bitPattern: 0x00FFFFFF)) == defaultWhiteboardColor)
  }

  private func contrastRatio(_ first: Int32, _ second: Int32) -> Double {
    let lighter = max(luminance(first), luminance(second))
    let darker = min(luminance(first), luminance(second))
    return (lighter + 0.05) / (darker + 0.05)
  }

  private func luminance(_ color: Int32) -> Double {
    func channel(_ shift: Int32) -> Double {
      let srgb = Double((UInt32(bitPattern: color) >> shift) & 0xFF) / 255.0
      return srgb <= 0.04045 ? srgb / 12.92 : pow((srgb + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
  }
}
