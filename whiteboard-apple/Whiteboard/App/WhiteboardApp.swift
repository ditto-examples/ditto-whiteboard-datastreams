import SwiftUI
import WhiteboardCore

@main
struct WhiteboardApp: App {
  @State private var model = AppModel.live()
  @Environment(\.scenePhase) private var scenePhase

  var body: some Scene {
    WindowGroup {
      RootView(model: model)
        .tint(WhiteboardTheme.primary)
        .frame(minWidth: 360, minHeight: 480)
        .onChange(of: scenePhase) { _, phase in
          model.setForeground(phase != .background)
        }
    }
    #if os(macOS)
      .defaultSize(width: 1100, height: 760)
    #endif

    #if os(macOS)
    Window("Presence Graph", id: "presence-graph") {
      PresenceViewerScreen(appModel: model)
        .tint(WhiteboardTheme.primary)
        .frame(minWidth: 720, minHeight: 520)
    }
    .defaultSize(width: 1440, height: 960)
    .defaultPosition(.center)
    #endif
  }
}

struct RootView: View {
  let model: AppModel

  var body: some View {
    ZStack {
      WhiteboardTheme.background.ignoresSafeArea()
      if model.startFailed {
        SessionStartErrorView(onRetry: model.retrySessionStart)
      } else if model.profile != nil {
        NavigationStack {
          BoardScreen(model: model)
        }
      } else {
        NavigationStack {
          ProfileSetupScreen(
            existing: nil,
            editing: false,
            errorMessage: model.actionError,
            onSave: { name, color in
              await model.saveProfile(displayName: name, colorArgb: color)
            }
          )
        }
      }
    }
    .task(id: model.profile) {
      if let profile = model.profile {
        FileHandle.standardError.write("DittoWhiteboardApp: startSession for profile '\(profile.displayName)'\n".data(using: .utf8)!)
        model.startSession(displayName: profile.displayName, colorArgb: profile.colorArgb)
      } else {
        FileHandle.standardError.write("DittoWhiteboardApp: no profile yet, waiting for setup\n".data(using: .utf8)!)
      }
    }
  }
}

private struct SessionStartErrorView: View {
  let onRetry: () -> Void

  var body: some View {
    VStack(spacing: 16) {
      Text("Whiteboard could not start")
        .font(.title2)
      Text(
        "The collaboration session failed to start. Your profile is safe on this device. Retry now; if it keeps failing, close and reopen the app."
      )
      .font(.body)
      .foregroundStyle(.secondary)
      .multilineTextAlignment(.center)
      Button("Retry", action: onRetry)
        .buttonStyle(.borderedProminent)
        .accessibilityIdentifier("retrySessionButton")
    }
    .padding(24)
    .frame(maxWidth: 420)
  }
}
