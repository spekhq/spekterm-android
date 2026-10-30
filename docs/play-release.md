# Releasing on Google Play

How spekterm for Android is published, first and every later time (`play-release` change). Every step says who does it:

- 【我】 — an agent session in this repository (Claude), on this machine;
- 【桌機】 — the maintainer at the desktop computer (Play Console in a browser, the terminal, the key);
- 【手機】 — the maintainer on the phone.

Play Console's labels are as the Console showed them in 2026-10; where a label has moved, follow the step's meaning.
Answers for every form are in [`play/forms.md`](../play/forms.md); listing texts and images in [`play/listings/`](../play/listings).
What was measured and tried is in [`docs/release-findings.md`](release-findings.md).

## Once: the upload key

1. 【桌機】 Generate the upload key — a PKCS12 file with one password (PKCS12 cannot give the key a password of its own):

   ```sh
   keytool -genkeypair -storetype PKCS12 -keystore ~/.android/spekterm-upload.p12 -alias upload \
     -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=spekterm for Android"
   ```

   keytool asks for the password; type it there only — never into a chat, an issue or a file in a repository.
2. 【桌機】 Put the three properties in `~/.gradle/gradle.properties` (outside the repository; `storeFile` an absolute path,
   since `~` is not expanded):

   ```properties
   spekterm.upload.storeFile=/home/<you>/.android/spekterm-upload.p12
   spekterm.upload.password=<the password>
   spekterm.upload.keyAlias=upload
   ```
3. 【桌機】 Back up the `.p12` file and its password in the password manager. Losing them costs an upload key reset through
   Play support (days), not the app: Play App Signing holds the key that signs what users install.

## Once: creating the app

4. 【桌機】 Play Console → **Create app**: name `spekterm`, default language English (United States) – en-US, **App**,
   **Free**, accept the declarations.
5. 【桌機】 **Store settings**: category and contact details from `play/forms.md` → Store settings.
6. 【桌機】 **Main store listing** (en-US): app name, short and full description from `play/listings/en-US/*.txt`; app icon
   `play/listings/en-US/graphics/icon/icon.png`; feature graphic `…/feature-graphic/feature.png`; phone screenshots
   `…/phone-screenshots/*.png` in their order. Then **Add translation** → Chinese (Traditional) – zh-TW, the same from
   `play/listings/zh-TW/`.
7. 【桌機】 **App content** (Policy → App content), each from `play/forms.md`: privacy policy URL; app access; ads;
   advertising ID; content rating (the questionnaire, then apply the rating); target audience and content; data safety
   (answer, preview, submit); government apps; financial features; health apps; news apps; any generative AI question;
   **foreground service permissions** (special use: the two answers and the video link).
8. 【桌機】 Upload the foreground service video (the file an agent recorded for step 7, path given in its report) to YouTube as
   **Unlisted**, and paste its link into step 7's foreground service declaration and app access answer.

## Once: the first build, to internal testing

9. 【我】 Check the version in `app/build.gradle.kts` (`0.1.0`, code `1` the first time), then build:

   ```sh
   ./gradlew check bundleRelease     # app/build/outputs/bundle/release/app-release.aab
   ```

   `bundleRelease` stops at once, naming what is missing, if step 2 was not done.
10. 【桌機】 **Test and release → Testing → Internal testing** → **Create new release**. The first upload asks to use
    **Play App Signing** — choose Google-generated key (the default). Upload `app-release.aab`, release name `0.1.0 (1)`,
    release notes from the commits since the previous tag, **Save**, **Review release**, **Start rollout to Internal
    testing**.
