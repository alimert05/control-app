#!/bin/bash
# Builds a real, double-clickable AliControlMac.app from the Swift package.
#
# Replaces the earlier bundle-less `swift run` + linker-embedded-Info.plist
# workaround: the package now only produces a bare executable (useful for
# `swift build`/`swift test` during development), and this script assembles
# that executable into a normal macOS application bundle with its own
# Contents/Info.plist — the officially supported way to run the app day to
# day (no Terminal, no Xcode, no `swift run` needed afterwards).
#
# Usage: ./Scripts/build_app.sh [debug|release]   (default: release)
#
# Signing: ad-hoc only (`codesign --sign -`) — no certificate, no Apple
# Developer account, nothing to create or commit. Sufficient for local
# testing on this Mac. See README.md for the distinction between this local
# build and a future distributable/notarized one.
set -euo pipefail

cd "$(dirname "$0")/.."

CONFIG="${1:-release}"
APP_NAME="AliControlMac"
BUILD_DIR=".build/${CONFIG}"
DIST_DIR="dist"
APP_BUNDLE="${DIST_DIR}/${APP_NAME}.app"

echo "==> Building ${APP_NAME} (${CONFIG})"
swift build -c "${CONFIG}"

echo "==> Assembling ${APP_BUNDLE}"
rm -rf "${APP_BUNDLE}"
mkdir -p "${APP_BUNDLE}/Contents/MacOS"
mkdir -p "${APP_BUNDLE}/Contents/Resources"

cp "${BUILD_DIR}/${APP_NAME}" "${APP_BUNDLE}/Contents/MacOS/${APP_NAME}"
cp "Info.plist" "${APP_BUNDLE}/Contents/Info.plist"

echo "==> Signing ${APP_BUNDLE} (ad-hoc)"
codesign --force --deep --sign - "${APP_BUNDLE}"
codesign --verify --verbose "${APP_BUNDLE}"

echo "==> Done: ${APP_BUNDLE}"
echo "    Run it with: open \"${APP_BUNDLE}\""
