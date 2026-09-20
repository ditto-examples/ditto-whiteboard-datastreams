import SwiftUI
import WhiteboardCore

extension DrawingTool {
  var label: String {
    switch self {
    case .pen: return "Pen"
    case .line: return "Line"
    case .rectangle: return "Rectangle"
    case .ellipse: return "Ellipse"
    case .text: return "Text"
    case .eraser: return "Eraser"
    }
  }

  var systemImage: String {
    switch self {
    case .pen: return "pencil"
    case .line: return "line.diagonal"
    case .rectangle: return "rectangle"
    case .ellipse: return "circle"
    case .text: return "textformat"
    case .eraser: return "eraser"
    }
  }
}

struct BoardScreen: View {
  let model: AppModel

  #if os(macOS)
  @Environment(\.openWindow) private var openWindow
  #endif
  @Environment(\.horizontalSizeClass) private var horizontalSizeClass
  @State private var confirmClear = false
  @State private var showSidebar = false
  @State private var sidebarSection = BoardSidebarSection.people
  @State private var showPresenceViewer = false
  @State private var textDraft: TextDraft?
  @State private var textInput = ""

  private struct TextDraft {
    var anchor: LogicalPoint
    var gestureId: String
  }

  private var people: [ConnectedPerson] {
    connectedPeople(
      board: model.boardState,
      diagnostics: model.diagnostics,
      localColorArgb: model.selectedColorArgb
    )
  }

  /// Adaptive rule: macOS is always expanded; iPadOS follows the horizontal size
  /// class (regular in full-screen and 50/50 Split View, compact in 33/25% splits
  /// and Slide Over); iPhone follows it too (regular in landscape Pro Max/plus
  /// is treated as expanded only when truly wide, which GeometryReader used to
  /// misjudge inside inspectors/sheets).
  private var isExpanded: Bool {
    #if os(macOS)
    true
    #else
    horizontalSizeClass == .regular
    #endif
  }

  var body: some View {
    boardContent(expanded: isExpanded)
  }

  @ViewBuilder
  private func boardContent(expanded: Bool) -> some View {
    HStack(spacing: 0) {
        if expanded {
          ToolRail(
            selectedTool: model.selectedTool,
            selectedColorArgb: model.selectedColorArgb,
            onSelectTool: model.selectTool,
            onSelectColor: model.selectColor
          )
          Divider()
        }
        BoardCanvasView(
          objects: model.boardState.objects,
          previews: model.previews,
          tool: model.selectedTool,
          colorArgb: model.selectedColorArgb,
          editingEnabled: model.diagnostics.editingReady,
          bannerMessage: model.bannerMessage,
          onPreview: { model.preview(gestureId: $0, points: $1) },
          onCommit: { gestureId, points in
            if model.selectedTool == .text, let anchor = points.first {
              textDraft = TextDraft(anchor: anchor, gestureId: gestureId)
              textInput = ""
            } else {
              model.commit(gestureId: gestureId, points: points)
            }
          }
        )
      }
      .frame(maxWidth: .infinity, maxHeight: .infinity)
      .safeAreaInset(edge: .bottom) {
        if !expanded {
          CompactToolbar(
            selectedTool: model.selectedTool,
            selectedColorArgb: model.selectedColorArgb,
            onSelectTool: model.selectTool,
            onSelectColor: model.selectColor
          )
        }
      }
    .navigationTitle("Ditto Whiteboard")
    .toolbar {
      ToolbarItemGroup(placement: .primaryAction) {
        Button {
          #if os(macOS)
          openWindow(id: "presence-graph")
          #else
          showPresenceViewer = true
          #endif
        } label: {
          Label("Presence graph", systemImage: "dot.radiowaves.left.and.right")
        }
        .accessibilityIdentifier("presenceGraphButton")
        Button {
          confirmClear = true
        } label: {
          Label("Clear board", systemImage: "trash")
        }
        .disabled(!model.diagnostics.editingReady)
        .accessibilityIdentifier("clearBoardButton")
        Button {
          showSidebar.toggle()
        } label: {
          Label("Sidebar", systemImage: "sidebar.trailing")
        }
        .accessibilityIdentifier("sidebarToggleButton")
      }
    }
    .confirmationDialog(
      "Clear the shared board?",
      isPresented: $confirmClear,
      titleVisibility: .visible
    ) {
      Button("Clear board", role: .destructive) {
        model.clear()
      }
      Button("Cancel", role: .cancel) {}
    } message: {
      Text("This removes every object for all connected collaborators and cannot be undone.")
    }
    .alert(
      "Add text",
      isPresented: Binding(
        get: { textDraft != nil },
        set: { if !$0 { textDraft = nil } }
      )
    ) {
      TextField("Text", text: $textInput)
        .accessibilityIdentifier("boardTextField")
        .onChange(of: textInput) { _, newValue in
          let filtered = newValue.removingISOControlCharacters()
          let capped = String(filtered.prefix(200))
          if capped != newValue { textInput = capped }
        }
      Button("Place text") {
        placeText()
      }
      .disabled(textInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
      .accessibilityIdentifier("placeTextButton")
      Button("Cancel", role: .cancel) {
        textDraft = nil
      }
    }
    #if !os(macOS)
    .fullScreenCover(isPresented: $showPresenceViewer) {
      PresenceViewerScreen(appModel: model)
    }
    #endif
    // Unified sidebar: People / Profile / Troubleshooting behind one segmented
    // control. Inspector (trailing column) on macOS + iPadOS regular width;
    // full-screen sheet on iPhone compact.
    #if os(macOS)
    .inspector(isPresented: $showSidebar) {
      BoardSidebarPanel(
        model: model,
        people: people,
        section: $sidebarSection,
        onDismiss: { showSidebar = false }
      )
      .inspectorColumnWidth(min: 360, ideal: 460, max: 720)
    }
    #else
    .inspector(isPresented: $showSidebar) {
      BoardSidebarPanel(
        model: model,
        people: people,
        section: $sidebarSection,
        onDismiss: { showSidebar = false }
      )
      .inspectorColumnWidth(min: 320, ideal: 400, max: 560)
    }
    #endif
  }

  private func placeText() {
    guard let draft = textDraft else { return }
    let text = textInput.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty else { return }
    model.commit(gestureId: draft.gestureId, points: [draft.anchor], text: text)
    textDraft = nil
  }
}

private struct ToolRail: View {
  let selectedTool: DrawingTool
  let selectedColorArgb: Int32
  let onSelectTool: (DrawingTool) -> Void
  let onSelectColor: (Int32) -> Void

