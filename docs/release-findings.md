# Release build findings

What the `play-release` change found when building, measuring and using the shrunk release build. Dates are 2026-10-19
unless noted; "profile" is the release build's R8 output signed with the debug key (`app/build.gradle.kts`).

## Building and signing (1.1, 1.2)

- Without the upload key's properties, `./gradlew bundleRelease` fails in 4 s at `:app:checkUploadKey`, before any compile
  or R8 task, with: *The upload key is not configured. Missing Gradle properties: spekterm.upload.storeFile,
  spekterm.upload.password, spekterm.upload.keyAlias. Set them in ~/.gradle/gradle.properties (see docs/play-release.md).*
  The same with the configuration cache stored and then reused. `./gradlew assembleDebug assembleProfile` passes without them.
- With a throwaway PKCS12 key generated in the session's scratchpad (RSA 4096, 30 years, alias `upload`) passed as `-P`,
  `bundleRelease` passes; `jarsigner -verify -verbose -certs` on `app-release.aab`: *jar verified*, signed by `CN=throwaway`.
  Run again: *Configuration cache entry reused*, `BUILD SUCCESSFUL`. `git status` showed no key or password file.
- R8 printed no warning for `minifyProfileWithR8` or `minifyReleaseWithR8`.
- One rule turned out to be needed (`app/proguard-rules.pro`): the log names exceptions by class
  (`ConnectionService.kt`, three places; `PairScreen.kt`, one), and R8 renames library exceptions (OkHttp's,
  kotlinx.serialization's), so `-keepnames class * extends java.lang.Throwable` keeps those names; they now go through
  `Throwable.logName()`. `NoClassNamesTest` found these four beside the two outcome names the design named.

## The bundle (1.3)

| | Size |
|---|---|
| Profile APK, unshrunk, at 3591c8b | 43.4 MB (43 378 657 bytes) |
| Profile APK, shrunk (this change) | 12.2 MB (12 204 413 bytes) |
| Release bundle (`app-release.aab`) | 10.2 MB |

APK sizes, universal (four ABIs): bundletool is not installed, so per-device download sizes were not measured; Play Console
shows them after upload.

- The bundle holds `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map` (61 MB).
- 16 KB pages: every `LOAD` segment of all 16 `.so` in the bundle (`libzxingcpp_android`, `libimage_processing_util_jni`,
  `libsurface_util_jni`, `libandroidx.graphics.path`, four ABIs each) is aligned to `0x4000`, and
  `zipalign -c -P 16 -v 4` on the profile APK reports each `(OK)`, *Verification successful*.

## Lint (1.5)

`./gradlew lintDebug` passes after `Replies.kt:152`'s literal U+FEFF became `'\uFEFF'` (it was the one error,
`ByteOrderMark`). Warnings left as they are: `PluralsCandidate` ×5, `UseKtx` ×3, `ExifInterface` ×2,
`ConfigurationScreenWidthHeight`, `ModifierParameter`, `OldTargetApi` (API 37 exists; Play requires 36).

## Licenses (2.3)

