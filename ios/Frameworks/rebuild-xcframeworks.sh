#!/bin/bash
#
# Rebuild the vendored xcframeworks (ApiVideoLiveStream, HaishinKit, Logboard).
#
# WHY THIS EXISTS
#   HaishinKit 1.9.3 (pulled by ApiVideoLiveStream 1.4.6) crashes swift-frontend
#   (SIL ownership verifier in MixerNode) under Xcode 26 whole-module
#   optimization. A per-app Podfile post_install hack that disabled optimization
#   for HaishinKit/Logboard used to work around it, but that hack has to be
#   copied into every consuming app. Instead we prebuild the SDK + its deps as
#   xcframeworks (with the crash worked around) and vendor them here, so
#   consumers never recompile HaishinKit and need no Podfile hack.
#
# WHAT IT PRODUCES
#   ./ApiVideoLiveStream.xcframework, ./HaishinKit.xcframework, ./Logboard.xcframework
#   (device: ios-arm64, simulator: ios-arm64_x86_64-simulator)
#
# IMPORTANT
#   * Built WITHOUT library evolution -> binary .swiftmodule only, so the
#     frameworks must be rebuilt with the SAME Xcode that builds the app
#     (currently Xcode 26.x). Rebuild when you bump Xcode or any version below.
#   * Requires a network connection (clones the three source repos).
#
# USAGE
#   cd ios/Frameworks && ./rebuild-xcframeworks.sh
#
set -euo pipefail

# ---- pinned source versions (match ApiVideoLiveStream 1.4.6's resolution) ----
APIVIDEO_TAG="v1.4.6"      # github.com/apivideo/api.video-swift-live-stream
HAISHINKIT_TAG="1.9.3"     # github.com/HaishinKit/HaishinKit.swift
LOGBOARD_TAG="2.5.0"       # github.com/shogo4405/Logboard

DEST_DIR="$(cd "$(dirname "$0")" && pwd)"          # ios/Frameworks
WORK="$(mktemp -d)/xcf-build"
mkdir -p "$WORK"
# Disable optimization AND whole-module for the build so HaishinKit compiles.
FLAGS=(SKIP_INSTALL=NO SWIFT_COMPILATION_MODE=singlefile SWIFT_OPTIMIZATION_LEVEL=-Onone)

echo ">>> workdir: $WORK"

# ---- 1. fetch sources -------------------------------------------------------
git clone --depth 1 --branch "$LOGBOARD_TAG"   https://github.com/shogo4405/Logboard.git              "$WORK/Logboard"
git clone --depth 1 --branch "$HAISHINKIT_TAG" https://github.com/HaishinKit/HaishinKit.swift.git     "$WORK/HaishinKit.swift"
git clone --depth 1 --branch "$APIVIDEO_TAG"   https://github.com/apivideo/api.video-swift-live-stream.git "$WORK/api.video-swift-live-stream"

# Remove bundled Xcode projects so xcodebuild builds our patched Package.swift
# (SwiftPM) rather than the repos' Carthage-oriented .xcodeproj.
for d in Logboard HaishinKit.swift api.video-swift-live-stream; do
  find "$WORK/$d" -maxdepth 1 \( -name '*.xcodeproj' -o -name '*.xcworkspace' \) -exec rm -rf {} +
done

# ---- 2. patch manifests -----------------------------------------------------
# Logboard: expose as a dynamic library.
perl -0pi -e 's/\.library\(name: "Logboard", targets: \["Logboard"\]\)/.library(name: "Logboard", type: .dynamic, targets: ["Logboard"])/' \
  "$WORK/Logboard/Package.swift"

# HaishinKit: dynamic product, Logboard as a prebuilt binaryTarget (no static
# merge), drop the SRT product we do not ship.
cat > "$WORK/HaishinKit.swift/Package.swift" <<'EOF'
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "HaishinKit",
    platforms: [ .iOS(.v13), .tvOS(.v13), .visionOS(.v1), .macOS(.v10_15), .macCatalyst(.v14) ],
    products: [
        .library(name: "HaishinKit", type: .dynamic, targets: ["HaishinKit"])
    ],
    targets: [
        .binaryTarget(name: "Logboard", path: "../Logboard.xcframework"),
        .target(name: "SwiftPMSupport"),
        .target(name: "HaishinKit",
                dependencies: ["Logboard", "SwiftPMSupport"],
                path: "Sources",
                sources: ["Codec","Extension","IO","ISO","Net","RTMP","Screen","Util"])
    ]
)
EOF

# HaishinKit's internal ObjC helper (SwiftPMSupport) must not leak into
# HaishinKit's public swiftinterface, or consumers would need that module too.
grep -rl "import SwiftPMSupport" "$WORK/HaishinKit.swift/Sources" | while IFS= read -r f; do
  perl -pi -e 's/^import SwiftPMSupport$/\@_implementationOnly import SwiftPMSupport/' "$f"
done

# ApiVideoLiveStream: dynamic product, HaishinKit + Logboard as binaryTargets.
cat > "$WORK/api.video-swift-live-stream/Package.swift" <<'EOF'
// swift-tools-version: 5.6
import PackageDescription

