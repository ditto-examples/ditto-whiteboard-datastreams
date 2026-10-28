import SwiftUI

/// Font family constants. Anvil-Swift does not bundle fonts — register Inter /
/// IBM Plex Mono in your app (assets + Info.plist `UIAppFonts`) and these
/// names resolve; the system font is used otherwise. Kairos Sans (brand
/// display font) is commercial and intentionally never bundled.
public enum DittoFonts {
    public static let sans = "Inter"
    public static let mono = "IBMPlexMono"
}

public extension Font {
    /// Ditto code/mono text style (IBM Plex Mono).
    static func dittoCode(size: CGFloat = 14) -> Font {
        .custom(DittoFonts.mono, size: size)
    }
}

// MARK: - Minimal themed components on core SwiftUI

/// Anvil primary/secondary/ghost button.
public struct AnvilButton: View {
    public enum Variant: Sendable { case primary, secondary, ghost }
    public enum Size: Sendable { case md, sm }

    private let title: String
    private let variant: Variant
    private let size: Size
    private let action: () -> Void

    @Environment(\.dittoColors) private var colors
    @Environment(\.isEnabled) private var isEnabled

    public init(
        _ title: String,
        variant: Variant = .primary,
        size: Size = .md,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.variant = variant
        self.size = size
        self.action = action
    }

    private var background: Color {
        guard isEnabled else { return colors.fillDisabled }
        return switch variant {
        case .primary: colors.fillBrandPrimary
        case .secondary: colors.surfaceSecondary
        case .ghost: .clear
        }
    }

    private var foreground: Color {
        guard isEnabled else { return colors.foregroundDisabled }
        return switch variant {
        case .primary: colors.foregroundOnBrandPrimary
        case .secondary: colors.foregroundNormal
        case .ghost: colors.foregroundAccent
        }
    }

    public var body: some View {
        Button(action: action) {
            Text(title)
                .font(.custom(DittoFonts.sans, size: size == .md ? 14 : 12))
                .fontWeight(.medium)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, size == .md ? 16 : 12)
        .padding(.vertical, size == .md ? 10 : 6)
        .frame(minHeight: size == .md ? 40 : 32)
        .background(background)
        .foregroundStyle(foreground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

/// Small status pill using Anvil's secondary status fills (AA body text).
public struct AnvilBadge: View {
    public enum Status: Sendable { case info, success, warning, critical, promo }

    private let text: String
    private let status: Status

    @Environment(\.dittoColors) private var colors

    public init(_ text: String, status: Status = .info) {
        self.text = text
        self.status = status
    }

    private var fill: Color {
        switch status {
        case .info: colors.fillInfoSecondary
        case .success: colors.fillSuccessSecondary
        case .warning: colors.fillWarningSecondary
        case .critical: colors.fillCriticalSecondary
        case .promo: colors.fillPromoSecondary
        }
    }

    public var body: some View {
        Text(text)
            .font(.custom(DittoFonts.sans, size: 12))
            .fontWeight(.medium)
            .foregroundStyle(colors.foregroundNormal)
            .padding(.horizontal, 10)
            .padding(.vertical, 2)
            .background(fill)
            .clipShape(Capsule())
    }
}

/// A Ditto-branded switch: enabled controls use the citrus track with a black
/// thumb in every appearance, instead of the platform's white switch thumb.
public struct DittoSwitchStyle: ToggleStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        DittoSwitchStyleBody(configuration: configuration)
    }
}

public extension ToggleStyle where Self == DittoSwitchStyle {
    static var ditto: DittoSwitchStyle { DittoSwitchStyle() }
}

private struct DittoSwitchStyleBody: View {
    let configuration: ToggleStyleConfiguration

    @Environment(\.dittoColors) private var colors
    @Environment(\.isEnabled) private var isEnabled

    private var trackColor: Color {
        guard isEnabled else { return colors.fillDisabled }
        return configuration.isOn ? AnvilLightPalette.citrus600 : colors.surfaceSecondary
    }

    private var thumbColor: Color {
        guard isEnabled else { return colors.foregroundDisabled }
        return configuration.isOn ? AnvilLightPalette.neutral950 : colors.foregroundNormal
    }

    var body: some View {
        Button {
            configuration.isOn.toggle()
        } label: {
            HStack(spacing: 12) {
                configuration.label
                Spacer(minLength: 0)
                Capsule()
                    .fill(trackColor)
                    .frame(width: 52, height: 32)
                    .overlay(alignment: configuration.isOn ? .trailing : .leading) {
                        Circle()
                            .fill(thumbColor)
                            .frame(width: 24, height: 24)
                            .padding(4)
                    }
            }
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .accessibilityValue(configuration.isOn ? Text("On") : Text("Off"))
        .animation(.snappy, value: configuration.isOn)
    }
}

/// Surface container with Anvil's normal border and radius.
public struct AnvilCard<Content: View>: View {
    private let content: Content

    @Environment(\.dittoColors) private var colors

    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    public var body: some View {
        content
            .padding(16)
            .background(colors.surface)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(colors.borderNormal, lineWidth: 1)
            )
    }
}

/// Themed text field with optional label and error state.
public struct AnvilInput: View {
    private let label: String?
    private let placeholder: String
    private var error: Bool
    @Binding private var text: String

    @Environment(\.dittoColors) private var colors

    public init(
        label: String? = nil,
        placeholder: String = "",
        error: Bool = false,
        text: Binding<String>
    ) {
        self.label = label
        self.placeholder = placeholder
        self.error = error
        self._text = text
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let label {
                Text(label)
                    .font(.custom(DittoFonts.sans, size: 14))
                    .fontWeight(.medium)
                    .foregroundStyle(colors.foregroundNormal)
            }
            TextField(placeholder, text: $text)
                .textFieldStyle(.plain)
                .font(.custom(DittoFonts.sans, size: 14))
                .foregroundStyle(colors.foregroundNormal)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .frame(minHeight: 40)
                .background(colors.surface)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(error ? colors.borderCritical : colors.borderNormal, lineWidth: 1)
                )
        }
    }
}
