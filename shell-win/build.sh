#!/usr/bin/env bash
# 交叉编译 Windows 版：dist/DubboLite-windows/ 与 dist/DubboLite-windows.zip
# 需要：Go 1.21+、python3；先执行项目根目录的 ./build.sh 生成 jar
set -euo pipefail
cd "$(dirname "$0")"
ROOT=..
OUT=$ROOT/dist/DubboLite-windows
B=$ROOT/build/win
[[ -f $ROOT/dist/dubbo-lite/dubbo-lite.jar ]] || { echo "请先运行 ./build.sh"; exit 1; }
rm -rf "$OUT" "$B" && mkdir -p "$OUT/app" "$B"

echo "  · 图标与清单"
if command -v qlmanage >/dev/null && qlmanage -t -s 1024 -o "$B" $ROOT/src/main/resources/web/icon.svg >/dev/null 2>&1; then
  PNGS=()
  for s in 16 24 32 48 64 128 256; do
    sips -z $s $s "$B/icon.svg.png" --out "$B/i$s.png" >/dev/null
    PNGS+=("$B/i$s.png")
  done
  python3 make_ico.py "$B/app.ico" "${PNGS[@]}"
else
  echo "    （非 macOS：沿用仓库中的 app.ico）"; cp app.ico "$B/app.ico"
fi
cp "$B/app.ico" app.ico
RSRC=$(command -v rsrc || echo "$(go env GOPATH)/bin/rsrc")
[[ -x $RSRC ]] || GOPROXY=${GOPROXY:-https://goproxy.cn,direct} go install github.com/akavel/rsrc@v0.10.2
"$RSRC" -arch amd64 -manifest app.manifest -ico app.ico -o rsrc_windows_amd64.syso

echo "  · 编译 DubboLite.exe"
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "-H windowsgui -s -w" -o "$OUT/DubboLite.exe" .

cp -R $ROOT/dist/dubbo-lite/dubbo-lite.jar $ROOT/dist/dubbo-lite/lib $ROOT/dist/dubbo-lite/lib-zms "$OUT/app/"
printf '@echo off\r\nrem 备用启动方式：不使用 WebView2，在 Edge/Chrome 的独立窗口中打开\r\ncd /d "%%~dp0"\r\nstart "" javaw -Xmx512m -Dfile.encoding=UTF-8 -jar "%%~dp0app\\dubbo-lite.jar" %%*\r\n' > "$OUT/DubboLite-browser.bat"
sed 's/$/\r/' README-windows.txt > "$OUT/README.txt"

( cd $ROOT/dist && rm -f DubboLite-windows.zip && zip -qr -X DubboLite-windows.zip DubboLite-windows )
echo "  · $OUT ($(du -sh "$OUT" | cut -f1))，压缩包 dist/DubboLite-windows.zip ($(du -sh $ROOT/dist/DubboLite-windows.zip | cut -f1))"
