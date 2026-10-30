## 1. The script

- [x] 1.1 `tools/play-publish.mjs` per D1–D5: a module exporting its pure parts and the three commands with an injectable
  `fetch`, run as `node tools/play-publish.mjs <check|internal|production> …` when executed directly; the
  key from `--key`, `SPEKTERM_PLAY_KEY_FILE` or `~/.android/spekterm-play-publisher.json` (missing → stop before any
  request, naming the path and the docs section; group- or world-readable → warning), `client_email` printed; the RS256
  JWT and the token request; the version from `app/build.gradle.kts`; the notes from
  `play/release-notes/<locale>/default.txt`, compared before the upload with the internal release's (`--same-notes`);
  `applications.tracks.releases.list` in `check` (internal, production), `internal` (warning) and `production` (stop on
  a release in review); `check`, `internal`, `production`, `--bundle`, `--version-code`, `--dry-run`,
  `--send-in-console`; the upload with `uploadType=media` and `application/octet-stream`, 5-minute timeout; every commit
  with `changesInReviewBehavior=ERROR_IF_IN_REVIEW` and, only with `--send-in-console`, `changesNotSentForReview=true`;
  the edit deleted on every failure and on SIGINT before the commit, the delete's HTTP status printed; a commit without
  an answer, or interrupted after it was sent, reported as unknown; version codes compared as strings; Play's and the token endpoint's errors printed without request headers, key, assertion or token.
  `.gitignore` gains `*play-publisher*.json`. Verify: 1.2
- [x] 1.2 `tools/play-publish.test.mjs` per D7, against a fake Play: the Gradle version (both lines; a missing or
  computed one is an error); the notes (missing, blank, 501 characters, 500 CJK characters accepted, trailing newlines
  dropped; equal to the internal release's notes stops before the upload, `--same-notes` goes on); the JWT verified with the test key's public key, its
  claims; the key (missing file stops before any request; mode 0644 warns; a token answer of 400 `invalid_grant` prints
  `invalid_grant` and its description); each scenario of the spec delta — the order of requests, the upload's URL and
  headers, the internal release body (`0.6.0 (6)`, `["6"]`, `completed`, both notes), the commit's query
  (`ERROR_IF_IN_REVIEW`; no `changesNotSentForReview` by default; `=true` with `--send-in-console`, for both commands),
  a mismatched upload version code, a 400 on `tracks.update`, `CHANGES_ALREADY_IN_REVIEW` (one commit only), a commit
  ending in a network error and one interrupted after it was sent (both reported unknown, no delete), production in review (no edit opened; `releases.list` version codes as numbers), production copying name and
  notes, a version code not in internal testing, `--dry-run`, `check` — each failing path after an edit was opened
  ending in exactly one `edits.delete` and no commit; the captured output of every run holding no part of the fake
  private key, assertion or token. Verify: `node --test tools/*.test.mjs` green; with `ERROR_IF_IN_REVIEW` removed from
  the commit, the tests fail; put back, green

## 2. Docs and the run sheet

- [x] 2.1 `docs/play-release.md`: the section "Once: publishing through the API" with D8's five 【桌機】 steps, the
  403-wait note and the leaked-key note; "Every later release" step 2 writes the notes files before building, step 4
  uses `internal` and, after the maintainer's OK, `production`, with Play Console as the fallback. Verify: it reads so;
  every command in it matches the script's options
- [x] 2.2 `.claude/skills/play-release/SKILL.md` per D6: the notes files written before `./gradlew check bundleRelease`
  and included in the release commit; `node --test tools/*.test.mjs` when the script changed since the last tag; step 3
  runs `check`, `internal`, the phone check, **kewang's OK**, `production --version-code <n>`, `check`; what to do on
  `CHANGES_ALREADY_IN_REVIEW`, on Play's refusal to send for review (`--send-in-console` and Publishing overview), on a
  403 or a missing permission, on a commit with an unknown outcome; a rebuild after a rejected review keeps the notes
  with `--same-notes`; the agent never reads the key file; the Claude in
  Chrome steps kept as a fallback subsection with the 10 MB limit. Verify: it reads so; step numbers cited elsewhere in
  the file still point at the right steps
- [x] 2.3 `tools/README.md`: a section for `play-publish.mjs` — what each command and option does, the key's place, the
  tests. Verify: it reads so

## 3. Against Play

- [x] 3.1 After kewang's one-time setup (docs/play-release.md, "Once: publishing through the API"):
  `node tools/play-publish.mjs check`. Verify: it prints the service account's e-mail and the internal and production
  releases as Play Console shows them (review state included), Play answers the edit's delete with a 2xx, and
  `edits.get` on the same edit id then answers a 4xx; its output,
  with nothing secret in it, recorded in `docs/release-findings.md`'s new section "Publishing through the API
  (`play-api-upload`)"
- [x] 3.2 Per D4: `play/release-notes/en-US/default.txt` and `play/release-notes/zh-TW/default.txt`, seeded with 0.5.0's
  notes as 3.1's `check` printed them from the internal track; then `PlayListingTest` checks both (present, not blank,
  ≤ 500 code points after dropping trailing newlines) and `app/build.gradle.kts` declares `play/release-notes` as a test
  input like `play/listings`. Verify: `./gradlew check` green; with a 501-character zh-TW file, `testDebugUnitTest`
  reruns (not `UP-TO-DATE`) and fails naming `zh-TW/default.txt`; restored, green
- [x] 3.3 Regression: `./gradlew check` and `node --test tools/*.test.mjs` pass. Verify: their output's last lines
  recorded in the findings section

## 4. The first release through the API

- [ ] 4.1 The first new version code (when, kewang decides: design, Open Questions) released by the run sheet with the
  API: new notes written (the files seeded in 3.2 replaced); `check`; `internal`; kewang installs it from Play on the Pixel (`dumpsys package`: the new
  versionCode, `installerPackageName=com.android.vending`); kewang's OK; `production --version-code <n>` (unless the
  release is for internal testing only); `check` shows it in review. Verify: each command's output and kewang's word
  recorded in the findings section, with D5's open point — whether an internal release was refused while production was
  in review, or "not tested: no review in progress"; the run sheet corrected where anything differed from the design
