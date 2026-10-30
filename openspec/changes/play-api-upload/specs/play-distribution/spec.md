## ADDED Requirements

### Requirement: Publish a release to Google Play from the terminal

The repository SHALL hold a tool that publishes through the Google Play Developer API, with a service account key read
from a file outside the repository, without a browser:

- releasing the built bundle to internal testing — uploaded, one release named `<versionName> (<versionCode>)` with the
  version of `app/build.gradle.kts`, served to all internal testers, with release notes in en-US and zh-TW;
- releasing a version code already in internal testing to production, with that release's name and notes, sent for
  review;
- checking access: printing the internal testing and production releases with their review state, changing nothing.

The release notes SHALL be the committed files `play/release-notes/en-US/default.txt` and
`play/release-notes/zh-TW/default.txt`, each present, not blank and at most 500 characters; the tool SHALL check them,
and the version, before sending anything, and `./gradlew check` SHALL fail when a file breaks these limits. The tool
SHALL refuse to release to internal testing with the same notes as the release internal testing holds unless told the
notes stay.

A commit SHALL never cancel a review in progress, and SHALL not ask Play to hold changes back from review unless told
to. Every run that does not commit SHALL discard its edit, except when the commit's outcome is unknown. The tool SHALL never print the key's private key, a signed
assertion or an access token.

#### Scenario: Checking access

- **WHEN** `check` runs with a valid key
- **THEN** it prints the service account's e-mail, the internal testing and production releases — name, version codes,
  status, notes and review state — and deletes the edit it opened, printing Play's answer to the delete, committing
  nothing

#### Scenario: Releasing to internal testing

- **WHEN** `internal` runs with `versionCode = 6` and `versionName = "0.6.0"` in `app/build.gradle.kts`, valid notes
  that differ from the current internal testing release's, and a bundle whose upload Play answers with version code 6
- **THEN** it sets the internal track to one release named `0.6.0 (6)`, version code 6, status `completed`, with the
  en-US and zh-TW notes, validates the edit and commits it with `changesInReviewBehavior=ERROR_IF_IN_REVIEW` and without
  `changesNotSentForReview`

#### Scenario: Notes over the limit

- **WHEN** `play/release-notes/zh-TW/default.txt` holds 501 characters, or a notes file is missing or blank
- **THEN** `internal` exits non-zero naming the file and the problem before sending any request, and `./gradlew check`
  fails naming it too

#### Scenario: Notes not updated

- **WHEN** `internal` runs and both notes files hold the same text as the current internal testing release's notes
- **THEN** it deletes the edit before uploading the bundle, and exits non-zero saying the notes did not change; with
  `--same-notes` it goes on

#### Scenario: A bundle of another version

- **WHEN** Play answers the upload with a version code other than `app/build.gradle.kts`'s
- **THEN** the tool deletes the edit, commits nothing, and exits non-zero naming both version codes

#### Scenario: Releasing to production

- **WHEN** `production --version-code 6` runs, no production release is in review, and internal testing holds a release
  with version code 6
- **THEN** it sets the production track to one release of version code 6 with the internal release's name and notes,
  status `completed`, and commits it with `changesInReviewBehavior=ERROR_IF_IN_REVIEW` and without
  `changesNotSentForReview`, sending it for review

#### Scenario: Production already in review

- **WHEN** `production` runs while a production release is in review
- **THEN** it exits non-zero before opening an edit, saying a review is in progress

#### Scenario: A version code not in internal testing

- **WHEN** `production --version-code 7` runs and no internal testing release holds version code 7
- **THEN** it deletes the edit, commits nothing, and exits non-zero naming the version codes internal testing holds

#### Scenario: A review in progress at commit

- **WHEN** Play answers the commit with `CHANGES_ALREADY_IN_REVIEW`
- **THEN** the tool deletes the edit, does not commit again in any other way, and exits non-zero saying a review is in
  progress

#### Scenario: Play will not send for review by itself

- **WHEN** `internal` or `production` runs with `--send-in-console`
- **THEN** the commit carries `changesNotSentForReview=true`, and the tool says the changes wait in Play Console's
  Publishing overview to be sent for review

#### Scenario: A failure mid-way

- **WHEN** any request after the edit was opened, other than the commit, fails, or the run is interrupted before the
  commit was sent
- **THEN** the edit is deleted, Play's error is printed, and nothing in the output holds the key's private key, the
  signed assertion or the access token

#### Scenario: A commit without an answer

- **WHEN** the commit request ends in a network error or a timeout, or the run is interrupted after it was sent
- **THEN** the tool exits non-zero saying the commit's outcome is unknown and that `check` must be run before anything
  else, and does not report the edit as deleted

#### Scenario: A dry run

- **WHEN** `internal --dry-run` or `production --dry-run` runs and every request succeeds
- **THEN** the edit is validated and then deleted, never committed
