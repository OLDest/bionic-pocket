Scope: Android message notifications and sent/received dates, then publish signed release.
- [x] Inspect current app and release pipeline; isolate clean baseline.
- [x] Implement timestamps, notification permission/channel/deep link, background sync.
- [x] Merge PC-120 assistant/archive/attachment changes; preserve both host routes.
- [x] Verify delivery, uploads, endpoint policy and notification deduplication on JVM.
- [x] Build/sign APK with existing release key; rebuild final lifecycle fixes before upload.
- [ ] Commit, push, publish release, verify public assets.
Keep phone requests and Codex replies deduplicated. No model calls for checks.
No Android device connected: runtime permission, notification shade and device sleep remain untested on hardware.
