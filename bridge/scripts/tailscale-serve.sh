#!/usr/bin/env bash
# tailscale-serve — 把只监听 loopback 的 dshbridge 经 Tailscale Serve 以 https 暴露给 tailnet。
#
# dshbridge 本身始终只监听 loopback（serve 形态以 dsh-bridge serve --behind-tls-proxy 运行），
# Serve 负责 TLS 与 tailnet 准入。本脚本只配置本服务端口的 Serve，不修改 tailnet ACL，
# 也不碰节点上其他端口的 Serve 配置。
#
# 安全铁律：只允许 serve --https（它会写 X-Forwarded-For）。serve --tcp、
# --tls-terminated-tcp、Funnel、ssh -L 等不写该头的转发一旦占用目标端口，本脚本拒绝继续
# ——不写 X-Forwarded-For 的转发会让远端请求的对端呈现为 127.0.0.1，被当作本机请求。
#
# 用法: tailscale-serve.sh [--state DIR] [serve|status|reset]
#   serve   （默认）配置 https://<ts 域名>:<端口> 反向代理到本机 dshbridge
#   status  显示本节点当前的 Serve 配置
#   reset   只移除指向本服务的 Serve 配置；端口属于其他服务时拒绝
#
# --state DIR 指向 dshbridge 私有状态目录（读 config.json 的 listen 得后端地址），
# 也可用 DSHBRIDGE_STATE 环境变量指定；DSHBRIDGE_SERVE_PORT 覆盖 tailnet 侧 https 端口，
# 默认与后端端口相同。需要 python3 与已登录的 tailnet 节点；Windows host 在 Git Bash 或
# WSL 下运行。确定性测试见 test-tailscale-serve.sh（假命令，不触碰真实 tailnet）。
set -euo pipefail

die() { printf 'tailscale-serve: %s\n' "$*" >&2; exit 1; }
info() { printf '==> %s\n' "$*"; }
success() { printf 'ok - %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }

usage() {
  printf '用法: %s [--state DIR] [serve|status|reset]\n' "$(basename "$0")" >&2
}

STATE="${DSHBRIDGE_STATE:-}"
SUBCOMMAND="serve"
while [ $# -gt 0 ]; do
  case "$1" in
    --state)
      [ $# -ge 2 ] || die "--state 需要一个目录参数"
      STATE="$2"
      shift 2
      ;;
    serve | status | reset)
      SUBCOMMAND="$1"
      shift
      ;;
    -h | --help | help)
      usage
      exit 0
      ;;
    *)
      die "未知参数: $1（可用：--state DIR / serve / status / reset）"
      ;;
  esac
done

[ -n "$STATE" ] || die "缺少 --state（dshbridge 私有状态目录）或 DSHBRIDGE_STATE 环境变量"
CONFIG="$STATE/config.json"
[ -f "$CONFIG" ] || die "未找到 $CONFIG；请先用 dsh-bridge init --state '$STATE' 初始化"

# 后端地址来自 config.json 的 listen；serve 形态只支持回环明文监听。
# 回环集合与 Go 侧 PlainListenerAllowed 对齐：回环 IP 字面量（127.0.0.0/8 与 ::1），
# 不接受 localhost（Go 侧同样拒绝，进程起不来）。
LISTEN="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["listen"])' "$CONFIG")"
BACKEND_HOST="${LISTEN%:*}"
BACKEND_PORT="${LISTEN##*:}"
case "$BACKEND_HOST" in
  "[::1]") ;;
  ::1) BACKEND_HOST="[::1]" ;;
  127.*) ;;
  *)
    die "config.json 的 listen（${LISTEN}）不是回环 IP（Go 侧 PlainListenerAllowed 只认回环 IP 字面量，不含 localhost）；serve 形态要求 dshbridge 仅监听回环，公网监听不经本脚本暴露"
    ;;
esac
[[ "$BACKEND_PORT" =~ ^[0-9]+$ ]] && ((BACKEND_PORT >= 1 && BACKEND_PORT <= 65535)) \
  || die "后端端口无效：${BACKEND_PORT}（应为 1-65535）"

BACKEND_URL="http://${BACKEND_HOST}:${BACKEND_PORT}"
SERVE_PORT="${DSHBRIDGE_SERVE_PORT:-$BACKEND_PORT}"
[[ "$SERVE_PORT" =~ ^[0-9]+$ ]] && ((SERVE_PORT >= 1 && SERVE_PORT <= 65535)) \
  || die "Serve 端口无效：${SERVE_PORT}（应为 1-65535）"

