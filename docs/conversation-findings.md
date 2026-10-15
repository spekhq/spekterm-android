# Sessions and conversations: findings

What the `session-conversation` change was checked against, scenario by scenario, and what is still open.

**Where it ran (2026-10-14):** an Android emulator (AVD `Pixel_9`, Android 16 / API 36, x86_64) on the
maintainer's Linux machine, against a throwaway spekterm run from source: its own profile, remote access on
port 47701, the dev server's renderer, on a virtual display, with a **real Claude Code** agent in this
repository's folder. The desktop was driven over the Chrome DevTools Protocol (as spekterm's own probes do);
the emulator over adb and uiautomator.

**Then on the Pixel 7 (2026-10-15)** (Android 17), over Tailscale, against the maintainer's own spekterm from
source (`npm run dev`) with its real sessions; the desktop was then restarted with a DevTools port and driven
over CDP like the emulator run's.

## Automated

| Suite | Where | Result |
|---|---|---|
| JVM unit tests (91): conversation reducer, unconfirmed matcher, reply gate and outcomes, session grouping, stamp rule, screen precedence, attention policy with a visible session, frame decoding | host | pass |
| `DesktopTest` (9): the desktop layer over a real pinned connection to a scripted desktop on loopback — events before the watch answer, no duplicates on an overlapping re-delivery, no second watch, reconnect resumes and applies the new list, send outcomes, window gone then back (dormant), stop/start unwatches and re-watches, focus ownership, frames of an ended connection ignored | emulator | pass |
| `AgentMarkdownTest` (2): no request for a Markdown image, an `<img>` tag or a link; `Vec<T>`, `<command-name>…</command-name>` and an HTML block shown as text; only `http`/`https` links open | emulator | pass |
| `RemoteClientTest` arrival stamps in wire order, plus the spike's suites | emulator | pass |

## Scenarios against the desktop

| Scenario (spec) | Outcome |
|---|---|
| Pairing the emulator by pasted link (a second device; the Pixel 7 untouched) | pass |
| A new session appears in the list, live, with "Waiting for you" (session-list) | pass |
| A session closed on the desktop disappears from the list; open on the phone, it says "closed on the desktop" (session-list, conversation-view) | pass |
| Not connected (airplane mode): the list says so with its time; remote access turned off: the list says "Remote access is off…, or spekterm quit"; back on: current again within 2 s (session-list) | pass |
| Paused (from the status screen): the list and the conversation say so and offer Resume; Resume reconnects (session-list, conversation-view) | pass |
| A shell opens a page saying it needs the terminal view; no watch is made (session-list) | pass |
| Reading a conversation; new entries appear without refreshing (conversation-view) | pass |
| A two-line reply arrives as **one** user record with its line break (`"…phone.\nReply with…"` in the transcript) (conversation-reply) | pass — and the phone then showed it on one line; fixed: line breaks in user and agent text are kept (Markdown soft breaks rendered as new lines) |
| A reply while the agent waits: shown at once, then confirmed by the conversation's own message (conversation-reply) | pass |
| A reply while the agent is generating: confirmed once the agent finished its turn — Claude Code records a reply typed meanwhile as an ordinary user record after the turn (design Risks: resolved) | pass |
| "Working…" while the agent works (conversation-view) | pass during tool calls. **During text-only generation the desktop reports `ready`** — see Findings |
| A permission prompt (agent in manual permission mode, `mkdir -p /tmp/…`): sending refused, "The agent asks for a choice: Bash — mkdir -p /tmp/…. Answer it on the desktop.", no answer controls; answered on the desktop → sending possible again (conversation-reply) | pass; a later wait event of the same prompt carries no tool, which showed "asks for a choice: ."; first fixed to say "asks for a choice" plainly, then (Pixel run) to keep the tool already shown |
| Airplane mode while the agent writes, then back: the conversation resumes from the last entry (`resume=true` in the log), nothing repeated or lost (conversation-view) | pass |
| Screen off while the agent writes, on again: unwatched while off, re-watched with resume, every entry once (conversation-view) | pass |
| The desktop's window reloaded (which ends every pty): "dormant" with Wake; Wake → the conversation resumes (conversation-view) | pass |
| Hibernated on the desktop while reading: dormant, unwatched; Wake from the phone → watched again (conversation-view) | pass |
| No notification while the conversation is visible (log outcome `visible`) (attention-notifications) | pass |
| Left the conversation: the waiting notification is posted; tapping it opens that conversation, Back shows the list, the notification is gone (attention-notifications) | pass |
| Tapping one session's notification while another session's conversation is open (attention-notifications) | pass |
| Tapping a notification after the app's process was killed | pass |
| The connection log holds watch / unwatch / send / wake codes and no conversation text or session label | pass |

## On the Pixel 7

