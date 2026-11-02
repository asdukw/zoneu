#!/usr/bin/env bash
# 生成「最近 N 个应用版本 → 本版」的补丁与清单，写进 release 资产目录。
#
# 用法：release-patches.sh <source-dir> <artifact-dir> <tag> [window]
#
# 三条与发布流程绑定的约定（见 docs/releasing.md 与 openspec 的 apk-delta-updates）：
# 1. **基线必须是已发布的字节**：从 release 重新下载旧包，并用它自己的 `.sha256` 校验 ——
#    不信任何本地缓存。补丁的 `baseSha256` 因此必然等于用户设备上那份。
# 2. **生成器来自源码，不走第二套实现**：调用 tag 源码里的 `:app:generatePatch`，与设备侧解码器
#    同源。老 tag 没有这个任务时会失败，那正是「该版本不发补丁」。
# 3. **失败不阻断发布**：调用方用 `continue-on-error: true`。补丁是流量优化，不是发布正确性的一部分；
#    一个没验证过的补丁发出去只会白耗用户流量。
set -euo pipefail

SOURCE=${1:?用法: release-patches.sh <source-dir> <artifact-dir> <tag> [window]}
ARTIFACTS=${2:?缺少 artifact 目录}
TAG=${3:?缺少 tag}
WINDOW=${4:-6}
: "${GH_REPO:?缺少 GH_REPO}"
: "${ANDROID_HOME:?缺少 ANDROID_HOME}"

# 绝对化：generatePatch 在 "$SOURCE/android" 下运行，相对路径会被拼成 source/android/source/...
SOURCE=$(cd "$SOURCE" && pwd)
mkdir -p "$ARTIFACTS"
ARTIFACTS=$(cd "$ARTIFACTS" && pwd)

APK="$SOURCE/android/app/build/outputs/apk/release/app-release.apk"
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt2"
[ -f "$APK" ] || { echo "::error::找不到 release APK：$APK"; exit 1; }
[ -x "$AAPT" ] || { echo "::error::找不到 aapt2：$AAPT"; exit 1; }

version_code_of() {
  "$AAPT" dump badging "$1" | grep -o "versionCode='[0-9]*'" | head -1 | tr -dc '0-9'
}

TARGET_CODE=$(version_code_of "$APK")
[ -n "$TARGET_CODE" ] || { echo "::error::读不出本版 versionCode"; exit 1; }
TARGET_NAME=${TAG#v}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# 窗口内最近 WINDOW 个应用版本（排除 rg-* 这类非应用 release 与本 tag）。
mapfile -t CANDIDATES < <(
  gh release list --repo "$GH_REPO" --limit 60 --json tagName,publishedAt \
    --jq '.[] | "\(.publishedAt)\t\(.tagName)"' |
    sort -r | cut -f2 |
    grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' |
    grep -vx "$TAG" |
    head -n "$WINDOW"
)

ARGS=()
SKIPPED=()
for candidate in "${CANDIDATES[@]}"; do
  mkdir -p "$WORK/$candidate"
  if ! gh release download "$candidate" --repo "$GH_REPO" \
      --pattern '*arm64-v8a.apk' --pattern '*.apk.sha256' --dir "$WORK/$candidate" --clobber >/dev/null; then
    SKIPPED+=("$candidate(下载失败)")
    continue
  fi
  base_apk=$(find "$WORK/$candidate" -maxdepth 1 -name '*arm64-v8a.apk' -print -quit)
  if [ -z "$base_apk" ]; then
    SKIPPED+=("$candidate(无 arm64 资产)")
    continue
  fi
  # 只认 release 里的哈希：基线必须与用户设备上那份逐字节相同，否则 C4 永远对不上。
  if ! (cd "$(dirname "$base_apk")" && sha256sum -c --status --ignore-missing ./*.apk.sha256); then
    SKIPPED+=("$candidate(哈希校验失败)")
    continue
  fi
  code=$(version_code_of "$base_apk")
  if [ -z "$code" ]; then
    SKIPPED+=("$candidate(读不出版本号)")
    continue
  fi
  ARGS+=(--base "$code:${candidate#v}:$base_apk")
done

if [ ${#ARGS[@]} -eq 0 ]; then
  echo "::notice::没有可用的基线版本，本次不发补丁（窗口内没有其他 release 或全部跳过）"
  exit 0
fi

echo "目标 $TAG（versionCode=$TARGET_CODE），基线 ${#ARGS[@]} 个"
(cd "$SOURCE/android" && ./gradlew :app:generatePatch --console=plain \
  --args="--out-dir $WORK/out --target-apk $APK --to-version-code $TARGET_CODE --to-version-name $TARGET_NAME ${ARGS[*]}")

mkdir -p "$ARTIFACTS"
shopt -s nullglob
for file in "$WORK"/out/*.patch "$WORK"/out/zoneu-patches.json; do
  cp "$file" "$ARTIFACTS/"
done
(cd "$ARTIFACTS" && for patch in ./*.patch; do sha256sum "$patch" > "$patch.sha256"; done)

COUNT=$(find "$ARTIFACTS" -maxdepth 1 -name '*.patch' | wc -l)
echo "::notice::已生成 $COUNT 个补丁 + zoneu-patches.json"
if [ ${#SKIPPED[@]} -gt 0 ]; then
  echo "::warning::跳过的基线：${SKIPPED[*]}"
fi
