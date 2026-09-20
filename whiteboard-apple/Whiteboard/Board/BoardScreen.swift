import Anvil
import SwiftUI
import WhiteboardCore

private let zoomStep = 1.25
private let drawingToolsInInterface: [DrawingTool] = [
  .pen, .line, .rectangle, .ellipse, .text, .hand, .eraser,
]

extension DrawingTool {
  var label: String {
    switch self {
    case .pen: return "Pen"
    case .line: return "Line"
    case .rectangle: return "Rectangle"
    case .ellipse: return "Ellipse"
    case .text: return "Text"
    case .hand: return "Hand"
    case .eraser: return "Eraser"
    }
  }

  var systemImage: String {
    switch self {
    case .pen: return "pencil"
    case .line: return "line.diagonal"
    case .rectangle: return "rectangle"
    case .ellipse: return "circle"
    case .text: return "long.text.page.and.pencil"
    case .hand: return "hand.draw"
    case .eraser: return "eraser"
    }
  }
}

struct BoardScreen: View {
  let model: AppModel
  @Environment(\.dittoColors) private var colors
  @Environment(\.dittoIsDark) private var isDark

  #if os(macOS)
  @Environment(\.openWindow) private var openWindow
  #endif
  @Environment(\.horizontalSizeClass) private var horizontalSizeClass
  #if os(iOS)
  @Environment(\.verticalSizeClass) private var verticalSizeClass
  #endif
  @State private var confirmClear = false
  @State private var showSidebar = false
  @State private var sidebarSection = BoardSidebarSection.people
  @State private var showPresenceViewer = false
  @State private var textDraft: TextDraft?
  @State private var textInput = ""
  @State private var textFont = defaultTextFont
  @State private var textSize = defaultTextSize
  @State private var viewport = BoardViewport()

  private struct TextDraft: Identifiable {
    var anchor: LogicalPoint
    var gestureId: String

    var id: String { gestureId }
  }

  private var people: [ConnectedPerson] {
    connectedPeople(
      board: model.boardState,
      diagnostics: model.diagnostics,
      localColorArgb: model.selectedColorArgb
    )
  }

  /// Adaptive rule: macOS is always expanded. iPadOS uses its regular inspector
  /// when both dimensions are regular; compact widths and short phone landscapes
  /// use a draggable sidebar sheet.
  private var isExpanded: Bool {
    #if os(macOS)
    true
    #elseif os(iOS)
    horizontalSizeClass == .regular && verticalSizeClass != .compact
    #else
    horizontalSizeClass == .regular
    #endif
  }

  private var usesToolRail: Bool {
    #if os(macOS)
    true
    #else
    false
    #endif
  }

  /// In iPhone landscape, retain the zoom readout but group the secondary
  /// actions so the navigation bar has room for its title and the status bar.
  private var usesCollapsedNavigationActions: Bool {
    #if os(iOS)
    !isExpanded && verticalSizeClass == .compact
    #else
    false
    #endif
  }

  #if os(iOS)
  private var inspectorSidebarPresentation: Binding<Bool> {
    Binding(
      get: { isExpanded && showSidebar },
      set: { isPresented in
        if !isPresented { showSidebar = false }
      }
    )
  }

  private var sheetSidebarPresentation: Binding<Bool> {
    Binding(
      get: { !isExpanded && showSidebar },
      set: { isPresented in
        if !isPresented { showSidebar = false }
      }
    )
  }
  #endif

