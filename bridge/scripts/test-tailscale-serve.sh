#!/usr/bin/env bash
# tailscale-serve.sh 的确定性测试。
#
# serve 脚本需要真实 tailnet 与本机 dshbridge，不能在回归里真实执行：这里把它复制进
# 临时目录，在 PATH 前放置记录调用的假 tailscale / curl / sudo，从外部观察脚本的分支
# 行为与副作用（写入 / 拒绝 / 提权），真实 tailnet 与本机服务不受影响。
# 不用 env -i：Windows host 的 python3 依赖 SYSTEMROOT 等环境变量，这里只覆盖 PATH。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT

FAKE="$T/fakebin"
FIX="$T/fixture"
STATE="$T/state"
mkdir -p "$FAKE" "$FIX" "$STATE"
cp "$SCRIPT_DIR/tailscale-serve.sh" "$T/tailscale-serve.sh"

printf '{"protocolVersion":1,"listen":"127.0.0.1:3090","upstream":"http://127.0.0.1:3080","publicOrigin":"https://node.example.ts.net:3090"}' >"$STATE/config.json"

PASS=0
fail() {
  echo "test-tailscale-serve: FAIL: $*" >&2
  if [ -f "$T/out" ]; then
    echo "--- 脚本输出 ---" >&2
    cat "$T/out" >&2
  fi
  exit 1
}
ok() { PASS=$((PASS + 1)); echo "  ok - $*"; }

# ── 假命令：记录调用并按 fixture 开关模拟行为 ──────────────────────────────

fake() {
  printf '#!/usr/bin/env bash\nFIX=%q\n%s\n' "$FIX" "$2" >"$FAKE/$1"
  chmod +x "$FAKE/$1"
}

fake tailscale '
echo "tailscale $*" >>"$FIX/calls.log"
case "$*" in
  status) exit 0 ;;
  "status --peers=false --json") echo "{\"Self\":{\"DNSName\":\"node.example.ts.net.\"}}" ;;
  "serve status") echo "fake serve status" ;;
  "serve status --json")
    [ ! -f "$FIX/no-status" ] || exit 1
    if [ -f "$FIX/garbage-status" ]; then echo "not json"; exit 0; fi
    if [ -f "$FIX/serve.json" ]; then cat "$FIX/serve.json"; else echo null; fi
    ;;
  "serve --bg --https="*)
    [ ! -f "$FIX/deny" ] || { echo "access denied" >&2; exit 1; }
    port="${3#--https=}"
    python3 - "$FIX/serve.json" "$port" "$4" <<PY
import json, os, sys
path, port, url = sys.argv[1:]
data = (json.load(open(path)) if os.path.exists(path) and os.path.getsize(path) else None) or {}
data.setdefault("TCP", {})[port] = {"HTTPS": True}
data.setdefault("Web", {})["node.example.ts.net:" + port] = {"Handlers": {"/": {"Proxy": url}}}
json.dump(data, open(path, "w"))
PY
    ;;
  "serve --https="*" off")
    port="${2#--https=}"
    python3 - "$FIX/serve.json" "$port" <<PY
import json, os, sys
path, port = sys.argv[1:]
if os.path.exists(path):
    data = json.load(open(path))
    data.get("TCP", {}).pop(port, None)
    data.get("Web", {}).pop("node.example.ts.net:" + port, None)
    json.dump(data, open(path, "w"))
PY
    ;;
  *) echo "fake tailscale: 未预期的参数 $*" >&2; exit 2 ;;
esac'

fake curl '
echo "curl $*" >>"$FIX/calls.log"
if [ -f "$FIX/unhealthy" ]; then exit 7; fi
printf 404'

fake sudo '
echo "sudo $*" >>"$FIX/calls.log"
if [ "$*" = "-n true" ]; then
  [ -f "$FIX/sudo-ok" ] || exit 1
  exit 0
fi
exec "$@"'

