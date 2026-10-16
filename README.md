# spekterm for Android

The companion Android app for [spekterm](https://github.com/spekhq/spekterm), the agent-first desktop
workbench. It connects **directly** to a spekterm you have paired with — over your LAN, Tailscale, or a port
you forward — with no relay and no push service in between.

**Status: in development.** Nothing is published yet. Findings from the phone tests:
[`docs/spike-findings.md`](docs/spike-findings.md) (connection) and
[`docs/conversation-findings.md`](docs/conversation-findings.md) (sessions and conversations).

## What it does

- **Pairing** by scanning the desktop's pairing QR code, or pasting its link.
- **Notifications** when an agent waits for you, asks for a choice, finishes a handoff, or an inbox item
  arrives — over the app's own held connection. Tapping one about a session opens its conversation.
- **Sessions**: the desktop's sessions by folder, with each agent's state (waiting for you, working, asks for a
  choice) or dormant, kept live. Folders can be collapsed; a collapsed folder still shows what waits for you.
  Long-pressing a session (or "⋮" on its screen) renames, hibernates, wakes or closes it. Closing asks first,
  and so does stopping a session the desktop says is busy.
- **New sessions**: start an agent session in any of the desktop's folders, quietly, and continue in its
  conversation on the phone.
- **Conversations**: an agent session's messages, thinking, tool calls and results; a dormant session can be
  woken. Agent text is Markdown that loads nothing from the network.
- **Replies**: free text to the agent while it waits or works, with photos (HEIC converted to JPEG), PDF or
  text files attached, shown as unconfirmed until it appears in the conversation.
- **Prompts**: a permission request, the agent's questions or a plan to approve is shown as plain text and,
  when the desktop says it can be, answered from the phone — allow once or deny, the questions' options or
  declining, approve or keep planning. "Tell Claude what to change" and a question's free-text answer are not
  offered: decline, then reply.

These need spekterm 29a5d0a or later (hibernating and closing: 9b02f53); against an older desktop the app shows
none of them.

Not yet: the terminal view (shell sessions), the inbox, handoffs, and OpenSpec browsing.

## How it talks to spekterm

The desktop side is built and documented in the spekterm repository:

- Protocol: [`docs/remote-protocol.md`](https://github.com/spekhq/spekterm/blob/master/docs/remote-protocol.md)
- Turning remote access on and pairing: spekterm's Settings → Remote access

In short: a TLS WebSocket on one port, a self-signed certificate the app pins by its SHA-256 fingerprint
(taken from the pairing QR code), and a per-device credential issued at pairing. Attention events (an agent
waiting for you, a finished handoff, a new inbox item) arrive only over the app's own connection; missed
ones are replayed on reconnect.

## Building and running

Needs JDK 17 and the Android SDK with the android-37 platform (compile) — the app targets Android 16 (API 36)
and runs on Android 10 (API 29) and later.

```sh
export ANDROID_HOME=~/Android/Sdk        # or put sdk.dir=… in local.properties
./gradlew assembleDebug                  # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                   # onto a phone connected with USB debugging
./gradlew test                           # JVM unit tests
./gradlew connectedDebugAndroidTest      # instrumented tests, on the connected phone
```

The build never downloads SDK components on its own (`android.builder.sdkDownload=false`); install a missing
one deliberately, for example once with `./gradlew assembleDebug -Pandroid.builder.sdkDownload=true`.

### Testing against a desktop

The released spekterm does not have remote access yet. Run it from source — `npm run dev` in a spekterm
checkout, which uses its own profile `~/.config/spekterm-dev` — then Settings → Remote access → turn on → Pair
device, and scan the code with the app. The phone must reach the desktop's port (47615 by default) over the LAN,
Tailscale, or a forwarded port. `tools/drop-intake.mjs` drops inbox items into that profile to produce attention
events on a schedule (see [`tools/README.md`](tools/README.md)).

## License

MIT — see [`LICENSE`](LICENSE).