  var body: some View {
    HStack(spacing: 0) {
      if usesToolRail {
        ToolRail(
          activeTool: model.activeTool,
          selectedColorArgb: model.selectedColorArgb,
          onSelectTool: model.selectTool,
          onSelectColor: model.selectColor
        )
        Divider()
      }
      BoardCanvasView(
        viewport: $viewport,
        objects: model.boardState.objects,
        previews: model.previews,
        tool: model.activeTool,
        colorArgb: model.selectedColorArgb,
        editingEnabled: model.diagnostics.editingReady,
        bannerMessage: model.bannerMessage,
        onPreview: { model.preview(gestureId: $0, points: $1) },
        onCommit: { gestureId, points in
          if model.activeTool == .text, let anchor = points.first {
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
      if !usesToolRail {
        if isExpanded {
          ExpandedBoardToolbar(
            viewport: $viewport,
            selectedTool: model.activeTool,
            selectedColorArgb: model.selectedColorArgb,
            onSelectTool: model.selectTool,
            onSelectColor: model.selectColor
          )
        } else if #available(iOS 27.1, *) {
          EmptyView()
        } else {
          CompactToolbar(
            selectedTool: model.activeTool,
            selectedColorArgb: model.selectedColorArgb,
            onSelectTool: model.selectTool,
            onSelectColor: model.selectColor,
            isCondensed: usesCollapsedNavigationActions
          )
        }
      }
    }
    .navigationTitle("Whiteboard")
    #if os(iOS)
      // Use the semantic surface rather than `inverse`: in Anvil's dark tier
      // `inverse` is deliberately light, which made the iOS title bar white.
      .toolbarBackground(colors.surface, for: .navigationBar)
      .toolbarBackground(.visible, for: .navigationBar)
      .toolbarColorScheme(isDark ? .dark : .light, for: .navigationBar)
      .navigationBarTitleDisplayMode(.inline)
    #endif
    .toolbar {
      #if !os(macOS)
      ToolbarItem(placement: .principal) {
        BoardNavigationTitle()
      }
      #endif
      #if os(iOS)
        if #available(iOS 27.1, *), !isExpanded {
          CompactBoardSystemToolbar(
            viewport: $viewport,
            selectedTool: model.activeTool,
            selectedColorArgb: model.selectedColorArgb,
            onSelectTool: model.selectTool,
            onSelectColor: model.selectColor,
            editingEnabled: model.diagnostics.editingReady,
            onShowPresence: { showPresenceViewer = true },
            onClear: { confirmClear = true },
            onToggleSidebar: { showSidebar.toggle() }
          )
        } else if usesCollapsedNavigationActions {
          ToolbarItem(placement: .topBarTrailing) {
            ZoomToolbarMenu(viewport: $viewport)
          }
          ToolbarItem(placement: .topBarTrailing) {
            BoardActionsMenu(
              editingEnabled: model.diagnostics.editingReady,
              onShowPresence: { showPresenceViewer = true },
              onClear: { confirmClear = true },
              onToggleSidebar: { showSidebar.toggle() }
            )
          }
        } else {
          ToolbarItemGroup(placement: .topBarTrailing) {
            if !isExpanded {
              ZoomToolbarMenu(viewport: $viewport)
            }
            Button {
              showPresenceViewer = true
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
              Label("Inspector", systemImage: "sidebar.trailing")
            }
            .accessibilityIdentifier("sidebarToggleButton")
          }
        }
      #else
        ToolbarItemGroup(placement: .primaryAction) {
          ZoomToolbarMenu(viewport: $viewport)
          Button {
            openWindow(id: "presence-graph")
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
            Label("Inspector", systemImage: "sidebar.trailing")
          }
          .accessibilityIdentifier("sidebarToggleButton")
        }
      #endif
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
    .sheet(item: $textDraft) { draft in
      TextPlacementSheet(
        text: $textInput,
        font: $textFont,
        size: $textSize,
        onPlace: { placeText(draft) },
        onCancel: { textDraft = nil }
      )
      #if os(iOS)
      .presentationDetents([.medium, .large])
      #endif
    }
    #if !os(macOS)
    .fullScreenCover(isPresented: $showPresenceViewer) {
      PresenceViewerScreen(appModel: model)
    }
    #endif
    // Unified sidebar: People / Profile / Troubleshooting behind one segmented
    // control. Inspector (trailing column) on macOS + iPadOS regular width;
    // a draggable sheet with a visible grabber on compact iOS widths.
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
    .sheet(isPresented: sheetSidebarPresentation) {
      CompactSidebarSheet(
        model: model,
        people: people,
        section: $sidebarSection,
        onDismiss: { showSidebar = false }
      )
    }
    #endif
  }

  private func placeText(_ draft: TextDraft) {
    let text = textInput.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty else { return }
    model.commit(
      gestureId: draft.gestureId,
      points: [draft.anchor],
      text: text,
      textFont: textFont,
      textSize: textSize
    )
    textDraft = nil
  }
}

/// Keeps the Ditto wordmark sharp, localized to the title bar, and adaptive to
/// the active appearance. The asset catalog supplies its dark and light marks.
private struct BoardNavigationTitle: View {
  var body: some View {
    HStack(spacing: 7) {
      Image("DittoLogotype")
        .resizable()
        .scaledToFit()
        .frame(height: 17)
        .accessibilityHidden(true)
      Text("Whiteboard")
        .font(.headline)
    }
    .fixedSize()
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Ditto Whiteboard")
  }
}

private struct TextPlacementSheet: View {
  @Binding var text: String
  @Binding var font: BoardTextFont
  @Binding var size: Int
  let onPlace: () -> Void
  let onCancel: () -> Void

  private let textSizes = [24, 36, 48, 64, 80, 96]

  var body: some View {
    NavigationStack {
      Form {
        Section("Text") {
          TextField("Text", text: $text, axis: .vertical)
            .lineLimit(3...6)
            .accessibilityIdentifier("boardTextField")
            .onChange(of: text) { _, newValue in
              let filtered = newValue.removingISOControlCharacters()
              let capped = String(filtered.prefix(200))
              if capped != newValue { text = capped }
            }
        }
        Section("Style") {
          Picker("Font", selection: $font) {
            ForEach(BoardTextFont.allCases, id: \.self) { option in
              Text(option.displayName)
                .font(.system(.body, design: option.fontDesign))
                .tag(option)
            }
          }
          .pickerStyle(.menu)
          .accessibilityIdentifier("textFontPicker")
          Picker("Text size", selection: $size) {
            ForEach(textSizes, id: \.self) { option in
              Text("\(option) pt")
                .tag(option)
            }
          }
          .pickerStyle(.menu)
          .accessibilityIdentifier("textSizePicker")
        }
        Section("Preview") {
          Text(text.isEmpty ? "Text preview" : text)
            .font(.system(size: CGFloat(size), design: font.fontDesign))
            .lineLimit(3)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
      }
      .navigationTitle("Add text")
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("Cancel", action: onCancel)
        }
        ToolbarItem(placement: .confirmationAction) {
          Button("Place text", action: onPlace)
            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .accessibilityIdentifier("placeTextButton")
        }
      }
    }
  }
}

private extension BoardTextFont {
  var displayName: LocalizedStringResource {
    switch self {
    case .system: "System"
    case .rounded: "Rounded"
    case .serif: "Serif"
    case .monospaced: "Monospaced"
    }
  }

