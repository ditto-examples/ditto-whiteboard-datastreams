public let whiteboardPalette: [Int32] = [
  Int32(bitPattern: 0xFF1D1B20),
  Int32(bitPattern: 0xFF0057B8),
  Int32(bitPattern: 0xFF007A3D),
  Int32(bitPattern: 0xFFC62828),
  Int32(bitPattern: 0xFF7B1FA2),
  Int32(bitPattern: 0xFFA94700),
  Int32(bitPattern: 0xFF00838F),
  Int32(bitPattern: 0xFFAD1457),
]

public let defaultWhiteboardColor: Int32 = whiteboardPalette[1]

public func isApprovedWhiteboardColor(_ colorArgb: Int32) -> Bool {
  whiteboardPalette.contains(colorArgb)
}

public func normalizeWhiteboardColor(_ colorArgb: Int32) -> Int32 {
  isApprovedWhiteboardColor(colorArgb) ? colorArgb : defaultWhiteboardColor
}
