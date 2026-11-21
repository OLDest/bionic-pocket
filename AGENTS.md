# Bionic Pocket release policy

The owner authorizes automatic publication after a successful signed build and relevant tests. Fetch and integrate the current upstream before preparing a release. Commit on the numbered task branch, push the reviewed source, publish the public APK with update.json and SHA256SUMS.txt, and verify the published assets. No additional approval is required for this Bionic Pocket release workflow.

Never publish a personal APK, embedded access tokens, signing keys or passwords. Preserve the existing signing identity and user data.