  var fontDesign: Font.Design {
    switch self {
    case .system: return .default
    case .rounded: return .rounded
    case .serif: return .serif
    case .monospaced: return .monospaced
    }
  }
}

private struct CompactSidebarSheet: View {
  let model: AppModel
  let people: [ConnectedPerson]
  @Binding var section: BoardSidebarSection
  let onDismiss: () -> Void
  #if os(iOS)
  @Environment(\.verticalSizeClass) private var verticalSizeClass
  #endif

  private var needsInContentDismissHandle: Bool {
    #if os(iOS)
    verticalSizeClass == .compact
    #else
    false
    #endif
  }

  var body: some View {
    VStack(spacing: 0) {
      if needsInContentDismissHandle {
        SidebarDismissHandle(onDismiss: onDismiss)
      }
      BoardSidebarPanel(
        model: model,
        people: people,
        section: $section,
        onDismiss: onDismiss
      )
    }
    .presentationDetents([.large])
    .presentationDragIndicator(.visible)
    .presentationContentInteraction(.scrolls)
  }
}

private struct SidebarDismissHandle: View {
  let onDismiss: () -> Void

  var body: some View {
    Capsule()
      .fill(.tertiary)
      .frame(width: 36, height: 5)
      .frame(maxWidth: .infinity)
      .frame(height: 36)
      .contentShape(Rectangle())
      .gesture(
        DragGesture(minimumDistance: 10)
          .onEnded { value in
            if value.translation.height > 44 {
              onDismiss()
            }
          }
      )
      .accessibilityLabel("Dismiss sidebar by dragging down")
      .accessibilityIdentifier("sidebarDismissHandle")
  }
}

private struct ToolRail: View {
  let activeTool: DrawingTool
  let selectedColorArgb: Int32
  let onSelectTool: (DrawingTool) -> Void
  let onSelectColor: (Int32) -> Void
  @Environment(\.dittoColors) private var colors

  var body: some View {
    VStack(spacing: 0) {
      ToolRailHeader()
      ToolRailContents(
        activeTool: activeTool,
        selectedColorArgb: selectedColorArgb,
        onSelectTool: onSelectTool,
        onSelectColor: onSelectColor
      )
    }
    #if os(iOS)
    .safeAreaPadding(.top, 44)
    #endif
    .frame(width: 112)
    .frame(maxHeight: .infinity)
    .background(colors.surface)
    .accessibilityIdentifier("toolRail")
  }
}

private struct ToolRailHeader: View {
  var body: some View {
    Text("Tools")
      .font(.headline)
      .frame(maxWidth: .infinity, alignment: .leading)
      .padding(.horizontal, 12)
      .padding(.vertical, 10)
  }
}

private struct ToolRailContents: View {
  let activeTool: DrawingTool
  let selectedColorArgb: Int32
  let onSelectTool: (DrawingTool) -> Void
  let onSelectColor: (Int32) -> Void

