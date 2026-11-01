# 遗留事项台账

2026-11-01 首次按当前源码与历史 QA 汇总。`open` 表示尚需工作，不等于已确认缺陷。现有历史计划中的未勾选框不作为未实现的唯一依据。

| 编号 | 状态 | 现象 / 影响 | 下一步 | 证据 |
| --- | --- | --- | --- | --- |
| OI-001 | open | SDK/Gradle/Go/ADB 已可用：Android 114 项单测、Debug 构建和前一轮 Go race/vet 已实跑通过；Pixel 现有 Debug 包与本机构建签名不同，安装返回 INSTALL_FAILED_UPDATE_INCOMPATIBLE，新版 UI 真机验收受阻；lint 未复验。 | 在匹配的构建/设备环境补真机验收与 lint；本轮不改签名或卸载现有应用。 | [Q1b 验证](2026-11-01-3.md)、[数据层验证](2026-11-01-2.md)、[构建配置](../../app/build.gradle.kts) |
| OI-002 | open | 计划审阅、多选、timed → continued 有源码/单测/手机注入验证，真实 Host 发出和调度尚未逐项端到端复测。 | 在隔离 QA 会话补真实 Host 三类交互与重连验收，保持任务与回答身份关联。 | [交互验收](../qa/interaction-parity/validation.md) |
| OI-003 | open | 源码为 1.1.2/code 8，公开 APK 仍为 v1.1.0；新包名迁移后的正式签名发行尚未交付。 | 选择发行目标后验证签名、Release 构建、应用身份与数据迁移边界，再准备发布产物。 | [构建配置](../../app/build.gradle.kts)、[PR #4](https://github.com/Venompool888/deepseek-harness-mobile/pull/4)、[公开发行](https://github.com/Venompool888/deepseek-harness-mobile/releases/tag/v1.1.0) |
| OI-004 | open | 公网部署、开机托管及非 macOS arm64 目标运行未验收；固定 Desktop Cookie 不自动续期，历史 LAN 运行状态不能代表当前可用。 | 选定部署目标后核对真实 Host、认证更新与服务托管，再做实际 HTTPS 外部链路验收。 | [bridge 说明](../../bridge/README.md)、[私有传输验收](../qa/private-transport/validation.md) |
| OI-005 | open | 会话归档数据层、bridge 精确白名单及长按归档/确认/选择回退已实现；Q1c 已增加已归档目录查询，Android 117 项通过。归档 UI 真机未验收，取消归档 UI、活动详情确认、排序、队列编辑/删除、附件上传/重试、workspace 改名/删除/排序和 preset 管理仍缺原生入口。 | 本轮第二提交接已归档折叠分区和取消归档；再在匹配构建/设备环境补归档/恢复真机验收，排序等另行安排。 | [Q1c 验证](2026-11-01-4.md)、[Q1b 验证](2026-11-01-3.md)、[更新后功能清单](../../harness-feature-parity.md) |
| OI-006 | open | 完整目标编辑、子代理树、jobs、schedules、workflow、交互终端、交付物、skills 和通用设置仍缺原生面板；私有模式仅能到服务端电脑本地 Web 操作。 | 按实际移动工作流分批选择功能域；保留私有桥接不代理原生 Web 的边界。 | [功能清单](../../harness-feature-parity.md)、[Web 入口限制](../../app/src/main/java/app/deepseek/harness/mobile/MainActivity.kt#L4974) |
| OI-007 | open | 完整 TalkBack、少数小于 48dp 的触控区、更广设备验收、本地化和长历史性能仍有遗留。 | 分别补无障碍/设备验收和长会话性能测量，再据实修复。 | [核心验收限制](../qa/dsh-rc2-core/validation.md)、[视觉验收边界](../qa/web-visual-fidelity/validation.md) |
| OI-008 | open | 未确认发送的去重 ID 仅存内存，进程销毁后的持久恢复尚未实现或验证。 | 明确进程死亡后的发送恢复需求，再设计持久化与服务端确认机制，避免重复接纳。 | [发送身份实现](../../app/src/main/java/app/deepseek/harness/mobile/HarnessPromptSubmission.kt)、[合并前验证边界](../qa/merge-readiness/2026-10-12.md) |

## 已解决

本轮仅盘点并纠正文档，未将产品遗留项标为已解决。
