# Play Console answers

Every answer the app's Play Console pages ask for, prepared with the `play-release` change (2026-10-19). Play Console's own
wording was not available outside the Console; an answer marked **(wording to check in Console)** gives the substance, to be
matched to the question as the Console shows it. `docs/play-release.md` says when each is filled in.

## Store settings

- **App category**: Productivity.
- **Contact details**: the developer account's contact email (kewang chooses; it is shown on the listing and in the privacy
  policy's contact line). Website: `https://spekterm.com`.
- **Privacy policy URL**: `https://spekterm.com/android/privacy/` (Traditional Chinese: `https://spekterm.com/zh-tw/android/privacy/`;
  Play takes one URL).

## Main store listing

From `play/listings/<locale>/` — `title.txt`, `short-description.txt`, `full-description.txt`, `graphics/icon/icon.png`,
`graphics/feature-graphic/feature.png`, `graphics/phone-screenshots/*.png` — for en-US (default) and zh-TW.

## App access

**All or some functionality is restricted** → one entry, name "Pairing with spekterm on a computer", no username or
password, and these instructions (the field takes at most 500 characters; this is 495):

> No account or password. The app is a remote client for spekterm, a desktop app (0.2.14 or later, Linux or Apple silicon
> Mac: github.com/spekhq/spekterm/releases), and shows nothing until paired. In spekterm: Settings > Remote access, turn it
> on, Pair a device. In the app: Scan QR code (the phone must reach the computer over LAN, Tailscale or a forwarded port).
> Agent activity needs an AI coding agent there, such as Claude Code. No demo mode. Video:
> https://www.youtube.com/watch?v=hX5qdI8e0yg

## Ads

**No, my app does not contain ads.**

## Advertising ID

**No** — the app does not use an advertising ID. The merged manifest has no `com.google.android.gms.permission.AD_ID`, and
`checkLicenses` refuses advertising and analytics libraries. **(wording to check in Console)**

## Content rating

IARC questionnaire, category **Utility, Productivity, Communication, or Other** **(wording to check in Console)**:

- Violence, sexuality, nudity, profanity or crude humor, controlled substances, gambling, horror or fear themes: **No** —
  the app shows only what the user's own agent on the user's own computer writes, and nothing from the developer.
- Users can interact or exchange content with other users: **No** — the only exchange is between the user's phone and the
  user's own computer.
- Shares the user's current location: **No**.
- Digital purchases: **No**.
- Unrestricted internet access (a browser or search engine): **No** — a link opens in the user's browser only when tapped.

## Target audience and content

- **Target age groups**: 18 and over only.
- **Appeal to children**: No; nothing in the listing or the app targets children.

## Data safety

**Does your app collect or share any of the required user data types? → No.**

Why (help page "Provide information for Google Play's Data safety section", read 2026-10-19): data "sent off device, but that
is unreadable by you or anyone other than the sender and recipient" because of end-to-end encryption is not collected; "no
intermediary, including the developer" may be able to read it. The app's only connection goes to the user's own computer,
over TLS pinned to that computer's own certificate (`net/Pinning.kt`) — the phone and the user's computer are the two ends,
with no server of the developer or anyone in between. The connection log leaves the phone only when the user shares it with
another app from Settings, which is the user giving a file to an app on the phone, not this app sending it. Even an app that
collects nothing must submit the form and a privacy policy URL; both state that nothing is collected or shared.

Fallback, should Play question it: declare **Messages** (other in-app messages: the replies), **Photos**, and **Files and
docs** as *collected*, *encrypted in transit*, *not shared*, *required for the app's functionality* (replies and
attachments), *processed ephemerally: no*, with deletion through the user's own computer; and add the same to the privacy
policy on spekterm.com (by handoff to the spekterm repository).

## Government apps, financial features, health apps, news apps

- Government app: **No**.
- Financial features: **My app doesn't provide any financial features**.
- Health apps: **My app does not have any health features**.
- News or magazine app: **No**.
- COVID-19 contact tracing and status apps (if Play still asks): **My app is not a publicly available COVID-19 contact
  tracing or status app**.

All **(wording to check in Console)**.

## Generative AI

If Play asks whether the app generates content with AI **(wording to check in Console)**: **No**. The app runs no AI
model and has no server; it shows what the user's own AI coding agent, running on the user's own computer under that
agent provider's terms, writes, the way a remote desktop or SSH client shows a remote program's output. A conversation's
menu still offers Report a problem, which opens the project's issue page (`play-release` D11).

## Foreground service permissions

Declared type: **Special use** (`FOREGROUND_SERVICE_SPECIAL_USE`, `android:foregroundServiceType="specialUse"` on
`ConnectionService`). Other types were weighed in `docs/spike-findings.md` §3.

- **Describe the app functionality that uses this permission**:

  > spekterm's companion app keeps a direct, encrypted connection to the user's own computer running spekterm (on the same
  > network, over Tailscale, or a port the user forwards), and shows a notification when an AI coding agent there waits for
  > the user's reply, asks for a choice or a permission, finishes a handed-off task, or an inbox item arrives. There is no
  > server in between, so push messaging cannot deliver these notifications; the held connection is the only way they reach
  > the phone. An ongoing notification shows the connection's state, and the user can pause it there or in Settings.

- **User impact if the task is deferred or interrupted by the system**:

  > The user is not told that an agent on their computer is blocked waiting for them; the agent stays idle until the user
  > happens to look. Events missed while disconnected are replayed when the connection returns, so an interruption delays
  > the notifications rather than losing them, but a delay of minutes or hours defeats their purpose.

- **Video link**: https://www.youtube.com/watch?v=hX5qdI8e0yg (unlisted), the script below.

### Video script (as recorded on 2026-10-19, on the emulator against a demo desktop, `play-release` D8)

75 seconds; the file is `~/Videos/spekterm-fgs-demo.mp4` on the maintainer's computer (not committed).

1. The session list of a paired desktop ("demo"), its agents and their states.
2. The notification shade: the ongoing "Connected via …" notification of the connection service.
3. A conversation opens; the app goes to the background (Home).
4. On the desktop, the agent finishes a turn: the phone shows "Waiting for your input" as a heads-up notification.
5. Tapping it opens that session's conversation, with the agent's new reply.
6. In the shade, Pause on the ongoing notification: the notification and the service go away ("No notifications").

The ongoing notification shows the desktop's private LAN address (192.168.x.x), which is unreachable from outside that
network.