  var body: some View {
    ScrollView {
      LazyVStack(spacing: 2) {
        ForEach(drawingToolsInInterface, id: \.self) { tool in
          ToolRailToolButton(
            tool: tool,
            isSelected: tool == activeTool,
            action: { onSelectTool(tool) }
          )
        }
        Divider()
          .padding(.vertical, 6)
        ForEach(Array(whiteboardPalette.enumerated()), id: \.element) { index, color in
          ColorSwatch(
            color: color,
            index: index,
            isSelected: color == selectedColorArgb,
            action: { onSelectColor(color) }
          )
        }
      }
    }
    .scrollIndicators(.automatic)
    .padding(.horizontal, 8)
    .padding(.bottom, 8)
  }
}

private struct ToolRailToolButton: View {
  let tool: DrawingTool
  let isSelected: Bool
  let action: () -> Void
  @Environment(\.dittoColors) private var colors
  @Environment(\.dittoIsDark) private var isDark

  var body: some View {
    Button(action: action) {
      VStack(spacing: 2) {
        Image(systemName: tool.systemImage)
          .font(.title3)
        Text(tool.label)
          .font(.caption2)
          .lineLimit(2)
          .multilineTextAlignment(.center)
      }
      .frame(maxWidth: .infinity)
      .padding(.vertical, 6)
      .foregroundStyle(
        isSelected
          ? (isDark ? colors.foregroundOnBrandPrimary : colors.foregroundOnFill)
          : colors.foregroundSubtle
      )
      .background(
        isSelected ? colors.fillControlSelected : Color.clear,
        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
      )
    }
    .buttonStyle(.plain)
    .accessibilityLabel(tool.label)
    .accessibilityAddTraits(isSelected ? .isSelected : [])
    .accessibilityIdentifier("toolButton.\(tool.label.lowercased())")
  }
}

private struct ZoomToolbarMenu: View {
  @Binding var viewport: BoardViewport
  var showsZoomReadout = true

  var body: some View {
    Menu {
      Text("Zoom: \(viewport.zoom.formatted(.percent.precision(.fractionLength(0))))")
      Divider()
      Button {
        viewport = viewport.withZoom(viewport.zoom / zoomStep)
      } label: {
        Label("Zoom out", systemImage: "minus.magnifyingglass")
      }
      Button {
        viewport = viewport.withZoom(viewport.zoom * zoomStep)
      } label: {
        Label("Zoom in", systemImage: "plus.magnifyingglass")
      }
      Divider()
      Button {
        viewport = viewport.withZoom(1)
      } label: {
        Label("Fit board", systemImage: "arrow.up.left.and.arrow.down.right")
      }
      Button {
        viewport = viewport.withZoom(viewport.fillZoom())
      } label: {
        Label("Fill screen", systemImage: "arrow.down.right.and.arrow.up.left")
      }
    } label: {
      if showsZoomReadout {
        HStack(spacing: 4) {
          Image(systemName: "magnifyingglass")
          Text(viewport.zoom, format: .percent.precision(.fractionLength(0)))
            .monospacedDigit()
        }
      } else {
        Image(systemName: "magnifyingglass")
      }
    }
    .accessibilityLabel("Zoom controls")
    .accessibilityValue(viewport.zoom.formatted(.percent.precision(.fractionLength(0))))
    .accessibilityIdentifier("zoomControlsMenu")
  }
}

private struct ExpandedZoomToolbar: View {
  @Binding var viewport: BoardViewport

  var body: some View {
    HStack(spacing: 16) {
      Button {
        viewport = viewport.withZoom(viewport.zoom * zoomStep)
      } label: {
        Image(systemName: "plus.magnifyingglass")
      }
      .accessibilityLabel("Zoom in")
      .accessibilityIdentifier("zoomInButton")

      Text(viewport.zoom, format: .percent.precision(.fractionLength(0)))
        .monospacedDigit()
        .accessibilityLabel("Zoom level")
        .accessibilityIdentifier("zoomLevelLabel")

      Button {
        viewport = viewport.withZoom(viewport.zoom / zoomStep)
      } label: {
        Image(systemName: "minus.magnifyingglass")
      }
      .accessibilityLabel("Zoom out")
      .accessibilityIdentifier("zoomOutButton")

      Menu {
        Button {
          viewport = viewport.withZoom(1)
        } label: {
          Label("Fit board", systemImage: "arrow.up.left.and.arrow.down.right")
        }
        Button {
          viewport = viewport.withZoom(viewport.fillZoom())
        } label: {
          Label("Fill screen", systemImage: "arrow.down.right.and.arrow.up.left")
        }
      } label: {
        Image(systemName: "ellipsis.circle")
      }
      .accessibilityLabel("More zoom actions")
      .accessibilityIdentifier("zoomOptionsMenu")
    }
  }
}

private struct CompactToolbar: View {
  let selectedTool: DrawingTool
  let selectedColorArgb: Int32
  let onSelectTool: (DrawingTool) -> Void
  let onSelectColor: (Int32) -> Void
  let isCondensed: Bool

