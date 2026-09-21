import Anvil
import SwiftUI
import WhiteboardCore

struct ProfileSetupScreen: View {
  let editing: Bool
  let errorMessage: String?
  let onSave: (String, Int32) async -> Bool
  var onCancel: () -> Void = {}
  /// Embedded mode (inside the unified sidebar panel) drops the Cancel affordance —
  /// the host's segmented control handles navigation away, and save keeps the panel open.
  var embedded: Bool = false

  @State private var name: String
  @State private var colorArgb: Int32
  @State private var attemptedSave = false
  @State private var saving = false
  @Environment(\.dismiss) private var dismiss
  @Environment(\.dittoColors) private var colors

  init(
    existing: ProfileSettings?,
    editing: Bool,
    errorMessage: String?,
    onSave: @escaping (String, Int32) async -> Bool,
    onCancel: @escaping () -> Void = {},
    embedded: Bool = false
  ) {
    self.editing = editing
    self.errorMessage = errorMessage
    self.onSave = onSave
    self.onCancel = onCancel
    self.embedded = embedded
    _name = State(initialValue: existing?.displayName ?? "")
    _colorArgb = State(initialValue: existing?.colorArgb ?? whiteboardPalette[1])
  }

  private var trimmedName: String {
    name.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  private var valid: Bool {
    (1...24).contains(trimmedName.utf16.count) && !trimmedName.containsISOControlCharacters
  }

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 0) {
        Text(
          "Your name and color are saved on this device. Profile updates remain in this ephemeral board’s history and may be re-shared to later nearby collaborators until every participant closes the app."
        )
        .font(.body)
        if let errorMessage {
          Text(errorMessage)
            .font(.callout)
            .foregroundStyle(.red)
            .padding(.top, 12)
        }
        TextField("Display name", text: $name)
          .textFieldStyle(.roundedBorder)
          .accessibilityIdentifier("displayNameField")
          .padding(.top, 24)
          .onChange(of: name) { _, newValue in
            let filtered = newValue.removingISOControlCharacters()
            let capped = String(filtered.prefix(24))
            if capped != newValue { name = capped }
          }
          .onSubmit {
            if valid { save() }
          }
        Text("1–24 characters")
          .font(.caption)
          .foregroundStyle(attemptedSave && !valid ? .red : .secondary)
          .padding(.top, 4)
        Text("Default drawing color")
          .font(.headline)
          .padding(.top, 20)
        LazyVGrid(
          columns: [GridItem(.adaptive(minimum: 56), spacing: 12)],
          spacing: 12
        ) {
          ForEach(Array(whiteboardPalette.enumerated()), id: \.element) { index, color in
            Button {
              colorArgb = color
            } label: {
              Circle()
                .fill(Color(argb: color))
                .frame(width: 48, height: 48)
                .overlay {
                  Circle()
                    .stroke(
                      color == colorArgb ? colors.borderControlSelected : colors.borderNormal,
                      lineWidth: color == colorArgb ? 4 : 1
                    )
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(
              color == colorArgb ? "Color \(index + 1), selected" : "Color \(index + 1)"
            )
            .accessibilityAddTraits(color == colorArgb ? .isSelected : [])
            .accessibilityIdentifier("profileColor.\(index)")
          }
        }
        .padding(.top, 12)
        Button {
          save()
        } label: {
          Text(editing ? "Save profile" : "Join the board")
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(!valid || saving)
        .accessibilityIdentifier("joinButton")
        .padding(.top, 32)
      }
      .padding(.horizontal, 24)
      .padding(.vertical, 16)
      .frame(maxWidth: 520)
      .frame(maxWidth: .infinity)
    }
    .navigationTitle(embedded ? "Profile" : (editing ? "Edit profile" : "Welcome to Whiteboard"))
    .toolbar {
      if editing, !embedded {
        ToolbarItem(placement: .cancellationAction) {
          Button("Cancel") {
            onCancel()
            dismiss()
          }
          .accessibilityIdentifier("cancelProfileButton")
        }
      }
    }
  }

  private func save() {
    attemptedSave = true
    guard valid else { return }
    saving = true
    Task {
      let saved = await onSave(trimmedName, colorArgb)
      saving = false
      if saved, editing, !embedded {
        dismiss()
      }
    }
  }
}
