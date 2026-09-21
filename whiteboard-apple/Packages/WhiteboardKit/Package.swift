// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "WhiteboardKit",
    platforms: [
        .macOS(.v14),
        .iOS(.v17),
    ],
    products: [
        .library(name: "WhiteboardKit", targets: ["WhiteboardKit"])
    ],
    dependencies: [
        .package(path: "../WhiteboardCore")
    ],
    targets: [
        .target(
            name: "WhiteboardKit",
            dependencies: [
                "WhiteboardCore",
                "DittoSwift",
            ]
        ),
        .binaryTarget(
            name: "DittoSwift",
            path: "../../Frameworks/DittoSwift.xcframework"
        ),
        .testTarget(
            name: "WhiteboardKitTests",
            dependencies: ["WhiteboardKit", "WhiteboardCore", "DittoSwift"]
        ),
    ]
)