  var body: some View {
    #if os(iOS)
      if #available(iOS 26.0, *) {
        LiquidGlassCompactToolbar(
          selectedTool: selectedTool,
          selectedColorArgb: selectedColorArgb,
          onSelectTool: onSelectTool,
          onSelectColor: onSelectColor,
          isCondensed: isCondensed
        )
      } else {
        CompactToolbarFallback(
          selectedTool: selectedTool,
          selectedColorArgb: selectedColorArgb,
          onSelectTool: onSelectTool,
          onSelectColor: onSelectColor,
          isCondensed: isCondensed
        )
      }
    #else
      CompactToolbarFallback(
        selectedTool: selectedTool,
        selectedColorArgb: selectedColorArgb,
        onSelectTool: onSelectTool,
        onSelectColor: onSelectColor,
        isCondensed: isCondensed
      )
    #endif
  }
}

/// iPad has room for zoom and editing controls in one bottom row. Keeping them
/// in the same glass container avoids a second bottom bar while preserving the
/// three overflow controls used on iPhone.
private struct ExpandedBoardToolbar: View {
  @Binding var viewport: BoardViewport
  let selectedTool: DrawingTool
  let selectedColorArgb: Int32
  let onSelectTool: (DrawingTool) -> Void
  let onSelectColor: (Int32) -> Void

  var body: some View {
    #if os(iOS)
      if #available(iOS 26.0, *) {
        GlassEffectContainer {
          ExpandedBoardToolbarContents(
            viewport: $viewport,
            selectedTool: selectedTool,
            selectedColorArgb: selectedColorArgb,
            onSelectTool: onSelectTool,
            onSelectColor: onSelectColor
          )
          .buttonStyle(.glass)
          .controlSize(.regular)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
      } else {
        ExpandedBoardToolbarFallback(
          viewport: $viewport,
          selectedTool: selectedTool,
          selectedColorArgb: selectedColorArgb,
          onSelectTool: onSelectTool,
          onSelectColor: onSelectColor
        )
      }
    #else
      ExpandedBoardToolbarFallback(
        viewport: $viewport,
        selectedTool: selectedTool,
        selectedColorArgb: selectedColorArgb,
        onSelectTool: onSelectTool,
        onSelectColor: onSelectColor
      )
    #endif
  }
}

private struct ExpandedBoardToolbarContents: View {
  @Binding var viewport: BoardViewport
  let selectedTool: DrawingTool
  let selectedColorArgb: Int32
  let onSelectTool: (DrawingTool) -> Void
  let onSelectColor: (Int32) -> Void

  var body: some View {
    HStack(spacing: 20) {
      ExpandedZoomToolbar(viewport: $viewport)
      Divider()
        .frame(height: 32)
      CompactToolbarMenus(
        selectedTool: selectedTool,
        selectedColorArgb: selectedColorArgb,
        onSelectTool: onSelectTool,
        onSelectColor: onSelectColor,
        isCondensed: false
      )
    }
  }
}

private struct ExpandedBoardToolbarFallback: View {
  @Binding var viewport: BoardViewport
  let selectedTool: DrawingTool
  let selectedColorArgb: Int32
  let onSelectTool: (DrawingTool) -> Void
  let onSelectColor: (Int32) -> Void

  var body: some View {
    ExpandedBoardToolbarContents(
      viewport: $viewport,
      selectedTool: selectedTool,
      selectedColorArgb: selectedColorArgb,
      onSelectTool: onSelectTool,
      onSelectColor: onSelectColor
    )
    .padding(.horizontal, 16)
    .padding(.vertical, 8)
    .frame(maxWidth: .infinity)
    .background(.bar)
  }
}

@available(iOS 26.0, macOS 26.0, *)
private struct LiquidGlassCompactToolbar: View {
  let selectedTool: DrawingTool
  let selectedColorArgb: Int32
  let onSelectTool: (DrawingTool) -> Void
  let onSelectColor: (Int32) -> Void
  let isCondensed: Bool

