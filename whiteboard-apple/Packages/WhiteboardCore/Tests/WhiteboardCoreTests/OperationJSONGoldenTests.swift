import Foundation
import Testing
@testable import WhiteboardCore

private let goldenOriginId = OperationId(senderPeerKey: "pkA", senderSequence: 1)
private let goldenObjectId = ObjectId(origin: goldenOriginId, fragmentDigest: "")
private let goldenStamp = OperationStamp(lamport: 7, peerKey: "pkB", senderSequence: 3)

private func goldenFreehand() -> BoardObject.Freehand {
  BoardObject.Freehand(
    id: goldenObjectId,
    stamp: goldenStamp,
    colorArgb: -14_869_728,
    width: 10,
    points: [LogicalPoint(x: 1, y: 2), LogicalPoint(x: 3, y: 4)]
  )
}

private let goldenProfileJSON =
  #"{"peerKey":"pkA","displayName":"Ada","colorArgb":-16754760}"#
private let goldenFreehandJSON =
  #"{"kind":"freehand","id":{"origin":{"senderPeerKey":"pkA","senderSequence":1},"fragmentDigest":""},"stamp":{"lamport":7,"peerKey":"pkB","senderSequence":3},"colorArgb":-14869728,"width":10,"points":[{"x":1,"y":2},{"x":3,"y":4}]}"#
private let goldenLineJSON =
  #"{"kind":"line","id":{"origin":{"senderPeerKey":"pkA","senderSequence":1},"fragmentDigest":""},"stamp":{"lamport":7,"peerKey":"pkB","senderSequence":3},"colorArgb":5,"width":6,"start":{"x":7,"y":8},"end":{"x":9,"y":10}}"#
private let goldenTextJSON =
  #"{"kind":"text","id":{"origin":{"senderPeerKey":"pkA","senderSequence":1},"fragmentDigest":""},"stamp":{"lamport":7,"peerKey":"pkB","senderSequence":3},"colorArgb":5,"anchor":{"x":11,"y":12},"text":"hi","size":48,"font":"system"}"#
private let goldenCommitJSON =
  #"{"kind":"commit","id":{"senderPeerKey":"pkA","senderSequence":1},"stamp":{"lamport":7,"peerKey":"pkB","senderSequence":3},"boardObject":{"kind":"freehand","id":{"origin":{"senderPeerKey":"pkA","senderSequence":1},"fragmentDigest":""},"stamp":{"lamport":7,"peerKey":"pkB","senderSequence":3},"colorArgb":-14869728,"width":10,"points":[{"x":1,"y":2},{"x":3,"y":4}]},"gestureId":"g1"}"#
private let goldenEraseJSON =
  #"{"kind":"erase","id":{"senderPeerKey":"pkA","senderSequence":2},"stamp":{"lamport":7,"peerKey":"pkB","senderSequence":3},"path":[{"x":1,"y":1}],"radius":40,"gestureId":"g2"}"#
private let goldenClearJSON =
  #"{"kind":"clear","id":{"senderPeerKey":"pkA","senderSequence":3},"stamp":{"lamport":7,"peerKey":"pkB","senderSequence":3}}"#
private let goldenProfileUpdateJSON =
  #"{"kind":"profile","id":{"senderPeerKey":"pkA","senderSequence":4},"stamp":{"lamport":7,"peerKey":"pkB","senderSequence":3},"profile":{"peerKey":"pkA","displayName":"Ada","colorArgb":-16754760}}"#
private let goldenPointJSON = #"{"x":1,"y":2}"#
private let goldenObjectIdJSON =
  #"{"origin":{"senderPeerKey":"pkA","senderSequence":1},"fragmentDigest":""}"#
private let goldenStampJSON = #"{"lamport":7,"peerKey":"pkB","senderSequence":3}"#
private let goldenOperationIdJSON = #"{"senderPeerKey":"pkA","senderSequence":1}"#

