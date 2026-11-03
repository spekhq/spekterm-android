## Context

A release today (`.claude/skills/play-release/SKILL.md` step 3, `docs/play-release.md` "Every later release"): an agent
drives Play Console in kewang's Chrome through Claude in Chrome — Internal testing → Create new release → `file_upload`
the bundle into the page's file input → release name and notes → Save and publish; after kewang's OK on the phone,
Production → Add from library → the same name and notes → Send for review. `file_upload` takes at most 10 MB a call:
0.2.0's bundle (about 10.2 MB) went through, 0.5.0's (10,650,257 bytes, 2026-10-30) was refused and kewang dragged it in.

The Google Play Developer API v3 does the same through **edits**: `edits.insert` opens an edit;
`edits.bundles.upload` (`POST https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{pkg}/edits/{id}/bundles`)
takes the `.aab` and answers its `versionCode`; `edits.tracks.update` (`PUT …/edits/{id}/tracks/{track}`) sets a
track's releases — `{name, versionCodes, status, releaseNotes: [{language, text}]}`; `edits.validate`; `edits.commit`;
`edits.delete` discards an edit. The API is reached with an OAuth 2.0 token for the scope
`https://www.googleapis.com/auth/androidpublisher`, which a service account gets by signing a JWT with its JSON key.
Google's getting-started page (read 2026-10-30): a Cloud project with the API enabled, a service account, and that
account's e-mail invited under Play Console → Users and permissions; linking the developer account to a Cloud project
is no longer needed.

