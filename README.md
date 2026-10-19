# spekterm for Android

The companion Android app for [spekterm](https://github.com/spekhq/spekterm), the agent-first desktop
workbench. It connects **directly** to a spekterm you have paired with — over your LAN, Tailscale, or a port
you forward — with no relay and no push service in between.

**Status: in development.** Nothing is published yet. Findings from the phone tests:
[`docs/spike-findings.md`](docs/spike-findings.md) (connection) and
[`docs/conversation-findings.md`](docs/conversation-findings.md) (sessions and conversations).

## What it does

- **Pairing** by scanning the desktop's pairing QR code — tap to focus, pinch to zoom; another QR code in view is
  skipped — or pasting its link. A pairing link opened from another app (the phone's camera, a scanner, a browser)
  pairs only after asking, and says whether it is the paired desktop or another. Unpairing from the phone asks first.
- **A look of spekterm's own**, light or dark as the phone is set, or as chosen in Settings → Appearance.
- **English or Traditional Chinese (Taiwan)**, as the phone is set, or per app in Android 13+'s language settings,
  in the desktop's own words. What the desktop and its repositories send is shown as sent.
- **Connecting**: the desktop's addresses are tried a quarter of a second apart, the first that answers winning.
  When none does, Settings says why — no network, an address refusing, off the desktop's network, or no answer —
  with each address's outcome, and "Try now" ends the wait.
- **Notifications** when an agent waits for you, asks for a choice, finishes a handoff, or an inbox item
  arrives — over the app's own held connection. Tapping one about a session opens its conversation.
- **Sessions**: the desktop's sessions by folder, with each agent's state (waiting for you, working, asks for a
  choice, idle) or dormant, kept live. An agent whose finished turn you already looked at — on the desktop, or by
  opening its conversation on the phone — says "Idle" instead of "Waiting for you", and its waiting notification
  goes away. Folders can be collapsed; a collapsed folder still shows what waits for you.
  Long-pressing a session (or the menu on its screen) renames, hibernates, wakes or closes it. Closing asks first,
  and so does stopping a session the desktop says is busy. When an agent asks to close its own session, its row
  and its screen say so with "Close" and "Keep"; that Close is the answer, so it does not ask again.
  An agent that still runs work in the background — subagents, shells (the Monitor tool's waits among them),
  monitors — says so on its row ("Background: 1 subagent, 2 shells"), and its screen lists each task as its agent
  last reported it.
- **Handoffs**: a session handed off from another sits under it, as on the desktop's rail, or says where it came
  from when its source is in another folder or gone; a session that handed off others says how many and how many
  are done. "Handoff" in a session's menu shows where it came from, the handoff's note, its latest report and the
  sessions handed off from it.
- **Inbox**: the desktop's inbox, from the session list's top bar, which counts what waits for you. Each item shows its
  whole body as plain text first; choose the folder it opens in (preselected as the desktop does), then accept or
  dismiss it. Handoffs scheduled for later are listed apart with their time, to run now or cancel. An accepted item's
  first prompt, filled in on the desktop and not sent, is shown next to its body, to send from the phone or finish at
  the desktop; meanwhile its session's screen says so and holds replies back, so they are not sent along with it. A new
  item's notification opens the inbox at it, or the session it already opened.
- **New sessions**: start an agent session in any of the desktop's folders, quietly, and continue in its
  conversation on the phone.
- **Conversations**: an agent session's messages, thinking, tool calls and results — a tool call shown with its
  result, and a run of calls summarized in one line that opens to each; a dormant session can be woken. Agent text is Markdown that loads nothing from the network.
- **Replies**: free text to the agent while it waits or works, with photos (HEIC converted to JPEG), PDF or
  text files attached, shown as unconfirmed until it appears in the conversation.
- **Prompts**: a permission request, the agent's questions or a plan to approve is shown as plain text and,
  when the desktop says it can be, answered from the phone — allow once or deny, the questions' options or
  declining, approve or keep planning. "Tell Claude what to change" and a question's free-text answer are not
  offered: decline, then reply.
- **OpenSpec**, read-only, opened from a folder's heading, the list's menu or a session's screen: each folder's
  active and archived changes, a change's proposal, design, delta specs, tasks and data files, and the main specs
  with the changes that touched them. While a folder is on screen, it follows the desktop's files — an agent
  ticking a task shows up by itself — on a desktop that offers that; otherwise a refresh reads them again.
  Repository text loads nothing and opens a link only when tapped.

These need spekterm 29a5d0a (0.2.6) or later (hibernating and closing: 9b02f53; an agent's close offer: 3feaac0;
following OpenSpec files live: 0fcdc1f, 0.2.8; "Idle": 01cb50a;
background work: c79785f, 0.2.11; scheduled handoffs: 445dc24, 0.2.12; sending an accepted item's first prompt: b45c359,
0.2.13); against an older desktop the app shows none of
them, and a ready agent stays "Waiting for you".

Not yet: the terminal view (shell sessions).

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

Use spekterm [0.2.14](https://github.com/spekhq/spekterm/releases/tag/v0.2.14) or later, which has everything
above, or run it from source — `npm run dev` in a spekterm checkout, which uses its own profile
`~/.config/spekterm-dev` — for a desktop change not released yet. Then Settings → Remote access → turn on → Pair
device, and scan the code with the app. The phone must reach the desktop's port (47615 by default) over the LAN,
Tailscale, or a forwarded port. `tools/drop-intake.mjs` drops inbox items into the dev profile (by default) to produce attention
events on a schedule (see [`tools/README.md`](tools/README.md)).

## License

MIT — see [`LICENSE`](LICENSE).

## Third-party

- [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) (Apache-2.0) decodes the pairing QR code on the phone.
