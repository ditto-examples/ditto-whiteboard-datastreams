import Foundation

/// Credentials needed to open the Ditto instance backing ``DittoWhiteboardTransport``.
public struct DittoCredentials: Sendable, Equatable {
  public var databaseID: String
  public var offlineLicenseToken: String
  /// `nil` uses the SDK default (`ditto-{database-id}` under the Ditto root directory).
  public var persistenceDirectory: URL?

  public init(
    databaseID: String,
    offlineLicenseToken: String,
    persistenceDirectory: URL? = nil
  ) {
    self.databaseID = databaseID
    self.offlineLicenseToken = offlineLicenseToken
    self.persistenceDirectory = persistenceDirectory
  }
}
