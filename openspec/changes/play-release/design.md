## Context

See `proposal.md` for why. The state this design starts from (master @ 3591c8b):

- `app/build.gradle.kts`: `applicationId com.spekterm.android` (frozen once published), `minSdk 29`, `targetSdk 36`,
  `compileSdk 37`, `versionCode 1`, `versionName 0.1.0`. `release` has no `signingConfig` and `isMinifyEnabled = false`;
  `profile` is `initWith(release)` signed with the debug key, for judging smoothness on the phone over a debug install.
  `.gitignore` already ignores `*.jks`, `*.keystore`, `keystore.properties`.
- Runtime dependencies: AndroidX core, activity, lifecycle, navigation, Compose (BOM 2026.09.00), CameraX 1.6.2, zxing-cpp
  3.1.1 (JNI, `.so` for four ABIs), OkHttp 5.5.0, kotlinx.serialization 1.11.0, kotlinx.coroutines 1.11.0, mikepenz
  multiplatform-markdown-renderer 0.46.0. The app's own serialization always names its serializer
  (`Pairing.serializer()`, `JsonElement.serializer()` …); no `Class.forName`, no `@Keep`. Navigation's typed routes are looked up
  through `KClass.serializer()`, which kotlinx.serialization's bundled rules keep. Two log lines name a class:
  `Desktop.kt:370` and `:398` write `outcome.javaClass.simpleName` (a `SendOutcome` / `AnswerOutcome`) into the connection log,
  and the findings documents quote them (`send outcome=Submitted`). zxing-cpp ships `-keep class zxingcpp.** { *; }`; the markdown renderer and OkHttp ship their own consumer rules.
- Manifest: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`,
  `CAMERA` (optional `camera.any`), `RECEIVE_BOOT_COMPLETED`; `ConnectionService` is `specialUse` with a
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` text; backup and device transfer exclude everything; cleartext is off.
- The app's network: one TLS WebSocket to the paired desktop's addresses, its certificate pinned by fingerprint. No other
  host appears in the code. Markdown loads nothing; a link opens in the browser only when tapped. No analytics, no crash
  reporter. The connection log (`files/log/`) stays on the phone; Settings can share it through the share sheet. It is read for
  findings with `adb shell run-as`, which needs a debuggable build: `profile` and `release` are not.
- Settings (`StatusScreen`) exists only while paired; `PairScreen` is shown outside the navigation graph, by an `if` in
  `MainActivity` (`MainActivity.kt:134`), when not paired and when pairing again (`repairing`, with a back button).
- The icon is an adaptive icon with a monochrome layer, drawn from the desktop's `build/icon.svg` (spekterm repository).
- Play facts checked on 2026-10-19: new apps and updates must target API 36 (since 2026-08-31) — met. Every app, even one
  collecting nothing, must complete Data safety and give a privacy policy URL. Data safety counts data as *collected* when it
  leaves the device, except data "unreadable by you or anyone other than the sender and recipient as a result of end-to-end
  encryption". Each declared foreground service type needs a description, the user impact of deferral or interruption, and a
  video link; `specialUse` is allowed "in limited scenarios" meeting the policy's criteria (user-perceptible, stoppable,
  core functionality, not deferrable, only as long as necessary). `docs/spike-findings.md` §3 chose `specialUse` over
  `dataSync` (6 h / 24 h cap, no `BOOT_COMPLETED` start), `remoteMessaging` and `connectedDevice`, and drafted the declaration.
- An R8 probe on 2026-10-19 (a scratch worktree of 3591c8b with `isMinifyEnabled`, `isShrinkResources` and
  `proguard-android-optimize.txt`, no app rule): `assembleProfile bundleRelease` succeeded with no R8 warning; the profile APK
  is 12.2 MB against 36.2 MB unshrunk; the bundle holds `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`;
  `zipalign -c -P 16` passes and every `LOAD` segment is `0x4000`-aligned for all 16 `.so` (zxing-cpp, CameraX's two,
  `androidx.graphics.path`, four ABIs each) — Play's 16 KB page size requirement. The release and profile mappings are
  identical. Without a `signingConfig`, `bundleRelease` succeeds and writes an unsigned bundle. The shipped dependency list
  (`sdk-dependencies/…/sdkDependencies.txt`) has 192 `group:artifact` entries: AndroidX, JetBrains Compose Multiplatform and
  Kotlin, OkHttp and Okio, Guava, Dagger, AutoValue, Error Prone, j2objc, JSpecify, `javax.inject`, `jakarta.inject`,
  jsr305, checker-qual, JetBrains `markdown`, mikepenz, zxing-cpp. Not yet run on a device.
