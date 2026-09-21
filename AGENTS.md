# Workspace Agent Rules

## Tool discovery

- Inspect known SDK paths and `.android/avd/*/hardware-qemu.ini` before searching for tools.
  Do not recursively search the whole AppData or Documents tree: protected caches cause noisy
  access-denied failures. If a recorded SDK was removed, provision an isolated build toolchain.

## Live VPN verification

- Log in through the app and save credentials using its encrypted store. The instrumentation
  target file contains only names, server addresses, and probe URIs, never usernames/passwords.
- Use temporary `verification-` profile IDs for device tests, restore the previous quick-connect
  selection, and retain raw instrumentation output alongside the validation report.
- Wait for a fresh UI hierarchy after opening or closing a popup before clicking another control;
  back-to-back taps can hit the still-dismissing popup instead of the intended control.

## Android build execution

- Gradle and `scripts\Publish-Apk.ps1` commands must use a timeout of at least 180000 ms. Never
  run them with a short-yield timeout; a forced timeout can interrupt the transactional publish flow.
- After an interrupted publish, inspect `version.properties`, `releases\apk`, the publish lock, and
  running Java processes before deciding whether it is safe to run again.
