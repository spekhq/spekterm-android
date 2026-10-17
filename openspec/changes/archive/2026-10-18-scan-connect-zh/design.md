## Context

See proposal.md for why. Facts that shape the approach, checked on 2026-10-17:

- **The desktop's QR code** (spekterm `src/renderer/src/shell/remote/RemoteAccessDialog.tsx`, `QrCode`): `uqr`
  `encode(link, { ecc: 'M', border: 2 })`, drawn black on white at `h-48 w-48` (192 CSS px). The link carries every
  address the desktop lists (`reachableAddresses()`, spekterm `src/main/remote-service.ts` 69–80, which drops only
  link-local IPv6), so on the maintainer's desktop five: 192.168.0.199, two global IPv6 addresses, 100.106.81.33 and
  fd7a:115c:a1e0::6432:5121. Re-encoded with `uqr` as the desktop builds it (review, scratchpad `qr/qr.mjs`):
  319 bytes → version 13, 73 modules with the border, 2.63 CSS px per module. Three addresses would be version 10–11
  (61–65 modules); temporary IPv6 addresses push it to version 14 and up.
- **The scanner today** (`ui/QrScanner.kt`): `ProcessCameraProvider` with a `Preview` and an `ImageAnalysis`
  built with no resolution, so CameraX's default 640×480 analysis frames; ZXing Java `QRCodeReader` with
  `TRY_HARDER` on the Y plane; no tap-to-focus or zoom. The first decoded text, whatever it is, ends scanning. At
  480 px across, a code a fifth of the frame wide gets 96 px for 73 modules — 1.3 px per module, below what a reader
  resolves, so the user has to bring the phone close enough to fill most of the frame, where focus then struggles.
  (Rotation and inversion are not the problem: ZXing's QR detector is rotation-invariant and the desktop draws
  black on white.)