  var body: some View {
    GlassEffectContainer {
      CompactToolbarMenus(
        selectedTool: selectedTool,
        selectedColorArgb: selectedColorArgb,
        onSelectTool: onSelectTool,
        onSelectColor: onSelectColor,
        isCondensed: isCondensed
      )
      .buttonStyle(.glass)
      .controlSize(isCondensed ? .small : .regular)
    }
    .padding(.horizontal, 16)
    .padding(.vertical, isCondensed ? 4 : 8)
  }
}

private struct CompactToolbarFallback: View {
  let selectedTool: DrawingTool
  let selectedColorArgb: Int32
  let onSelectTool: (DrawingTool) -> Void
  let onSelectColor: (Int32) -> Void
  let isCondensed: Bool

  var body: some View {
    CompactToolbarMenus(
      selectedTool: selectedTool,
      selectedColorArgb: selectedColorArgb,
      onSelectTool: onSelectTool,
      onSelectColor: onSelectColor,
      isCondensed: isCondensed
    )
    .padding(.horizontal, 16)
    .padding(.vertical, isCondensed ? 4 : 8)
    .frame(maxWidth: .infinity)
    .background(.bar)
  }
}

#if os(iOS)
/// Uses standard toolbar placements. iPhone Duo moves these controls into the
/// lower portion of its vertical shared bar; other compact iPhones keep the
/// same content in a horizontal bottom toolbar.
@available(iOS 27.1, *)
private struct CompactBoardSystemToolbar: ToolbarContent {
  @Binding var viewport: BoardViewport
  let selectedTool: DrawingTool
  let selectedColorArgb: Int32
  let onSelectTool: (DrawingTool) -> Void
  let onSelectColor: (Int32) -> Void
  let editingEnabled: Bool
  let onShowPresence: () -> Void
  let onClear: () -> Void
  let onToggleSidebar: () -> Void

  var body: some ToolbarContent {
    ToolbarItem(placement: .topBarTrailing) {
      ZoomToolbarMenu(viewport: $viewport, showsZoomReadout: false)
    }
    .axisBehavior(.verticalPreferred)

    ToolbarItem(placement: .bottomBar) {
      ToolbarColorMenu(
        selectedColorArgb: selectedColorArgb,
        onSelectColor: onSelectColor
      )
    }
    .axisBehavior(.verticalPreferred)

    ToolbarItem(placement: .bottomBar) {
      ToolbarToolSelectionMenu(
        title: "Shapes",
        systemImage: "square.on.circle",
        tools: [.line, .rectangle, .ellipse],
        selectedTool: selectedTool,
        onSelectTool: onSelectTool
      )
    }
    .axisBehavior(.verticalPreferred)

    ToolbarItem(placement: .bottomBar) {
      ToolbarToolSelectionMenu(
        title: "Text and tools",
        systemImage: "long.text.page.and.pencil",
        tools: [.pen, .text, .eraser, .hand],
        selectedTool: selectedTool,
        onSelectTool: onSelectTool
      )
    }
    .axisBehavior(.verticalPreferred)

    ToolbarOverflowMenu {
      Button(action: onShowPresence) {
        Label("Presence graph", systemImage: "dot.radiowaves.left.and.right")
      }
      Button(role: .destructive, action: onClear) {
        Label("Clear board", systemImage: "trash")
      }
      .disabled(!editingEnabled)
      Button(action: onToggleSidebar) {
        Label("Inspector", systemImage: "sidebar.trailing")
      }
    }
  }
}

@available(iOS 27.1, *)
private struct ToolbarColorMenu: View {
  let selectedColorArgb: Int32
  let onSelectColor: (Int32) -> Void
  @Environment(\.dittoColors) private var colors

  var body: some View {
    Menu {
      ForEach(whiteboardPalette, id: \.self) { color in
        Button {
          onSelectColor(color)
        } label: {
          Label {
            Text(whiteboardColorName(color))
          } icon: {
            Circle()
              .fill(Color(argb: color))
          }
        }
      }
    } label: {
      Circle()
        .fill(Color(argb: selectedColorArgb))
        .overlay {
          Circle().stroke(colors.borderNormal, lineWidth: 1)
        }
        .frame(width: 22, height: 22)
        .frame(width: 32, height: 32)
    }
    .accessibilityLabel("Color")
    .accessibilityValue(whiteboardColorName(selectedColorArgb))
    .accessibilityIdentifier("colorMenu")
  }
}

@available(iOS 27.1, *)
private struct ToolbarToolSelectionMenu: View {
  let title: String
  let systemImage: String
  let tools: [DrawingTool]
  let selectedTool: DrawingTool
  let onSelectTool: (DrawingTool) -> Void
  @Environment(\.dittoColors) private var colors
  @Environment(\.dittoIsDark) private var isDark

