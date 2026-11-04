#!/usr/bin/env bash
# tstunnel Go 模块（自写薄包装，包 tailscale.com/tsnet，App 进程内 userspace tailnet
# 客户端）经 gomobile 构建出 app/libs/tstunnel.aar，供 :app 的 androidTest 源集链接。
#
# 为什么不进门禁：依赖 Go 工具链 + Android SDK/NDK（真实外部环境），进门禁会让门禁
# 结果取决于开发机当下的状态。它是构建入口不是取证脚本：行为由 tstunnel 模块自身的
# go 单测与真机 spike 仪器测试验证。
#
# AAR 不入库（.gitignore 覆盖 app/libs/）：gomobile/gobind 与 tailscale.com 的版本钉在
# tstunnel/go.mod 的 tool 指令与 require 里。蓝本：private-content-assistant 的
# scripts/ops/build-android-tstunnel.sh（2026-09 真机验证形态，源自 mobile-manage）。
#
# 用法：
#   bash tstunnel/build-aar.sh [NDK 版本，缺省 28.2.13676358]
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
GO_DIR="$REPO_ROOT/tstunnel"
APP_AAR="$REPO_ROOT/app/libs/tstunnel.aar"
NDK_VERSION="${1:-28.2.13676358}"

ANDROID_HOME="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
[ -d "$ANDROID_HOME" ] || ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$NDK_VERSION}"
NDK_STRIP=$(echo "$NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-strip)

fail() { echo "build-aar: $*" >&2; exit 1; }

[ -x "$(command -v go)" ] || fail "未找到 go（模块要求 go ≥1.26）"
[ -d "$ANDROID_HOME/platforms" ] || fail "ANDROID_HOME=$ANDROID_HOME 下没有 platforms/，先装 Android SDK"
[ -d "$NDK_HOME" ] || fail "NDK 不存在：${NDK_HOME}（sdkmanager --install \"ndk;$NDK_VERSION\"）"
[ -x "$NDK_STRIP" ] || fail "NDK 里找不到 llvm-strip：$NDK_STRIP"

export ANDROID_HOME ANDROID_NDK_HOME="$NDK_HOME"
export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"

cd "$GO_DIR"
echo "==> 安装 gomobile/gobind（版本钉在 go.mod 的 tool 指令）"
mkdir -p .gobin
GOBIN="$GO_DIR/.gobin" go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
export PATH="$GO_DIR/.gobin:$PATH"

echo "==> gomobile bind（-target android -androidapi 26，与 tailscale-android 上游同参）"
mkdir -p "$(dirname "$APP_AAR")"
rm -f "$APP_AAR"
# 16KB page-size 对齐的 ldflags 照抄上游（tailscale-android/Makefile 的注释口径）。
gomobile bind -target android -androidapi 26 \
  -ldflags "-linkmode=external -extldflags=-Wl,-z,max-page-size=16384" \
  -o "$APP_AAR" . || fail "gomobile bind 失败"
[ -f "$APP_AAR" ] || fail "AAR 未生成：$APP_AAR"

echo "==> 剥离调试符号（llvm-strip，对齐上游做法）并重打包"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
unzip -q "$APP_AAR" -d "$WORK"
(
  cd "$WORK"
  found=0
  for so in jni/*/libgojni.so; do
    [ -f "$so" ] || continue
    found=1
    before=$(wc -c <"$so" | tr -d ' ')
    "$NDK_STRIP" "$so"
    printf '  %s: %s -> %s\n' "$so" "$before" "$(wc -c <"$so" | tr -d ' ')"
  done
  [ "$found" = 1 ] || fail "AAR 里没有 jni/*/libgojni.so（gomobile 产物形状变了？）"
  zip -q -r -X "$APP_AAR" . -x 'META-INF/*' || fail "重打包失败"
)

echo "==> 完成：$APP_AAR ($(du -h "$APP_AAR" | cut -f1))"
