#if os(macOS)
import AppKit
import SwiftUI

/// Places the Ditto logotype in the native title-bar area before the system
/// navigation title. A toolbar item would be rendered as a liquid-glass
/// capsule on current macOS, which is inappropriate for static app branding.
struct MacTitlebarBrand: NSViewRepresentable {
  func makeCoordinator() -> Coordinator { Coordinator() }

  func makeNSView(context: Context) -> NSView {
    let host = TitlebarBrandHostView(frame: .zero)
    host.didMoveToWindow = { [weak coordinator = context.coordinator] window in
      coordinator?.install(in: window)
    }
    return host
  }

  func updateNSView(_ nsView: NSView, context: Context) {
    context.coordinator.install(in: nsView.window)
  }

  static func dismantleNSView(_ nsView: NSView, coordinator: Coordinator) {
    coordinator.remove()
  }

  final class Coordinator {
    private weak var window: NSWindow?
    private var accessory: NSTitlebarAccessoryViewController?

    func install(in window: NSWindow?) {
      guard let window, self.window !== window else { return }
      remove()

      let imageView = NSImageView()
      imageView.image = NSImage(named: "DittoLogotype")
      imageView.imageScaling = .scaleProportionallyDown
      imageView.frame = NSRect(x: 0, y: 2, width: 66, height: 18)
      imageView.autoresizingMask = [.width, .minYMargin, .maxYMargin]

      // A left/right title-bar accessory owns its width. Avoid Auto Layout
      // here: the title-bar host supplies the height and otherwise collapses
      // an unconstrained root view to zero.
      let brandView = NSView(frame: NSRect(x: 0, y: 0, width: 66, height: 22))
      brandView.addSubview(imageView)

      let accessory = NSTitlebarAccessoryViewController()
      accessory.view = brandView
      accessory.layoutAttribute = .left
      window.addTitlebarAccessoryViewController(accessory)

      self.window = window
      self.accessory = accessory
    }

    func remove() {
      guard let window, let accessory,
            let index = window.titlebarAccessoryViewControllers.firstIndex(of: accessory)
      else {
        self.window = nil
        self.accessory = nil
        return
      }
      window.removeTitlebarAccessoryViewController(at: index)
      self.window = nil
      self.accessory = nil
    }
  }
}

/// SwiftUI can create an `NSViewRepresentable` before it belongs to a window.
/// `viewDidMoveToWindow` is the first lifecycle callback at which a title-bar
/// accessory can be attached to the correct `NSWindow`.
private final class TitlebarBrandHostView: NSView {
  var didMoveToWindow: (NSWindow?) -> Void = { _ in }

  override func viewDidMoveToWindow() {
    super.viewDidMoveToWindow()
    didMoveToWindow(window)
  }
}
#endif