@Suite("OperationJSONGoldenTests")
struct OperationJSONGoldenTests {
  @Test func profileMatchesGoldenBytes() throws {
    let profile = try UserProfile(peerKey: "pkA", displayName: "Ada", colorArgb: -16_754_760)
    #expect(OperationJSON.encode(profile) == goldenProfileJSON)
  }

  @Test func freehandMatchesGoldenBytes() {
    #expect(canonicalJSONString(BoardObject.freehand(goldenFreehand())) == goldenFreehandJSON)
  }

  @Test func lineMatchesGoldenBytes() {
    let line = BoardObject.Line(
      id: goldenObjectId,
      stamp: goldenStamp,
      colorArgb: 5,
      width: 6,
      start: LogicalPoint(x: 7, y: 8),
      end: LogicalPoint(x: 9, y: 10)
    )
    #expect(canonicalJSONString(BoardObject.line(line)) == goldenLineJSON)
  }

  @Test func textMatchesGoldenBytes() {
    let text = BoardObject.Text(
      id: goldenObjectId,
      stamp: goldenStamp,
      colorArgb: 5,
      anchor: LogicalPoint(x: 11, y: 12),
      text: "hi",
      size: 48
    )
    #expect(canonicalJSONString(BoardObject.text(text)) == goldenTextJSON)
  }

  @Test func textFontAndSizeRoundTrip() throws {
    let text = BoardObject.Text(
      id: goldenObjectId,
      stamp: goldenStamp,
      colorArgb: 5,
      anchor: LogicalPoint(x: 11, y: 12),
      text: "Styled",
      size: 80,
      font: .rounded
    )
    let encoded = canonicalJSONString(BoardObject.text(text))
    let decoded = try OperationJSON.decoder.decode(BoardObject.self, from: Data(encoded.utf8))
    #expect(decoded == .text(text))
  }

  @Test func commitMatchesGoldenBytes() {
    let commit = BoardOperation.Commit(
      id: goldenOriginId,
      stamp: goldenStamp,
      boardObject: .freehand(goldenFreehand()),
      gestureId: "g1"
    )
    #expect(OperationJSON.encode(.commit(commit)) == goldenCommitJSON)
  }

  @Test func eraseMatchesGoldenBytes() {
    let erase = BoardOperation.Erase(
      id: OperationId(senderPeerKey: "pkA", senderSequence: 2),
      stamp: goldenStamp,
      path: [LogicalPoint(x: 1, y: 1)],
      radius: 40,
      gestureId: "g2"
    )
    #expect(OperationJSON.encode(.erase(erase)) == goldenEraseJSON)
  }

  @Test func clearMatchesGoldenBytes() {
    let clear = BoardOperation.Clear(
      id: OperationId(senderPeerKey: "pkA", senderSequence: 3),
      stamp: goldenStamp
    )
    #expect(OperationJSON.encode(.clear(clear)) == goldenClearJSON)
  }

  @Test func profileUpdateMatchesGoldenBytes() throws {
    let profile = try UserProfile(peerKey: "pkA", displayName: "Ada", colorArgb: -16_754_760)
    let update = BoardOperation.ProfileUpdate(
      id: OperationId(senderPeerKey: "pkA", senderSequence: 4),
      stamp: goldenStamp,
      profile: profile
    )
    #expect(OperationJSON.encode(.profileUpdate(update)) == goldenProfileUpdateJSON)
  }

  @Test func pointMatchesGoldenBytes() {
    #expect(canonicalJSONString(LogicalPoint(x: 1, y: 2)) == goldenPointJSON)
  }

  @Test func objectIdMatchesGoldenBytes() {
    #expect(canonicalJSONString(goldenObjectId) == goldenObjectIdJSON)
  }

  @Test func stampMatchesGoldenBytes() {
    #expect(canonicalJSONString(goldenStamp) == goldenStampJSON)
  }