  var body: some View {
    VStack(spacing: 2) {
      ForEach(DrawingTool.allCases, id: \.self) { tool in
        Button {
          onSelectTool(tool)
        } label: {
          VStack(spacing: 2) {
            Image(systemName: tool.systemImage)
              .font(.title3)
            Text(tool.label)
              .font(.caption2)
          }
          .frame(maxWidth: .infinity)
          .padding(.vertical, 6)
          .foregroundStyle(tool == selectedTool ? WhiteboardTheme.primary : Color.secondary)
          .background(
            tool == selectedTool ? WhiteboardTheme.secondaryContainer : Color.clear,
            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
          )
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 8)
        .accessibilityLabel(tool.label)
        .accessibilityAddTraits(tool == selectedTool ? .isSelected : [])
        .accessibilityIdentifier("toolButton.\(tool.label.lowercased())")
      }
      Spacer(minLength: 8)
      ForEach(Array(whiteboardPalette.enumerated()), id: \.element) { index, color in
        ColorSwatch(
          color: color,
          index: index,
          isSelected: color == selectedColorArgb,
          action: { onSelectColor(color) }
        )
      }
    }
    .padding(.vertical, 8)
    .frame(width: 88)
    .frame(maxHeight: .infinity)
    .background(WhiteboardTheme.surface)
  }
}

private struct CompactToolbar: View {
  let selectedTool: DrawingTool
  let selectedColorArgb: Int32
  let onSelectTool: (DrawingTool) -> Void
  let onSelectColor: (Int32) -> Void

  private var overflowTool: DrawingTool? {
    let overflowTools = DrawingTool.allCases.suffix(2)
    return overflowTools.contains(selectedTool) ? selectedTool : nil
  }

  var body: some View {
    HStack(spacing: 4) {
      ForEach(DrawingTool.allCases.prefix(4), id: \.self) { tool in
        CompactToolButton(
          tool: tool,
          isSelected: tool == selectedTool,
          action: { onSelectTool(tool) }
        )
      }
      Menu {
        ForEach(DrawingTool.allCases.suffix(2), id: \.self) { tool in
          Button {
            onSelectTool(tool)
          } label: {
            Label(tool.label, systemImage: tool.systemImage)
          }
        }
      } label: {
        Image(systemName: overflowTool?.systemImage ?? "ellipsis")
          .font(.title3)
          .frame(width: 44, height: 44)
          .foregroundStyle(overflowTool != nil ? WhiteboardTheme.primary : Color.secondary)
      }
      .accessibilityLabel(
        overflowTool.map { "\($0.label), selected tool. More tools" } ?? "More tools"
      )
      .accessibilityIdentifier("overflowToolMenu")
      Divider()
        .frame(height: 28)
      ScrollView(.horizontal) {
        HStack(spacing: 4) {
          ForEach(Array(whiteboardPalette.enumerated()), id: \.element) { index, color in
            ColorSwatch(
              color: color,
              index: index,
              isSelected: color == selectedColorArgb,
              action: { onSelectColor(color) }
            )
          }
        }
        .padding(.vertical, 4)
      }
      .scrollIndicators(.hidden)
    }
    .padding(.horizontal, 8)
    .frame(maxWidth: .infinity)
    .background(.bar)
  }
}

private struct CompactToolButton: View {
  let tool: DrawingTool
  let isSelected: Bool
  let action: () -> Void

  var body: some View {
    Button(action: action) {
      Image(systemName: tool.systemImage)
        .font(.title3)
        .frame(width: 44, height: 44)
        .foregroundStyle(isSelected ? WhiteboardTheme.primary : Color.secondary)
    }
    .buttonStyle(.plain)
    .accessibilityLabel(tool.label)
    .accessibilityAddTraits(isSelected ? .isSelected : [])
    .accessibilityIdentifier("toolButton.\(tool.label.lowercased())")
  }
}

private struct ColorSwatch: View {
  let color: Int32
  let index: Int
  let isSelected: Bool
  let action: () -> Void

  var body: some View {
    Button(action: action) {
      Circle()
        .fill(Color(argb: color))
        .frame(width: 30, height: 30)
        .overlay {
          Circle()
            .stroke(
              isSelected ? WhiteboardTheme.primary : Color.secondary.opacity(0.6),
              lineWidth: isSelected ? 3 : 1
            )
        }
        .frame(width: 44, height: 44)
    }
    .buttonStyle(.plain)
    .accessibilityLabel(isSelected ? "Color \(index + 1), selected" : "Color \(index + 1)")
    .accessibilityAddTraits(isSelected ? .isSelected : [])
    .accessibilityIdentifier("colorSwatch.\(index)")
  }
}
