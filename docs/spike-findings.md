# Connection spike: findings

The three questions the `connection-spike` change set out to answer on a real phone, what was measured, and what
is still unverified. Test phone: Google Pixel 7, Android 17 (API 37), build CP3A.260905.009, security patch 2026-09-05; on the same
Wi-Fi as the desktop (192.168.50.0/24), no Tailscale on the phone. Desktop: spekterm run from
source (`npm run dev`, profile `~/.config/spekterm-dev`) on the maintainer's Linux machine.

Status: **complete** for this change. The overnight run was taken out of this change on 2026-10-15, to be run later,
so question 2 is answered only for forced idle (see below).

## 1. Does the phone accept spekterm's certificate when pinned by fingerprint?

spekterm's certificate is self-signed ECDSA P-256, `CN=spekterm`, X.509 v3 with no extensions at all (no SAN, no
basicConstraints). The app trusts it only by SHA-256 over its DER, checked in the trust manager, in the host
name verifier, and once more on the established connection before the first frame (design D2).

| Evidence | Result |
|---|---|
| JVM: `OnOpenCheckTest` — a fabricated established connection carrying another certificate is refused on `onOpen` and nothing is written | pass |
| Phone: `PinningTest` — loopback TLS server presenting a fixture in spekterm's exact shape (`app/src/androidTest/assets/`) | pass (2026-10-14): the right pin completes the handshake and a frame round-trips; one digit off fails the handshake with no request reaching the server |
| Phone, control groups: the platform's default trust refuses the certificate; the pinning trust manager with OkHttp's default host name verifier refuses `127.0.0.1` ("not verified") | pass |
| Phone against the real desktop: pairing by QR, then `hello` over the LAN address | pass (2026-10-14 09:27 UTC) |

**Answer: yes.** Android 17's TLS stack (Conscrypt) completes the handshake with the extension-less, SAN-less
certificate when the app's own `X509ExtendedTrustManager` and host name verifier accept it by fingerprint, and
refuses anything else before a byte of application data is sent.

One measured surprise: OkHttp's `Handshake.peerCertificates` (on the WebSocket's `onOpen` response) is **empty**
with this trust setup — OkHttp "cleans" the chain against the trust manager's accepted issuers, which are
deliberately none, and returns an empty list when no chain can be built. The check on open therefore reads the
leaf of the TLS session itself, captured by the host name verifier from the same connection (design D2).

## 2. Does the connection survive the phone being idle, and at what cost?

### Idle checks with adb (2026-10-14, 6.3)

Clock offset: the phone's clock was 1.23 s ahead of the desktop's (both NTP-synchronized; measured with
`adb shell date +%s%3N` against the desktop, round trip about 25 ms). The `delayMs` the app logs is phone receive
time minus the desktop's `at`; corrected delays below subtract the offset.

| Condition | Commands | Connection | Inbox item delay (logged / corrected) |
|---|---|---|---|
| Awake, app in foreground | — | held | 1326 ms / ~0.1 s |
| Deep Doze | `dumpsys battery unplug`, screen off, `dumpsys deviceidle force-idle` (state `IDLE`) | held | 2174 ms / ~0.9 s |
| Doze + standby bucket `rare` (40) | `am set-standby-bucket com.spekterm.android rare` | held | 1788 ms / ~0.6 s |
| Doze + bucket `restricted` (45) | `am set-standby-bucket com.spekterm.android restricted` | held | 1446 ms / ~0.2 s |

Forced idle is not a night: the overnight run below is what counts.

### Overnight run (not run)

**Not run.** The user took it out of this change on 2026-10-15, to be run later ("過夜測試我覺得沒什麼好測的 這個之後再說吧"). The tools for
it remain: `tools/drop-intake.mjs --every 30` drops an inbox item every 30 minutes, and `tools/analyze-log.mjs`
summarizes the exported connection log (`--offset-ms` corrects the clock offset). Before the run, reset
`dumpsys batterystats`; after it, collect the report.

