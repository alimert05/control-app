// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AliControlMac",
    platforms: [.macOS(.v13)],
    targets: [
        // A menu-bar helper, not a library — there's nothing else in this
        // repo that needs to depend on it.
        .executableTarget(
            name: "AliControlMac",
            path: "Sources/AliControlMac",
            linkerSettings: [
                // Embeds Info.plist directly into the executable's __TEXT
                // segment. A plain SPM executable has no app bundle (and
                // therefore no Info.plist) by default, but CoreBluetooth
                // still looks one up to find NSBluetoothAlwaysUsageDescription
                // before it will allow CBPeripheralManager to be used.
                .unsafeFlags([
                    "-Xlinker", "-sectcreate",
                    "-Xlinker", "__TEXT",
                    "-Xlinker", "__info_plist",
                    "-Xlinker", "Info.plist"
                ])
            ]
        ),
        .testTarget(
            name: "AliControlMacTests",
            dependencies: ["AliControlMac"],
            path: "Tests/AliControlMacTests"
        )
    ]
)
