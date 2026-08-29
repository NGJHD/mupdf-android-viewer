# CLAUDE.md

Notes for Claude Code working in this repository.

## Releases

**Always update the GitHub release whenever you push to git.** Pushing commits
without refreshing the release leaves the published APK out of sync with the
source, which is the whole reason the release exists.

The routine after committing and pushing:

1. Build the release APK:
   `./gradlew :app:assembleRelease`
2. Bump `versionName` / `versionCode` in `app/build.gradle` if the change is
   user visible (the tag follows `versionName`, e.g. `v1.28.3a`).
3. Tag the commit and push the tag.
4. Create or update the release, attaching **only**
   `app/build/outputs/apk/release/app-arm64-v8a-release.apk`:
   `gh release create <tag> app/build/outputs/apk/release/app-arm64-v8a-release.apk --title <tag> --notes "..."`
   or, for an existing tag, `gh release upload <tag> <apk> --clobber`.

## APK variants

The build only produces `app-arm64-v8a-release.apk`. `arm64-v8a` is the ABI of
essentially every Android phone still in use, so the other ABI splits and the
universal APK were removed from `splits.abi` in `app/build.gradle`. Do not add
them back or attach them to a release unless someone actually asks for a 32 bit
or emulator build.
