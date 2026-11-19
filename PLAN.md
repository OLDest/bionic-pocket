# Bionic Pocket

## Runtime6 two-slot activation

- [x] Two shared job slots, FIFO single-model inference and prompt queue cancellation.
- [x] Simultaneous admissions accept both independent requests; retries remain idempotent.
- [x] Phone YaGo tasks map to a stable separate yago-phone worktree; review leases remain.
- [x] Scheduling/isolation/auth/shutdown tests pass; runtime/source mobile modules match.
- [x] Activate runtime6 while idle and observe local+external requests accepted202/202 with runningJobs2.
- [x] Both local/external live chat smoke responses persisted:393/475 characters, completed200, after runningJobs0.
- [x] Current full suite45/45 passed; external auth remains401/200/403 as expected; no APK update needed.
- [x] Synchronize source to GitHub (runtime6 mobile/scheduling implementation and tests).

## Personal provisioning

- [x] Add optional first-launch personal settings import into encrypted preferences.
- [x] Separate public and personal build artifacts; refuse personal APK publication.
- [x] Build/verify v0.1.1 public and personal APKs using the existing signing key.
- [x] Verify personal APK settings and absence of token/personal asset in the public APK.
- [x] Publish token-free source/release and deliver personal APK through a private random HTTPS link valid24h.
- [ ] On-device install: no authorized ADB connection; user can install the private APK in the phone browser. TV disconnected without installation.

- [x] Confirm Android SDK 35/build tools and JDK are available locally.
- [x] Build authenticated asynchronous mobile chat API, with durable responses and bounded context.
- [x] Build Android chat UI: history, projects, connection settings, reconnect and copy.
- [x] Store token encrypted with Android Keystore; keep it out of Git and logs.
- [x] Add GitHub release update checks, hash/signature verification and Android installer handoff.
- [x] Test API routing, validation, failures and persistence; Android UI compiles, no connected device for interaction tests.
- [x] Build/sign APK and create GitHub repository; signature verified with SDK tools.
- [x] Deploy compatible bridge changes while idle; verify live HTTPS chat returns ГОТОВО.
- [x] Publish v0.1.0 release; verify downloaded APK and update manifest match signed local artifacts.
- [x] Deliver APK, repository and concise setup instructions.

Validation: 34 runtime/mobile tests passed. Live HTTPS mobile chat returned
ГОТОВО. Missing/wrong token401, valid token200, Origin403. APK signature v2/v3
verified; no Android device connected for on-device interaction testing.
YaGo's failed prior lease was reviewed (no application changes) and released;
future file tasks remain scoped and require separate integration review.
