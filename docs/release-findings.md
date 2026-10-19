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

`./gradlew lintDebug` passes after `Replies.kt:152`'s literal U+FEFF became `'﻿'` (it was the one error,
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