- Facts found in review: the `Pixel_9` AVD is a `google_apis_playstore` image (no `adb root`); `bundletool` is not installed;
  `/bin/sh` here is dash, which drops environment variables with a dot in their name; a JVM test that reads a non-source file
  is `UP-TO-DATE` after that file changes unless the file is a declared input of the test task. Play's AI-Generated Content
  policy covers "text-to-text conversational generative AI chatbots, in which interacting with the chatbot is a central
  feature of the app" and asks for in-app reporting or flagging of offensive content "without needing to exit the app".
- spekterm 0.2.14 (`93bf535`) is the latest public release (2026-10-19; Linux AppImage, macOS arm64 dmg) and has remote
  access; README's "Testing against a desktop" points to it since `87b4328`.

## Goals / Non-Goals

**Goals:**

- One command builds the bundle Play takes, from a clean checkout plus the upload key on kewang's machine.
- The bundle that ships is the build that was tried: the R8 output is used on the emulator and on the Pixel before upload.
- Everything kewang types into Play Console is prepared in the repository, reviewed like code, in both languages where the
  listing has two.

**Non-Goals:**

- Automated upload (Gradle Play Publisher, a CI pipeline, a service account): the first upload has to be manual anyway, and
  a release a month does not repay the setup. Revisit when releases are frequent.
- A demo mode or a public demo desktop for reviewers (D9).
- Moderating agent output: the developer runs no model and sees no content (D11).
- Other stores, a GitHub Releases APK, F-Droid (kewang, 2026-10-19).
- Changing the foreground service type, or any connection behavior.
- Uploading to Play, submitting for review: outward actions done by kewang.


## Decisions

### D1. Signing: an upload key outside the repository, read from Gradle properties; Play App Signing

