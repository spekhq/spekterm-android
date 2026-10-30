# spekterm for Android

The companion Android app for [spekterm](https://github.com/spekhq/spekterm), the agent-first desktop
workbench. It connects **directly** to a spekterm you have paired with — over your LAN, Tailscale, or a port
you forward — with no relay and no push service in between.

**Status: in development.** Nothing is published yet. Findings from the phone tests:
[`docs/spike-findings.md`](docs/spike-findings.md) (connection) and
[`docs/conversation-findings.md`](docs/conversation-findings.md) (sessions and conversations).

## Install

[Get it on Google Play](https://play.google.com/store/apps/details?id=com.spekterm.android), or build it as below.
Publishing it is described in [`docs/play-release.md`](docs/play-release.md). It pairs with a computer running spekterm
[0.2.14](https://github.com/spekhq/spekterm/releases) or later — Linux or an Apple silicon Mac — with Settings → Remote
access turned on.

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
  opening its conversation on the phone, or a screen opened from it that can reply to it — says "Idle" instead of
  "Waiting for you", and its waiting notification goes away. Folders can be collapsed; a collapsed folder still shows what waits for you.
  Long-pressing a session (or the menu on its screen) renames, hibernates, wakes or closes it. Closing asks first,
  and so does stopping a session the desktop says is busy. When an agent asks to close its own session, its row
  and its screen say so with "Close" and "Keep"; that Close is the answer, so it does not ask again.
  An agent that still runs work in the background — subagents, shells (the Monitor tool's waits among them) — says
  so on its row ("Background: 1 subagent, 2 shells"), and its screen lists each task as its agent
  last reported it. Subscriptions the agent's CLI keeps on its own, such as an artifact's live updates, are not
  listed.
- **Handoffs**: a session handed off from another sits under it, as on the desktop's rail, or says where it came
  from when its source is in another folder or gone; a session that handed off others says how many and how many
  are done. "Handoff" in a session's menu shows where it came from, the handoff's note, its latest report and the
  sessions handed off from it.
- **Inbox**: the desktop's inbox, from the session list's top bar, which counts what waits for you. Each item shows its
  whole body as plain text first; choose the folder it opens in (preselected as the desktop does), then accept or
  dismiss it. Handoffs scheduled for later are listed apart with their time, to run now or cancel. A repeating handoff an
  agent proposes is listed apart, waiting for approval, with its schedule, its next times and its end; it is approved on
  the desktop and can be declined from the phone; each of its times is listed with the schedule, to run now or skip
  that time only. An accepted item's first prompt, filled in on the desktop and not sent, is shown next to its body, to
  send from the phone or finish at the desktop; meanwhile its session's screen says so and holds replies back, so they are not sent along with it. A new
  item's notification opens the inbox at it, or the session it already opened.
- **New sessions**: start an agent session in any of the desktop's folders, quietly, and continue in its
  conversation on the phone.
- **Conversations**: an agent session's messages, thinking, tool calls and results — a tool call shown with its
  result, and a run of calls summarized in one line that opens to each; a dormant session can be woken. Each call
  says what it did: a command and its output told apart, a message to another session with that session's name, an
  edit's removed and added lines, the subagent and its prompt, the skill, a file read line by line with its numbers
  set apart. Pictures a tool brought back — an image the agent read, a screenshot — show in the conversation, a run's
  as a strip of tiles, and open full screen. A message from another session or a
  subagent's report says who sent it. A file the agent sends you opens on the phone — images, Markdown and text in
  the app, anything else handed to another app. Agent text is Markdown that loads nothing from the network. Your messages and the
  agent's text show when they were written, and the conversation is divided by day ("Today", "Yesterday", the date).
  A line under the title shows what the agent last reported: its model and effort, how full its context is, the
  session's cost, and the account's rate limits when they are close to full. Tapping it (or "Model and effort" in the
  menu) changes the session's effort or model — which Claude Code also keeps as the default for new sessions on that
  computer — and shows every value, with when the limits reset. A model switch holds replies and answers until the
  agent reports the new model, so nothing sent meanwhile can confirm a question the desktop's terminal may be asking.
  The answer to a built-in command such as `/effort` or `/compact` is shown as the command's output.
- **Replies**: free text to the agent while it waits or works, with photos (HEIC converted to JPEG), PDF or
  text files attached, shown as unconfirmed until it appears in the conversation. Typing `/` at the start of
  the reply shows the session's skills, filtered as you type; choosing one fills it in as a slash command. A command that never appears says
  why it may not have, and one too long to run as a command says so before it is sent.
- **Shell commands**: Command mode, in the menu of the button beside the reply field, turns it into a command field that runs a command in the
  agent's terminal on the desktop, as `!` does there — without a permission prompt and without input, so a login
  that asks for a password or opens a browser cannot be finished from the phone. "Run it myself" under an agent's
  `Bash` call, or tapping a `` `! command` `` in its text, puts that command in the field without running it. A
  command shows as running until it finishes; commands run from the phone or the desktop show with their output,
  marked failed when they printed errors.
- **Prompts**: a permission request, the agent's questions or a plan to approve is shown as plain text and,
  when the desktop says it can be, answered from the phone — allow once or deny, the questions' options or
  declining, approve or keep planning. "Tell Claude what to change" and a question's free-text answer are not
  offered: decline, then reply.
- **OpenSpec**, read-only, opened from a folder's heading, the list's menu or a session's screen: each folder's
  active and archived changes, a change's proposal, design, delta specs, tasks and data files, and the main specs
  with the changes that touched them. While a folder is on screen, it follows the desktop's files — an agent
  ticking a task shows up by itself — on a desktop that offers that; otherwise a refresh reads them again.
  Repository text loads nothing and opens a link only when tapped. Opened from a session's screen, the OpenSpec —
  and the session's handoff screen — keeps that session's reply field at the bottom, attachments, skills and
  prompts included, with a line saying how the agent and your last reply stand; tapping it goes back to the
  conversation.

These need spekterm 29a5d0a (0.2.6) or later (hibernating and closing: 9b02f53; an agent's close offer: 3feaac0;
following OpenSpec files live: 0fcdc1f, 0.2.8; "Idle": 01cb50a;
background work: c79785f, 0.2.11; scheduled handoffs: 445dc24, 0.2.12; sending an accepted item's first prompt: b45c359,
0.2.13; the agent's model and usage, the session's skills and changing its effort: 6b6ebfa, 0.2.15; changing its model: b7feaaa,
0.2.24; repeating handoffs: d88a3d1, 0.2.16; what tool calls did, who sent a message and opening sent files:
13d8ee7, 0.2.23; pictures in tool results: 6ff5fa4, 0.2.24); against an older desktop the app shows none of
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
./gradlew installDebug                   # onto a phone connected with USB debugging, as "spekterm dev"
./gradlew test                           # JVM unit tests
./gradlew connectedDebugAndroidTest      # instrumented tests, on the connected phone
```

A debug build is `com.spekterm.android.debug`, named "spekterm dev", so it installs beside the Google Play
build; each pairs with a desktop on its own.

The build never downloads SDK components on its own (`android.builder.sdkDownload=false`); install a missing
one deliberately, for example once with `./gradlew assembleDebug -Pandroid.builder.sdkDownload=true`.

### Testing against a desktop

Use spekterm [0.2.16](https://github.com/spekhq/spekterm/releases) or later, which has everything
above, or run it from source — `npm run dev` in a spekterm checkout, which uses its own profile
`~/.config/spekterm-dev` — for a desktop change not released yet. Then Settings → Remote access → turn on → Pair
device, and scan the code with the app. The phone must reach the desktop's port (47615 by default) over the LAN,
Tailscale, or a forwarded port. `tools/drop-intake.mjs` drops inbox items into the dev profile (by default) to produce attention
events on a schedule (see [`tools/README.md`](tools/README.md)).

## Privacy

The app sends nothing to its developer or anyone else: it connects only to the desktop you pair it with. The privacy
policy: [spekterm.com/android/privacy](https://spekterm.com/android/privacy/).

## License

MIT — see [`LICENSE`](LICENSE).

## Third-party

Every library the app ships, with its license, is listed in the app under Settings → About → Open-source licenses (from
[`app/src/main/res/raw/third_party_licenses.txt`](app/src/main/res/raw/third_party_licenses.txt), which the build checks
against what it ships). Among them, [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) (Apache-2.0) decodes the pairing QR
code on the phone.
