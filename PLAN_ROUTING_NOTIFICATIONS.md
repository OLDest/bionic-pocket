# Pocket routing, notifications and compact chat

- [x] Fetch origin; preserve dirty existing checkout; create clean worktree from latest main.
- [x] Reproduce explicit Codex status request routed to a helper; add deterministic routing and peer guard. Local runtime29 active;158 runtime checks passed.
- [x] Share durable notification dedup across foreground/background; suppress old/read replies and replace per-chat notifications. JVM regressions passed including stale snapshots, changed versions, restart and other chats.
- [x] Compact chat header and collapse optional model/project/task controls. Android compilation passed; no ADB device connected.
- [x] Run server/JVM checks and signed public APK build with existing signer.52 server tests; all JVM harnesses and Android compilation passed. Public APK matches published0.1.13 signer; no personal preset.
- [x] Published source/main and releasev0.1.14 at e9872368260531990e58633a868ff5f6d0acdbed under repository policy. Actual latest APK/update.json/SHA256SUMS downloaded and verified; APK SHA25676038ed0fd3c5852064a432bca0442c6f9b9884fb24e98d7e68f90369fe30cdc,57814bytes. Physical phone behavior remains unverified: no ADB device.
- [x] Sent API-only/routing correction for Domovoy Codex as peerc8bab98f-01d1-4cc9-a5a1-d4dd3830befa. Queue acceptance only; remote receipt/install not claimed. Existing remote coordinator owns that follow-up.