# 写入 Serve 配置时的提权前缀，空数组表示直接执行。
SERVE_RUNNER=()

require_tailscale() {
  command -v tailscale >/dev/null 2>&1 \
    || die "未找到 tailscale 命令。请先安装：https://tailscale.com/download"
  tailscale status >/dev/null 2>&1 \
    || die "tailscale 未登录或 tailscaled 未运行。请先执行：tailscale up"
}

require_python() {
  command -v python3 >/dev/null 2>&1 \
    || die "未找到 python3，无法解析 config.json 与 tailscale serve status --json。"
}

# dshbridge 对已知与未知未认证端点一律空 404，没有健康发现页；因此任何 HTTP 状态码
# （包括 404）都证明监听进程存在并能应答，连接被拒才说明后端未启动。
require_backend_healthy() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BACKEND_URL/" || true)"
  case "$code" in
    [1-5][0-9][0-9]) return 0 ;;
    *) die "本机 dshbridge 在 ${BACKEND_URL} 上不可达（curl 状态码：${code:-无}）。请先启动：dsh-bridge serve --state '$STATE' --behind-tls-proxy --public-origin https://<节点域名>:${SERVE_PORT} --dsh-token-file <私有文件>" ;;
  esac
}

# port_state 读取 serve status JSON（stdin），输出 free、ours 或 taken:<说明>；
# 输出为空或无法解析时以非零退出，调用方据此拒绝写入。
#
# 从未配置过 Serve 的节点输出 null，按空配置处理。前台会话（tailscale serve 不带
# --bg，记录在 Foreground 下）占用同一端口时也算占用：tailscale CLI 自己不检查它。
port_state() {
  python3 -c '
import json, sys
port, backend = sys.argv[1], sys.argv[2]
raw = sys.stdin.read().strip()
if not raw:
    sys.exit("serve status 输出为空")
data = json.loads(raw)
if data is None:
    data = {}
if not isinstance(data, dict):
    sys.exit("serve status 不是 JSON 对象")

def on_port(mapping):
    return {k: v for k, v in (mapping or {}).items() if k.rsplit(":", 1)[-1] == port}

for session, conf in (data.get("Foreground") or {}).items():
    conf = conf or {}
    if port in (conf.get("TCP") or {}) or on_port(conf.get("Web")):
        print("taken:前台 serve 会话 " + session + " 正在使用该端口")
        sys.exit(0)

tcp = (data.get("TCP") or {}).get(port)
web = on_port(data.get("Web"))
funnel = [k for k, v in on_port(data.get("AllowFunnel")).items() if v]
if funnel:
    print("taken:该端口开启了 Funnel（" + ", ".join(funnel) + "），本项目禁止 Funnel")
elif tcp is None and not web:
    print("free")
elif tcp and tcp.get("TCPForward"):
    print("taken:TCP 转发到 " + tcp["TCPForward"] + "（不写 X-Forwarded-For，被当作本机请求，禁止）")
else:
    handlers = {}
    for host in web.values():
        handlers.update((host or {}).get("Handlers") or {})
    proxies = sorted("%s -> %s" % (p, h.get("Proxy") or h.get("Path") or h.get("Text") or "?") for p, h in handlers.items())
    https = bool(tcp and tcp.get("HTTPS"))
    if https and list(handlers) == ["/"] and handlers["/"].get("Proxy", "").rstrip("/") == backend:
        print("ours")
    elif not https and tcp:
        print("taken:该端口是明文 HTTP Serve" + ("（" + "; ".join(proxies) + "）" if proxies else ""))
    else:
        print("taken:" + ("; ".join(proxies) or "未知的 Serve 配置"))
' "$SERVE_PORT" "$BACKEND_URL"
}

# current_port_state 读取当前 Serve 配置并输出本服务端口的状态；读取或解析失败时 die。
current_port_state() {
  local status state
  status="$(tailscale serve status --json 2>/dev/null)" \
    || die "读取 tailscale serve status --json 失败，无法确认端口 ${SERVE_PORT} 的归属，拒绝修改。"
  state="$(printf '%s' "$status" | port_state)" \
    || die "无法解析 tailscale serve status --json，拒绝修改以免影响其他服务。"
  printf '%s' "$state"
}

