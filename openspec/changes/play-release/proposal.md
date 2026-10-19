## Why

kewang decided on 2026-10-19 that publishing the app on Google Play comes first, ahead of the terminal view and the overnight
connection run. Today the repository cannot produce anything Play accepts: the release build has no signing configuration,
there is no upload key, the version is the template's `1` / `0.1.0` with no rule for raising it, the code is not shrunk, and
nothing a Play listing needs exists — no privacy policy at a public URL, no listing text or graphics, no answers prepared for
the Data safety, content rating, foreground service and app access forms. The account is a personal one opened before
November 2023, so the 12-tester closed test does not apply: an internal test, then production.

The one prerequisite outside this repository is met: spekterm 0.2.14 (`93bf535`), published on 2026-10-19 as the latest
GitHub release, has remote access and every protocol feature the app uses (the app needs 0.2.6, its full feature set 0.2.13).
A user who installs the app from Play can pair with a released desktop.

## What Changes

- **A signed, shrunk release bundle.** `./gradlew bundleRelease` builds an Android App Bundle signed with an upload key kept
  outside the repository (path and password from Gradle properties in the Gradle home, never committed), shrunk and
  obfuscated by R8 with resource shrinking, its mapping file inside the bundle for Play's deobfuscation. Without the properties
  a release build fails at once naming them (today it silently writes an unsigned bundle). Play App Signing holds the app
  signing key. The `profile` build stays debug-signed and runs the same R8 output, so the shrunk app is tried on the phone over
  a debug install.
- **Logs that survive obfuscation.** The two log lines that name an outcome by its class name get fixed names, and a test
  keeps class names out of the app's code; otherwise R8 would turn `outcome=Submitted` into `outcome=pa3`.
- **Version rule** (in the release checklist): `versionCode` +1 and a new `MAJOR.MINOR.PATCH` `versionName` for every upload,
  tagged `v<versionName>`. The first upload keeps today's `0.1.0` / `1`.
- **An About screen**, from Settings and from the pairing screen: the app's version, the privacy policy, the source code, and
  a Licenses screen listing every library the release ships (192 artifacts, by group) with their licenses and NOTICE texts —
  kept complete by a build check that also refuses analytics, advertising and crash-reporting libraries.
- **Report a problem** in a conversation's menu, opening the repository's issue page — for Play's AI-generated content
  policy, which a reviewer may apply to an app whose central screen is a conversation with an AI agent.
- **A privacy policy**, a page on spekterm.com in English and Traditional Chinese (drafted here, published by the spekterm
  repository's session through a handoff): the app sends nothing to its
  developer or any third party; it connects only to the user's own desktop, over TLS pinned to that desktop's certificate;
  the camera only reads the pairing code, on the phone; replies and attachments go only to that desktop, where the user's own
  agent tools decide what happens to them.
- **The Play listing, in the repository** (`play/`, in Gradle Play Publisher's layout): title, short and full description in
  en-US and zh-TW; the 512 × 512 icon and 1024 × 500 feature graphics rendered from the desktop's icon; phone screenshots and
  the foreground service video taken on the `Pixel_9` emulator against a demo desktop with throwaway repositories; and the
  prepared answers for Play Console's forms — Data safety (no data collected or shared: the only transfer is end-to-end
  encrypted between the phone and the user's own computer), content rating, target audience, ads and advertising ID, AI
  content, the `specialUse` foreground service declaration, app access, store settings.
- **A release checklist** (`docs/play-release.md`): every Play Console step — creating the app, uploading, each form, internal
  test, production — numbered and marked 【我】/【桌機】/【手機】, and how to build, tag and upload each later release.
  Findings of the release build's runs go to `docs/release-findings.md`.
- **README**: "Install", "Privacy", "Third-party" pointing to the Licenses screen; "Status" no longer says nothing is
  published once the internal test is live. ("Testing against a desktop" already points to spekterm 0.2.14, `87b4328`.)
- **No foreground service type change.** `specialUse` stays (`docs/spike-findings.md` §3); its manifest subtype text is
  tightened to the declaration's answers.

## Capabilities

### New Capabilities

- `play-distribution`: the signed, shrunk bundle; logs that read the same in every build; talking to no one but the user's
  desktop, as the privacy policy states it; the About and Licenses screens; Report a problem.

### Modified Capabilities

None. The new screens' texts fall under `app-language`'s existing requirement (every text the app writes, in both
languages) without changing it; the Licenses screen's notices are third-party text shown as written.

## Impact

- `app/build.gradle.kts`: release `signingConfig` from Gradle properties and `checkUploadKey`; `isMinifyEnabled`,
  `isShrinkResources`, an empty `proguard-rules.pro`; `checkLicenses` over `releaseRuntimeClasspath`; test task inputs; a
  comment on the version fields. `.gitignore`: `*.p12`.
- `app/src/main`: `SendOutcome.logName` / `AnswerOutcome.logName` used in `Desktop.kt`; `ui/AboutScreen.kt` (About and
  Licenses), routes in `MainActivity`, an About section in `StatusScreen`, an About button in `PairScreen`; Report a problem
  in the conversation menu; `res/raw/third_party_licenses.txt`; strings in `values` and `values-zh-rTW`; the manifest's
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` text.
- New files: `play/` (listings, graphics, screenshots, `forms.md`, sources, the privacy policy's draft), `docs/play-release.md`,
  `docs/release-findings.md`, `tools/render-play-graphics.sh` (headless Chrome).
- Tests: new `NoClassNamesTest`, `NetworkHostsTest`, `PlayListingTest`, outcome-name cases in `RepliesTest` / `AnswersTest`;
  instrumented Compose tests for About, the pairing screen's About and Report a problem; `StringsTest` / `HardcodedTextTest`;
  the whole JVM and instrumented suites (emulator); the shrunk build used on the emulator and the Pixel.
- Outside the repository, none done by this change: the upload key file (on kewang's machine, backed up by kewang); every Play
  Console action and the video's upload (kewang); the privacy page on spekterm.com (spekterm's session, by handoff; its push deploys the site).
- No new library dependency.