11. 【我】 Tag the commit the bundle was built from: `git tag v0.1.0 && git push origin v0.1.0` (pushing only with the
    maintainer's OK).
12. 【桌機】 Internal testing → **Testers**: create an email list with the maintainer's Google account; copy the **opt-in
    link**.
13. 【手機】 Uninstall the debug build first (a Play build is signed by Play's key, not the debug key, so it cannot install over
    it; the pairing is lost and paired again after). Open the opt-in link, accept, install spekterm from Play, and pair with
    the desktop again (Settings → Remote access → Pair a device on the desktop, Scan QR code on the phone).
14. 【桌機】 After an hour or so: **Test and release → Pre-launch report** (Play runs the app on real devices; without a
    desktop it stays on the pairing screen). Read its crashes, accessibility and security findings; a crash there is fixed
    before production.

    From here on, the phone runs Play's build, so `adb shell run-as` (reading the log, writing `theme.json`) needs the debug
    build installed again — which needs uninstalling the Play build first.

## Once: production

15. 【桌機】 **Test and release → Production** → **Countries / regions**: add the countries to offer it in.
16. 【桌機】 **Production → Create new release** → **Add from library**: the bundle already in internal testing; the same
    release notes; **Review release** → **Send for review** (Publishing overview → **Send changes for review** if asked).
    Review takes from hours to days; a rejection names its policy and reason (`play-release` design, Risks).

## Once: publishing through the API

Every release is uploaded and released with `tools/play-publish.mjs` through the Google Play Developer API
(`play-api-upload`), so no browser limits the bundle's size. It needs a service account with a key, set up once:

17. 【桌機】 [Google Cloud console](https://console.cloud.google.com), signed in as the account that owns the developer
    account: pick a project of your own (or create one) and enable **Google Play Android Developer API** (APIs & Services →
    Library).
18. 【桌機】 IAM & Admin → **Service accounts** → **Create service account**: name `play-publisher`, no roles (Play Console
    grants its access).
19. 【桌機】 The account → **Keys** → **Add key** → **Create new key** → JSON. Save it as
    `~/.android/spekterm-play-publisher.json` — or move it there from the download folder, so no copy stays behind — and
    `chmod 600` it. Back it up in the password manager. Never paste its contents into a chat, an issue or a file in a
    repository.
20. 【桌機】 Play Console, developer account of the app → **Users and permissions** → **Invite new users**: the service
    account's e-mail; **App permissions** → add spekterm → **Release to production, exclude devices, and use Play App
    Signing** and **Release apps to testing tracks** (View app information comes ticked) → **Invite user**.
21. 【我】 `node tools/play-publish.mjs check`: it prints the account's e-mail and the internal and production releases. A
    403 right after inviting the account can mean its access has not taken effect yet: try again in a few hours.

A leaked key: delete it on the service account's Keys page (it stops working at once) and create another.

## Every later release

1. 【我】 Raise the version in `app/build.gradle.kts`: `versionCode` by one, and a `versionName` never uploaded before —
   before 1.0, a new feature raises MINOR, anything else PATCH, a rebuild after a rejected review included.
2. 【我】 If a change altered a screen in `play/listings/*/graphics/phone-screenshots/`, retake those screenshots (the
   `play-release` design, D6, says how); if a change altered what the app sends or to whom, update the privacy policy on
   spekterm.com (handoff to the spekterm repository) and `play/forms.md` first. Write the release notes for users into
   `play/release-notes/en-US/default.txt` and `play/release-notes/zh-TW/default.txt` (at most 500 characters each).
3. 【我】 `./gradlew check bundleRelease` (it checks the notes' length); use the profile build on the emulator and, with the
   maintainer's OK, on the phone. Commit the release with the notes.
4. 【我】 `node tools/play-publish.mjs check`, then `node tools/play-publish.mjs internal`: the bundle goes to internal
   testing as `<versionName> (<versionCode>)` with the notes (`--same-notes` when they stay as they were, as for a rebuild
   after a rejected review). 【手機】 The maintainer updates from Play and tries it. After the maintainer's OK,
   【我】 `node tools/play-publish.mjs production --version-code <versionCode>` sends the same release to review, and
   `check` shows it in review. `tools/README.md` says what each refusal means. When the API cannot do it, 【桌機】 Internal
   testing → **Create new release** → upload, notes, roll out; then the same bundle to Production as in step 16.
5. 【我】 `git tag v<versionName>` on the commit built from; push with the maintainer's OK. A tag never moves.
6. 【桌機】 A bad release: Production → **Halt rollout**, then a fixed build with a higher `versionCode`.
