# Pocket routing, notifications and compact chat

- [x] Fetch origin; preserve dirty existing checkout; create clean worktree from latest main.
- [x] Reproduce explicit Codex status request routed to a helper; add deterministic routing and peer guard. Local runtime29 active;158 runtime checks passed.
- [x] Share durable notification dedup across foreground/background; suppress old/read replies and replace per-chat notifications. JVM regressions passed including stale snapshots, changed versions, restart and other chats.
- [x] Compact chat header and collapse optional model/project/task controls. Android compilation passed; no ADB device connected.
- [x] Run server/JVM checks and signed public APK build with existing signer.52 server tests; all JVM harnesses and Android compilation passed. Public APK matches published0.1.13 signer; no personal preset.
- [ ] Publish under repository release policy; verify remote assets. Physical phone behavior needs device verification.