| Scenario (spec) | Outcome |
|---|---|
| The new build installed over the spike's, keeping its pairing; paused → Resume → the list (session-list) | pass |
| The desktop's LAN address had changed and Tailscale was off on the phone: "Connecting to the desktop… No session list received yet."; Tailscale on → connected, list shown (session-list) | pass |
| The list by folder, Global first, dormant agents and shells, a handoff session's lifecycle (session-list) | pass |
| Waking a dormant session from the phone; its conversation loads (conversation-view) | pass |
| A woken session whose folder (the home directory) was never trusted by Claude Code: the agent sits at its trust prompt, sends no hook, so the wait is unknown and the phone says to reply on the desktop (conversation-reply) | pass — correct, not a defect |
| A reply while the agent waits: shown, confirmed, the agent's answer appears live (conversation-reply) | pass |
| No notification while that conversation is visible (log outcome `visible`) (attention-notifications) | pass |
| Left the app: "Waiting for your input" posted; tapping it opens the conversation, removes the notification; Back shows the list (attention-notifications) | pass |
| Tapping one session's notification while another session's conversation is open: the tapped one opens, the list behind it (attention-notifications) | pass |
| Task removed, opened from the notification (its base intent opens that conversation), Back to the list, process killed, reopened from recents: the list, not the conversation again (D8) | pass |
| "Connection stopped": after the kill Android allowed the sticky restart, so the notification was not posted; its intent sent by hand: Sessions shown, connected (D8) | pass by its intent only |
| A long session: 210 tool calls (1,043 transcript lines) followed live; reopened fresh (`resume=false`): the newest entry visible within 3 s including the test's screen reads, "Older entries are not shown." at the top; scrolled up, "Newest" returns to the end (conversation-view) | pass |
| The connection log holds no reply text | pass |
| A new session made on the desktop appears in the list, live, "Waiting for you" (session-list) | pass |
| A permission prompt (manual permission mode): "The agent asks for a choice: Bash — rmdir /tmp/…. Answer it on the desktop."; Send does nothing; answered on the desktop → sending possible (conversation-reply) | pass after a fix: the desktop reports the same prompt twice and only the first names the tool, so the second report replaced it with a bare "asks for a choice"; a report without a tool now keeps the one held while the choice lasts |
| A reply while the agent runs tools: answered by the agent, but never confirmed — Claude Code delivers it mid-turn as a `queued_command` attachment, which spekterm does not project; labeled "Sent while the agent worked — it may not show in the conversation" at its send position (conversation-reply, design Risks) | pass as designed; see Findings |
| Hibernated on the desktop while reading: dormant at once; Wake → the conversation resumes from where it was (`resume=true`) (conversation-view) | pass |
| The desktop's window reloaded while reading: dormant; Wake → resumes (conversation-view) | pass |
| Remote access turned off: the conversation and the list say so, the list with its time; on again: connected about 3 s later (session-list) | pass |
| Paused, then a conversation opened: "The connection is paused…" with Resume; Resume → connected and re-watched (conversation-view) | pass |
| A session closed on the desktop while reading: "This session was closed on the desktop."; gone from the list (session-list, conversation-view) | pass |
| Airplane mode for 25 s while the agent made one tool call per message: reconnected and re-watched with `resume=true`, the entries continue in order with no repeats (conversation-view) | pass |
| Screen off for about 90 s while the agent made 40 tool calls, one per message (done by hand: the lock screen needs the owner): unwatched when the screen went off, 33 of the 40 calls made meanwhile, re-watched with `resume=true` after unlocking; every call shown once, in order (conversation-view) | pass |

## Not run, and why

- **`filled-not-submitted`**: a prompt must appear within 500 ms of a reply; carried by `DesktopTest` and the
  unit tests.
- **Wake with no window** (`NO_WINDOW`): only macOS has a desktop without a window.
- **"Connection stopped" posted by Android refusing a restart**: the Pixel allowed the restart after a kill;
  only the notification's intent was exercised.

## Findings

- **Fixed in spekterm 0.2.5 (commit 4c37609); re-run below: "Working…" during a text-only answer.**
  **The desktop reports `ready` while an agent writes without tools.** spekterm derives "busy" from
  `PreToolUse`/`PostToolUse` and "ready" from `Stop`; it injects no `UserPromptSubmit` hook
  (`agent-events.ts` `nextWaitState`, the injected `settings.json`). So during a text-only answer both the
  desktop and the phone show "Waiting for you", and a reply sent then is treated as sent while ready (its "not
  in the conversation yet" label can appear after 30 s, and clears when the turn ends). A spekterm change:
  `UserPromptSubmit` → `busy`.
- **The Markdown renderer's defaults conflict with the spec**: it animates content size (a transition whose
  length grows with the text) and parses asynchronously (an entry first shows empty). Both are switched off
  (`ui/AgentMarkdown.kt`).