  @Test func operationIdMatchesGoldenBytes() {
    #expect(canonicalJSONString(goldenOriginId) == goldenOperationIdJSON)
  }

  @Test func goldenOperationsDecodeAndReencodeByteIdentically() throws {
    let goldens = [
      goldenCommitJSON, goldenEraseJSON, goldenClearJSON, goldenProfileUpdateJSON,
    ]
    for golden in goldens {
      let decoded = try OperationJSON.decodeOperation(golden)
      #expect(OperationJSON.encode(decoded) == golden)
    }
  }

  @Test func goldenObjectsDecodeAndReencodeByteIdentically() throws {
    let goldens = [goldenFreehandJSON, goldenLineJSON, goldenTextJSON]
    for golden in goldens {
      let decoded = try OperationJSON.decoder.decode(
        BoardObject.self, from: Data(golden.utf8)
      )
      #expect(canonicalJSONString(decoded) == golden)
    }
  }

  @Test func goldenProfileDecodesAndReencodesByteIdentically() throws {
    let decoded = try OperationJSON.decodeProfile(goldenProfileJSON)
    #expect(OperationJSON.encode(decoded) == goldenProfileJSON)
  }

  @Test func decodingToleratesShuffledKeyOrderAndMissingDefaults() throws {
    let shuffled = """
      {"size":48,"text":"hi","anchor":{"y":12,"x":11},"colorArgb":5,\
      "stamp":{"senderSequence":3,"peerKey":"pkB","lamport":7},\
      "id":{"fragmentDigest":"","origin":{"senderSequence":1,"senderPeerKey":"pkA"}},"kind":"text"}
      """
    let object = try OperationJSON.decoder.decode(BoardObject.self, from: Data(shuffled.utf8))
    #expect(canonicalJSONString(object) == goldenTextJSON)

    let missingDefaults = """
      {"kind":"erase","stamp":{"lamport":7,"peerKey":"pkB","senderSequence":3},\
      "id":{"senderPeerKey":"pkA","senderSequence":2},"path":[{"x":1,"y":1}]}
      """
    let operation = try OperationJSON.decodeOperation(missingDefaults)
    guard case .erase(let erase) = operation else {
      Issue.record("Expected erase, got \(operation)")
      return
    }
    #expect(erase.radius == defaultEraserRadius)
    #expect(erase.gestureId == "pkA:2")
  }

  @Test func stringEscapingMatchesKotlinx() throws {
    let newline = BoardObject.Text(
      id: goldenObjectId, stamp: goldenStamp, colorArgb: 5,
      anchor: LogicalPoint(x: 0, y: 0), text: "hi\nthere"
    )
    let encodedNewline = canonicalJSONString(BoardObject.text(newline))
    #expect(encodedNewline.contains(#""text":"hi\nthere""#))

    let emoji = BoardObject.Text(
      id: goldenObjectId, stamp: goldenStamp, colorArgb: 5,
      anchor: LogicalPoint(x: 0, y: 0), text: "emoji 🎨"
    )
    let encodedEmoji = canonicalJSONString(BoardObject.text(emoji))
    #expect(encodedEmoji.contains(#""text":"emoji 🎨""#))

    let quotesAndSlash = BoardObject.Text(
      id: goldenObjectId, stamp: goldenStamp, colorArgb: 5,
      anchor: LogicalPoint(x: 0, y: 0), text: "a\"b\\c/d\te\rf\u{0C}g\u{08}h\u{01}"
    )
    let encodedSpecials = canonicalJSONString(BoardObject.text(quotesAndSlash))
    #expect(encodedSpecials.contains(#""text":"a\"b\\c/d\te\rf\fg\bh\u0001""#))

    for golden in [encodedNewline, encodedEmoji, encodedSpecials] {
      let decoded = try OperationJSON.decoder.decode(BoardObject.self, from: Data(golden.utf8))
      #expect(canonicalJSONString(decoded) == golden)
    }
  }
}
