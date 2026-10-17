## Why

Three things get in the way of using the app, each found in use:

- **The pairing QR code is hard to scan.** The camera has to be held close and square to the desktop's QR code
  before it reads. The desktop draws a dense code (version 13 for the maintainer's five addresses: 73 modules) at
  192 CSS pixels with a two-module border, and the app decodes it from CameraX's default 640×480 analysis frames
  with ZXing's Java reader, without tap-to-focus or zoom.
- **When the desktop cannot be reached, the app does not say why.** Off the desktop's network, the phone's status
  said only "Waiting to retry" while it failed 28 times in six minutes (`docs/spike-findings.md`, leaving the
  office Wi-Fi). Addresses are tried one at a time with a 5 s connect timeout each, so an unreachable LAN address
  delays the Tailscale one that works by 5 s on every reconnection.
- **The app is English only.** Its maintainer and its first users read Traditional Chinese, and the desktop
  already ships a zh-TW translation; `ui-polish` left localization to a later change.

## What Changes

- **Scanning** decodes with zxing-cpp at a higher analysis resolution, with tap-to-focus and pinch-to-zoom on the
  preview, reading rotated, inverted and small codes. A QR code that is not a spekterm pairing link is ignored and
  the scanner keeps looking, saying what it saw.
- **Pairing links from other apps**: the app opens `spekterm://pair?…` links, so the phone's own camera or a
  scanner app can read the code and hand it over. Such a link never pairs on its own: the app shows the name and
  addresses the link gives and pairs only when the user confirms; while paired it asks over the current screen and
  says whether the link is for the same desktop (same fingerprint) or a different one.
- **Addresses are tried in parallel, staggered**: a new attempt starts every 250 ms (or at once when the previous
  one fails) while earlier ones continue; the first pinned handshake wins and the others are closed before any
  frame is sent. The order of starts is today's order. Pairing tries the link's addresses the same way.
- **Saying why the desktop cannot be reached**: after a round in which no address connects, the status screen and
  the ongoing notification say why in plain words, naming a cause only on direct evidence — no network; an address
  that refused on the port (spekterm not running or remote access off, if that address is the desktop); on mobile
  data while the desktop's addresses are local, Tailscale or global IPv6 and no VPN is on; no answer
  at all (asleep, off, another network or a firewall) — and the status screen lists every address with its outcome.
  This replaces "remote access is off, or the desktop quit" once a later round fails. Pairing's "could not reach"
  message says the same. "Try now" on the status screen ends the wait for the next round.
- **Traditional Chinese (Taiwan)**: every text the app itself shows — screens, dialogs, notices, notifications and
  their channels — has a zh-TW translation, shown when the phone's or the app's language is Chinese (Traditional);
  English stays the default. The terms follow the desktop's zh-TW translation. What comes from the desktop or a
  repository (session names, agent text, prompts, OpenSpec files, the desktop's sentences) is shown as sent, and
  the connection log stays English. On Android 13+ the language can be chosen per app in the system settings.

## Capabilities

### New Capabilities

- `app-language`: the app's own texts in English or Traditional Chinese (Taiwan), following the phone's or the
  app's language, with what the desktop sends shown as sent.

### Modified Capabilities

- `device-pairing`: scanning reads denser, smaller and rotated codes with focus and zoom, and skips QR codes that
  are not pairing links; a pairing link opened from another app pairs only after the user confirms; pairing tries
  the link's addresses staggered in parallel and says per address why none could be reached.
- `desktop-connection`: addresses are tried staggered in parallel, the first pinned handshake winning; when no
  address connects, the status screen and the ongoing notification say why and list each address's outcome, and
  that replaces the 4005 "remote access is off" state once a later round fails; "Try now" ends the wait between
  rounds.

## Impact

- `ui`: `QrScanner` (rewritten over `LifecycleCameraController` and zxing-cpp), `PairScreen` (confirmation for an
  opened link, the skipped-code hint, per-address reasons), `StatusScreen` (reason, per-address outcomes,
  "Try now"), `MainActivity` (the pairing link intent). Every screen's texts move to string resources.
- `net` / `service` / `pairing`: a staggered race over addresses shared by `ConnectionService` and `Pairer`; a
  classification of failed attempts and of the phone's network; `Status` carries the round's outcomes.
- `desktop`, `attention`, `service`: texts built outside composables (handoff lines, menu notices, notifications)
  become resource references, resolved where they are shown.
- `res`: `values/strings.xml`, `values-zh-rTW/strings.xml`, a generated locale config; the manifest gains the
  `spekterm://pair` intent filter.
- Dependencies: `io.github.zxing-cpp:android` 3.1.1 (Apache-2.0, native, 16 KB-aligned, no network, no Google
  Play services) replaces `com.google.zxing:core` in the app; ZXing's Java encoder stays only in instrumented
  tests, to draw test codes. About 1.2–1.8 MB of native code per ABI.
- No protocol change and no desktop change. A larger QR code on the desktop would help further; that is
  spekterm's, not this change's.
- Tests: JVM tests for the race, the failure and network classification, the reason chosen, link-intent
  handling, string-resource references, both string files matching, and no hard-coded text left; instrumented tests
  (emulator only) decoding generated codes with both decoders and racing against a loopback desktop; emulator
  screenshots in both languages; use on the Pixel recorded in `docs/conversation-findings.md`.
