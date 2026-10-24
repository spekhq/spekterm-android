---
name: play-release
description: Release a new version of spekterm for Android on Google Play — version bump, checks, bundle, internal testing, production review, tag. Use when kewang asks to release, publish, ship or "發新版" / "上 Google Play".
---

# Releasing spekterm for Android on Google Play

The reference is [`docs/play-release.md`](../../../docs/play-release.md) ("Every later release"); this is the run
sheet an agent follows, with what was learned releasing 0.2.0 (2026-10-24). Talk to kewang in 繁體中文; label every
step for them by device (【手機】【桌機】【我】). Stop and ask only where this sheet says so.

## 1. Before building 【我】

1. `git status` clean and `git log origin/master..HEAD` empty, or ask to push first. Note the last tag
   (`git tag --sort=-creatordate | head -1`) and the commits since it (`git log --format='%h %s' <tag>..HEAD`).
2. Version in `app/build.gradle.kts`: `versionCode` + 1; `versionName` never uploaded before. Before 1.0: a new
   feature since the last tag raises MINOR (`0.1.0` → `0.2.0`), anything else PATCH; a rebuild after a rejected review
   is a new PATCH.
3. Listing screenshots (`play/listings/*/graphics/phone-screenshots/`): read each one (Read tool) and compare with what
   the release changed. Only a real change in what a screen shows needs a retake (`play-release` design D6 says how);
   a small wording change is kewang's call — ask, recommending to skip.
4. Privacy: if the release changes what the app sends, to whom, or what it stores, update `play/forms.md` (Data
   safety reasoning) and, if the answer itself changes, the privacy policy on spekterm.com by handoff to the spekterm
   repository. Data that only goes to the user's own computer, or to an app the user picks, is not collected.
5. Desktop dependency: if the release needs a spekterm desktop feature, check that a spekterm release carries it
   (`git -C ~/git/spekterm tag --contains <commit>`) and name that version in `README.md`'s "These need spekterm …"
   paragraph. Unreleased on the desktop → tell kewang before going on.

## 2. Build and check 【我】

```sh
./gradlew check bundleRelease assembleProfile
# app/build/outputs/bundle/release/app-release.aab, app/build/outputs/apk/profile/app-profile.apk
```

`bundleRelease` stops at once if the upload key properties are missing from `~/.gradle/gradle.properties`: that is
kewang's step (docs/play-release.md, "Once: the upload key"); never ask for the password.

Smoke-test the shrunk build: boot the `Pixel_9` AVD, `adb install -r` the profile APK, launch it, check
`adb logcat -b crash` is empty and screenshot the first screen. The Pixel usually runs the Play build (installer
`com.android.vending`), which a profile or debug APK cannot install over without uninstalling and losing the pairing —
so test the real build on the Pixel through internal testing (step 3), not by sideloading.

Commit `chore(release): <versionName> (<versionCode>)` (stage explicit paths). The tag goes on this commit.

## 3. Upload — internal testing first, then production

kewang may ask the agent to do the Play Console steps. Then use Claude in Chrome (load the `chrome-browser` skill;
one ToolSearch with `tabs_context_mcp, navigate, computer, find, file_upload, form_input, get_page_text,
tabs_create_mcp, tabs_close_mcp`). Otherwise give kewang the same steps, labelled 【桌機】.

1. `https://play.google.com/console` → developer account **kewang** (not Funliday) → app **spekterm**.
2. **Test and release → Testing → Internal testing → Create new release** (內部測試 → 建立新版本).
3. Upload: `find` the file input in "Upload app bundle" (上傳應用程式套件) and `file_upload` the `.aab` — never click
   the upload button (a native file picker opens). The tool takes at most 10 MB per call; 0.2.0's bundle was about
   10.2 MB decimal and went through. If a bundle is refused for size, ask kewang to drag it in.
4. Wait for processing; the table shows `<versionCode> (<versionName>)`.
5. Release name (版本名稱): Play fills `2 (0.2.0)`; set `<versionName> (<versionCode>)`, e.g. `0.2.0 (2)`.
6. Release notes (版本資訊): one block per language, each ≤ 500 characters, written from the commits since the last tag
   for users (what they can now do), not for developers:

   ```
   <en-US>
   - …
   </en-US>
   <zh-TW>
   - …（台灣用語）
   </zh-TW>
   ```
7. **Next** (下一步) → review. Known warning, not blocking: the bundle has native code without uploaded debug
   symbols. Anything else (an error, a new warning): stop and show kewang.
8. **Save and publish** (儲存並發布) → confirm. Internal testers get it within minutes.
9. 【手機】kewang updates spekterm from the Play Store and tries the release's features. Confirm with
   `adb shell dumpsys package com.spekterm.android` (versionCode, `installerPackageName=com.android.vending`) and,
   when the app is a debug-signed build instead, the connection log via `run-as`. **Wait for kewang's OK.**
10. **Production → Create new release → Add from library** (從檔案庫新增): tick the same bundle → **Add to release**;
    the same release name and notes → **Next** → **Save** → **Go to Publishing overview** (前往總覽頁面).
11. Publishing overview: **Send N changes for review** (送審) → confirm. Managed publishing is off, so the release goes
    live by itself once approved; review takes hours to days. A rejection names its policy: fix, new PATCH version.
12. Close the tabs you opened.

## 4. After 【我】

1. `git tag v<versionName> <release commit>` — a tag never moves.
2. Push the commits and the tag **only with kewang's OK** (`git push origin master`, `git push origin v<versionName>`).
3. Report in three parts (卡在你 / 做了什麼 / 發現): the review status, what was uploaded where, Play's warnings and
   suggestions. Play Console's "suggested actions" on the production page (library updates, edge-to-edge, deprecated
   APIs) are follow-ups for a change of their own, not blockers.

A bad release: Production → **Halt rollout**, then a fixed build with a higher `versionCode`.