- **Fixed in spekterm 0.2.5 (commit 4c37609); re-run below: shown as "Injected by the agent".**
  **A background task's completion shows as the user's message.** When an agent runs a command in the background,
  Claude Code later writes a user record holding `<task-notification>…</task-notification>`; it has
  `origin.kind: "task-notification"` and `promptSource: "system"` but no `isMeta`, so spekterm sends it with
  `meta: false` and both the desktop ("你") and the phone ("You") show it as the user's words, raw XML included.
  The same turn also produces two "waiting" attention events: one when the agent ends its turn after starting
  the background command, one when it answers the task's completion. Both are spekterm's to classify.
- **Fixed in spekterm 0.2.5 (commit 4c37609); re-run below: confirmed where the agent took it in.**
  **A reply typed while the agent runs tools never shows in the conversation.** Claude Code (2.1.300) delivers it
  mid-turn as an `attachment` of type `queued_command` (with the text in `prompt`), not as a user record, and
  spekterm projects no entry for it — the desktop's own conversation view does not show it either. Replies typed
  during text-only generation do arrive as user records after the turn (emulator run). A spekterm change: project
  `queued_command` attachments as user entries.
- **Fixed in spekterm 0.2.5 (commit 4c37609); re-run below: the tool stays shown.**
  **The desktop drops a prompt's tool on its second report.** `pending` comes from the last drained batch, and a
  batch holding only the prompt's `Notification` yields none. The phone keeps the tool it holds; the desktop could
  keep it while the state stays `awaiting-choice`.
- **Frame times in the debug build**: 15 % janky frames over the whole run, scrolling the long conversation included (90th percentile 27 ms), measured on a
  debuggable, unminified build; not a release measurement.
- Desktop attention events for the session displayed in the desktop's focused window arrive `suppressed`, as
  the spike found; with a virtual display the throwaway desktop's window counts as focused.

## Against spekterm 0.2.5

Change `addresses-and-mid-turn-replies`, 2026-10-15, on the Pixel 7 (Android 17) against the maintainer's installed
spekterm 0.2.5 (the AppImage; it sends the address list, which only 0.2.5 and later do), with remote access turned on and the phone paired with it
anew for this run. A new Claude Code session in this repository's folder was driven from the phone (adb, ADB
Keyboard, uiautomator); the maintainer worked the desktop's settings and answered its prompts. The desktop's
transcript (`~/.claude/projects/…/<session>.jsonl`) was read to see how each reply reached the agent.

| Scenario (spec) | Outcome |
|---|---|
| JVM unit tests (111), including the address order and list reading, event routing with an unknown kind, and the busy-reply grace | host: pass. `CredentialStoreTest` (instrumented) updated and compiled, not run: instrumented runs uninstall the app on the Pixel |
| Pairing: the `pair` answer's list stored with the pairing; the first `hello` answer carried the same three addresses (log `addresses source=hello count=3 changed=false`) (desktop-connection) | pass |
| The desktop's additional address set to its Tailscale MagicDNS name, not in the pairing link, while connected: the phone stored the new list 0.6 s after the desktop saved it (log `addresses source=event count=4 changed=true`); the status screen listed the name (desktop-connection) | pass |
| Remote access off for about 15 s: every round tried the LAN address, the Tailscale IPv4 and IPv6, then the MagicDNS name (resolved, refused); back on: reconnected through the last good address, `hello` carrying the same four (`changed=false`) (desktop-connection) | pass |
| An address that disappears for good was not produced; reaching the desktop by another address after the LAN one changed was run in the Pixel run above, and the order is carried by `PairingTest` | carried by tests |
| An unknown event kind: the desktop cannot be made to send one; carried by `EventRouteTest` and `FramesTest` (desktop-connection) | carried by tests |
| A text-only answer (a 700-word story, no tools): "Working…" throughout (conversation-view) | pass |
| A reply sent during that text-only answer: "Waiting for the agent to take it", then confirmed as the user's message when the agent took it in a second turn; the fallback label never shown (conversation-reply) | pass |
| Replies while the agent ran eight foreground `sleep 5` calls: one line and three lines, each delivered as a `queued_command` and shown as the user's message between the tool calls where the agent took it, text verbatim; neither labeled (conversation-reply) | pass |
| A permission prompt (WebFetch of a new domain, "accept edits" mode): "The agent asks for a choice: WebFetch — https://example.org", the tool still shown across the desktop's later reports of the same prompt (conversation-reply) | pass |
| A reply queued during the text before a prompt, the prompt then raised before the agent took it: "Waiting for the agent to take it" for the 30+ s the prompt stayed open (conversation-reply) | pass |
| That prompt answered "No": Claude Code interrupted the turn, then submitted the queued reply as the next prompt (`dequeue`, then a user record); the phone confirmed it. Nothing was left in the desktop's input | pass — the design's unmeasured case: a refused prompt does not lose a queued reply |
| A background task's notice (`sleep 30` moved to the background by Claude Code): "Injected by the agent", not "You" (conversation-view) | pass |

A `mkdir -p /tmp/…` raised no prompt in this setup (the maintainer's permissions allow it), unlike the earlier runs;
WebFetch of a new domain was used instead.
