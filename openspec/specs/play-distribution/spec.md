# play-distribution Specification

## Purpose
Makes the app fit for Google Play: a release bundle signed with the upload key and shrunk by R8, a log that reads the same
in every build, no connection to anyone but the user's own desktop, an About screen with the privacy policy, source and
licenses, and a way to report a problem from the settings.

## Requirements

### Requirement: Build the Play bundle signed with the upload key

The release build SHALL produce an Android App Bundle signed with the upload key named by the Gradle properties
`spekterm.upload.storeFile`, `spekterm.upload.password` and `spekterm.upload.keyAlias`; neither the key file nor the password
SHALL be committed to the repository. When any of the three is missing, a release build SHALL fail before compiling, with a
message naming each missing property; debug and profile builds SHALL build without them.

#### Scenario: Building with the key

- **WHEN** the three properties name an existing key and `./gradlew bundleRelease` runs
- **THEN** it writes `app/build/outputs/bundle/release/app-release.aab`, and `jarsigner -verify` reports it signed by that key's
  certificate

#### Scenario: Building without the key

- **WHEN** a property is missing and `./gradlew bundleRelease` runs
- **THEN** the build fails before any compile or R8 task runs, its message names each missing property, and
  `./gradlew assembleDebug assembleProfile` still succeeds

### Requirement: Ship a shrunk bundle Play can deobfuscate

The release and profile builds SHALL be shrunk and obfuscated by R8 with resource shrinking, from the same R8 configuration.
The release bundle SHALL carry R8's mapping file, and every native library in it SHALL be aligned for 16 KB pages.

#### Scenario: The bundle's contents

- **WHEN** the release bundle is built
- **THEN** it contains `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`, every `LOAD` segment of every
  `.so` in it is aligned to `0x4000`, and the profile APK passes `zipalign -c -P 16`

#### Scenario: The shrunk app in use

- **WHEN** the profile build is installed and paired with a desktop, and the user opens the session list, a conversation with
  Markdown and tool calls, sends a reply with a photo and a PDF, answers a prompt, browses OpenSpec, accepts an inbox item,
  opens Settings, About and Licenses, pairs again and unpairs
- **THEN** each works as it does in the debug build, and the connection log holds no crash and no decoding failure

### Requirement: Write the same log in every build

The connection log SHALL name every outcome and kind by a fixed name written in the app's code, never by a class name taken
at run time, so that a log from the shrunk build reads the same as one from the debug build.

#### Scenario: A reply's outcome in the shrunk build

- **WHEN** the profile build sends a reply that the desktop submits
- **THEN** the connection log's line for it reads `outcome=Submitted`, as in the debug build

#### Scenario: A class name in a log line

- **WHEN** code under `app/src/main` takes a class's name at run time — `javaClass`, `::class` or `::class.java` followed by
  `.simpleName`, `.name`, `.qualifiedName` or `.canonicalName`
- **THEN** the JVM test suite fails, naming each place

### Requirement: Talk to no one but the user's desktop

The app SHALL open network connections only to the addresses of the desktop it is paired with or of the pairing link the user
is pairing with. It SHALL include no analytics, advertising or crash-reporting library, and send nothing to its developer or
any third party. Camera frames SHALL be decoded on the phone and neither stored nor sent. A link in shown text SHALL open in
another app only when the user taps it. The privacy policy the app links to SHALL state each of these.

#### Scenario: The app's own sockets while in use

- **WHEN** the app is paired and used through every screen on the emulator, and the sockets owned by the app's UID are listed
  every second meanwhile
- **THEN** every remote address among them is one of the paired desktop's addresses

#### Scenario: No other host in the code

- **WHEN** `./gradlew check` runs
- **THEN** it fails if a Kotlin string literal or a string resource under `app/src/main` holds a URL other than the privacy policy's (in each language) and the source
  repository's (and its issue page), or if the release runtime classpath holds a library of a listed analytics, advertising or crash-reporting
  vendor (Firebase, Google Mobile Ads and Analytics, Crashlytics, Sentry, Bugsnag, AppsFlyer, Adjust, Mixpanel, Amplitude)

### Requirement: Show the app's version, privacy policy, source and licenses

The app SHALL have an About screen, reached from Settings and, while not paired, from the pairing screen, showing the app's
name, version name and version code, and offering the privacy policy and the source repository (opened in the browser when
tapped) and a Licenses screen. The Licenses screen SHALL show, as plain text, the app's own license and, for every library the
release build ships, its project and license, followed by the full text of each of those licenses and the NOTICE texts the
libraries carry. The screens' titles and section headings are the app's own texts; the entries, license texts and notices
are shown in their original English in every language. A release build and `./gradlew check` SHALL fail when a shipped
library has no entry.

#### Scenario: About from Settings

- **WHEN** the user opens Settings and taps About
- **THEN** the About screen shows "spekterm", the version name and code of the installed build, and the three entries

#### Scenario: About before pairing

- **WHEN** the phone is not paired, the user has typed part of a link on the pairing screen, taps About, then goes back
- **THEN** the About screen opens, and back returns to the pairing screen with the typed text kept

#### Scenario: The privacy policy

- **WHEN** the user taps Privacy policy on the About screen
- **THEN** the browser opens the policy's page on spekterm.com in the app's language (English, or Traditional Chinese)

#### Scenario: A shipped library without a license entry

- **WHEN** a dependency added to the app brings a library whose group no entry covers
- **THEN** `./gradlew check` and `./gradlew bundleRelease` fail, naming that library's `group:artifact`

### Requirement: Let the user report a problem from the settings

The settings screen SHALL offer Report a problem, and no conversation's menu SHALL, a channel to the developer outside the app, which explains that what the agent writes comes from the user's
own agent on the user's computer and is not seen by the app's developer, and offers to open the repository's issue page in the
browser. Nothing from the conversation SHALL be attached or sent.

#### Scenario: Reporting

- **WHEN** the user chooses Report a problem in the settings, then Report
- **THEN** the browser opens the repository's new-issue page, with nothing of the conversation in its address

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
