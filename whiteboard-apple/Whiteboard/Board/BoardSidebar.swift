import SwiftUI
import WhiteboardCore

/// The unified trailing panel (inspector on macOS/iPadOS, full-screen sheet on
/// iPhone) hosting People / Profile / Transport behind one segmented
/// control, driven by the board's single sidebar toggle.
enum BoardSidebarSection: String, CaseIterable, Identifiable {
  case people, profile, troubleshooting
  var id: String { rawValue }
  var title: String {
    switch self {
    case .people: return "People"
    case .profile: return "Profile"
    case .troubleshooting: return "Transport"
    }
  }
  var systemImage: String {
    switch self {
    case .people: return "person.2"
    case .profile: return "person.crop.circle"
    case .troubleshooting: return "antenna.radiowaves.left.and.right"
    }
  }
}

struct BoardSidebarPanel: View {
  let model: AppModel
  let people: [ConnectedPerson]
  @Binding var section: BoardSidebarSection
  /// Called when a child flow (profile save) wants the panel closed.
  var onDismiss: () -> Void = {}

  var body: some View {
    NavigationStack {
      VStack(spacing: 0) {
        SidebarSectionPicker(selection: $section)

        Group {
          switch section {
          case .people:
            ConnectedPeoplePane(people: people, onClose: onDismiss)
          case .profile:
            ProfileSetupScreen(
              existing: model.profile,
              editing: true,
              errorMessage: model.actionError,
              onSave: { name, color in
                await model.saveProfile(displayName: name, colorArgb: color)
              },
              onCancel: onDismiss,
              embedded: true
            )
          case .troubleshooting:
            TroubleshootingScreen(
              diagnostics: model.diagnostics,
              appModel: model,
              embedded: true
            )
          }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
      }
      .navigationTitle(section.title)
      #if os(iOS)
      .navigationBarTitleDisplayMode(.inline)
      #endif
    }
  }
}

private struct SidebarSectionPicker: View {
  @Binding var selection: BoardSidebarSection

  var body: some View {
    Picker("Sidebar section", selection: $selection) {
      ForEach(BoardSidebarSection.allCases) { section in
        Text(section.title)
          .tag(section)
      }
    }
    .pickerStyle(.segmented)
    .labelsHidden()
    .frame(maxWidth: .infinity)
    #if os(macOS)
    .controlSize(.large)
    .padding(.horizontal, 16)
    .padding(.vertical, 12)
    #else
    .padding(.horizontal, 12)
    .padding(.vertical, 8)
    #endif
    .accessibilityIdentifier("sidebarSectionPicker")
  }
}