# 写入 Serve 需要 root（POSIX）或管理员/operator（Windows）；能否写入只有真的写才知道，
# 被拒时给出修复命令。
select_serve_runner() {
  SERVE_RUNNER=()
  if command -v id >/dev/null 2>&1 && [ "$(id -u)" -eq 0 ]; then
    return
  fi
  if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    info "检测到免密 sudo，将以 sudo 写入 Serve 配置。"
    SERVE_RUNNER=(sudo)
  fi
}

serve_denied_hint() {
  die "配置 Serve 失败。写入 Serve 配置需要 root / 管理员或已设置 operator，可任选其一：
  一次性提权：  sudo tailscale serve --bg --https=${SERVE_PORT} ${BACKEND_URL}
  永久免 sudo： sudo tailscale set --operator=\$USER
  Windows：     在管理员终端运行本脚本，或 tailscale set --operator=<用户>"
}

# node_dns_name 输出本节点的 tailnet 域名（证书签给它，不能用 100.x 地址访问）。
node_dns_name() {
  local fqdn
  fqdn="$(tailscale status --peers=false --json 2>/dev/null | python3 -c '
import json, sys
try:
    print(((json.load(sys.stdin) or {}).get("Self") or {}).get("DNSName", "").rstrip("."))
except ValueError:
    pass
')"
  [ -n "$fqdn" ] || die "无法从 tailscale status 取得本节点的 tailnet 域名，请确认已启用 MagicDNS。"
  printf '%s' "$fqdn"
}

cmd_serve() {
  require_tailscale
  require_python
  require_backend_healthy

  # 先取域名再写入：写入成功后才失败会让用户误以为配置未生效。
  local dns
  dns="$(node_dns_name)"

  local state
  state="$(current_port_state)"

  case "$state" in
    ours)
      info "端口 ${SERVE_PORT} 已反向代理到 ${BACKEND_URL}，无需修改。"
      ;;
    free)
      select_serve_runner
      info "配置 https://<node>:${SERVE_PORT} -> ${BACKEND_URL} ..."
      "${SERVE_RUNNER[@]}" tailscale serve --bg --https="${SERVE_PORT}" "${BACKEND_URL}" \
        || serve_denied_hint
      ;;
    taken:*)
      die "端口 ${SERVE_PORT} 已被其他 Serve 配置占用：${state#taken:}
不会覆盖它。请用 DSHBRIDGE_SERVE_PORT 指定其他端口，或先确认后手工移除占用者。"
      ;;
    *)
      die "无法识别的端口状态：${state}"
      ;;
  esac

  success "tailnet 访问地址: https://${dns}:${SERVE_PORT}"
  warn "首次配置时证书签发约需 1-3 分钟，期间访问可能超时。谁能连接由 tailnet ACL 决定，本脚本不修改 ACL。"
}

cmd_status() {
  require_tailscale
  tailscale serve status
}

# reset 只移除确实指向本服务的配置：tailscale serve --https=<端口> off 会删除该端口
# 上的全部挂载，端口若属于其他服务（例如曾用 DSHBRIDGE_SERVE_PORT 改用别的端口）就会误删。
cmd_reset() {
  require_tailscale
  require_python

  local state
  state="$(current_port_state)"
  case "$state" in
    free)
      success "端口 ${SERVE_PORT} 没有 Serve 配置，无需移除。"
      ;;
    ours)
      select_serve_runner
      "${SERVE_RUNNER[@]}" tailscale serve --https="${SERVE_PORT}" off \
        || die "移除端口 ${SERVE_PORT} 的 Serve 配置失败。需要 root / 管理员或 operator：sudo tailscale serve --https=${SERVE_PORT} off"
      success "已移除端口 ${SERVE_PORT} 的 Serve 配置，其他端口未受影响。"
      ;;
    taken:*)
      die "端口 ${SERVE_PORT} 的 Serve 配置不属于本服务：${state#taken:}
不做任何修改。若当初用 DSHBRIDGE_SERVE_PORT 指定过其他端口，请带上同一个值重新执行 reset。"
      ;;
    *)
      die "无法识别的端口状态：${state}"
      ;;
  esac
}

case "$SUBCOMMAND" in
  serve) cmd_serve ;;
  status) cmd_status ;;
  reset) cmd_reset ;;
  *) die "未知子命令: ${SUBCOMMAND}（可用：serve / status / reset）" ;;
esac