**Answer: partly.** The held connection survived forced deep Doze and the `rare` and `restricted` standby buckets,
with inbox items delivered within about a second. A sticky restart after a kill was allowed in the background. The
connection also came back by itself after network changes, a desktop restart and a reboot (scenario table below).
**Not answered, because the overnight run was not done:** whether the connection holds through a real night of
idle maintenance windows, what it costs in battery, and whether OkHttp's 30-second ping adds wake-ups.

## 3. Which foreground service type, and what does Google Play need?

Researched 2026-10-14 from Android's and Google Play's documentation. **Chosen: `specialUse`.**

| Type | Finding |
|---|---|
| `dataSync` | At most 6 hours per 24 for apps targeting Android 15+; on timeout `onTimeout` is called and a service that does not stop crashes (`RemoteServiceException`), and further starts throw until the user opens the app. May not start from `BOOT_COMPLETED` on Android 15+. Ruled out. |
| `shortService` | About 3 minutes. Ruled out. |
| `remoteMessaging` | "Transfer text messages from one device to another … continuity of a user's messaging tasks when they switch devices"; Play's use case is relaying text communication to another device. Agent alerts are not that. |
| `connectedDevice` | Covers a local network connection; prerequisite satisfied by declaring `CHANGE_NETWORK_STATE`. Play's use case is "continuous data transfer to an external device". Fits LAN; Tailscale or the internet stretches it. Second choice. |
| `specialUse` | The catch-all for valid uses no other type covers. No time limit, no runtime prerequisite, may start from `BOOT_COMPLETED`. Needs the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` text, which Play reviews. |

Sources:

- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Timeouts: https://developer.android.com/develop/background-work/services/fgs/timeout and
  https://developer.android.com/about/versions/15/behavior-changes-15
- Background-start restrictions and exemptions:
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Play Console declaration: https://support.google.com/googleplay/android-developer/answer/13392821
- Play policy criteria (perceptible, stoppable, …): https://support.google.com/googleplay/android-developer/answer/16559646
- Target API requirement (API 36 since 2026-08-31): https://support.google.com/googleplay/android-developer/answer/11926878
- Doze and App Standby: https://developer.android.com/training/monitoring-device-state/doze-standby
- Network access of a foreground service while idle: https://developer.android.com/topic/performance/power/power-details
  and `NetworkPolicyManager.isProcStateAllowedWhileIdleOrPowerSaveMode` in AOSP
- Notification permission and foreground services:
  https://developer.android.com/develop/ui/views/notifications/notification-permission
- Android 14 dismissible ongoing notifications: https://developer.android.com/about/versions/14/behavior-changes-all
- Sticky restart refused in the wild (Thunderbird):
  https://git.wbrawner.com/wbrawner/thunderbird-android/commit/9401de25802c8251bd5beb098234c509f87650d7

Two lines of the Doze guide bear on review: its acceptable cases for the battery-optimization *exemption* include
apps that cannot use FCM high-priority messages for a technical reason; and "Don't start a foreground service just
to prevent the system from determining that your app is idle" — this service's job is the user-visible delivery of
the desktop's notifications, with a persistent notification that can pause it.

### Draft Play Console declaration (for the first upload)

- **Feature**: spekterm's companion app keeps a direct, encrypted connection to the user's own computer running
  spekterm (on the same network, over Tailscale, or a port the user forwards), and shows a notification when an
  AI agent there is waiting for the user, asks for a permission, or finishes a handed-off task. There is no
  server in between, so push messaging cannot deliver these notifications.
- **User impact if deferred or interrupted**: the user is not told that an agent on their computer is blocked
  waiting for them; the agent stays idle until the user happens to look. Events missed while disconnected are
  replayed on reconnect, so interruption delays notifications rather than losing them.
- **Video steps**: pair the phone by scanning the desktop's QR code; the ongoing "Connected" notification
  appears; on the desktop, an agent finishes a turn; the phone shows the notification; pause from the ongoing
  notification stops the service.

## Scenario outcomes (6.2)

Every scenario of the three specs, run on the Pixel 7 against spekterm from source.

| Scenario | Outcome |
|---|---|
| Pairing by QR | pass — paired, `hello` on the LAN address, service `specialUse` in the foreground |
| A live inbox item notifies | pass — "New inbox item" notification, keyed by the item |
| Two events while disconnected (Wi-Fi off, two items dropped, Wi-Fi on) | pass — the reconnect answer held exactly those two, in order, `complete: true`, each notified; the network callback cut the backoff short (reconnected 45 ms after the network came back) |
| Process killed (`run-as … kill`), app in the foreground | pass — sticky restart about 1 s later, reconnected, no repeated notification (position kept) |
| Process killed, app in the background, battery optimization on | the system **allowed** the sticky restart (no `ForegroundServiceStartNotAllowedException`) and the app reconnected; the "connection stopped" path did not trigger on Android 17 |
| Desktop quits | the phone received close code **4005** (the same as "remote access off") and backed off; the status said "remote access is off, or the desktop quit" |
| Desktop restarts | reconnected about 6 s after the desktop listened again; answer `complete: false` with no events (new span), so no summary |
| First watch after pairing | answer `complete: false`, no events: no summary (correct: none unsuppressed) |
| Re-pairing while paired ("Pair with a new code") | pass — the old service stopped only after the new pairing succeeded; the new one connected with `hello`; the fresh position gave an incomplete answer, summarized |
| Used / invalid code while paired (pasted link to the live desktop with a made-up code, by adb) | pass — "The code is no longer valid…"; the existing connection stayed up throughout. Scanning the same QR twice is not possible: the desktop closes the pairing view once a code is used |
| Pasted links: `v=2` / 63-digit fingerprint / unreachable address / wrong fingerprint at the live address | pass — "needs a newer version of the app" / "not a valid pairing link" / "Could not reach the desktop. Tried: 192.168.50.250." / "A server answered at 192.168.50.22, but it is not the desktop in the link." |
| Camera permission denied (by adb) | pass — "Scanning needs the camera. You can paste the pairing link instead." |
| Revoking another device of the same phone on the desktop (user) | pass — the connected pairing was unaffected |
| Revoke while connected, notifications denied, process killed, app opened (user) | pass — close 4004 → credential deleted, `pairAgain` persisted, service stopped; after the process was killed, opening the app showed "The desktop no longer accepts this phone… Pair again." Turning notifications off in system settings killed the app's process; the service restarted on its own (sticky) and reconnected |
| Revoke while away (`AUTH_FAILED`) | not run separately — a revoked device's `hello` gets `AUTH_FAILED`; carried by `ConnectionPolicyTest` |
| Desktop reset while the phone is away (Wi-Fi off), then Wi-Fi on (user + adb) | pass — every attempt refused by the trust manager (`check=trust`), nothing sent; after three rounds the "identity changed" state and notification appeared; pairing kept. Re-pairing cleared both |
| Another certificate on one address → next address | the refusal path is the one above; the fall-through to a next address is carried by the supervisor's address loop (seen with unreachable addresses); not produced with a second desktop |
| Remote access off and on | see above (pass) |
| Leaving the office Wi-Fi without Tailscale on the phone (user, 10:06–10:12 UTC) | as designed, **no address was reachable** from the mobile network: the LAN address is private and the phone had no Tailscale. The app retried in rounds of three addresses (5 s timeout each) with backoff up to a minute: 28 failed attempts in about 6 minutes. The status said only "Waiting to retry" |
| Turning Tailscale on outside (user) | pass — the VPN coming up fired the network callback; the app tried the LAN address first (5 s timeout), then authenticated over `100.106.81.33`, 5.9 s after the VPN was available, without re-pairing |
| Back in the office with Tailscale still on | the connection dropped on the network change and came back over Tailscale (now the last-good address, tried first) in 1.3 s |
| `choice` then `waiting` for one session, live (user, manual permission mode, looking at another session) | pass — "Asks for a choice: Bash", then the same notification replaced by "Waiting for your input" |
| The same while looking at that session on the desktop | pass — both events `suppressed`, no notification |
| `choice` then `waiting` for one session in a replay | carried by `AttentionPolicyTest` (coalescing); not produced live |
| Remote access off and on (user) | pass — close 4005, reconnected on its own after it was turned back on |
| Desktop restarted while away, with one event | pass — answer `complete: false`, one summary notification, no per-event notification |
| App swiped away from recents (user) | pass — the service kept running |
| Dismissing the ongoing notification (Android 14+) (user) | the notification can be swiped away; the service **stays in the foreground** (`isForeground=true`) and the connection holds. The app re-posts the notification on its next state change |
| Pause / Resume from the status screen (user) | pass — Pause stopped the service, Resume reconnected in 160 ms |
| Exporting the log (user) | pass — the share sheet offered the text file |
| Reboot, unlocked, app not opened (adb + user) | pass — the service started by itself after unlock and reconnected; answer `complete: true` |
| Throttling (4030 / `THROTTLED` with `id: null`), `UNSUPPORTED_VERSION` | carried by `ConnectionPolicyTest` (JVM) and `RemoteClientTest` (phone, loopback server) — a real phone cannot reasonably produce them |

## Open product question: reaching the desktop from outside

The spike's rule is direct connections only. Away from the desktop's network, that means the phone needs
Tailscale (or a similar VPN) or a router port forward; the user found installing Tailscale a burden and asked
for a way without it. There is none without something in between. The candidates, for a separate change:

- **A relay run by spekterm**: both sides connect out to it and it splices the two byte streams. TLS stays end to
  end between phone and desktop, so the fingerprint pin still holds and the relay sees no content; it does see
  who connects when, and someone must run and pay for it. This reverses the "no relay" decision.
- **A third-party tunnel** (for example Cloudflare Tunnel): usually terminates TLS at the provider, breaking the
  pin unless another encrypted layer is added; trust moves to the provider.
- **Guiding the user to Tailscale**: no server, but an extra app, which is what the user objected to.

Smaller follow-ups seen in the same run: the status should say plainly that the desktop is not reachable from
this network (and why), and the retry rate while unreachable for hours on mobile data needs its own battery
measurement — the overnight run is on Wi-Fi.

The desktop's address can also change under a pairing. On 2026-10-15 the desktop's LAN address moved from
192.168.50.22 to 192.168.0.199 (seen by the session-conversation run), and the phone could reach it only over Tailscale.
The phone knows only the addresses in the pairing link: the `hello` answer carries no addresses
(`docs/remote-protocol.md` sections 1 and 4 in spekterm), so nothing tells the app the new one. Today's remedies
are pairing again, a DHCP reservation on the router, or a stable extra address entered on the desktop (a Tailscale
or DDNS name). A lasting fix needs the desktop to send its current addresses after authentication, which is a protocol
change for spekterm and a separate change here.

## Unverified

- Whether Google Play accepts `specialUse` for this use — only the first upload can tell.
- Whether a sticky restart is refused on other Android versions or after a vendor's kill: on the Pixel 7
  (Android 17) it was allowed, in the background, with battery optimization on.
- Whether OkHttp's ping timer adds wake-ups while the CPU sleeps, and the battery cost of the held connection —
  not measured: the overnight run was taken out of this change.
- Whether Conscrypt wraps a plain `X509TrustManager` with its own endpoint identification — not tested; the app
  implements `X509ExtendedTrustManager`, which makes the question moot for it.
- Android's notification update rate limit and per-app cap (about 5 per second, about 50) — from memory of
  `NotificationManagerService`, not checked; the app coalesces replays regardless.
- Behavior on API levels other than the Pixel 7's — only one phone is tested.
- Backoff waits run on a monotonic clock that stops while the CPU sleeps, so in Doze a wait can stretch past its
  nominal length; "reconnects within one backoff cap" is a property of an awake phone. How long reconnections take
  overnight is not measured.