The release `signingConfig` reads three Gradle properties — `spekterm.upload.storeFile` (an absolute path; `file()` does not
expand `~`), `spekterm.upload.password` and `spekterm.upload.keyAlias` — through `providers.gradleProperty`, which kewang puts in
`~/.gradle/gradle.properties` (outside the repository; `-P` on the command line works too). One password: a PKCS12 key store
has one, shared by the store and the key. When any property is missing, the release signing config is not set, and a task
`checkUploadKey`, which `preReleaseBuild` depends on, fails at once (before R8's minutes) naming the missing ones and
`docs/play-release.md`. The probe showed why the check is needed: without it `bundleRelease` writes an unsigned bundle and
succeeds. Debug and `profile` builds never need the properties (`profile` keeps the debug key after `initWith(release)`).
`.gitignore` gains `*.p12`.

The key: RSA 4096, validity 30 years (Play wants a signing certificate valid past 2033-10-22), alias `upload`, in a PKCS12 file at
`~/.android/spekterm-upload.p12` (kewang, 2026-10-19). It is generated once
with `keytool -genkeypair` by kewang (the password is kewang's, typed into keytool, never into a chat or a file Claude writes).
Backup: kewang's password manager holds the password and a copy of the file.

Play App Signing (mandatory for new apps) keeps the app signing key; this file is only the upload key. A lost or leaked upload
key is replaced through Play Console's upload key reset, so the backup protects against delay, not against losing the app.

*Alternatives:* `keystore.properties` in the repository root (already gitignored) — one more secret file next to the code,
easy to copy along with the repository; rejected for the Gradle home. Environment variables
(`ORG_GRADLE_PROJECT_spekterm.upload.*`) — `gradlew` runs under `/bin/sh`, which on this machine is dash and drops names with a
dot, so they never reach Gradle; not offered. Letting Claude generate the key — a secret would pass through this session's
tools; rejected.

### D2. Versions: semantic `versionName`, `versionCode` +1 per upload, tag `v<versionName>`

Both stay literal in `app/build.gradle.kts`, with a comment pointing to `docs/play-release.md`, which holds the rule: every bundle
uploaded to Play — internal test included — raises `versionCode` by one (Play refuses a code it has seen, even from a discarded
draft) and gets a `versionName` never uploaded before, `MAJOR.MINOR.PATCH`, where before 1.0 a new feature raises MINOR and
anything else PATCH — a rebuild after a rejected review included. Each upload's commit is tagged `v<versionName>`; a tag never
moves. The first upload is `0.1.0` / `1`. This is a release procedure, not app behavior, so it lives in the checklist, not in
the spec.

*Alternatives:* deriving `versionCode` from `versionName` (`MAJOR*10000+…`) — workable under this rule, but the code then jumps
(10000 → 10100) and a hotfix numbering scheme has to be invented later; a plain counter is what Play shows and what the next
upload needs. Deriving it from the commit count — changes with a rebase and is invisible in review. Rejected.

### D3. R8 with resource shrinking on `release` (and `profile`), and how the shrunk build is checked

`isMinifyEnabled = true`, `isShrinkResources = true`, `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
"proguard-rules.pro")`. The app's `proguard-rules.pro` starts with no keep rule: the probe built with none and no warning, the
libraries' consumer rules cover zxing-cpp's JNI, OkHttp and the markdown renderer, and kotlinx.serialization's bundled rules keep
the generated serializers and the routes' `serializer()` lookups. A rule is added only for a failure seen on a device, with a
comment naming it.

The app's own code must not depend on class names. *(Found in apply: besides the two outcome lines below, four log lines name
an exception by class — `ConnectionService.kt` ×3, `PairScreen.kt` — and R8 renames library exceptions. They go through one
`Throwable.logName()` in `log/ConnectionLog.kt`, and `proguard-rules.pro` gets its one rule,
`-keepnames class * extends java.lang.Throwable`, so those names stay; `NoClassNamesTest` allows that file only.)* The two log
lines that write `outcome.javaClass.simpleName` change to an explicit name per outcome — `SendOutcome.logName` / `AnswerOutcome.logName`, an exhaustive `when` returning today's strings
(`Submitted`, `NotSubmitted`, `Refused`, `Unknown`; `Done`, `Partial`, `Mismatch`, `Gone`, `Refused`, `Unknown`) — so logs from every build
read as the findings quote them. A JVM test scans `app/src/main` with
`(javaClass|::class(\.java)?)\.(simpleName|name|qualifiedName|canonicalName)` and fails naming each hit.

`profile` inherits R8 through `initWith(release)` — the probe's release and profile mappings are identical — so a `profile`
install is the shipped code signed with the debug key, installed with `adb install -r` over a debug build without losing the
pairing. The bundle carries R8's mapping (`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`), so Play
deobfuscates crash reports with no separate upload. The `.so` come stripped, so Play Console will warn that native debug
symbols are missing; accepted (native crashes would show addresses only).

**Checking it.** The instrumented suites keep running against `debug`: pointing them at `profile` (`testBuildType`) would need
keep rules for what the tests reach inside the app, and then they would not test the shipped R8 output. Instead the `profile`
build is used through every screen (task 4.1, emulator; 4.2, Pixel). A non-debuggable build's log cannot be read with
`run-as`, so after each run the debug build is installed over it with `adb install -r` (same key, same files) and the log is
read as always; only lines before the reinstall's time are the shrunk build's (the debug build starts the service again on
`MY_PACKAGE_REPLACED` and appends). Nothing is added to the app for this.

*Alternative:* ship without R8 — no keep-rule risk, but the APK is about three and a half times larger (43.4 MB unshrunk at
3591c8b against 12.2 MB, `docs/release-findings.md`) and unobfuscated; rejected because the walk keeps the risk small.

### D4. The About and Licenses screens

`AboutScreen`: the app's name, version name and long version code (`PackageManager.getPackageInfo` — no `BuildConfig`, which
this project does not enable), and rows that open the privacy policy and the source repository in the browser, and the
Licenses screen. Reached from Settings (a last section "About", while paired) and from the unpaired pairing screen — a text
button at its bottom, hidden while pairing is busy and not shown when pairing again (that screen has a back button to
Settings, which has About). On the pairing screen, About replaces the pairing content inside a `SaveableStateHolder` keyed by
screen, so what was typed into the link field survives; the camera preview leaves composition with it and the scanner stops,
and coming back starts it again as on first open.

