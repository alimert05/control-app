// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AliControlMac",
    platforms: [.macOS(.v13)],
    targets: [
        // A menu-bar helper, not a library — there's nothing else in this
        // repo that needs to depend on it. `swift build`/`swift run` still
        // work for fast iteration and are what `swift test` uses, but the
        // supported way to actually run the app is the real bundle produced
        // by Scripts/build_app.sh (AliControlMac.app), which carries its own
        // proper Info.plist — this target no longer embeds one via a linker
        // trick, since the bare executable is a development convenience now,
        // not the shipped artifact.
        .executableTarget(
            name: "AliControlMac",
            path: "Sources/AliControlMac"
        ),
        .testTarget(
            name: "AliControlMacTests",
            dependencies: ["AliControlMac"],
            path: "Tests/AliControlMacTests"
        )
    ]
)
