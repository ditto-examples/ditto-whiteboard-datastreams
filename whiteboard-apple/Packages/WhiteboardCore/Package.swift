// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "WhiteboardCore",
    platforms: [
        .macOS(.v14),
        .iOS(.v17),
    ],
    products: [
        .library(name: "WhiteboardCore", targets: ["WhiteboardCore"])
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.28.0")
    ],
    targets: [
        .target(
            name: "WhiteboardCore",
            dependencies: [
                .product(name: "SwiftProtobuf", package: "swift-protobuf")
            ],
            resources: [.copy("whiteboard.proto")]
        ),
        .testTarget(
            name: "WhiteboardCoreTests",
            dependencies: ["WhiteboardCore"]
        ),
    ]
)