Licenses shows `res/raw/third_party_licenses.txt` as plain selectable text. Its screen title and section headings are string
resources in both languages (`app-language`); the license notices themselves are shown in English, as their authors wrote
them — they are third-party text, like what the desktop sends. The file lists the app's own MIT license first, then entries
by **group** (`androidx.*`, `org.jetbrains.compose.*`, `com.squareup.okhttp3`, `com.google.guava`, …) with the projects'
names and their license, then the full texts of each license named (Apache License 2.0, MIT, and any other the check finds),
then the NOTICE texts of the shipped Apache-licensed artifacts that carry a NOTICE file (Apache-2.0 §4(d)).

A Gradle task `checkLicenses` resolves `releaseRuntimeClasspath` (as the release really ships it — all 192 coordinates, not
only the direct ones), and fails naming every `group:artifact` no entry's group pattern covers; `check` and `preReleaseBuild`
depend on it, so neither the test suite nor a release can pass with a dependency missing. The same task fails on any
coordinate of a listed analytics, advertising or crash-reporting vendor (the spec's list), so "no such library" is checked, not
only promised. It reads the resolution result
through a provider, which the configuration cache allows; the file is its declared input.

*Alternatives:* Google's `oss-licenses-plugin` or AboutLibraries — a build plugin and a library, with screens in their own
style; rejected for a list of about thirty group entries that a check keeps complete. A JVM test parsing `build.gradle.kts` —
sees only direct dependencies; rejected.

### D5. Privacy policy: a page on spekterm.com, in both languages (kewang, 2026-10-19)

The policy is a page of spekterm.com — `site/` in the spekterm repository (Astro and Starlight, every page in English and
Traditional Chinese, deployed by Cloudflare Pages on every push to spekterm's master). Proposed paths: `/android/privacy/` and
`/zh-tw/android/privacy/`; the spekterm session settles them within its site's rules (`content-plan.json`, its checks), and
this repository takes the URLs it reports. The app opens the page in the phone's language (one string resource per locale).

The text is drafted here, as `play/privacy-policy.md` (English, then Traditional Chinese), reviewed with this change, and
handed to the spekterm repository by spekterm handoff; the site's page is then the published one, and the draft is removed
once the page is live so there is one copy. It states what Context lists: no data to the developer or any third party;
connections only to the desktop the user paired, or is pairing with, TLS-pinned to its certificate; camera frames decoded on
the phone and never stored or sent; replies, answers and attachments go only to that desktop — and what happens to them there
is up to the user's spekterm and agent tools (an agent may send them to its AI provider, under that provider's terms); the
pairing credential and the log stay on the phone (excluded from backup), shared only when the user chooses; each Android
permission and what it is for; contact (the repository's issues, and the developer email shown on the Play listing); its date.
A later change of what the app sends is a change of that page, by the same handoff.

*Alternative:* `PRIVACY.md` in this repository, linked by its GitHub URL — no other repository involved, live on the next
push; kewang chose spekterm.com.

### D6. The listing and form answers live in `play/`, in Gradle Play Publisher's layout

```
play/
  listings/en-US/{title.txt, short-description.txt, full-description.txt}
  listings/en-US/graphics/{icon/icon.png, feature-graphic/feature.png, phone-screenshots/01.png …}
  listings/zh-TW/…same…
  source/{icon.svg, feature.html}     — what the PNGs are rendered from
  forms.md                            — every Play Console form's answers
```

This is Gradle Play Publisher's layout (`listings/<locale>/…`, `graphics/{icon,feature-graphic,phone-screenshots}`), rooted at
`play/` rather than its default `app/src/main/play/`; automating later (Non-Goals) is one `git mv`. Lengths are Play's limits —
title ≤ 30, short description ≤ 80, full description ≤ 4000 characters — checked by a JVM test `PlayListingTest`; the unit test task
declares `play/listings`, `src/main/res` and `src/main/java` as inputs (`inputs.dir`, project-relative), so editing a listing,
an existing string or a comment reruns the tests that read them (`PlayListingTest`, `NetworkHostsTest`, `NoClassNamesTest`, and
the existing `StringsTest`, which had the same gap). Title: "spekterm" in both locales (the brand is lowercase). The icon (512 × 512, 32-bit PNG) is the
desktop's `build/icon.svg` (copied to `play/source/`); the feature graphic (1024 × 500, 24-bit, one per locale, as its line of
text is localized) is `play/source/feature.html`; `tools/render-play-graphics.sh` rasterizes both with headless Google Chrome
(`--screenshot --window-size`), already on this machine, and strips alpha from the feature graphic with ImageMagick's `convert`.

Screenshots (2–8 per locale) are 1080 × 1920 (9:16): Play refuses one whose long side is more than twice its short side, which
the `Pixel_9`'s 1080 × 2424 is, and 9:16 at 1080 wide is what its promotional formats ask for. The emulator's display is set
with `adb shell wm size 1080x1920` for the session and reset after; status bar demo mode fixes the clock and battery; PNGs are
written without alpha. They show the session list with states, a conversation with Markdown and a tool call, a prompt being
answered, the inbox, OpenSpec — and not Settings, which lists the desktop's addresses. They are taken against a **demo
desktop**: a dev spekterm started with a throwaway profile (`XDG_CONFIG_HOME=<scratchpad>/demo-config`, remote port 47617)
over a workspace of throwaway repositories, paired by a link whose addresses are cut to `10.0.2.2` and whose desktop name
(`n=`, shown in the session list's top bar) is set to `demo` before pasting. Nothing
on them may name a real repository, session, person, host or address. Starting that desktop needs kewang's OK (a second
spekterm instance next to the installed one and the dev one), and its agents run Claude Code on kewang's account.

### D7. Data safety: nothing collected, nothing shared

Every transfer leaves the phone only to the user's own desktop, inside TLS pinned to that desktop's leaf certificate
(`net/Pinning.kt`), with no intermediary: unreadable by the developer or anyone but the two ends — Play's end-to-end exemption
from "collected". Handing the log to another app through the share sheet is the user giving a file to another app on the
phone, not this app sending it. So "Does your app collect or share any of the required user data types?" → **No**. `forms.md`
records the reasoning with the help page's sentences, and the fallback should Play question it: declare Messages, Photos and
Files and docs as collected, encrypted in transit, not shared, not optional for the feature, and add that to the privacy policy.
The `play-distribution` requirement on connections is what keeps the answer true; a change that adds another host revisits it.

### D8. Foreground service: keep `specialUse`, tighten the subtype text, script the video

The decision of `docs/spike-findings.md` §3 stands; nothing found since changes it. The manifest's subtype text is rewritten to
match the declaration's three answers, one sentence each (what it does, why no other type or push fits, how the user stops it).
`forms.md` carries the declaration from the spike findings, updated with the current features (the inbox, prompts), and the
video's script: pair, the ongoing notification, an agent on the desktop waiting, the phone's notification, Pause from the
ongoing notification ends the service. It is recorded on the emulator against the demo desktop (D6) with `adb emu screenrecord`
(no 180-second limit, unlike `adb shell screenrecord`) — not on the Pixel, whose notifications are kewang's — and kewang uploads it as an unlisted YouTube video, since
Play takes a link.

### D9. App access: written instructions and the video; no demo mode

A reviewer cannot pair without a spekterm desktop, and seeing an agent work also needs Claude Code with an Anthropic account.
`forms.md` gives Play's "App access" answer: all functionality needs the user's own computer running spekterm with remote
access (and an AI coding agent such as Claude Code there); how to install spekterm 0.2.14 or later on Linux or an Apple silicon Mac and pair; and the video (D8) as the main evidence of
the app at work. No credentials of the app exist to hand over.

*Alternative:* a demo mode in the app (canned sessions without a desktop) — reviewers could see every screen, but it is a
second, fake data path in every screen, and its own review risk ("functionality not as described"). A public demo desktop —
it would run agents on kewang's machine for strangers; rejected outright. If review rejects for app access, a demo mode becomes
its own change.

### D10. README and the desktop the listing names

README gains "Install" (Google Play link once live) and "Privacy" (linking the spekterm.com page); "Status" stops saying nothing is
published once the internal test is live. The listing, README and the App access answer name **spekterm 0.2.14 or later, on
Linux or an Apple silicon Mac** (its only builds; no Windows, no Intel Mac) as the desktop to pair with — the first public release with remote access, and newer than every feature's minimum (0.2.13) —
linking to its GitHub releases.

### D11. Play's AI-generated content policy: out of scope, said so; "Report a problem" as a side channel

The policy covers apps that *generate* content with AI. This app runs no model and has no server; what it shows is written by the
user's own agent on the user's own computer, under the agent provider's terms. `forms.md` answers the policy questions ("Does
your app generate content using AI?") with **No** and this reasoning. The policy's remedy — reporting "without needing to exit
the app" — is not possible without a server anyway: any report leaves the app (a browser, a mail app).

As a side channel, not as compliance, the conversation screen's menu gains **Report a problem**: a dialog saying that agent
output comes from the user's own agent and is not seen by the developer, with **Report** opening the repository's new-issue page
in the browser. Nothing from the conversation is attached or sent. kewang may drop it at review; it is one menu entry.

### D12. Lint joins `check`

`checkLicenses` hangs on `check`, and AGP's `check` runs `lint`, which this project never ran: it stops on one error today,
`Replies.kt:152`, a literal U+FEFF in a character literal (`ByteOrderMark`). It becomes `'\uFEFF'`, the same character. Lint's
13 warnings stay warnings; no baseline, no `abortOnError` change. `bundleRelease` runs only `lintVitalRelease`, unaffected.

## Risks / Trade-offs

- [Play rejects `specialUse`] → the spike's second choice is `connectedDevice` (needs `CHANGE_NETWORK_STATE`, fits LAN better
  than Tailscale); a rejection names its reason, and switching is a small change of its own, re-run on the phone.
