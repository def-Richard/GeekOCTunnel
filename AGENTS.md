# Workspace Agent Rules

## Android build execution

- Gradle and `scripts\Publish-Apk.ps1` commands must use a timeout of at least 180000 ms. Never
  run them with a short-yield timeout; a forced timeout can interrupt the transactional publish flow.
- After an interrupted publish, inspect `version.properties`, `releases\apk`, the publish lock, and
  running Java processes before deciding whether it is safe to run again.
