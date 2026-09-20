import SwiftUI
import WhiteboardCore

struct ConnectedPerson: Equatable, Identifiable, Sendable {
  var peerKey: String
  var displayName: String
  var colorArgb: Int32?
  var isLocal: Bool

  var id: String { peerKey }
}

func connectedPeople(
  board: BoardState,
  diagnostics: TransportDiagnostics,
  localColorArgb: Int32
) -> [ConnectedPerson] {
  let localKey = diagnostics.localPeerKey
  let localProfile = board.profiles[localKey]
  let local = ConnectedPerson(
    peerKey: localKey,
    displayName: localProfile?.displayName ?? "",
    colorArgb: localProfile?.colorArgb ?? localColorArgb,
    isLocal: true
  )
  let remote =
    diagnostics.peers.values
    .filter { diagnostics.incompatiblePeers[$0.peerKey] == nil }
    .map { peer -> ConnectedPerson in
      let profile = board.profiles[peer.peerKey]
      return ConnectedPerson(
        peerKey: peer.peerKey,
        displayName: profile?.displayName ?? peer.displayName ?? "",
        colorArgb: profile?.colorArgb ?? peer.colorArgb,
        isLocal: false
      )
    }
    .sorted { lhs, rhs in
      let order = lhs.displayName.caseInsensitiveCompare(rhs.displayName)
      if order != .orderedSame { return order == .orderedAscending }
      return lhs.peerKey < rhs.peerKey
    }
  return [local] + remote
}

func whiteboardColorName(_ colorArgb: Int32) -> String {
  switch whiteboardPalette.firstIndex(of: colorArgb) {
  case 0: return "Charcoal"
  case 1: return "Blue"
  case 2: return "Green"
  case 3: return "Red"
  case 4: return "Purple"
  case 5: return "Orange"
  case 6: return "Teal"
  case 7: return "Magenta"
  default: return "Custom color"
  }
}

struct ConnectedPeoplePane: View {
  let people: [ConnectedPerson]
  let onClose: () -> Void

  var body: some View {
    VStack(spacing: 0) {
      HStack {
        VStack(alignment: .leading, spacing: 2) {
          Text("People on this board")
            .font(.title3)
            .fontWeight(.semibold)
          Text("\(people.count) connected")
            .font(.callout)
            .foregroundStyle(.secondary)
        }
        Spacer()
        Button(action: onClose) {
          Image(systemName: "xmark.circle.fill")
            .font(.title3)
            .foregroundStyle(.secondary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Close people list")
      }
      .padding(.horizontal, 20)
      .padding(.vertical, 12)
      Divider()
      List {
        ForEach(people) { person in
          ConnectedPersonRow(person: person)
        }
        if people.count == 1 {
          Text("No one else is connected yet.")
            .font(.callout)
            .foregroundStyle(.secondary)
        }
      }
      .listStyle(.plain)
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(WhiteboardTheme.surface)
  }
}

private struct ConnectedPersonRow: View {
  let person: ConnectedPerson

  private var resolvedName: String {
    if !person.displayName.isEmpty { return person.displayName }
    return person.isLocal ? "You" : "Nearby artist \(person.peerKey.suffix(4))"
  }

  private var detail: String {
    let syncing = "Color syncing…"
    if person.isLocal {
      let colorName = person.colorArgb.map(whiteboardColorName) ?? syncing
      return "You • \(colorName)"
    }
    return person.colorArgb.map(whiteboardColorName) ?? syncing
  }

  var body: some View {
    HStack(spacing: 14) {
      Circle()
        .fill(person.colorArgb.map(Color.init(argb:)) ?? WhiteboardTheme.surfaceVariant)
        .overlay {
          if person.colorArgb == nil {
            Image(systemName: "ellipsis")
              .foregroundStyle(.secondary)
          }
        }
        .overlay {
          Circle().stroke(Color.secondary.opacity(0.6), lineWidth: 1)
        }
        .frame(width: 42, height: 42)
        .accessibilityHidden(true)
      VStack(alignment: .leading, spacing: 2) {
        Text(resolvedName)
          .font(.headline)
          .lineLimit(1)
        Text(detail)
          .font(.caption)
          .foregroundStyle(.secondary)
          .lineLimit(1)
      }
      Spacer(minLength: 4)
      if person.isLocal {
        Text("You")
          .font(.caption)
          .padding(.horizontal, 8)
          .padding(.vertical, 4)
          .background(
            WhiteboardTheme.secondaryContainer,
            in: RoundedRectangle(cornerRadius: 6, style: .continuous)
          )
      }
    }
    .padding(.vertical, 4)
  }
}
