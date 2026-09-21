import XCTest

final class WhiteboardUITests: XCTestCase {
  @MainActor
  func testJoinBoardShowsCanvas() throws {
    let app = XCUIApplication()
    app.launchArguments += ["-WhiteboardResetProfile"]
    app.launch()

    let nameField = app.textFields["displayNameField"]
    guard nameField.waitForExistence(timeout: 15) else {
      XCTFail("Profile setup screen did not appear")
      return
    }
    #if os(macOS)
      nameField.click()
    #else
      nameField.tap()
    #endif
    nameField.typeText("UITester")

    let join = app.buttons["joinButton"]
    guard join.waitForExistence(timeout: 5) else {
      XCTFail("Join button missing")
      return
    }
    #if os(macOS)
      join.click()
    #else
      join.tap()
    #endif

    let canvas = app.descendants(matching: .any)["boardCanvas"]
    XCTAssertTrue(canvas.waitForExistence(timeout: 15), "Board canvas did not appear")
  }
}
