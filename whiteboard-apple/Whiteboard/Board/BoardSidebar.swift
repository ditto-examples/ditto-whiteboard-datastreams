import SwiftUI
import WhiteboardCore

/// The unified trailing panel (inspector on macOS/iPadOS, full-screen sheet on
/// iPhone) hosting People / Profile / Troubleshooting behind one segmented
/// control, driven by the board's single sidebar toggle.
enum BoardSidebarSection: String, CaseIterable, Identifiable {
  case people, profile, troubleshooting
  var id: String { rawValue }
  var title: String {
    switch self {
    case .people: return "People"
    case .profile: return "Profile"
    case .troubleshooting: return "Troubleshooting"
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
        Picker("Sidebar section", selection: $section) {
          ForEach(BoardSidebarSection.allCases) { section in
            Image(systemName: section.systemImage)
              .accessibilityLabel(section.title)
              .tag(section)
          }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .accessibilityIdentifier("sidebarSectionPicker")

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
              onCancel: onDismiss
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
