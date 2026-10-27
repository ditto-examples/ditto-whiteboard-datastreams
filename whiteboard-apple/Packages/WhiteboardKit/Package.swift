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
        .package(path: "../WhiteboardCore"),
        .package(url: "https://github.com/getditto/DittoSwiftPackage.git", exact: "5.2.0-rc.2"),
    ],
    targets: [
        .target(
            name: "WhiteboardKit",
            dependencies: [
                "WhiteboardCore",
                .product(name: "DittoSwift", package: "DittoSwiftPackage"),
            ]
        ),
        .testTarget(
            name: "WhiteboardKitTests",
            dependencies: [
                "WhiteboardKit", "WhiteboardCore",
                .product(name: "DittoSwift", package: "DittoSwiftPackage"),
            ]
        ),
    ]
)