- [Review rejects for app access] → D9's instructions and video; a demo mode as its own change.
- [Review treats the app as a generative AI app] → D11's reasoning first; if Play holds to it, an in-app remedy (for example
  hiding a message on the phone with a note) becomes its own change, since a report that stays inside the app needs a server.
- [R8 breaks something the walk does not reach] → the walk covers every screen; `profile` stays shrunk so kewang's daily use is
  on the shrunk code; Play's pre-launch report (it crawls the app on real devices) is read after the internal upload.
- [The Data safety "No" is questioned] → D7's fallback declaration.
- [Upload key lost] → upload key reset through Play Console (days, not the app); backup in the password manager.
- [The privacy page is not live when Play asks for its URL] → the internal test can be set up and uploaded first; the URL is
  needed before the app's content is submitted, and the handoff is sent early in apply (task 3.1).
- [Push to spekterm's master deploys the site] → that is the spekterm session's step, done under its own rules and kewang's OK.
- [Screenshots go stale as screens change] → retaken when a change alters a pictured screen; `docs/play-release.md` lists it in
  the per-release steps.
- [On the Pixel, `profile` blocks `run-as`] → between runs, debug is installed over it to read the log (D3), and
  `docs/play-release.md` notes that `run-as` edits (such as writing `theme.json`) need the debug build.

## Migration Plan

Nothing on users' phones yet. The Play build is signed by Play's app signing key, not the debug key, so installing it over a
debug or profile build fails: kewang uninstalls first (losing the pairing, re-paired after) only when switching the Pixel to the
Play build, and `docs/play-release.md` says so. Rollback of a bad release: halt the rollout in Play Console and upload a fixed
build with a higher `versionCode`.
