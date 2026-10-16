#!/usr/bin/env bash
# 生成 dist/DubboLite.app：原生窗口(WKWebView) + dist/dubbo-lite 中的 jar。运行时使用本机已安装的 Java。
set -euo pipefail
cd "$(dirname "$0")/.."

APP=dist/DubboLite.app
VERSION=1.0.0
rm -rf "$APP" build/shell
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/app" build/shell

echo "  · 编译 Swift 外壳"
ARCHS=()
for arch in arm64 x86_64; do
  if swiftc -O -target "$arch-apple-macos11" -o "build/shell/DubboLite-$arch" shell/DubboLite.swift \
       -framework Cocoa -framework WebKit 2>build/shell/swiftc-$arch.log; then
    ARCHS+=("build/shell/DubboLite-$arch")
  fi
done
if [[ ${#ARCHS[@]} -eq 0 ]]; then
  cat build/shell/swiftc-*.log
  exit 1
fi
lipo -create "${ARCHS[@]}" -output "$APP/Contents/MacOS/DubboLite"

cp -R dist/dubbo-lite/dubbo-lite.jar dist/dubbo-lite/lib dist/dubbo-lite/lib-zms "$APP/Contents/Resources/app/"

echo "  · 生成图标"
ICON_OK=0
# 用 NSImage 渲染为透明背景（qlmanage 的缩略图带白底，图标四角会有白边）；每个尺寸单独渲染，小图更清晰
if swiftc -O -o build/shell/svg2png shell/svg2png.swift -framework AppKit 2>build/shell/svg2png.log; then
  SET=build/shell/AppIcon.iconset
  mkdir -p "$SET"
  for s in 16 32 128 256 512; do
    build/shell/svg2png shell/AppIcon.svg "$SET/icon_${s}x${s}.png" $s shadow
    build/shell/svg2png shell/AppIcon.svg "$SET/icon_${s}x${s}@2x.png" $((s * 2)) shadow
  done
  iconutil -c icns "$SET" -o "$APP/Contents/Resources/AppIcon.icns" && ICON_OK=1
fi

cat > "$APP/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>Dubbo Lite</string>
  <key>CFBundleDisplayName</key><string>Dubbo Lite</string>
  <key>CFBundleIdentifier</key><string>local.dubbolite.app</string>
  <key>CFBundleExecutable</key><string>DubboLite</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>${VERSION}</string>
  <key>CFBundleVersion</key><string>${VERSION}</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>LSMinimumSystemVersion</key><string>11.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSHumanReadableCopyright</key><string>轻量的 Dubbo 请求工具 · 兼容 JMeter .jmx</string>
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsLocalNetworking</key><true/></dict>
  <key>CFBundleDocumentTypes</key>
  <array>
    <dict>
      <key>CFBundleTypeName</key><string>JMeter Test Plan</string>
      <key>CFBundleTypeRole</key><string>Editor</string>
      <key>LSHandlerRank</key><string>Alternate</string>
      <key>CFBundleTypeExtensions</key><array><string>jmx</string></array>
    </dict>
  </array>
</dict>
</plist>
EOF
[[ $ICON_OK -eq 1 ]] || /usr/libexec/PlistBuddy -c "Delete :CFBundleIconFile" "$APP/Contents/Info.plist"

codesign --force --deep --sign - "$APP" >/dev/null 2>&1 || true
echo "  · $APP ($(du -sh "$APP" | cut -f1))"