The release runtime classpath resolves to 192 `group:artifact`. Their POMs (or parent POMs, for Guava) name: Apache License
2.0 for 190 (in five spellings), MIT for `org.checkerframework:checker-qual`, and Apache 2.0 with BSD-3-Clause for
`androidx.camera:camera-core` (it includes libyuv). Shipped NOTICE-type files found in the artifacts:
`androidx.concurrent:concurrent-futures-ktx` (`META-INF/NOTICE.txt`, kotlinx.coroutines' notice), and OkHttp 5's Android
artifact ships `assets/PublicSuffixDatabase.list`, compiled from the Public Suffix List under the Mozilla Public License
2.0 — so the licenses file carries the Apache 2.0, MIT, BSD 3-Clause and MPL 2.0 texts. `checkLicenses`: green; with
Okio's entry removed it fails naming `com.squareup.okio:okio` and `okio-jvm`, then runs again (not `UP-TO-DATE`) once the
file is restored; the configuration cache is stored, then reused. In a scratch copy with
`com.google.firebase:firebase-analytics:22.1.2` added (downloaded for this), it fails listing Firebase and the
`play-services-measurement*` / `play-services-ads-identifier` artifacts under "Analytics, advertising or crash-reporting
libraries", apart from the missing entries.

## Tests that read files (2.6, 2.8, 3.2)

After the test task declared `play/listings`, `src/main/res` and `src/main/java` as inputs, `testDebugUnitTest` ran again
(not `UP-TO-DATE`) after each of: an 81-character short description (`PlayListingTest` red, without `--rerun`), an existing
string's text changed (`pair_or`), and a comment added to `App.kt`. `NetworkHostsTest` went red when `pair_or`'s text held
`https://example.com/x`, naming it; `NoClassNamesTest` went red on a scratch `String::class.java.simpleName`, naming
`App.kt:84`. All restored.

## About, Licenses, Report a problem (2.4, 2.5)

`AboutTest` on the `Pixel_9` emulator (the Pixel disconnected from adb first): 6 of 6 pass — the installed version name and
code shown; Licenses scrolls to the MPL text and the Notices heading; on the unpaired pairing screen, typed text survives
About and back; no About button while pairing (a link to 192.0.2.1, which never answers) or when pairing again; Report starts
exactly one `ACTION_VIEW` for `https://github.com/spekhq/spekterm-android/issues/new`, caught so nothing opens. (An
`ActivityMonitor` built with a filter never calls `onStartActivity`; the no-argument one does.)

## The whole suites (4.3)

`./gradlew test check assembleDebug assembleProfile`: `BUILD SUCCESSFUL` (JVM tests, lint, `checkLicenses`). With the Pixel
disconnected from adb and only `emulator-5554` listed, `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`:
`BUILD SUCCESSFUL`, 103 tests, 102 passed and `DesktopRaceTest.droppedAttemptsAreNotCountedAsFailures` skipped by its
assumption (it needs a desktop's addresses as runner arguments). The Pixel was reconnected afterwards.

## Screenshots and the video (3.4)

Taken on the `Pixel_9` emulator at `wm size 1080x1920` with status bar demo mode (9:30, full Wi-Fi and battery), the profile
build paired with a demo desktop: spekterm run from source with its own profile in the session's scratchpad, remote port
47617, three throwaway repositories (`weather-app` with an OpenSpec change, `notes-api`, `landing-page`), paired by a link
cut to `10.0.2.2` with `n=demo`. Five screens per locale in `play/listings/<locale>/graphics/phone-screenshots/`: the session
list (idle, working, waiting, a handoff, the inbox count), a conversation (Markdown, a tool call), a question being answered,
the inbox with an item from "#weather-team", an OpenSpec change's tasks. Each is 1080 × 1920 RGB without alpha and was
looked at. Not used: screens showing a tool call or an attachment message with a path in the scratchpad (it holds the
maintainer's user name), and Settings (it lists the desktop's addresses). `tools/drop-intake.mjs` gained `--origin` for the
inbox item's source. The agents were asked to answer in English, since the maintainer's own Claude Code settings apply to them.

The foreground service video (`play/forms.md`, Video script) is 75 s, recorded with `adb emu screenrecord`; a first take was
discarded because the opened conversation showed such a path. It shows the desktop's private LAN address in the ongoing
notification.

## The shrunk build on the emulator (4.1)

The profile build (R8) on the emulator against the demo desktop, 2026-10-19 21:34–22:00 +08:00, each through the app:

| Step | Outcome |
|---|---|
| Pairing by a link (an expired one first: "The code is no longer valid…", then a fresh one) | pass |
| Session list, live states, a handoff's relation, the inbox count | pass |
| Wake a dormant session | pass |
| A conversation with Markdown (lists, code, a table) and tool calls | pass |
| A reply with a photo (photo picker) and a PDF (file picker) | pass — `send outcome=Submitted attachments=2`; the agent read both |
| A question answered (`AskUserQuestion`) | pass — `answer kind=question decision=answer outcome=Done` |
| OpenSpec: folder, change, tasks | pass |
| Inbox: dismiss (asked first), accept into a chosen folder, send the first prompt | pass — `inbox op=ignore`, `op=accept outcome=accepted`, `op=submit outcome=confirmed` |
| New session from the phone | pass — `create outcome=created` |
| Notifications: waiting, intake, handoff; a tap opens the conversation | pass |
| Settings: pause and resume; About; Privacy policy opens spekterm.com in Chrome; Licenses | pass |
| Pair again (the offer says "Pair again with demo?"), then unpair (asked first) | pass — the service stops |

The connection log, read with `run-as` after the debug build was installed over the profile build at 22:00:52 (+08:00), has
134 lines, all written by the profile build; every outcome is named as in a debug build (`outcome=Submitted`, `Done`,
`accepted`, `confirmed`, `created`); no error, no exception, no decoding failure. The per-UID socket sample (every second over
`/proc/net/tcp*` and `udp*`, IPv4-mapped addresses normalized) saw one remote address for the app's UID across the session:
`192.168.0.199:47617`, the desktop's own address, which it announces after pairing (in the 18:25 run, `192.168.50.22:47617` on
another network). The pairing attempts to `10.0.2.2` were too short for the one-second sample; the log records them. Chrome,
opened by Privacy policy, runs under its own UID.

Found on the way, not changed here: the inbox shows an item's time in the English format under 中文（台灣）
("Oct 19, 2026 9:46 PM"); the pairing screen's first step says "Pair device" where the desktop's button reads "Pair a
device".

## In progress (2026-10-19 evening)

- 4.2: the profile build was installed on the Pixel at 18:24:36 +08:00 over the debug build (`adb install -r`, pairing kept;
  the pairing saved first). The service came up in the foreground and reconnected to the installed desktop over Tailscale.
  Waiting for a day of real use; then the debug build goes back over it and the log's lines from 18:24:36 up to the
  reinstall are read.
