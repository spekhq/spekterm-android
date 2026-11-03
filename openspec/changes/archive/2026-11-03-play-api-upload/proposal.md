## Why

Every release uploads its bundle to Play Console through Claude in Chrome's `file_upload`, which takes at most 10 MB a
call. 0.5.0's bundle (versionCode 5, 2026-10-30) was 10,650,257 bytes and was refused, so kewang had to drag it in by
hand; bundles only grow, so every release from now on would need kewang at the desktop. kewang agreed (2026-10-30) to
publish through the Google Play Developer API (the Edits API) from the terminal instead, with no browser in the way.

## What Changes

- **`tools/play-publish.mjs`**, a Node script with no dependencies (Node 22's `fetch` and `node:crypto`), talking to the
  Edits API with a service account's JSON key kept outside the repository (`~/.android/spekterm-play-publisher.json` by
  default):
  - `check` — signs in, prints the internal and production releases with their review state (e.g. in review), opens
    an edit, reads the tracks and deletes the edit: proves the key and the app access without changing anything;
  - `internal` — uploads `app-release.aab`, makes it the internal testing release, full rollout, named
    `<versionName> (<versionCode>)`, with the en-US and zh-TW release notes from the committed files, and commits;
  - `production` — releases a version code already in internal testing to production with the same name and notes,
    and commits, which sends it for review; it stops first when production already has a release in review;
  - every commit asks Play to fail rather than cancel a review already in progress (the API's default cancels it);
    any failure deletes the edit, so nothing half-done is left; a commit whose answer never came is reported as
    unknown, to be checked with `check`; `--dry-run` validates and then deletes instead of committing;
    `--send-in-console` leaves the sending for review to one click in Play Console when Play refuses to do it itself.
- **Release notes as files in the repository**, in Gradle Play Publisher's layout like the listings:
  `play/release-notes/en-US/default.txt` and `play/release-notes/zh-TW/default.txt`, each at most 500 characters,
  overwritten and committed with each release, checked by the script before anything is sent and by `./gradlew check`.
  `internal` stops when they are the same as the notes internal testing holds now, unless told the notes stay.
- **`docs/play-release.md`** gains "Once: publishing through the API" (kewang's one-time Google Cloud and Play Console
  setup, 【桌機】) and "Every later release" uses the script; **`.claude/skills/play-release/SKILL.md`** step 3 uses the
  script for the upload, the internal release and the production release, with Claude in Chrome kept as the fallback.
  Production is still released only after kewang's OK on internal testing.
- `tools/play-publish.test.mjs` (`node --test tools/*.test.mjs`) covers the notes, the version, the requests and the
  failure paths against a fake Play.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `play-distribution`: a new requirement — publishing a release to internal testing and then production from the
  terminal through the Play Developer API, with the key outside the repository and without ever cancelling a review in
  progress.

## Impact

- New: `tools/play-publish.mjs`, `tools/play-publish.test.mjs`, `play/release-notes/{en-US,zh-TW}/default.txt`.
- Changed: `tools/README.md`, `docs/play-release.md`, `docs/release-findings.md`, `.claude/skills/play-release/SKILL.md`,
  `PlayListingTest` and the test inputs in `app/build.gradle.kts`, `.gitignore`.
- The app: nothing; its build only checks the notes files. No new Gradle plugin, no npm dependency, no `package.json`.
- kewang, once (【桌機】): a Google Cloud project with the Google Play Android Developer API enabled, a service account
  and its JSON key in `~/.android/`, and that account invited in Play Console (developer account kewang) to the app
  spekterm with the two release permissions. The key never enters the repository or a chat.
- Verified for real at the next release (a new versionCode is needed): until then the change stays open.
