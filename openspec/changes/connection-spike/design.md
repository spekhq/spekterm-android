## Context

See proposal.md for why this is a spike. The repository holds only a README, a license and a `.gitignore`; this
change creates the Android project. The desktop side is finished and fixed: the wire contract is spekterm's
`docs/remote-protocol.md` (version 1), and nothing in spekterm changes here.

Facts about the desktop that shape the design (read from spekterm's source):

- The certificate is produced by spekterm's own DER writer (`src/main/remote-cert.ts`): ECDSA P-256,
  `CN=spekterm`, X.509 v3 with **no extensions field at all**, signed with ECDSA-SHA256, valid ten years. Its
  fingerprint is SHA-256 over the DER. A reset produces a new key and certificate, so after a reset the old pin
  no longer matches.
- The server pings every 20 s and closes a connection that missed two (`remote-server.ts`), so a live
  connection costs at least one inbound packet every 20 s.
- `attention.watch` registers the live listener and computes the held events synchronously; the answer frame
  is sent one microtask later, and new events are appended only from other macrotasks (`remote-protocol.ts`), so
  the answer frame is written before any live `attention` event of that watch. A position is
  `{ epoch: string, seq: number }`; with no position, the answer is always `complete: false`
  (`remote-attention.ts`).
- Reset revokes every device (4004 to connected phones) before it replaces the certificate
  (`remote-service.ts`). A phone that was away during a reset never sees 4004 or `AUTH_FAILED`: it meets only a
  certificate that does not match its pin.
- 4005 is sent both when the user turns remote access off and when the desktop quits (`will-quit` stops the
  remote service), so the phone cannot tell the two apart.
- A throttled address is answered at accept time with a `THROTTLED` error frame carrying `id: null`, then closed
  with 4030 at once; the client's own first frame is never read (`remote-server.ts`). `UNSUPPORTED_VERSION` on
  `hello` is followed by 4003 one second later and does not count toward throttling.
- The pairing link lists every non-loopback interface address: on this desktop `192.168.50.22`,
  `100.106.81.33` and `fd7a:115c:a1e0::6432:5121` — so at home without Tailscale on the phone, two of the three
  addresses are normally unreachable.

Toolchain on this machine: Android SDK at `~/Android/Sdk` with platforms up to android-36 and build-tools 36.0.0
(no `cmdline-tools`, so no `sdkmanager`), JDK 17, Gradle 8.6 from sdkman. Google Maven, Maven Central and
services.gradle.org are reachable (checked 2026-10-14: AGP 9.4.1 and Gradle 9.8.1 are current). No phone was
attached when this was written.

## Goals / Non-Goals

**Goals:**

- Answer the three questions with evidence from the user's real phone, written in `docs/spike-findings.md`.
- Build the pieces the real app keeps (link parsing, pinning, credential store, connection supervisor,
  attention policy) as production code with tests, so the spike is the app's foundation rather than a throwaway.

**Non-Goals:**

- Any screen beyond pairing and status. No session, conversation, terminal, inbox, handoff or OpenSpec
  operations, beyond `pair`, `hello` and `attention.watch`.
- More than one paired desktop.
- Uploading to Google Play, signing configuration for release, store listing, or app icon design.
- Tuning for battery (for example a wake lock or a different ping interval) before the baseline is measured.
  If the overnight run fails, the findings say how, and the remedy is a follow-up change.

## Decisions

### D1. One `:app` module, Kotlin, Compose, minSdk 29, targetSdk 36, compileSdk 37

A single Gradle module with packages by concern: `pairing` (link parsing, pairing flow), `net` (pinning, frames,
the WebSocket client), `service` (the foreground service and the connection supervisor), `attention` (the
notification policy and the notifier), `store` (credential and position storage), `log` (the connection log),
`ui`. Pure logic (link parsing, frame codec, backoff, close-code policy, attention policy) has no Android
dependency, so it runs as JVM unit tests.

- **targetSdk 36** — Google Play's target level for new apps since 2026-08-31 (D10).
- **compileSdk 37** — the current stable AndroidX (Compose 1.12, core 1.19, lifecycle 2.11) and OkHttp 5.5 refuse
  to be compiled against less (found at the first build). The user chose installing the android-37 platform
  over pinning older libraries; it was downloaded once with `-Pandroid.builder.sdkDownload=true`. compileSdk
  only decides which APIs the code may reference; runtime behavior follows targetSdk.
- **minSdk 29** (Android 10): `startForeground` with a service type exists from 29, so there is one code path,
  and Android 10 covers all but a sliver of phones in use. Lower would add branches for nothing the spike
  needs to learn. Only the user's phone is tested, so behavior on the other API levels in 29–36 stays
  unverified and the findings say so.
- **AGP and Gradle**: the newest stable AGP that supports compileSdk 36, with the Gradle version it requires,
  pinned through a committed Gradle wrapper. The wrapper is generated with sdkman's Gradle 8.6 and then pointed
  at the required distribution. `gradle.properties` sets `android.builder.sdkDownload=false`, so if AGP needs an
  SDK component that is not installed the build fails and it is reported to the user rather than downloaded
  silently (the SDK's license files are present, so AGP would otherwise download it).
- **Jetpack Compose (Material 3)** for the two screens — the real app's screens will be Compose, so the spike
  does not build on views it will discard. Alternative: XML views — less setup, but thrown away later.

### D2. Pin the certificate DER in a custom trust manager and host name verifier; check again on open

Per connection, the client builds an `SSLContext` with one `X509ExtendedTrustManager` whose
`checkServerTrusted` (every overload) computes SHA-256 over `chain[0].encoded` and compares it to the pin with
`MessageDigest.isEqual`, throwing `CertificateException` otherwise; `getAcceptedIssuers` returns nothing; client
checks always throw. OkHttp's `HostnameVerifier` repeats the same comparison on
`session.peerCertificates[0]`. In `WebSocketListener.onOpen`, before the first frame, the client compares the
established connection's leaf once more and closes without sending if it differs — the reference client's
two-step check (`remote-client.mjs`). **Measured on the Pixel 7:** OkHttp's `Handshake.peerCertificates` is
empty here, because OkHttp "cleans" the chain against the trust manager's accepted issuers (deliberately none)
and reports an empty list when no chain can be built; so the check on open reads the leaf of the TLS session
itself, which the host name verifier captured from that same connection's session.

- **Why not OkHttp's `CertificatePinner`**: it pins a public-key hash, not the certificate DER the link carries,
  and it runs after a trust manager that must already have accepted the chain; with a self-signed leaf the
  default trust manager rejects first.
- **Why not "accept all" plus a later check**: the protocol forbids sending before the pin is checked, and an
  accept-all trust manager or verifier is what Google Play's scanning flags (protocol section 2).
- **Why the extended trust manager**: a TLS provider may wrap a plain `X509TrustManager` in an adapter that adds
  its own endpoint identification (the JDK's SunJSSE does; whether Conscrypt does is unverified). Implementing
  the extended interface keeps every overload under our control either way. Whether Conscrypt
  accepts the extension-less certificate under this trust manager is question 1 — answered by the instrumented
  test in D9 and by the real desktop.
- The URL is built with `HttpUrl.Builder().scheme("https").host(address).port(port).addPathSegment("v1")`, which
  brackets IPv6 literals; OkHttp's WebSocket accepts `https` URLs.

### D3. Credential encrypted with a Keystore AES-GCM key, outside backup

A 256-bit AES key with alias `pairing` is generated in `AndroidKeyStore` (purpose encrypt/decrypt, GCM, no
padding, random IV), with no user-authentication requirement and **without** `setUnlockedDeviceRequired`,
because the service must read it while the phone is locked. The pairing record — desktop name, addresses, port,
fingerprint, device id, device secret, last connected address — is serialized to JSON, encrypted, and written
atomically (temp file + rename) into `noBackupFilesDir`. The manifest sets `android:allowBackup="false"` and
data extraction rules that exclude everything, so neither backup nor device transfer carries it (a restored
blob would be undecryptable anyway, because the key never leaves the Keystore).

The attention position is not secret and changes often; it is a separate small plain file, written atomically.

- Alternative: `EncryptedSharedPreferences` (androidx.security-crypto) — deprecated by Google. Alternative: Tink
  with DataStore — a larger dependency for one record.

### D4. Wire client on OkHttp and kotlinx.serialization

One `OkHttpClient` per connection attempt (it carries the pin), sharing a dispatcher and connection-free pool.
Frames are encoded and decoded with kotlinx.serialization's `JsonElement` tree, ignoring unknown keys, so
fields the desktop adds later do not break the app. Requests carry incrementing numeric ids; a pending map
completes them from `res` frames, with a timeout. Events are delivered to the supervisor in arrival order on
OkHttp's single reader thread, which preserves the desktop's guarantee that the watch answer precedes its live
events; the supervisor still processes the answer before any `attention` event it has queued.

- **Client pings**: OkHttp `pingInterval(30 s)`. The desktop's 20 s pings keep the desktop's side honest; the
  client's own pings are the only way the phone notices a connection that died silently (OkHttp fails the
  socket when a pong is late). **Unverified**: that OkHttp's ping timer runs on a clock that stops while the CPU
  sleeps, and so adds no wake-ups of its own; the overnight log (wake-ups in batterystats) shows it, and the
  findings record it. Whether the phone actually wakes for the desktop's 20 s pings in Doze is part of
  question 2.
- **`THROTTLED` with `id: null`**: an error frame whose id is null is not matched to a pending request; it is
  handed to the supervisor as a connection-level error, so the close that follows is understood as throttling
  (and, during pairing, shown as "wait a minute").
- **Per-address connect timeout**: 5 s, addresses tried one at a time (D6).

### D5. A foreground service that owns a connection supervisor

`ConnectionService` is a foreground service of type `specialUse` (D10). It is started after pairing succeeds,
when the app opens while paired, from a `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` receiver, and from the
"tap to resume" notification, unless the user paused it; it returns `START_STICKY`, and a restart the system
refuses is handled as in D10. Pause (on the ongoing notification and the status screen) stops the service and
is remembered across restarts until the user resumes. It calls `startForeground` at once with an
ongoing notification on a low-importance "Connection" channel, and runs a coroutine-based supervisor:

```
Idle → Connecting(address i) → Authenticating → Watching ─(drop)→ Backoff ─(timer | network)→ Connecting
                     │ pin mismatch / unreachable → next address; all failed → Backoff
                     ├ close 4004 / AUTH_FAILED → Unpaired (credential and position deleted, notification,
                     │                            service stops itself)
                     └ hello UNSUPPORTED_VERSION → Stopped (pairing kept, status screen says what to update)
```

The delay resets only once a connection has authenticated **and** its attention watch is answered, so a
connection closed right after `hello` keeps backing off.

**Pairing hands over to the service.** The pair screen's connection is used only for `pair`: once the answer is
stored, the screen closes that connection normally and starts the service, which connects with `hello`. So the
phone never holds two authenticated connections for long, and the service is the only owner of the connection.
Pairing again leaves the running service alone until the new pairing has succeeded and is stored; only then is
the old service stopped, the old pairing and position replaced, and the service started on the new pairing. A
failed re-pairing therefore leaves the old connection running (the pairing connection and the service's overlap
for a few seconds, far below the desktop's cap of 16).

`ConnectivityManager.registerDefaultNetworkCallback` cuts the backoff short when a network becomes available or
the default network changes. The ongoing notification text follows the state.

The close-code policy (protocol section 3) is a pure function from (close code, last error code) to the next
step, unit-tested against the table in the `desktop-connection` spec. Backoff: delay
`min(60 s, 1 s · 2^n) · U(0.5, 1)`; after `THROTTLED` or 4030, at least 60 s.

A pin mismatch on one address is not fatal: on another network the same LAN address can be someone else's
machine. The "identity changed" state is raised after three consecutive rounds in each of which at least one
address presented a mismatching certificate and none presented the pinned one; unreachable addresses count
neither way, because the link lists addresses (Tailscale, IPv6) that are often unreachable. A later round that
matches clears it. A false alarm on a foreign network costs only a notification — the pairing is kept — while
requiring every address to mismatch would never fire for a phone at home without Tailscale, which is the main
case after a reset (Context).

Every state announced by a notification (connection stopped, pair again, identity changed, version unsupported)
is also written to a small plain file the status screen reads, because with the notification permission denied
the notifications are invisible. It is persisted, not kept in memory: "connection stopped" is written by a
process about to be reclaimed, and "pair again" outlives the deleted credential — without it, opening the app
would show the pair screen with no way to tell a revoked phone from one never paired. Each state is cleared by
what resolves it: a resume, a new pairing, a matching handshake, or the user dismissing it on the screen.

### D6. Address order

The last address that authenticated goes first, then the link's addresses in their order. The pairing keeps all
of them. Tried sequentially with the 5 s timeout: with two or three addresses, the worst case before falling
through is 10–15 s, acceptable for a background reconnect. Alternative: racing all addresses at once (happy
eyeballs) — faster, but opens several TLS connections to the desktop on every reconnect, and the pairing code
must never be sent twice; revisit if the measurements show slow LAN→Tailscale failover.

### D7. Attention policy as a pure function; notifier keyed by session

`AttentionPolicy` takes a watch answer or a live event and returns actions: `Notify(event, alert)`,
`Cancel(key)`, `Summarize(count)`, `StorePosition(pos)`. Rules, from the `attention-notifications` spec:
suppressed → `Cancel` that session's notification, no `Notify`; `complete: false` → one `Summarize(count of
unsuppressed)` if that count is above zero; `complete: true` → coalesce by key, keeping only each key's latest
event (a suppressed latest event cancels), and `Notify` those with only the first alerting; live → `Notify`
alerting. The position of the last event is always stored, after the notifications are posted, so a crash
between the two repeats a notification rather than losing one.

Coalescing matters beyond tidiness: Android rate-limits updates to a package's notifications (about five per
second, from memory of `NotificationManagerService`; **unverified**) and silently drops the excess, so posting a
`choice` and then a `waiting` for the same key in a burst could leave the stale `choice` showing. It also caps a
package's live notifications (about 50, likewise unverified).

The notifier posts on a high-importance "Attention" channel, one notification per key — `session:<sessionId>`,
or `intake:<adapter>:<id>`, or `pos:<epoch>:<seq>` when an event has neither — using `notify(tag = key, id = 1)`,
so keys never collide through hashing. Non-alerting posts set `setOnlyAlertOnce`/`setSilent`. No `setGroup`: the
system groups an app's notifications itself once there are several, and a group without an explicit summary
notification does not collapse. The incomplete-answer summary has its own fixed tag, so a later one replaces it.
Text is set as plain `CharSequence`, untouched. Tapping opens the status screen.

Of the protocol's client obligations (section 9): 1 (pin in both checks) is D2; 4 (no notification for a
suppressed event, summarize an incomplete answer) and 5 (reduce nothing further) are this decision; 6
(protected storage, forget on 4004) is D3 and D5. 2 (show an inbox item's full body before accepting) and 3
(render the terminal at the stream's size) concern screens this change does not build.

### D8. Pairing and status screens; QR decoding with CameraX and ZXing

- **Pair screen**: "Scan QR code" (CameraX preview + ImageAnalysis feeding ZXing `QRCodeReader`, camera
  permission asked on tap) and "Paste link" (a text field and the clipboard). On a valid link it shows the
  desktop's name and pairs at once; errors are shown in place.
- **Status screen**: desktop name, state, connected address and since when, reconnect count, last close code
  or error, any announced state (pair again, identity changed, connection stopped, version unsupported), the
  last ten attention events received (kind, title, time, notified or suppressed), whether notifications are
  allowed (with a button to the app's notification settings), the battery-optimization state, and buttons:
  Pause / Resume, Export log, Open battery settings, Pair with a new code (the pair screen with a way back; the
  current pairing stays until the new one succeeds), Unpair.
- **Why ZXing core over ML Kit or Google's code scanner**: both depend on Google Play services; the rest of the
  app has no such dependency, which keeps it usable on phones without Play services. ZXing core is pure Java,
  Apache-2.0, and decodes an on-screen QR code reliably. Cost: a camera permission and CameraX.
- No `spekterm://` intent filter in this change: a link opened from another app would pair without the user
  having seen the desktop's pairing view, which deserves its own design.

### D9. Evidence: tests and measurements

- **JVM unit tests**: link parsing (every refusal in the spec, the encoded address list, IPv6, fingerprint
  normalization), frame codec, backoff bounds, close-code policy, attention policy.
- **Instrumented tests on the real phone** (`connectedAndroidTest`):
  - *Pinning (question 1 without the desktop)*: a fixture certificate and key in spekterm's exact shape,
    generated once with spekterm's `generateIdentity()` and committed under `app/src/androidTest/assets/` as
    test-only material, stored as PEM (the `.gitignore` excludes `*.jks` and `*.keystore`), with its provenance
    stated next to it — a committed `BEGIN PRIVATE KEY` in a public repository can trip GitHub's secret
    scanning, and the README there says why it is harmless. OkHttp's `mockwebserver` (`useHttps` with an
    `SSLSocketFactory` from the phone's own TLS provider, and `withWebSocketUpgrade`) presents it, its key handed
    over by a small `X509KeyManager` that returns the key read with `KeyFactory("EC")` from the PKCS#8 PEM —
    avoiding key-store type differences. Assert: the matching pin completes the handshake and a frame
    round-trips; a pin differing in one digit fails the handshake and the server receives no WebSocket request;
    the host name used (`127.0.0.1`) appears nowhere in the certificate.
  - *The `onOpen` check*: extracted as a function of the `Handshake`, and tested with a fabricated `Handshake`
    whose certificate differs from the pin (a real server cannot present one certificate to the trust manager
    and another to `onOpen`).
  - *Keystore*: the credential round-trips through the store, and the stored file does not contain the secret's
    bytes.
- **Against the real desktop** (spekterm from source, `npm run dev`, `~/.config/spekterm-dev`): pairing, revoke
  (4004), remote access off/on, desktop restart, LAN→Tailscale failover, events while disconnected, suppression.
- **Overnight measurement (question 2)**, recorded in the findings with the phone's manufacturer, model and
  Android version:
  1. Quick checks first with adb: `dumpsys battery unplug` + `dumpsys deviceidle force-idle`, then standby
     buckets via `am set-standby-bucket com.spekterm.android rare` and `restricted`; record whether the
     connection holds and events arrive.
  2. A real night, unplugged, screen off, at least eight hours, with `dumpsys batterystats --reset` before and
     `dumpsys batterystats` after. A small script on the desktop drops an inbox item into the dev profile's
     intake drop point every 30 minutes, so the phone has events to deliver; the connection log's receive time
     for each attention event against the event's `at` gives the delivery delay in Doze. The two clocks differ
     by their NTP offsets, which are recorded alongside (`timedatectl` / `chronyc tracking` on the desktop, the
     phone's automatic-time setting). Each item carries a unique id (duplicates are silently dropped), and the
     desktop's inbox holds at most 200 pending items, so the script's README says to clear it between nights.
  3. The connection log (exported) and the batterystats summary (the app's estimated drain and wake-ups) go into
     the findings. A control night without the app is optional and noted if not done.
- **Vendor battery management**: the findings record the phone's vendor-specific settings encountered (from
  dontkillmyapp.com and the phone itself), and whether the app needed exempting.

### D10. Foreground service type `specialUse`, and what Google Play needs

Researched 2026-10-14; the findings document carries the full source list and is updated if the phone shows
otherwise.

| Type | Why not / why |
|---|---|
| `dataSync` | At most 6 h per 24 h for apps targeting 35+, then `onTimeout` and a crash if not stopped; may not start from `BOOT_COMPLETED` on Android 15+. Ruled out. |
| `shortService` | About 3 minutes. Ruled out. |
| `remoteMessaging` | Defined as transferring text messages between devices for messaging continuity; agent alerts from the user's desktop are not that. Likely to be challenged in review. |
| `connectedDevice` | Its definition covers a local network connection (prerequisite: declaring `CHANGE_NETWORK_STATE` suffices), and the Play use case is "continuous data transfer to an external device". Fits the LAN case; a desktop reached over Tailscale or the internet stretches it. Second choice. |
| **`specialUse`** | The documented catch-all for valid uses no other type covers. No time limit, no runtime prerequisite, may start from `BOOT_COMPLETED`. Cost: a free-text justification that Play reviews. **Chosen.** |

Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, and on the service
`android:foregroundServiceType="specialUse"` with
`<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="…"/>` stating: keeps a
direct connection to the user's own computer, with no server in between, to deliver its notifications; push
messaging cannot be used because there is no server to send it.

Two lines of the Doze guide bear on review. Its list of acceptable cases for the *battery-optimization
exemption* includes apps that cannot use FCM high-priority messages for a technical reason — the exemption, not
the foreground service type, but it supports asking the user to exempt the app. And it says "Don't start a
foreground service just to prevent the system from determining that your app is idle": the justification must
be the user-visible job (delivering the desktop's notifications, with a persistent, stoppable notification),
which is what this service is.

The Play Console declaration (App content → Foreground service permissions), needed at the first upload, asks
per type for a description of the feature, the user impact if it is deferred or interrupted, a video link
showing how the user triggers it, and a use case. The policy also requires the service to be perceptible to the
user and **stoppable by the user** — hence the Pause control in D5 and D8.

Consequences for the design:

- **A foreground service keeps network access in Doze** (power-details page; `NetworkPolicyManager` allows
  process states up to bound-foreground-service while idle). The CPU still sleeps; whether incoming pings wake
  it is question 2.
- **Starting a foreground service from the background is refused on Android 12+** unless an exemption applies.
  Exempt: `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, the user tapping a notification, and **an app the user has
  exempted from battery optimization**. A `START_STICKY` restart after the process is killed has no documented
  exemption (Thunderbird catches `ForegroundServiceStartNotAllowedException` there). So the service catches that
  exception, posts a "connection stopped — tap to resume" notification (tapping it is an exemption), and stops.
  The status screen offers the battery-optimization settings page (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`,
  no special permission). The app does **not** declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in this change:
  the Doze guide's acceptable cases (no FCM for a technical reason) suggest it would qualify, but no Play page
  confirms eligibility, and the settings page needs no permission at all.
- The service never restarts itself from a network callback; it stays alive while disconnected and reconnects
  from inside.
- `POST_NOTIFICATIONS` is not needed for the service to run; denied, its ongoing notification shows only in
  the system's task manager, and attention notifications are not shown.
- Google Play requires **target SDK 36** for new apps since 2026-08-31 (D1).
- Unconfirmed, to be checked on the phone or left marked in the findings: that dismissing the ongoing
  notification on Android 14+ leaves the service running; that a sticky restart is really refused; whether Play
  accepts `specialUse` here (only the first upload can tell).

## Risks / Trade-offs

- [Conscrypt refuses the extension-less certificate even under a custom trust manager] → The instrumented test
  shows it on day one, before anything else is built on it. The remedy would be on the desktop (adding a
  `basicConstraints` or SAN extension), which changes the fingerprint and needs a spekterm change; the findings
  would say exactly what Conscrypt rejected.
- [The phone does not wake for the desktop's pings in Doze, so the connection dies or events arrive late] →
  That is what the overnight run measures. Options for a follow-up (not built here): a partial wake lock on a
  timer, a longer server ping interval, or asking the user to exempt the app from battery optimization.
- [Vendor battery killers stop the service regardless] → Recorded per phone; the status screen links to the
  battery settings. Only the user's own phone is tested; other vendors stay unverified.
- [Sequential address attempts make failover slow] → Measured in the failover test; racing is the follow-up.
- [Stored position and posted notification can diverge on a crash] → Chosen direction is a repeated
  notification, never a lost one.
- [Google Play rejects the chosen foreground service type at first upload] → The type and declaration are
  researched with sources now, and anything unconfirmed is marked; the first upload is a later change.

## Migration Plan

New project; nothing to migrate. The spike is not committed until the user has tested it on the phone; the
change is archived only after that.

## Open Questions

None. The test phone is the user's Google Pixel 7 (stock Android, so no third-party vendor battery manager; the
findings record its Android version and Google's own adaptive-battery behavior).
