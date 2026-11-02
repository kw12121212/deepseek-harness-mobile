# Harness Android feature parity

Status reviewed against repository source and checked-in QA records on 2026-11-01.
The current source is `1.1.2` / versionCode 8, targeting dsh `0.2.0-rc.2`.
The public GitHub release remains `v1.1.0`. Device and test results below are historical evidence, not a fresh run on this checkout.
Open work is tracked in [OPEN-ISSUES.md](docs/devlog/OPEN-ISSUES.md).

## Native and wired

- Session list, selection, creation, rename, fork, archive/unarchive, history, cancel, and periodic running-state refresh. Long-press a drawer session for rename/fork/archive; archived sessions sit in a collapsed 「已归档」 drawer section where long-press unarchives, and the archive baseline is loaded before the first session list render.
- Remembered default workspace plus a manual workspace chooser for every new session.
- Model selection and adapter-advertised Thinking effort through `session/selectModel`.
- Queue and steer prompt delivery through the official `session/prompt` mode field.
- Default and Plan collaboration modes through the official `/plan` command and `plan` projection.
- Permission presets through the official `/permission` command and `permissions` projection; options are host-provided rather than hard-coded.
- Agent preset roster and blank-session selection through `agentPresets/list` and `agentPresets/select`.
- Local session search, workspace/default-directory management, and the ChatGPT-style drawer information architecture.
- Authenticated rc.2 Remote mux updates (`/api/remote.mux` for direct connections, the configured stream route for private bridge connections), with history polling retained as a recovery fallback.
- Character-by-character assistant reveal for both partial and single-shot completed responses, including a semi-transparent newest-character fade-in.
- DeepSeek first-run credential onboarding derived from `llm/listProviders`, `llm/listConfigurableProviders`, `settings/describe` and `credentials/describe`. Writable credentials can be saved in place; remote/read-only configuration gets an explicit host-Web recovery message instead of a dead control.
- Model-catalog partial failures from `session/modelCatalog`'s `failures`, shown in the model picker with their provider error and a retry action.
- Native approval request card and allow/deny response.
- Native single/multiple-choice and free-text questions, multi-question submission, plan review, timed-to-continued question handling, cancellation, and independent drafts.
- Cross-session pending-interaction attention and child-agent approval/question routing with a read-only child session page. This does not include a complete subagent tree.
- Older-history pagination through `session/page` and the native load-earlier control.
- Remote directory browsing, directory creation, and adopting a directory as a workspace, subject to the Host's directory-picker capability.
- Native compact/export/feedback/goal/permission/plan command entry points and session ZIP export. The goal command inserts text; it is not a complete goal editor.
- Final-assistant action strip matching Web: clipboard copy with confirmation, positive/negative message feedback with compare-and-set reconciliation, branch-at-message, and turn runtime/TTFT/token-rate metrics. Narrow screens keep the official single-line order and allow horizontal scrolling.

## Web source audit: easy-to-miss states

- Implemented: official DeepSeek credential missing, read-only/unavailable credential configuration, provider catalog partial failure, whole refresh failure, prompt submission restore/error, terminal turn error, retry activity, and max-token activity.
- Intentionally different: Web silently skips onboarding when its configuration adapter is absent or read-only. Android keeps normal startup quiet too, but an explicit send/model action or a real `MISSING_CREDENTIAL` turn shows a recovery dialog because a disabled mobile control otherwise looks broken.
- Still absent: the internal-testing welcome/onboarding notice, attachments and upload failure/retry, and the full settings-schema editor.

## Still needs a native surface

- Queued prompt strip above the composer: pending items with target label, preview, edit / steer / remove through `session/updateQueue`, hidden when empty (fdf59ee, effad4b). Session and workspace reorder RPCs are wired and tested, but their menu entries stay hidden until the long-press drag interaction lands.
- Workspace reorder (`workspace/rename` and `workspace/delete` are wired through long-press on a drawer workspace header; delete only removes the registration, not sessions or files).
- Agent preset read/copy/open/delete authoring screens.
- Image/text attachments and upload failure/retry.
- Complete goal editing, subagent tree, background jobs, schedules, workflow runs, interactive terminal, deliverables/downloads, skills management, and settings schema editor. The command menu now reads the Host command catalog (`commands/list`, 8b4b3cd) with a static fallback; it is not a full plugin catalog browser.

Direct connections retain a Web Settings entry. Private dshbridge connections do not proxy native Web: the Android entry tells the user to open Web locally on the server computer. Missing native domains therefore have no mobile Web fallback through the private bridge. This is an intentional transport boundary, not a reason to enable arbitrary Web proxying.

## Delivery and validation still to complete

- Publish a signed release of the newer source if that is the selected delivery goal; `1.1.2-debug` evidence does not establish a signed release or migration from the old application ID.
- Re-establish this checkout's Android SDK/ADB/Gradle and Go environment before claiming fresh test or build results. JDK 21 is available; Go and ADB were absent from PATH, common Android SDK locations were absent, and Gradle 9.4.1 was not cached during this review.
- Validate real Host plan review, multiple-choice and timed-to-continued scheduling end to end. Existing QA includes real ordinary questions and rejected approvals plus synthetic device coverage of the other types.
- Complete actual public HTTPS deployment, process supervision and target-platform runtime validation if remote deployment is selected. Historical LAN services are not proof of current deployment availability.
- Complete TalkBack traversal, remaining small touch targets, broader device coverage, remaining localization and long-history performance work. Some theme, keyboard and 150% font checks already passed historically.
- Unconfirmed prompt IDs survive interleaved sessions in memory, but process-death persistence is not implemented or claimed.

Evidence: [pre-merge verification](docs/qa/merge-readiness/2026-10-12.md), [interaction validation](docs/qa/interaction-parity/validation.md), [visual validation](docs/qa/web-visual-fidelity/validation.md), and [bridge operation limits](bridge/README.md).