reset_fix() {
  rm -f "$FIX"/*
  : >"$FIX/calls.log"
}

# run [VAR=v ...] -- <脚本参数...>：在假环境中运行，输出落 $T/out，退出码存 RC。
# 不覆盖 HOME：脚本本身不读 HOME，而 Windows 常见的 python3 shim 依赖它定位解释器。
run() {
  RC=0
  env PATH="$FAKE:$PATH" \
    DSHBRIDGE_STATE="${DSHBRIDGE_STATE:-}" DSHBRIDGE_SERVE_PORT="${DSHBRIDGE_SERVE_PORT:-}" \
    bash "$T/tailscale-serve.sh" "$@" >"$T/out" 2>&1 || RC=$?
}
assert_ok() { [ "$RC" -eq 0 ] || fail "$*（退出码 $RC）"; }
assert_fail() { [ "$RC" -ne 0 ] || fail "$*（应非零退出）"; }
called() { grep -qF -- "$1" "$FIX/calls.log" 2>/dev/null; }
not_called() { ! called "$1"; }
out_has() { grep -q -- "$1" "$T/out"; }

set_serve_json() { printf '%s' "$1" >"$FIX/serve.json"; }

OURS='{"TCP":{"3090":{"HTTPS":true}},"Web":{"node.example.ts.net:3090":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:3090"}}}}}'

# ── serve 子命令 ────────────────────────────────────────────────────────────

echo "serve：空闲端口 + 后端健康 → 写入 https 反代"
reset_fix
run --state "$STATE"
assert_ok "serve 应成功"
called "serve --bg --https=3090 http://127.0.0.1:3090" || fail "应写入 serve --bg --https=3090"
out_has "https://node.example.ts.net:3090" || fail "应输出 tailnet 访问地址"
ok "serve 写入空闲端口"

echo "serve：已是本服务配置 → 幂等跳过"
reset_fix
set_serve_json "$OURS"
run --state "$STATE"
assert_ok "幂等 serve 应成功"
not_called "serve --bg" || fail "已配置时不应再次写入"
out_has "无需修改" || fail "应提示无需修改"
ok "serve 幂等"

echo "serve：端口被 TCP 转发占用 → 拒绝"
reset_fix
set_serve_json '{"TCP":{"3090":{"TCPForward":"127.0.0.1:9999"}}}'
run --state "$STATE"
assert_fail "TCP 转发占用应拒绝"
not_called "serve --bg" || fail "被占用时不得写入"
out_has "TCP 转发" || fail "应说明 TCP 转发占用"
ok "serve 拒绝 TCP 转发占用"

echo "serve：端口开启 Funnel → 拒绝"
reset_fix
set_serve_json '{"TCP":{"3090":{"HTTPS":true}},"Web":{"node.example.ts.net:3090":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:3090"}}}},"AllowFunnel":{"node.example.ts.net:3090":true}}'
run --state "$STATE"
assert_fail "Funnel 应拒绝"
not_called "serve --bg" || fail "Funnel 时不得写入"
out_has "Funnel" || fail "应说明 Funnel 禁止"
ok "serve 拒绝 Funnel"

echo "serve：端口被前台 serve 会话占用 → 拒绝"
reset_fix
set_serve_json '{"Foreground":{"abc":{"TCP":{"3090":{"TCPForward":"127.0.0.1:1"}}}}}'
run --state "$STATE"
assert_fail "前台会话占用应拒绝"
not_called "serve --bg" || fail "前台会话占用时不得写入"
out_has "前台 serve 会话" || fail "应说明前台会话占用"
ok "serve 拒绝前台会话占用"

echo "serve：端口被其他服务的反代占用 → 拒绝"
reset_fix
set_serve_json '{"Web":{"node.example.ts.net:3090":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:9999"}}}}}'
run --state "$STATE"
assert_fail "他人配置应拒绝"
not_called "serve --bg" || fail "他人配置时不得写入"
ok "serve 拒绝他人反代占用"

echo "serve：端口被明文 HTTP serve 占用 → 拒绝"
reset_fix
set_serve_json '{"TCP":{"3090":{"HTTP":true}},"Web":{"node.example.ts.net:3090":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:3090"}}}}}'
run --state "$STATE"
assert_fail "明文 HTTP serve 应拒绝"
not_called "serve --bg" || fail "明文 HTTP serve 时不得写入"
out_has "明文 HTTP" || fail "应说明明文 HTTP serve 占用"
ok "serve 拒绝明文 HTTP serve 占用"

echo "serve：serve status 读取失败 → 拒绝修改"
reset_fix
: >"$FIX/no-status"
run --state "$STATE"
assert_fail "status 读取失败应拒绝"
not_called "serve --bg" || fail "读取失败时不得写入"
out_has "拒绝修改" || fail "应说明拒绝写入的原因"
ok "serve 拒绝于 status 读取失败"

echo "serve：serve status 输出非 JSON → 拒绝修改"
reset_fix
: >"$FIX/garbage-status"
run --state "$STATE"
assert_fail "非 JSON 输出应拒绝"
not_called "serve --bg" || fail "解析失败时不得写入"
ok "serve 拒绝于 status 解析失败"

echo "serve：后端不可达 → 先拒绝，不碰 Serve 配置"
reset_fix
set_serve_json "$OURS"
: >"$FIX/unhealthy"
run --state "$STATE"
assert_fail "后端不可达应拒绝"
not_called "serve --bg" || fail "后端不可达时不得写入"
out_has "不可达" || fail "应提示后端不可达"
ok "serve 拒绝于后端不可达"

echo "serve：缺少 --state → 拒绝"
reset_fix
run
assert_fail "缺少 state 应拒绝"
out_has "DSHBRIDGE_STATE" || fail "应提示 --state / DSHBRIDGE_STATE"
ok "serve 缺少 state 拒绝"

echo "serve：listen 非回环地址 → 拒绝"
reset_fix
printf '{"protocolVersion":1,"listen":"0.0.0.0:3090","upstream":"http://127.0.0.1:3080","publicOrigin":"https://node.example.ts.net:3090"}' >"$STATE/config.json"
run --state "$STATE"
assert_fail "非回环监听应拒绝"
out_has "回环" || fail "应说明仅支持回环监听"
printf '{"protocolVersion":1,"listen":"localhost:3090","upstream":"http://127.0.0.1:3080","publicOrigin":"https://node.example.ts.net:3090"}' >"$STATE/config.json"
run --state "$STATE"
assert_fail "localhost 监听应拒绝（与 Go 侧 PlainListenerAllowed 对齐，只认回环 IP 字面量）"
printf '{"protocolVersion":1,"listen":"127.0.0.1:3090","upstream":"http://127.0.0.1:3080","publicOrigin":"https://node.example.ts.net:3090"}' >"$STATE/config.json"
ok "serve 拒绝非回环监听"

echo "serve：listen 为 [::1] 回环 → 接受并规范化 URL"
reset_fix
printf '{"protocolVersion":1,"listen":"[::1]:3090","upstream":"http://127.0.0.1:3080","publicOrigin":"https://node.example.ts.net:3090"}' >"$STATE/config.json"
run --state "$STATE"
assert_ok "[::1] 回环 serve 应成功"
called "serve --bg --https=3090 http://[::1]:3090" || fail "应以规范化 URL 写入"
printf '{"protocolVersion":1,"listen":"127.0.0.1:3090","upstream":"http://127.0.0.1:3080","publicOrigin":"https://node.example.ts.net:3090"}' >"$STATE/config.json"
ok "serve 接受 [::1] 回环"

echo "serve：写入被拒 → 输出提权/operator 修复提示"
reset_fix
: >"$FIX/deny"
run --state "$STATE"
assert_fail "写入被拒应非零退出"
out_has "operator" || fail "应输出 operator 修复提示"
out_has "https=3090" || fail "应给出一次性提权命令"
ok "serve 写入被拒提示修复命令"

echo "serve：DSHBRIDGE_SERVE_PORT 覆盖 serve 端口"
reset_fix
DSHBRIDGE_SERVE_PORT=8443 run --state "$STATE"
assert_ok "覆盖端口的 serve 应成功"
called "serve --bg --https=8443 http://127.0.0.1:3090" || fail "应以覆盖端口写入、后端不变"
out_has "https://node.example.ts.net:8443" || fail "访问地址应使用覆盖端口"
unset DSHBRIDGE_SERVE_PORT
ok "serve 端口覆盖"

echo "serve：免密 sudo 可用 → 经 sudo 写入"
reset_fix
: >"$FIX/sudo-ok"
run --state "$STATE"
assert_ok "sudo 提权 serve 应成功"
called "sudo tailscale serve --bg" || fail "应以 sudo 前缀写入"
ok "serve 经 sudo 提权写入"

# ── status / reset 子命令与参数 ─────────────────────────────────────────────

echo "status：透传 tailscale serve status"
reset_fix
run --state "$STATE" status
assert_ok "status 应成功"
out_has "fake serve status" || fail "应透传 serve status 输出"
ok "status 透传"

echo "reset：配置属于本服务 → 移除"
reset_fix
set_serve_json "$OURS"
run --state "$STATE" reset
assert_ok "reset 应成功"
called "serve --https=3090 off" || fail "应执行 serve --https=3090 off"
ok "reset 移除本服务配置"

echo "reset：端口空闲 → 无需移除"
reset_fix
run --state "$STATE" reset
assert_ok "空闲端口 reset 应成功"
not_called "off" || fail "空闲端口不应执行 off"
out_has "没有 Serve 配置" || fail "应提示无需移除"
ok "reset 空闲端口幂等"

echo "reset：配置属于其他服务 → 拒绝移除"
reset_fix
set_serve_json '{"Web":{"node.example.ts.net:3090":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:9999"}}}}}'
run --state "$STATE" reset
assert_fail "他人配置 reset 应拒绝"
not_called "off" || fail "他人配置不得执行 off"
out_has "不属于本服务" || fail "应说明配置不属于本服务"
ok "reset 拒绝他人配置"

echo "未知子命令 → 拒绝"
reset_fix
run --state "$STATE" explode
assert_fail "未知子命令应拒绝"
ok "未知子命令拒绝"

echo
echo "test-tailscale-serve: ${PASS} 项全部通过"
