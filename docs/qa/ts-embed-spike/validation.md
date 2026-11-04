# ts-embed-spike 验证记录（tailscale-private-transport）

验证目标（planned change `ts-embed-spike`）：设备以 tagged auth key 起进程内 tsnet 节点 → Up →
经 host 的 tailscale serve --https 入口访问 dshbridge → 完成一次配对与一条白名单 RPC。
**结论：spike 通过**（2026-11-03，用户指示本轮以模拟器执行，真机补验待设备可用）。

## 环境

| 组件 | 取值 |
| --- | --- |
| host | Windows，tailnet 节点 gamepc-1.tail89569c.ts.net，tailscale 1.102.3 |
| dshbridge | 本仓库现源码（含 0.2.0-rc.2 版本门禁），`serve --behind-tls-proxy` 监听 127.0.0.1:3091 |
| serve 暴露 | `bridge/scripts/tailscale-serve.sh ... serve` → https://gamepc-1.tail89569c.ts.net:3091 |
| 上游 | 宿主假上游 `runs/tailnet-serve-qa/fake_upstream.py`（__DSH_BOOT__ version=0.2.0-rc.2 + /api/session/list ok:true）——本机真实 dsh（Desktop 与 WSL `dsh-url` 的 https://gamepc.tail89569c.ts.net:13080）均为 0.2.1-rc.2，被版本门禁按设计拒绝（这本身就是 dsh-version-lock 的真机负向证据）；上游真实性不是本 spike 的未知数，与 bridge 单测同策略 |
| AAR | `bash tstunnel/build-aar.sh 28.2.13676358`：62.8 MB（剥离后），tailscale v1.94.1 + x/mobile 钉版（go.mod tool 指令） |
| 设备 | 模拟器 api31 x86_64（用户指示「使用模拟器来调试」；planned change 原文要求真机非模拟器，本轮按用户指示以模拟器执行，真机补验列为后续） |
| auth key | 用户提供（tagged）。注：该 key 实际挂 **tag:pca-client**（tailscale whois 证实），非计划中的 tag:dsh-mobile——tagged 入网本身成立；里程碑专用 tag 分离（可单独吊销）留待 ts-token-profile / ts-tunnel-integration |

## 运行记录

```
adb shell am instrument -w -e authkey tskey-… -e origin https://gamepc-1.tail89569c.ts.net:3091 \
  -e pairPath /rrFl-FcFltk/VWIIM8N0FnY -e pairingCode CJ9N… -e hostname dsh-spike-emu \
  app.deepseek.harness.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner

app.deepseek.harness.mobile.TstunnelSpikeTest:.
Time: 5.865
OK (1 test)
```

仪器测试内部断言（全部通过）：

1. `StatusJSON().BackendState == "Running"`，Self.TailscaleIPs 非空（tagged 入网完成）；
2. pair 经 SOCKS5 回环 → tailnet → serve → dshbridge 返回 200，protocolVersion=1、
   deviceToken ≥32 字符、routes.rpc 就绪；
3. session/list（Bearer 设备凭据）返回 200 且 `result.ok == true`（白名单 RPC 经全链路转发）。

host 侧交叉印证：`tailscale status` 出现 `dsh-spike-emu 100.95.176.58 tagged-devices android
active; relay "tok", tx 11236 rx 3460`——节点真实在网、流量经 DERP 中继流动；
`tailscale whois` 确认 Tags: [tag:pca-client]。

途中排除的两个真实问题（已修复并保留在代码里）：

- Git Bash MSYS 会把以 `/` 开头的 pairPath 参数改写成 Windows 路径——adb 命令需整体加引号 +
  `MSYS_NO_PATHCONV=1`（运行手册已按此写）；
- instrumentation 上下文不能从 useLegacyPackaging=false 的 APK 直载 libgojni.so（api31 实测
  dlopen "not found"）——app gradle 已设 `jniLibs.useLegacyPackaging = true`（只影响含 AAR 的
  androidTest APK）。

## 清理

- 三台 spike/探测设备（tailnet-serve-qa、version-probe、sdk_gphone64_x86_64-spike）已在 bridge
  `revoke`；serve 3091 配置已 reset（节点既有的 443/19387/8765 配置未动）；
- bridge、假上游、模拟器进程全部停止，3080/3091 端口已释放；
- dsh-spike-emu 节点仍在 tailnet（tagged、可随时在管理台移除；若 key 为 ephemeral 则自动过期）。

## 对里程碑的意义

spike-first 门槛通过：内嵌 tsnet 经 serve 访问 dshbridge 的连通与身份成立，ts-token-profile 与
ts-tunnel-integration 可以继续。遗留事项（如实列出）：

- 真机（非模拟器）复验 + tag:dsh-mobile 专用 tag：本轮按用户指示以模拟器执行、key 实挂
  tag:pca-client（见上），真机补验列为后续；
- **直连（非 DERP）路径未验证**：本轮流量经 DERP 中继（relay "tok"）完成，节点间 WireGuard
  直连建立后的表现留待集成变更覆盖；
- 仪器原始输出存于本地 `runs/tailnet-serve-qa/spike-instrument.log`（gitignore，不入库）。
