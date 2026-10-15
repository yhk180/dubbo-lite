#!/usr/bin/env bash
# 构建 Dubbo Lite：只需要 JDK 8+（依赖已放在 lib/，离线可用）
#   ./build.sh          → dist/dubbo-lite/（跨平台：jar + lib + 启动脚本）
#   ./build.sh app      → 另外生成 dist/DubboLite.app（macOS 原生窗口，需要 Xcode 命令行工具）
#   ./build.sh win      → 另外生成 dist/DubboLite-windows.zip（Windows 原生窗口，需要 Go，可在 macOS 上交叉编译）
#   ./build.sh all      → 以上全部
set -euo pipefail
cd "$(dirname "$0")"

JAVAC=javac
if [[ "$(uname)" == "Darwin" ]] && /usr/libexec/java_home -v 1.8 >/dev/null 2>&1; then
  JAVAC="$(/usr/libexec/java_home -v 1.8)/bin/javac"
fi

rm -rf build dist
mkdir -p build/classes dist/dubbo-lite/lib

echo "▸ 编译 Java ($JAVAC)"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn -cp "lib/*" -d build/classes $(find src/main/java -name '*.java')
cp -R src/main/resources/. build/classes/

echo "▸ 打包 jar"
CLASS_PATH=$(cd lib && ls *.jar | sed 's#^#lib/#' | tr '\n' ' ')
cat > build/MANIFEST.MF <<EOF
Manifest-Version: 1.0
Main-Class: dubbolite.Main
Class-Path: ${CLASS_PATH}
Add-Opens: java.base/java.lang java.base/java.util java.base/java.math java.base/java.time java.base/java.text java.base/java.lang.reflect java.base/java.util.concurrent java.base/java.net
EOF
jar cfm dist/dubbo-lite/dubbo-lite.jar build/MANIFEST.MF -C build/classes .
cp lib/*.jar dist/dubbo-lite/lib/
# ZMS 客户端依赖较多且与 Dubbo 冲突，单独放在 lib-zms，运行时用独立类加载器加载
mkdir -p dist/dubbo-lite/lib-zms && cp lib-zms/*.jar dist/dubbo-lite/lib-zms/

# 启动脚本：优先 Java 8（与 Dubbo 2.6 最匹配）；更高版本也能运行（jar 清单已声明 Add-Opens）
cat > dist/dubbo-lite/dubbo-lite.sh <<'EOF'
#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA=java
if [[ "$(uname)" == "Darwin" ]] && /usr/libexec/java_home -v 1.8 >/dev/null 2>&1; then
  JAVA="$(/usr/libexec/java_home -v 1.8)/bin/java"
fi
exec "$JAVA" -Xmx512m -jar "$DIR/dubbo-lite.jar" "$@"
EOF
printf '@echo off\r\njava -Xmx512m -jar "%%~dp0dubbo-lite.jar" %%*\r\n' > dist/dubbo-lite/dubbo-lite.bat
chmod +x dist/dubbo-lite/dubbo-lite.sh

if [[ "${1:-}" == "app" || "${1:-}" == "all" ]]; then
  echo "▸ 构建 macOS 应用"
  ./shell/build-app.sh
fi
if [[ "${1:-}" == "win" || "${1:-}" == "all" ]]; then
  echo "▸ 构建 Windows 版"
  ./shell-win/build.sh
fi
echo "✓ 完成：$(du -sh dist/dubbo-lite | cut -f1)  dist/dubbo-lite/"