- **zxing-cpp's Android library** `io.github.zxing-cpp:android:3.1.1` (Maven Central `maven-metadata.xml`, last
  updated 2026-07-29): Apache-2.0 (its POM); native `libzxingcpp_android.so` for arm64-v8a (1.75 MB),
  armeabi-v7a (1.23 MB), x86, x86_64; every `LOAD` segment aligned to 0x4000 (16 KB pages, as Google Play requires;
  `readelf -lW`); its POM depends on `androidx.camera:camera-core:1.5.2` (the app's 1.6.2 wins resolution);
  `BarcodeReader(Options).read(ImageProxy)` returns `List<Result>`, honouring the crop rect and rotation, and throws
  `IllegalStateException` for a non-YUV image; `Options()` defaults `tryHarder`, `tryRotate`, `tryInvert` and
  `tryDownscale` to false; consumer ProGuard rule `-keep class zxingcpp.** { *; }`.
- **CameraX `camera-view` 1.6.2** (`javap`, review): `LifecycleCameraController` has
  `setImageAnalysisResolutionSelector`, `setImageAnalysisBackpressureStrategy`, `setEnabledUseCases`; tap-to-focus
  and pinch-to-zoom default to on; it uses the `PreviewView`'s viewport, so the analysis crop is the square the user
  sees and "a fraction of the preview's width" is well defined.
- **Attempts today** (`ConnectionService.round`, `Pairer.pair`): one address at a time, each bounded by
  `CONNECT_TIMEOUT_MS` 5 s (TCP) and `OPEN_TIMEOUT_MS` 10 s (the whole open). A round's failures are logged
  (`UNREACHABLE` with the exception's class) and dropped. `Status` holds `state`, `address`, `since`, `reconnects`,
  `lastClose`, `retryAt`, `remoteOff` and `recent`; `remoteOff` is set on close 4005 (`ConnectionService` 232) and
  cleared only on authentication (275). The status screen and the ongoing notification say "Waiting to retry".
  `Pairer` writes no log entry.
- **`RemoteClient.open` and cancellation** (`net/RemoteClient.kt` 66–86): an `Open` that arrives as the attempt is
  cancelled is closed in every interleaving (cancel first; resume then cancel; failure after cancel).
  `RemoteConnection.cancel()` drops the socket without a frame; `close()` sends a WebSocket close frame (1000).
- **The desktop's attempt accounting** (spekterm `src/main/remote-server.ts` 262–298 and 370–377, protocol
  section 4): only a connection that sends no `hello`/`pair` within 10 s, or fails authentication, counts as a failed
  attempt; closing a connection before then clears its timer and counts nothing. At most 16 unauthenticated
  connections; `TOO_MANY_CONNECTIONS` counts authenticated ones only. So connections opened and dropped without a
  frame cost nothing with the desktop.
- **The desktop quitting** sends 4005 (spekterm `index.ts` 695 `will-quit` → `remote-server.ts` 242), the same code
  as turning remote access off.
- **Network change**: `ConnectionService` already has a conflated `kick` channel that cuts a backoff wait short on
  a network change; `waitToRetry(throttled = true)` uses a plain `delay` that `kick` does not cut. The next backoff
  delay is drawn before the wait starts.
- **Texts**: about 400 English literals: in composables (`ui/*`), in helper composables (`Small`, `Section`,
  `TopBar`, `Step`, `SettingRow`), in constants (`CAMERA_DENIED`), in `when` branches outside composition
  (`desktop/Relations.kt`, `desktop/SessionActions.kt` 107–140, `desktop/OpenSpecText.kt` 32–40,
  `ui/PromptCard.kt` 106–235, `attention/AttentionNotifier.kt`, `service/Notifications.kt` including channel names
  and the "Pause" action, `ConnectionService`'s posted texts), many asserted verbatim by JVM tests (`RelationsTest`,
  `SessionActionsTest`, `OpenSpecTextTest`, `ConversationItemsTest`, …). No `strings.xml` exists; the manifest label
  is a literal. Main specs quote English texts in requirement bodies (`close-offer`, `app-appearance`).
- **The desktop's zh-TW** (spekterm `src/shared/i18n/zh-TW.json`, last changed in 01cb50a): keeps "session",
  "agent", "change", "spec", "artifact", "task", "port", "shell" in English inside Chinese text; 休眠 / 喚醒 / 休眠中,
  交接, 收件匣, 等你回應, 閒置, 遠端存取, 配對, 撤銷, 已封存, 進行中, 對話, 你, 思考中, 結果, 送出, 尚未確認.
- **`MainActivity`** handles intents in `handle()` only on a fresh launch (`savedInstanceState == null`, not from
  history) and in `onNewIntent`; it is `singleTop`; `configChanges` does not include `locale`, so a language change
  recreates it. `repairing = true` replaces the whole paired UI (`Paired()` and its `rememberNavController()`) with
  `PairScreen`, and `repairing` is not saved state.

## Goals / Non-Goals

**Goals:**

- The maintainer's real code (73 modules), a fifth of the preview wide, tilted and off center, reads within a
  second or two on the Pixel 7; pinch-to-zoom gets it there from farther away.
- A reconnection whose first address is dead costs about 250 ms, not 5 s.
- Every failed round leaves the user a sentence that says what is wrong and what to do, naming a cause only on
  direct evidence.
- The whole app's own text in zh-TW, in the desktop's words, with nothing missed and tests that keep it so.

**Non-Goals:**

- Changing the desktop's QR code (size, border, ECC, leaving global IPv6 out of the link). Recorded in the findings
  as a spekterm follow-up.
- Photo or gallery decoding inside the app; the system camera path (D4) covers "another way to read it".
- Retry pacing or battery tuning while unreachable for hours (the spike's open measurement), and reaching the
  desktop without Tailscale or a forward.
- Telling one Wi-Fi from another (SSIDs need location permission).
- Translating into any language but zh-TW; Simplified Chinese falls back to English.
- An in-app language picker (the system's per-app setting is the picker), and pre-13 per-app choice.
- Translating the README or the openspec artifacts (they stay English, by the repository's convention).

## Decisions

### D1. Decoder: zxing-cpp, QR only, rotate/invert/downscale on

`QrScanner` reads frames with `zxingcpp.BarcodeReader` configured with `formats = setOf(Format.QR_CODE)`,
`tryHarder = true`, `tryRotate = true`, `tryInvert = true`, `tryDownscale = true` (all off by default), defaults
otherwise. One reader per scanner, used only from the analysis executor. `com.google.zxing:core` leaves the app's
dependencies and becomes `androidTestImplementation`, to draw codes and to run the old path in D2's decode test.

Alternatives: **ML Kit barcode scanning, bundled** — reads well and needs no Play services, but it is closed source
under Google's ML Kit terms and may send usage metrics, against the app's "no service in between" stance (spike D8);
the unbundled variant needs Play services. **Keep ZXing Java at a higher resolution** — fixes most of the pixel
budget, but zxing-cpp is markedly more tolerant of small, blurred modules and fast enough native to keep up with
1080p frames, where the Java reader drops more of them. zxing-cpp is the maintained successor of the same
algorithms, open, and on-device.

### D2. Camera: `LifecycleCameraController`, 1080p analysis, focus and zoom by gesture

The scanner binds a `LifecycleCameraController` to the composition's lifecycle owner and sets it on the
`PreviewView` (`FILL_CENTER`, the existing square). Enabled use cases: image analysis only (preview is always on).
`imageAnalysisResolutionSelector`: `ResolutionStrategy(Size(1920, 1080), FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)`;
backpressure `STRATEGY_KEEP_ONLY_LATEST`; the default YUV output. Tap-to-focus and pinch-to-zoom stay on (the
controller's defaults); the camera is unbound when the scanner leaves composition, as today.

With the square viewport, the analysis crop is about 1080 px across; a fifth of it is 216 px for 73 modules, about
3 px per module (against 1.3 today).

Why the controller over today's `ProcessCameraProvider` binding: gestures, focus metering and zoom state come with
it instead of a hand-written touch listener and `CameraControl` calls.

**Measuring it** (`QrDecodeTest`, instrumented, emulator): ZXing's encoder draws links with five and with seven
addresses (the maintainer's real five; seven adds two temporary IPv6 addresses) at ECC M border 2, as the desktop
does. The axis is the code's share of the preview's width — 1/4, 1/5, 1/6, 1/8 — rendered into the square crop of
each path: 1080 px for the new, 480 px for the old; each share at 0°, 15° and 90°, sharp and with a 1 px box blur.
Each frame is decoded by the old path (ZXing Java, 480 px) and the new (zxing-cpp, 1080 px). The test asserts the
new path reads every five-address frame at 1/5 and larger, and prints the whole grid for both paths, which goes in
the findings. The Pixel run (tasks 8.1) is the real check.

### D3. What the scanner does with a decoded text

`QrScanner` reports every decoded text to the caller's `onText(text): Boolean` (return true to stop). `PairScreen`
returns true for text that `PairingLink.isPairingLink` accepts — trimmed, scheme `spekterm` and host `pair`, both
case-insensitive, as `parse` reads them — and hands it to the same `pair(raw)` as a paste, so a refused link ends
scanning with its refusal. Any other text sets a hint under the preview ("This QR code is not a spekterm pairing
code.") that clears 3 s after the last such read, and scanning continues. When a frame holds several codes, the
first pairing link among them wins.

### D4. Pairing links from other apps, confirmed first

The manifest gives `MainActivity` an intent filter: `ACTION_VIEW`, categories `DEFAULT` and `BROWSABLE`, scheme
`spekterm`, host `pair` (lower case, as the desktop writes it; intent matching is case-sensitive). `handle()` takes
such an intent into `offeredLink: String?`, kept in the saved instance state (as is `repairing` from now on, so a
recreation keeps both).

- **Unpaired**: `PairScreen(offered = …)` parses the link at once — a refused one shows its refusal; an accepted
  one shows a confirmation card inline (the name the link gives, addresses, port) with "Pair" and "Cancel".
- **Paired**: the question is an `AlertDialog` over whatever screen is showing, so the paired UI and its navigation
  stay composed and Cancel returns to exactly where the user was. The dialog compares the link's fingerprint with
  the pairing's: equal → "A new pairing code for <pairing's desktop name>. Pair again?"; different → "This link is
  for a different desktop (it calls itself <n>). Pairing replaces <pairing's desktop name>." The link's `n` is shown
  as what the link claims, never as proof of identity. "Pair" sets `repairing = true` and opens `PairScreen` with the
  link to start pairing at once (`PairScreen(start = link)`); a failure leaves the user on the re-pairing screen
  with the reason and "Back to settings", as re-pairing does today.
- **A link while a pairing runs** (`PairScreen` busy) is dropped with a notice ("A pairing is already in
  progress."); **while scanning**, scanning stops and the link is asked about.

Nothing is opened before "Pair". Why a confirmation: any web page or app can fire a `spekterm://` intent. Pairing on
arrival would let a page pair the phone with a server of its choosing and, while paired, replace the user's desktop;
the fingerprint comparison tells the user whether it is even the same desktop. Scanning inside the app stays one
step because the user pointed the camera at the code on purpose. Not Android App Links: there is no domain.

Whether the Pixel's camera app offers to open a `spekterm://` link from a QR code is not known; the Pixel run checks
it (tasks 8.2). A QR scanner app and links tapped in chat use the same path either way.

### D5. Staggered race over addresses

A new `net/AddressRace.kt`, generic so it is testable without sockets:

```kotlin
sealed interface Attempt<out T> {
    data class Opened<T>(val value: T) : Attempt<T>
    data class Failed(val outcome: OpenResult) : Attempt<Nothing>   // PinMismatch or Unreachable
}
suspend fun <T> race(
    addresses: List<String>,
    open: suspend (String) -> Attempt<T>,
    drop: (T) -> Unit,                       // RemoteConnection::cancel — no frame, not even a close frame
    stagger: Long = 250,
    onStart: (String) -> Unit = {},
    onOutcome: (String, OpenResult) -> Unit = { _, _ -> },
): RaceResult<T>   // winner: Pair<String, T>?, finished: Map<String, OpenResult>, notTried: List<String>
```

Inside a `coroutineScope`: attempt *i* starts when attempt *i − 1* started `stagger` ms ago, or at once when every
started attempt has finished without opening; `onStart` is called as each starts. Results go into a
`Channel(UNLIMITED, onUndeliveredElement = { (it as? Opened)?.let { o -> drop(o.value) } })`, read by the single
coroutine running `race`, which therefore calls `onStart`/`onOutcome` sequentially (the identity tracker is not
thread-safe). The first `Opened` is the winner: the other attempts are cancelled (`RemoteClient.open` drops an
`Open` that lands during cancellation), any `Opened` still in the channel is dropped as the channel is cancelled,
addresses not yet started are `notTried`, and `race` returns. Cancelled attempts report nothing to `onOutcome`.

`ConnectionService.round` and `Pairer.pair` both call `race` with `drop = RemoteConnection::cancel`; only the
winner's connection ever carries a frame, so `hello` or the code is sent once. `onStart` writes the `ATTEMPT` log
entry — for pairing too, with `source=pair` (`Pairer` gets an `onAttempt` callback from `PairScreen`, which has the
log). Status during a round is `CONNECTING` with `address = null` and the round's addresses; the screen says
"Connecting…" and marks each started address "trying" until its outcome arrives.

Why 250 ms: RFC 8305's recommended connection attempt delay. A LAN TLS handshake completes well within it, so the
preferred address still wins when it works; a dead one costs a quarter second instead of five. Alternatives:
**all at once** — slightly faster, but on the LAN it opens a Tailscale connection on every reconnection for
nothing, and it gives up the order's preference. **Sequential with a shorter timeout** — a 1 s connect timeout would
fail real Tailscale relays (DERP) and slow mobile networks.

### D6. Saying why: outcomes, kinds, network, reason

Pure functions in `service/Unreachability.kt`, unit-tested:

- **Attempt outcome** from `OpenResult`: `PinMismatch` → `CERTIFICATE`; `Unreachable(cause)` walks the cause chain:
  `SocketTimeoutException`, or an `InterruptedIOException` whose message is "timeout" (OkHttp's call timeout) →
  `TIMED_OUT`; `UnknownHostException` → `NAME_NOT_FOUND`; `NoRouteToHostException`, or `EHOSTUNREACH` /
  `ENETUNREACH` in a message of the chain → `NO_ROUTE`; `ConnectException` with `ECONNREFUSED` or
  "Connection refused" in the chain → `REFUSED`; else `FAILED`. Matching by class name and errno text keeps it
  JVM-testable (`android.system.ErrnoException` is not on the JVM test classpath). The exceptions the Pixel actually
  produces are recorded (tasks 8.3) and the matcher adjusted to them; an unknown shape falls to `FAILED`, never to a
  wrong specific outcome.
- **Address kind**, as the spec lists: `TAILSCALE` first (100.64/10, fd7a:115c:a1e0::/48, `*.ts.net` — fd7a::/16
  is inside fc00::/7), then `LOCAL` (10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10, fc00::/7, `*.local`), then
  `GLOBAL_V6` (2000::/3), else `OTHER`.
- **Network** at the round's start from `ConnectivityManager.getNetworkCapabilities(activeNetwork)`: `NONE` (no
  active network); otherwise, looking at the transports apart from `TRANSPORT_VPN` (a VPN network carries its
  underlying transports): Wi-Fi or Ethernet → `WIFI`, else cellular → `MOBILE`, else `OTHER`; plus
  `vpn = hasTransport(TRANSPORT_VPN)`. Tailscale on Android is a `VpnService`, so it shows as a VPN — as does any
  always-on VPN (an ad blocker, say). With such a VPN on, rule 4 and the Tailscale hint never apply and the generic
  texts are shown: less specific, not misleading.
- **Reason**: the first rule of the spec's list that applies, as `Reason { NO_NETWORK, NOT_THE_DESKTOP, REFUSED,
  OFF_NETWORK, NO_ANSWER, UNREACHABLE }`, with the first refusing address kept for `REFUSED`'s text and
  `tailscaleHint` (some address on Tailscale, no VPN) for `NO_ANSWER`'s. There is no separate "Tailscale off"
  reason: without the outcomes saying otherwise, a sleeping desktop on the phone's own Wi-Fi looks the same, and
  telling that user to turn on Tailscale would be wrong.

**Lifecycle.** `Status` gains `round: RoundReport?` (`outcomes` in the order tried, `network`, `vpn`, `reason`,
`refusedAddress`) and `throttled: Boolean`. A round that ends with no pinned open sets `round` and clears
`remoteOff` (the round's own evidence now says more, and after a 4005 from a quitting desktop it says "refused");
a round with a pinned open clears `round` whatever follows on that connection (throttling, refusal, a session);
pausing clears it. `throttled` is true during a throttled wait. The connection log gets `outcome=<OUTCOME>` on
`UNREACHABLE`/`PIN_MISMATCH` and a new `ROUND` entry `network=… vpn=… reason=…` — no addresses beyond those
`ATTEMPT` already logs.

**Showing it.** `StatusScreen`: when `round` is set and none of paused, throttled, identity-changed or unsupported
applies, a `Tone.Warning` notice with the reason's sentence and advice:

| Reason | Says | Advice |
|---|---|---|
| `NO_NETWORK` | The phone has no network. | Connect to Wi-Fi or mobile data. |
| `NOT_THE_DESKTOP` | A server at <address> is not the paired desktop. | If you reset remote access, pair again. |
| `REFUSED` | <address> refused connections on port <port>. | If that is your desktop, check that spekterm is running with remote access on. |
| `OFF_NETWORK` | On mobile data, the phone can't reach the desktop's local and Tailscale addresses. | Join the desktop's Wi-Fi, or turn on Tailscale (or your VPN) on this phone. |
| `NO_ANSWER` | The desktop doesn't answer on any of its addresses. | It may be asleep or off, on another network, or a firewall may block port <port>. With `tailscaleHint`: "If you're not on the desktop's network, turn on Tailscale on this phone." |
| `UNREACHABLE` | Could not reach the desktop on any of its addresses. | — |

The address list then shows every address (no "Show all" collapse) with its outcome: "timed out", "refused",
"no route", "name not found", "not the desktop", "failed", "not tried", or "trying" during a round. The ongoing
notification while waiting: `NO_NETWORK` → "No network"; `REFUSED` → "Connection refused"; `NOT_THE_DESKTOP` →
"Not the paired desktop"; others → "Can't reach the desktop"; each followed by " · retrying".

**Pairing.** `Pairer.Result.Failed` carries a `RoundReport` built from its race and a network snapshot that
`PairScreen` takes from its context (`Pairer.pair(…, network: () -> NetworkSnapshot)`); `PairScreen`'s unreachable
and wrong-certificate texts use the same sentences and list.

### D7. "Try now"

`ConnectionService.ACTION_TRY_NOW` (sent with `startService` from the status screen, which is in the foreground)
does `kick.trySend(Unit)`. The button shows while `state == WAITING && !throttled && !paused`. The backoff's next
delay is drawn before the wait starts, so cutting the wait short does not shorten the one after (`Backoff` is
untouched).

### D8. String resources, and `UiText` for texts built outside composition

All English text moves to `res/values/strings.xml` (and `plurals`), and zh-TW to `res/values-zh-rTW/strings.xml`.
Names are prefixed by where they are used: `common_`, `pair_`, `status_`, `sessions_`, `conversation_`,
`prompt_`, `menu_`, `handoff_`, `openspec_`, `notification_`, `scan_`, `reason_`, `outcome_`. Positional
placeholders (`%1$s`, `%1$d`) throughout, so word order can change in Chinese. The manifest's label becomes
`@string/app_name` ("spekterm" in both). Composables use `stringResource` / `pluralStringResource`.

Code that builds a text outside composition returns `UiText` (new `ui/UiText.kt`):

```kotlin
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText            // what the desktop sent, shown as sent
}
fun UiText.resolve(context: Context): String              // args that are UiText resolve first
@Composable fun UiText.text(): String
```

`Relations`, `SessionActions`, `OpenSpecText`, `ConversationItems`' summaries, `PromptCard`'s label helpers,
`Pairer` failure texts, D6's texts and the attention notifier return or build `UiText`; the screens and
`Notifications` resolve them. The JVM tests compare `UiText` values (`R.string.*` ids are on the unit-test
classpath) instead of English sentences; what the English reads is checked by the strings test below and by the
screenshots.

Alternatives: **pass `Resources` into the logic** — the JVM tests would need Robolectric or fakes duplicating the
texts. **Keep English in the logic and map at the UI** — every sentence becomes a lookup key; silent misses.

**Notifications**: `Notifications.createChannels` already runs in `App.onCreate`; it now takes names and
descriptions from resources (re-creating a channel updates its name), and `App.onConfigurationChanged` calls it
again. `ConnectionService.onConfigurationChanged` re-posts the ongoing notification, so it does not keep the old
language until the next state change.

**Locale config**: `androidResources { generateLocaleConfig = true }` in `app/build.gradle.kts` with
`res/resources.properties` `unqualifiedResLocale=en-US`, so Android 13+ lists the app in per-app language settings
with English and 中文（台灣）. No AppCompat: on 13+ the system applies the per-app locale to the app's process
(activities and the service), and older Android uses the phone's language. `zh-rTW` resources serve zh-Hant, zh-HK
and zh-MO by Android's script matching (API 24+). Whether the service's context and
`Application.onConfigurationChanged` follow a per-app change while the process lives is checked in tasks 6.2.

**Recreation on a language change**: the navigation back stack and `rememberSaveable` state survive it; the
composer draft, the rename field, the paste field, `offeredLink` and `repairing` are made saveable (tasks 6.1).

### D9. Translation and its checks

The zh-TW texts are written for Taiwan (設定, 連線, 網路, 資料夾, 檔案, 通知, 螢幕), with the desktop's terms
(Context) — English "session", "agent", "change", "spec", "task", "artifact", "port", "shell", "OpenSpec" kept
inside Chinese text as the desktop does. A glossary lives at the top of `values-zh-rTW/strings.xml` as an XML
comment.

`StringsTest` (JVM) parses both files and fails when: a name is in one and not the other; a string's placeholders
differ (type, count or index); a plural lacks `other`; or an English string is copied unchanged into zh-TW except an
allowlist (`app_name` and texts that are only names, like "OpenSpec").

`HardcodedTextTest` (JVM) scans every string literal in `app/src/main/java` (including multi-line calls and
templates) and fails on one that reads as text for people: it contains a letter, a space and a letter; or ends with
`.`, `?`, `…` or `:`; or is a capitalized word (`[A-Z][a-z]+`). Exempt by an allowlist of files (the connection log,
protocol frames and codes in `net/`, the log's own keys) and of exact literals (protocol values, MIME types, log
kinds), each with the reason. The review's grep found 275 such lines today that a call-pattern scan misses
(scratchpad `missed.txt`), which is why it scans literals, not calls.

The instrumented Compose tests keep asserting English and run on the emulator with its locale en-US; a zh-TW
emulator run is screenshots, not assertions.

## Risks / Trade-offs

- [The Pixel camera app may not offer to open a `spekterm://` link] → Checked on the Pixel (tasks 8.2); the
  in-app scanner is the main fix either way, and the intent path still serves scanner apps and tapped links.
- [Native code: ~1.2–1.8 MB per ABI; a crash in native code takes the process down] → Play's per-ABI splits keep the
  download to one ABI; the reader runs only while scanning, on its own executor.
- [1080p analysis costs CPU while scanning] → `KEEP_ONLY_LATEST` drops frames the reader can't keep up with; the
  camera runs only while the scanner is on screen.
- [Dense codes keep growing with the desktop's address list (temporary IPv6)] → The test covers seven addresses;
  leaving global IPv6 out of the link, or a larger code, is the desktop's follow-up.
- [Racing can pick Tailscale over a slow LAN (>250 ms handshake)] → `lastAddress` then becomes the Tailscale one and
  is tried first next time; the connection works either way. Recorded if seen.
- [The exception-to-outcome mapping is inferred, not measured] → Logged per attempt, checked on the Pixel (tasks
  8.3) before the texts are trusted; unknown shapes fall to `FAILED`.
- [A wrong reason misleads more than a vague one] → Rules name a cause only on direct evidence; "refused" names the
  address and says "if that is your desktop" (a home subnet often repeats on another network); "no answer" lists
  sleep and firewalls, not only the network.
- [~400 strings moved by hand; a slip changes an English text] → The English screenshots of 7.2 are compared with the
  current wording; `StringsTest` and `HardcodedTextTest` catch omissions, not rewording.
- [Machine-written Chinese reads stiffly] → The maintainer reads every screen in zh-TW on the phone; wording fixes
  are cheap string edits.

## Migration Plan

No data migration: no stored format changes. Installing over the current build keeps the pairing. Rollback is
installing the previous APK.