What the API documents about commit (`edits.commit`, read 2026-10-30): `changesInReviewBehavior`, when unset, is
`CANCEL_IN_REVIEW_AND_SUBMIT` — it **cancels a review in progress** and submits everything; `ERROR_IF_IN_REVIEW`
answers 400 `FAILED_PRECONDITION`, reason `CHANGES_ALREADY_IN_REVIEW`, instead and leaves the edit valid.
`changesNotSentForReview=true` keeps the edit's changes out of review until someone sends them from Play Console.
Reported by others (fastlane #18670, podshelf-app PR #155; not in Google's docs): Play sometimes refuses a commit with
"Changes cannot be sent for review automatically. Please set the query parameter changesNotSentForReview to true" — on
the internal track too, typically after a rejected review — and refuses the parameter where it does not apply, even
set to `false` ("changesNotSentForReview must not be set"). Right now 0.5.0 (5) is in production review, which is
exactly the state where the default would bite.

Outside edits, `applications.tracks.releases.list` (`GET …/applications/{pkg}/tracks/{track}/releases`, read-only)
gives each release's `releaseLifecycleState` — among them `RELEASE_LIFECYCLE_STATE_IN_REVIEW`, `…_NOT_SENT_FOR_REVIEW`,
`…_NOT_APPROVED`, `…_PUBLISHED`; a track inside an edit only says `draft`, `inProgress`, `halted` or `completed`.
Opening an edit invalidates any other open edit (Google's edits page), so a leftover edit does no lasting harm, but
it also means a second successful `check` does not prove the first one deleted its edit.

The machine: Node 22.22.0 (nvm default; `fetch`, `node:crypto`), no Ruby, `gcloud` installed. The repository has no
`package.json`; `tools/` holds dependency-free `.mjs` scripts. AGP 9.4.1, Gradle 9.8.1.

## Goals / Non-Goals

**Goals:**

- Upload, release to internal testing and send to production review from the terminal, with no size limit from a
  browser tool and no step that needs kewang at the desktop other than giving the OK.
- Never cancel a review in progress, never leave a half-made edit, never print or commit a secret.
- Keep the release notes' format and limits (`<en-US>`, `<zh-TW>`, ≤ 500 characters each) and the release name
  (`<versionName> (<versionCode>)`).

**Non-Goals:**

- Publishing the store listing, screenshots or forms through the API: they change rarely and are checked by eye in
  Play Console.
- Staged rollouts, other tracks (closed, open testing), halting a rollout through the API: a bad release is halted in
  Play Console as today.
- Running from CI: there is none; the key stays on kewang's desktop.
- Building: `./gradlew check bundleRelease` stays as it is; the script takes the bundle it wrote.

## Decisions

### D1. A dependency-free Node script, `tools/play-publish.mjs`

The script signs the service account's JWT with `node:crypto` (RS256), trades it for a token at
`https://oauth2.googleapis.com/token`, and calls the edit methods and the read-only `applications.tracks.releases.list` with `fetch`. About 250 lines, testable through
an injected `fetch` (D7).

*Alternatives:*

- **Gradle Play Publisher** (`com.github.triplet.play`; 4.0.0, January 2026, supports AGP 9 and nothing older; 4.1.1 is
  the latest). It would fit the `play/` layout chosen for it (`play-release` D6). But its commit sets only
  `changesNotSentForReview` (read in `DefaultPlayPublisher.commitEdit`, master, 2026-10-30), so every commit — an
  internal upload included — would take the API's default and cancel a production review in progress, as 0.5.0's is
  now. It also adds a plugin to the build of an app whose build is checked closely (licenses, configuration cache),
  for four API calls. Rejected; switching later stays possible, since the release notes use its layout (D4).
- **fastlane supply**: Ruby is not installed here, and fastlane brings a large gem tree to maintain for the same calls.
  Rejected.
- **`googleapis` from npm**: a `package.json`, a lock file and some hundred transitive packages in a repository that has
  none, for the same calls. Rejected.
- **kewang's own Google login (`gcloud auth application-default login` with the androidpublisher scope)** instead of a
  key: no long-lived secret on disk, but it needs an OAuth client set up for the scope and expires on its own schedule;
  kewang already chose the service account key. Not taken; noted in Risks.

### D2. The key: a JSON file in `~/.android/`, read by path

Default `~/.android/spekterm-play-publisher.json`, beside the upload key `~/.android/spekterm-upload.p12`; `--key <path>`
or `SPEKTERM_PLAY_KEY_FILE` (a path, never the key itself) overrides it. The script reads `client_email` and
`private_key` from it and nothing else; it prints `client_email` (not secret; it is what Play Console's Users and
permissions shows, so a permission problem can be traced without opening the key), and never the private key, the
signed assertion or the access token; errors are printed without the request's headers. When the file is missing it
stops before any request, saying where it looked and pointing at `docs/play-release.md` ("Once: publishing through the
API"). When the file is readable by group or others it warns (chmod 600). The repository's `.gitignore` gains
`*play-publisher*.json` — a guard against a renamed copy only, since the file lives outside the repository.

kewang creates the key (D8). No agent asks for its contents, and no agent reads the file (`cat`, Read): the run sheet
says so.

### D3. Three commands

```
node tools/play-publish.mjs check
node tools/play-publish.mjs internal [--bundle <aab>] [--same-notes] [--dry-run] [--send-in-console]
node tools/play-publish.mjs production --version-code <n> [--dry-run] [--send-in-console]
```

Version codes are compared as strings: edits give them as strings, `applications.tracks.releases.list`
(`activeArtifacts[].versionCode`) as numbers. The package name is `com.spekterm.android`, fixed in the script; `--bundle` defaults to
`app/build/outputs/bundle/release/app-release.aab`.

- **`check`**: token → `applications.tracks.releases.list` for `internal` and `production` (each release's name,
  version codes and lifecycle state, e.g. in review) → `edits.insert` → `edits.tracks.list` (names, status, version
  codes, notes) → `edits.delete`, printing the delete's HTTP status. Proves the key, the API being enabled and the
  account's access to the app. It cannot prove the release permissions — only a commit uses them (Risks).
- **`internal`**, before any request: reads `versionCode` and `versionName` from `app/build.gradle.kts` (the
  `versionCode = <n>` and `versionName = "<s>"` lines; anything else is an error) and the notes (D4). Then: token →
  `applications.tracks.releases.list` `production`: a release in review prints a warning that the commit may be refused
  (D5) and goes on → `edits.insert` → `edits.tracks.get` `internal`: notes equal to the current internal release's stop
  it, unless `--same-notes` (D4) → `edits.bundles.upload` (`uploadType=media`, `Content-Type:
  application/octet-stream`, the whole file; Google recommends a 2-minute timeout, the script allows 5, which is also
  the ceiling of Node's built-in `fetch`) → the answer's `versionCode` must equal the Gradle file's, or it stops →
  `edits.tracks.update` `internal` with one release `{name: "<versionName> (<versionCode>)", versionCodes:
  ["<versionCode>"], status: "completed", releaseNotes}` → `edits.validate` → `edits.commit` (D5). Prints the release
  it made.
- **`production`**: token → `applications.tracks.releases.list` `production`: a release in review stops it before any
  edit (cancel or wait in Play Console) → `edits.insert` → `edits.tracks.get` `internal` → the release holding `<n>`, or
  it stops naming what internal holds → `edits.tracks.update` `production` with one release of that version code, that
  release's name and notes, `status: "completed"` (full rollout, as today) → `edits.validate` → `edits.commit` (D5),
  which sends it for review; managed publishing is off, so it goes live once approved. Taking name and notes from
  internal testing makes production carry exactly what kewang tested, even if the notes files changed since.

`--dry-run` does everything but the commit: after `edits.validate` it deletes the edit. For `production` it costs
nothing. For `internal` it uploads, and Google's edits page says an upload is kept in "a storage area, so it can be
assigned to a track in this or a subsequent edit" — so a deleted edit may well have used up the version code. The run
sheet therefore does not use `internal --dry-run`; the flag stays for a throwaway build. The real `internal` already
validates before it commits, and an internal release is replaced by the next one.

Every failure after `edits.insert` — an HTTP error, a version mismatch, unchanged notes, an interrupt (SIGINT) — deletes the edit and
exits non-zero with Play's own error (`error.message`, `error.status`, the first `error.details[].reason`; for the token
endpoint, whose `error` is a string, `error` and `error_description`). One exception: when the **commit** request ends
without an answer from Play (a network error, a timeout, or an interrupt after the commit was sent), its outcome is unknown; the script says so, tells to run
`check` before anything else, and does not call it a failure or delete the edit (a commit that went through cannot be
deleted, and one that did not is invalidated by the next edit anyway).

### D4. Release notes: `play/release-notes/<locale>/default.txt`, committed

Gradle Play Publisher's layout, rooted at `play/` like the listings (`play-release` D6): `play/release-notes/en-US/default.txt`
and `play/release-notes/zh-TW/default.txt`, plain text, the `- …` lines the run sheet writes today without the
`<en-US>` tags. Each release overwrites them, before building, and commits them in its release commit, so the commit
carries what users were told and git keeps the history. Both must exist, be non-blank and be at most 500 characters
(code points, trailing newlines dropped) — checked by the script before any request, and by `PlayListingTest` in
`./gradlew check`, so a too-long note fails the build before the upload.

Files that never change are the risk of this layout: forgetting them would silently ship the previous release's notes.
So `internal`, in its edit and before the upload, compares the files with the notes of the release internal testing
holds now (`edits.tracks.get` `internal`, per language, trailing white space ignored) and stops when both are the same;
`--same-notes` says on purpose that the notes stay (a rebuild after a rejected review usually keeps them). Comparing
with Play rather than with the last `v*` tag also covers the first release through the API: the files are seeded after
`v0.5.0`, so a tag comparison would see them as changed even if nobody wrote new notes (found by the review).

`play/release-notes` is declared a test input (like `play/listings`) only once the files exist: Gradle fails every test
task on an input directory that does not exist (measured by the review, Gradle 9.8.1). The files are seeded with
0.5.0's notes as `check` prints them from the internal track, after kewang's setup, in the same task as the test.

*Alternative:* one file per version (`play/release-notes/0.6.0.txt`) — it cannot be forgotten, but it is a layout of our
own that a later move to Gradle Play Publisher would have to convert; the tag check gives the same protection.
Rejected.

### D5. Commit: never cancel a review

Every commit carries `changesInReviewBehavior=ERROR_IF_IN_REVIEW`, and by default no `changesNotSentForReview` at all
(Play refuses it where it does not apply, even `false`). On `CHANGES_ALREADY_IN_REVIEW` the script deletes the edit and
says a review is in progress (cancel it in Play Console or wait for it), and stops. It never retries with another
behavior on its own.

Whether an internal testing release counts as a "change in review" while production has one in review is not
documented (reports disagree). If it does, uploading N+1 to internal testing while N waits for review fails; that is
the safe outcome, and the run sheet then waits for N's review or asks kewang.

When Play refuses to send changes for review automatically (its error names `changesNotSentForReview`; reported after a
rejected review — exactly when the run sheet makes a new PATCH release), `internal --send-in-console` and `production
--send-in-console` commit with `changesNotSentForReview=true`, and kewang (or Claude in Chrome) sends them from
Publishing overview → Send changes for review. Not the default: the default sends in one step.

### D6. The run sheet and the docs

`.claude/skills/play-release/SKILL.md`:

- Step 2 gains, before `./gradlew check bundleRelease` (which checks them): write `play/release-notes/<locale>/default.txt`
  (for users, from the commits since the last tag) and include them in the release commit — for a rebuild after a
  rejected review, keep them and pass `--same-notes`; run
  `node --test tools/*.test.mjs` when `tools/play-publish.mjs` changed since the last tag.
- Step 3 becomes: `check` once at the start (a clear error beats a failed upload; a production release in review is
  seen here); `internal`; 【手機】 kewang tests the internal build (unchanged, with the `dumpsys package` confirmation);
  **wait for kewang's OK**; `production --version-code <n>`; then `check` shows production's release in review. What to
  do on `CHANGES_ALREADY_IN_REVIEW`, on Play's refusal to send for review (`--send-in-console`, then Publishing
  overview), on a 403 or a missing permission, on a commit with an unknown outcome. Play's warnings in the validate and
  commit answers are shown to kewang; the one known warning (native code without debug symbols) appears in Play
  Console, not the API, and stays as it is. The agent never reads the key file.
- Claude in Chrome stays as the fallback, described in a short subsection: when the API refuses for a reason the
  script cannot handle (a policy declaration, an unanswered form, a permission), or `check` fails. The 10 MB limit is
  said there; a bundle over it is dragged in by kewang.
- The "Upload" heading keeps its place, so step numbers elsewhere stay valid.

`docs/play-release.md`: a new section "Once: publishing through the API" (D8), and "Every later release" writes the
notes in step 2 and uses the script in step 4, Play Console as fallback. `tools/README.md` describes the script.

### D7. Tests: `node --test` against a fake Play

`tools/play-publish.test.mjs`, run with `node --test tools/*.test.mjs` (Node 22 takes a bare directory argument as a
module path and fails — measured by the review), covers the pure parts — reading the Gradle version, the notes
(missing, blank, 501 characters, 500 with CJK, trailing newlines), the release body, the JWT (signed with a key
generated in the test, verified with its public key; claims `iss`, `scope`, `aud`, `exp` − `iat` = 3600) — and each
command against an injected `fetch` that records requests and answers like
Play: the order of calls; the upload's URL and headers; the commit's query (`ERROR_IF_IN_REVIEW`, no
`changesNotSentForReview` by default, `=true` with `--send-in-console`); an upload answering another version code; a
400 on `tracks.update`; `CHANGES_ALREADY_IN_REVIEW` on commit; a commit ending in a network error; `production` with a
version code not in internal testing; `production` while production is in review; notes equal to internal testing's
with and without `--same-notes`; an interrupt after the commit was sent; `--dry-run`; `check`; a missing key file, a key file readable by others, a token answer of 400
`invalid_grant` — each failing path ending in exactly one `edits.delete` where an edit was opened, and no commit. No
test reaches Google.

`./gradlew check` does not run them (it would make Node a build requirement); the run sheet runs them when the script
changed, and the task list runs them.

### D8. kewang's one-time setup (【桌機】), written into `docs/play-release.md`

1. Google Cloud Console (signed in as the account that owns the Play developer account kewang): create a project, e.g.
   `spekterm-release` (or use an existing personal one), and enable **Google Play Android Developer API**
   (`gcloud services enable androidpublisher.googleapis.com --project <project>` does the same).
2. IAM & Admin → Service accounts → Create: name `play-publisher`; no Cloud roles (Play Console grants its access).
3. The account → Keys → Add key → JSON. The browser saves it as `<project-id>-<key id>.json` in `~/Downloads`: **move**
   it to `~/.android/spekterm-play-publisher.json` (so no copy stays in Downloads) and `chmod 600` it — or create it in
   place with `gcloud iam service-accounts keys create ~/.android/spekterm-play-publisher.json --iam-account
   play-publisher@<project>.iam.gserviceaccount.com`. Back it up in the password manager. Never paste its contents
   anywhere.
4. Play Console, developer account **kewang** (not Funliday) → Users and permissions → Invite new users → the service
   account's e-mail → App permissions → add spekterm → tick **Release to production, exclude devices, and use Play App
   Signing**, **Release apps to testing tracks**, and **View app information and download bulk reports (read-only)**
   if it is not already ticked → Invite user. Labels as Google's help names them in 2026; where one has moved, the
   meaning counts.
5. Tell the agent it is done; the agent runs `node tools/play-publish.mjs check` and reports what it printed. A new
   service account's access is reported to take from minutes to a day to start working: a 403 right after inviting
   it means wait a few hours and try again, not a wrong setup.

A leaked key: delete it in the service account's Keys page (it stops working at once) and make a new one.

## Risks / Trade-offs

- **The release permissions are proven only by the first real commit** (`check` cannot use them). → `check` right
  after setup proves the rest; the first `internal` at the next release proves the permission for testing tracks, and
  its `production` the production one. A refusal names the missing permission; kewang adds it in Users and permissions.
- **Organization policy may forbid key creation** (`iam.disableServiceAccountKeyCreation`, enforced by default for
  Google Cloud organizations created since 2024). kewang's personal account should have no organization; not
  confirmed. → If step 3 is refused, the setup stops and the agent proposes the user-login alternative (D1) as a new
  decision.
- **An internal release may be blocked while production is in review** (D5). → The safe failure; `check` shows the
  review first; the run sheet waits or asks.
- **Play refuses to send for review automatically** (D5). → `--send-in-console` and one click in Play Console, no
  upload through the browser.
- **A commit's outcome unknown** after a network failure (D3). → `check` before anything else; never a blind rerun,
  which would end in "version code already used".
- **The API changes** (v3 has been stable since 2017). → The script is small and its tests describe each request;
  Claude in Chrome remains the fallback.
- **A secret in a terminal**: the script prints neither key nor assertion nor token, and its errors drop request
  headers; tests check that a failing run's output holds no part of the fake key's `private_key`, the assertion or the
  fake token.

## Migration Plan

1. This change: the script, its tests, the docs and the run sheet; kewang's setup (D8); `check`; the notes files seeded
   from `check`'s output and checked by `PlayListingTest`.
2. The next release (the first new version code): `check`, `internal`, kewang's OK on the phone, `production`,
   `check`; findings recorded in `docs/release-findings.md`. Only then are the change's last tasks done.

Rollback: the run sheet's Chrome fallback is the old path, unchanged.

## Open Questions

- When to verify for real (task 4.1), which also decides whether D5's open point gets an answer:
  - at the next ordinary release (the default here; the change stays open until then) — most likely with no review in
    progress, so whether an internal release is blocked by a production review stays unknown;
  - now, while 0.5.0 is in review, with a 0.5.1 (6) for internal testing only: `ERROR_IF_IN_REVIEW` guarantees an error
    rather than a cancelled review, so it answers D5 safely, but it uses a version code and puts a build on kewang's
    phone that production never gets (0.5.1 would then go to production after 0.5.0, or be skipped);
  - after 0.5.0's review, the same 0.5.1 for internal testing only — answers nothing about D5.

  kewang decides at review.
