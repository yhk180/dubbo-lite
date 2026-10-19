#!/usr/bin/env bash
# 发布新版本：已安装的 Dubbo Lite 通过“检查更新”从 GitHub Releases 拉取
#   ./release.sh 1.1.0 "更新说明"              写入 VERSION 并构建，生成 dist/release/（mac/win 安装包 + latest.json），不上传
#   ./release.sh 1.1.0 notes.md --publish      同上；再提交 VERSION、push，并用 gh 创建 Release v1.1.0 上传三个文件
# 更新说明可以是文字，也可以是文件路径（会显示在应用的更新弹窗里）。
# --publish 需要 GitHub CLI（brew install gh && gh auth login），且除 VERSION 外没有未提交的改动。
set -euo pipefail
cd "$(dirname "$0")"

REPO=yhk180/dubbo-lite
USAGE="用法：./release.sh <版本号> <更新说明|说明文件> [--publish]"
VER="${1:?$USAGE}"
NOTES_ARG="${2:?$USAGE}"
PUBLISH="${3:-}"
[[ $VER =~ ^[0-9]+(\.[0-9]+)*$ ]] || { echo "版本号格式应为 1.2.3"; exit 1; }
if [[ -f $NOTES_ARG ]]; then NOTES=$(cat "$NOTES_ARG"); else NOTES=$NOTES_ARG; fi

if [[ $PUBLISH == --publish ]]; then
  command -v gh >/dev/null || { echo "需要 GitHub CLI：brew install gh && gh auth login"; exit 1; }
  if [[ -n $(git status --porcelain --untracked-files=no | grep -v ' VERSION$' || true) ]]; then
    echo "有未提交的改动，请先提交："; git status --short --untracked-files=no; exit 1
  fi
  if gh release view "v$VER" --repo "$REPO" >/dev/null 2>&1; then
    echo "Release v$VER 已存在"; exit 1
  fi
elif [[ -n $PUBLISH ]]; then
  echo "$USAGE"; exit 1
fi

echo "$VER" > VERSION
./build.sh all

OUT=dist/release
rm -rf "$OUT" && mkdir -p "$OUT"
# ditto 保留可执行权限、签名与扩展属性；应用内更新同样用 ditto 解压
ditto -c -k --keepParent dist/DubboLite.app "$OUT/DubboLite-mac.zip"
cp dist/DubboLite-windows.zip "$OUT/"

python3 - "$VER" "$NOTES" "$REPO" "$OUT" <<'EOF'
import datetime, hashlib, json, os, sys
ver, notes, repo, out = sys.argv[1:5]
base = f"https://github.com/{repo}/releases/download/v{ver}"
def asset(name):
    p = os.path.join(out, name)
    with open(p, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()
    return {"url": f"{base}/{name}", "sha256": sha, "size": os.path.getsize(p)}
latest = {
    "version": ver,
    "date": datetime.date.today().isoformat(),
    "notes": notes,
    "page": f"https://github.com/{repo}/releases/tag/v{ver}",
    "assets": {"mac": asset("DubboLite-mac.zip"), "win": asset("DubboLite-windows.zip")},
}
with open(os.path.join(out, "latest.json"), "w", encoding="utf-8") as f:
    json.dump(latest, f, ensure_ascii=False, indent=2)
EOF
echo "▸ 已生成 $OUT："; ls -lh "$OUT" | tail -n +2

if [[ $PUBLISH != --publish ]]; then
  echo "未上传。确认无误后执行：./release.sh $VER '<更新说明>' --publish"
  exit 0
fi

echo "▸ 提交并推送 VERSION"
if ! git diff --quiet -- VERSION; then
  git commit -m "发布 $VER" -- VERSION
fi
git push

echo "▸ 创建 GitHub Release v$VER"
gh release create "v$VER" "$OUT/DubboLite-mac.zip" "$OUT/DubboLite-windows.zip" "$OUT/latest.json" \
  --repo "$REPO" --target "$(git rev-parse HEAD)" --title "Dubbo Lite $VER" --notes "$NOTES"
echo "✓ 已发布：https://github.com/$REPO/releases/tag/v$VER"