let package = Package(
    name: "ApiVideoLiveStream",
    platforms: [ .macOS(.v11), .iOS(.v13) ],
    products: [
        .library(name: "ApiVideoLiveStream", type: .dynamic, targets: ["ApiVideoLiveStream"])
    ],
    targets: [
        .binaryTarget(name: "HaishinKit", path: "../HaishinKit.xcframework"),
        .binaryTarget(name: "Logboard", path: "../Logboard.xcframework"),
        .target(name: "ApiVideoLiveStream", dependencies: ["HaishinKit", "Logboard"])
    ]
)
EOF

# ---- 3. build helpers -------------------------------------------------------
archive_one () { ( cd "$1"
  xcodebuild archive -scheme "$2" -destination "$3" \
    -archivePath "$WORK/out/$2-$4" -derivedDataPath "$WORK/dd-$2-$4" \
    "${FLAGS[@]}" > "$WORK/log-$2-$4.log" 2>&1 ); }

# SwiftPM installs an incomplete .framework (binary only) and emits the
# swiftmodule / generated header alongside it; reassemble a complete bundle.
assemble () { # scheme tag sdk
  local scheme="$1" tag="$2" sdk="$3"
  local DD="$WORK/dd-$scheme-$tag/Build/Intermediates.noindex/ArchiveIntermediates/$scheme"
  local SRC_FW="$WORK/out/$scheme-$tag.xcarchive/Products/usr/local/lib/$scheme.framework"
  local SWM="$DD/BuildProductsPath/Release-$sdk/$scheme.swiftmodule"
  local SWH="$DD/IntermediateBuildFilesPath/GeneratedModuleMaps-$sdk/$scheme-Swift.h"
  local OUT="$WORK/assembled/$tag/$scheme.framework"
  rm -rf "$OUT"; mkdir -p "$OUT/Modules/$scheme.swiftmodule" "$OUT/Headers"
  cp "$SRC_FW/$scheme" "$OUT/$scheme"
  cp "$SRC_FW/Info.plist" "$OUT/Info.plist"
  cp "$SWM"/* "$OUT/Modules/$scheme.swiftmodule/"
  local hdr_line=""
  if [ -f "$SWH" ]; then cp "$SWH" "$OUT/Headers/$scheme-Swift.h"; hdr_line="  header \"$scheme-Swift.h\""; fi
  { echo "framework module $scheme {"; [ -n "$hdr_line" ] && echo "$hdr_line"; echo "  export *"; echo "}"; } > "$OUT/Modules/module.modulemap"
}

# create-xcframework refuses binary-only (no swiftinterface) frameworks, so
# assemble the .xcframework directory by hand.
make_xcframework () { # scheme
  local scheme="$1" x="$WORK/xcframeworks/$1.xcframework"
  rm -rf "$x"; mkdir -p "$x/ios-arm64" "$x/ios-arm64_x86_64-simulator"
  cp -R "$WORK/assembled/ios/$scheme.framework" "$x/ios-arm64/"
  cp -R "$WORK/assembled/sim/$scheme.framework" "$x/ios-arm64_x86_64-simulator/"
  cat > "$x/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>AvailableLibraries</key><array>
    <dict>
      <key>LibraryIdentifier</key><string>ios-arm64</string>
      <key>LibraryPath</key><string>$scheme.framework</string>
      <key>SupportedArchitectures</key><array><string>arm64</string></array>
      <key>SupportedPlatform</key><string>ios</string>
    </dict>
    <dict>
      <key>LibraryIdentifier</key><string>ios-arm64_x86_64-simulator</string>
      <key>LibraryPath</key><string>$scheme.framework</string>
      <key>SupportedArchitectures</key><array><string>arm64</string><string>x86_64</string></array>
      <key>SupportedPlatform</key><string>ios</string>
      <key>SupportedPlatformVariant</key><string>simulator</string>
    </dict>
  </array>
  <key>CFBundlePackageType</key><string>XFWK</string>
  <key>XCFrameworkFormatVersion</key><string>1.0</string>
</dict></plist>
EOF
}

build_lib () { # pkgdir scheme
  echo ">>> $2: archive ios";  archive_one "$1" "$2" "generic/platform=iOS" ios
  echo ">>> $2: archive sim";  archive_one "$1" "$2" "generic/platform=iOS Simulator" sim
  assemble "$2" ios iphoneos
  assemble "$2" sim iphonesimulator
  make_xcframework "$2"
  # stage for the next lib's binaryTarget (path is ../<name>.xcframework)
  rm -rf "$WORK/$2.xcframework"; cp -R "$WORK/xcframeworks/$2.xcframework" "$WORK/$2.xcframework"
  echo ">>> $2.xcframework READY"
}

mkdir -p "$WORK/xcframeworks"
build_lib "$WORK/Logboard" "Logboard"
build_lib "$WORK/HaishinKit.swift" "HaishinKit"
build_lib "$WORK/api.video-swift-live-stream" "ApiVideoLiveStream"

# ---- 4. publish into ios/Frameworks ----------------------------------------
for f in Logboard HaishinKit ApiVideoLiveStream; do
  rm -rf "$DEST_DIR/$f.xcframework"
  cp -R "$WORK/xcframeworks/$f.xcframework" "$DEST_DIR/$f.xcframework"
done
echo ">>> Published to $DEST_DIR"
ls -1 "$DEST_DIR"/*.xcframework
echo ">>> Done. Review the diff and commit."