  private var isSelected: Bool {
    tools.contains(selectedTool)
  }

  private var displayedSystemImage: String {
    isSelected ? selectedTool.systemImage : systemImage
  }

  var body: some View {
    Menu {
      ForEach(tools, id: \.self) { tool in
        Button {
          onSelectTool(tool)
        } label: {
          Label(tool.label, systemImage: tool.systemImage)
        }
      }
    } label: {
      Image(systemName: displayedSystemImage)
        .font(.title3)
        .frame(width: 32, height: 32)
        .foregroundStyle(
          isSelected
            ? (isDark ? colors.foregroundOnBrandPrimary : colors.foregroundOnFill)
            : colors.foregroundSubtle
        )
        .background(
          colors.fillControlSelected.opacity(isSelected ? 1 : 0),
          in: Circle()
        )
    }
    .accessibilityLabel(title)
    .accessibilityValue(isSelected ? "\(selectedTool.label), selected" : "No selected tool")
    .accessibilityAddTraits(isSelected ? .isSelected : [])
    .accessibilityIdentifier(
      tools == [.line, .rectangle, .ellipse] ? "shapesMenu" : "textAndToolsMenu"
    )
  }
}
#endif

private struct CompactToolbarMenus: View {
  let selectedTool: DrawingTool
  let selectedColorArgb: Int32
  let onSelectTool: (DrawingTool) -> Void
  let onSelectColor: (Int32) -> Void
  let isCondensed: Bool

  private var controlLength: CGFloat { isCondensed ? 28 : 32 }

  var body: some View {
    HStack(spacing: isCondensed ? 12 : 16) {
      CompactColorMenu(
        selectedColorArgb: selectedColorArgb,
        onSelectColor: onSelectColor,
        controlLength: controlLength
      )
      CompactToolSelectionMenu(
        title: "Shapes",
        systemImage: "square.on.circle",
        tools: [.line, .rectangle, .ellipse],
        selectedTool: selectedTool,
        onSelectTool: onSelectTool,
        controlLength: controlLength
      )
      CompactToolSelectionMenu(
        title: "Text and tools",
        systemImage: "long.text.page.and.pencil",
        tools: [.pen, .text, .eraser, .hand],
        selectedTool: selectedTool,
        onSelectTool: onSelectTool,
        controlLength: controlLength
      )
    }
  }
}

private struct CompactColorMenu: View {
  let selectedColorArgb: Int32
  let onSelectColor: (Int32) -> Void
  let controlLength: CGFloat
  @State private var isPalettePresented = false
  @Environment(\.dittoColors) private var colors

  var body: some View {
    Button {
      isPalettePresented = true
    } label: {
      Circle()
        .fill(Color(argb: selectedColorArgb))
        .overlay {
          Circle().stroke(colors.borderNormal, lineWidth: 1)
        }
        .frame(width: 22, height: 22)
        .frame(width: controlLength, height: controlLength)
    }
    .accessibilityLabel("Color")
    .accessibilityValue(whiteboardColorName(selectedColorArgb))
    .accessibilityIdentifier("colorMenu")
    .popover(isPresented: $isPalettePresented, attachmentAnchor: .rect(.bounds), arrowEdge: .bottom) {
      CompactColorPalette(
        selectedColorArgb: selectedColorArgb,
        onSelectColor: { color in
          onSelectColor(color)
          isPalettePresented = false
        }
      )
      #if os(iOS)
      .presentationCompactAdaptation(.popover)
      #endif
    }
  }
}

private struct CompactColorPalette: View {
  let selectedColorArgb: Int32
  let onSelectColor: (Int32) -> Void
  @Environment(\.dittoColors) private var colors

  private let columns = Array(repeating: GridItem(.fixed(44), spacing: 12), count: 4)

  var body: some View {
    LazyVGrid(columns: columns, spacing: 12) {
      ForEach(whiteboardPalette, id: \.self) { color in
        Button {
          onSelectColor(color)
        } label: {
          Circle()
            .fill(Color(argb: color))
            .overlay {
              Circle().stroke(
                color == selectedColorArgb ? colors.borderControlSelected : colors.borderNormal,
                lineWidth: color == selectedColorArgb ? 3 : 1
              )
            }
            .overlay {
              if color == selectedColorArgb {
                Image(systemName: "checkmark")
                  .font(.caption.weight(.bold))
                  .foregroundStyle(.white)
              }
            }
            .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(whiteboardColorName(color))
        .accessibilityAddTraits(color == selectedColorArgb ? .isSelected : [])
        .accessibilityIdentifier("colorSwatch.\(whiteboardPalette.firstIndex(of: color) ?? 0)")
      }
    }
    .padding(16)
    .accessibilityIdentifier("colorPalette")
  }
}

private struct CompactToolSelectionMenu: View {
  let title: String
  let systemImage: String
  let tools: [DrawingTool]
  let selectedTool: DrawingTool
  let onSelectTool: (DrawingTool) -> Void
  let controlLength: CGFloat
  @State private var isPalettePresented = false
  @Environment(\.dittoColors) private var colors
  @Environment(\.dittoIsDark) private var isDark

  private var isSelected: Bool {
    tools.contains(selectedTool)
  }

  private var displayedSystemImage: String {
    isSelected ? selectedTool.systemImage : systemImage
  }

  var body: some View {
    Button {
      isPalettePresented = true
    } label: {
      Image(systemName: displayedSystemImage)
        .font(.title3)
        .frame(width: controlLength, height: controlLength)
        .foregroundStyle(
          isSelected
            ? (isDark ? colors.foregroundOnBrandPrimary : colors.foregroundOnFill)
            : colors.foregroundSubtle
        )
        .background(
          colors.fillControlSelected.opacity(isSelected ? 1 : 0),
          in: Circle()
        )
    }
    .accessibilityLabel(title)
    .accessibilityValue(isSelected ? "\(selectedTool.label), selected" : "No selected tool")
    .accessibilityAddTraits(isSelected ? .isSelected : [])
    .accessibilityIdentifier(
      tools == [.line, .rectangle, .ellipse] ? "shapesMenu" : "textAndToolsMenu"
    )
    .popover(isPresented: $isPalettePresented, attachmentAnchor: .rect(.bounds), arrowEdge: .bottom) {
      CompactToolPalette(
        tools: tools,
        selectedTool: selectedTool,
        onSelectTool: { tool in
          onSelectTool(tool)
          isPalettePresented = false
        }
      )
      #if os(iOS)
      .presentationCompactAdaptation(.popover)
      #endif
    }
  }
}

private struct CompactToolPalette: View {
  let tools: [DrawingTool]
  let selectedTool: DrawingTool
  let onSelectTool: (DrawingTool) -> Void

  var body: some View {
    VStack(spacing: 0) {
      ForEach(tools, id: \.self) { tool in
        Button {
          onSelectTool(tool)
        } label: {
          HStack(spacing: 12) {
            Image(systemName: tool.systemImage)
              .frame(width: 24)
            Text(tool.label)
            Spacer()
            Image(systemName: "checkmark")
              .opacity(tool == selectedTool ? 1 : 0)
          }
          .frame(minWidth: 196, minHeight: 44, alignment: .leading)
          .padding(.horizontal, 16)
          .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tool.label)
        .accessibilityAddTraits(tool == selectedTool ? .isSelected : [])
        .accessibilityIdentifier("toolOption.\(tool.label.lowercased())")
      }
    }
    .padding(.vertical, 8)
    .accessibilityIdentifier("toolPalette")
  }
}

private struct ColorSwatch: View {
  let color: Int32
  let index: Int
  let isSelected: Bool
  let action: () -> Void
  @Environment(\.dittoColors) private var colors

  var body: some View {
    Button(action: action) {
      Circle()
        .fill(Color(argb: color))
        .frame(width: 30, height: 30)
        .overlay {
          Circle()
            .stroke(
              isSelected ? colors.borderControlSelected : colors.borderNormal,
              lineWidth: isSelected ? 3 : 1
            )
        }
        .frame(width: 44, height: 44)
    }
    .buttonStyle(.plain)
    .accessibilityLabel(
      isSelected ? "\(whiteboardColorName(color)), selected" : whiteboardColorName(color)
    )
    .accessibilityAddTraits(isSelected ? .isSelected : [])
    .accessibilityIdentifier("colorSwatch.\(index)")
  }
}

private struct BoardActionsMenu: View {
  let editingEnabled: Bool
  let onShowPresence: () -> Void
  let onClear: () -> Void
  let onToggleSidebar: () -> Void

  var body: some View {
    Menu {
      Button(action: onShowPresence) {
        Label("Presence graph", systemImage: "dot.radiowaves.left.and.right")
      }
      Button(role: .destructive, action: onClear) {
        Label("Clear board", systemImage: "trash")
      }
      .disabled(!editingEnabled)
      Button(action: onToggleSidebar) {
        Label("Inspector", systemImage: "sidebar.trailing")
      }
    } label: {
      Label("Board actions", systemImage: "ellipsis.circle")
    }
    .accessibilityIdentifier("boardActionsMenu")
  }
}
