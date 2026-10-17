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

## Folders and new sessions

Change `folders-and-new-sessions`, 2026-10-15.

| Check | Where | Outcome |
|---|---|---|
| JVM unit tests, including group keys / branches / summaries, folder choices, `canCreate`, `CreateOutcome` for every answer, `CreateTracker` (answer then list, list first, listed not yet running, another connection's list, detach, timeout, an earlier create's timer, an answer after its connection detached, refusal, leaving the list), and the global item's explicit `"folderId":null` on the wire | host | pass |
| `DesktopTest` (18, nine new): answer then list → opened; list before the answer → opened at once; listed not running → still starting, opened once running; no list in time → unlisted; detach while waiting → unlisted; detach before the answer → unknown, and the next connection receives no `sessions.create`; a second create while starting sends nothing; `clear()` mid-create writes nothing; the desktop's `CREATE_FAILED` reason shown | emulator (AVD `Pixel_9`, Android 16), the Pixel disconnected from adb | pass |
| `CollapsedStoreTest`: round trip, another pairing reads nothing, delete; and the earlier instrumented suites (38 tests in all) | emulator | pass |

On the Pixel 7 against the maintainer's installed spekterm 0.2.5, installed with `adb install -r` (pairing kept).
The maintainer looked through the screens; the outcomes below are what they reported and what the exported
connection log shows. Items not exercised are listed as such.

| Scenario (spec) | Outcome |
|---|---|
| Top bar shows only "New" and "⋮"; "⋮ › Settings" opens the settings screen, connection first; back returns (session-list) | pass (maintainer) |
| Headings with name, branch and count stay on top while a long group scrolls; tapping collapses and expands; a collapsed group shows its waiting count; collapse all / expand all (session-list) | pass (maintainer) |
| A collapsed group still collapsed after `am force-stop` and reopening (session-list) | pass (maintainer) |
| Starting a session in a folder and in the global item: the conversation opened 0.25–0.27 s after the answer (log `create … outcome=created`, then `create event=listed`, then `conversation event=watch`); the log holds no folder name (session-create) | pass |
| A first reply from a session started on the phone: started in a folder at 08:43:15.0 Z, listed and opened 0.18 s later, "hi" sent 7.1 s after opening (`send outcome=Submitted`; the 7.1 s include typing, so the wait state left unknown within that), and the agent's turn ended 3.3 s later (`attention kind=waiting outcome=visible`) (session-create, conversation-reply) | pass |
| The desktop's selection unchanged; the session in the chosen folder on the rail (session-create) | pass (maintainer) |
| Going to another conversation right after choosing a folder: no navigation, "Open" offered afterwards (session-create) | not run — carried by `CreateTrackerTest` (`leftList`) only |
| Remote access turned off while the folder sheet is open: the sheet closes (session-create) | not run — carried by `canCreate` tests only |
| What the desktop shows when the chosen empty folder is itself selected (design risk) | not run |

## Prompts, attachments and the session menu

Change `prompts-attachments-rename`, 2026-10-16, against the protocol of spekterm 9b02f53.

| Check | Where | Outcome |
|---|---|---|
| `./gradlew test assembleDebug`: 179 JVM tests, including `FramesTest` (operations, the new payloads with their optional keys, `detail` fields, results), `PromptTest`, `AnswersTest`, `AttachmentKindTest`, `UploadsTest`, `RenameTest`, `SessionActionsTest`, and the new cases of `RepliesTest`, `ConversationReducerTest` and `SessionsViewTest` | host | pass |
| `DesktopTest` (31, thirteen new): operations kept across a detach and forgotten by `clear()`; an answer names its prompt and records `partial`; another prompt id and a second answer in flight send nothing; a detach mid-answer → unknown; leaving while an answer is in flight still records it, and the next prompt can be answered; a 150 KiB file in three chunks (64 + 64 + 22 KiB, the id from the second); resume from `detail.received` after a reconnect; `NOT_FOUND` mid-upload restarts without an id; `TOO_MANY` with its limit; focusing another session deletes the file; a send carries the ids and is confirmed by `r.text`; rename / hibernate / close payloads, `force` only when agreed; `hibernated` → dormant, not window-gone; a list without the session, or `closed`, drops the attachments and their files | emulator (AVD `Pixel_9`, Android 16), only the emulator listed by `adb devices` | pass |
| `PreparerTest` (7): a HEIC sample becomes an upright JPEG; a JPEG with EXIF orientation 6 is re-encoded upright; an upright JPEG is copied byte for byte; a PNG of noise over 10 MiB becomes a JPEG under 10 MiB; a ZIP and an empty file are refused; a 3 KB UTF-8 text passes; a 25 MB PDF stream is refused after reading just over 10 MiB; and the earlier instrumented suites (58 tests in all) | emulator | pass |

On the Pixel 7 against spekterm 9b02f53 (an AppImage built from master in a scratch clone, version still 0.2.5,
replacing the installed one), the app installed with `adb install -r` (pairing kept), over Tailscale. The
maintainer used it for real; the outcomes below are what they reported and what the exported connection log
shows (times UTC).

| Scenario (spec) | Outcome |
|---|---|
| A 684,290-byte PNG from the photo picker sent with "我測一下上傳檔案": prepared 03:52:31.0, uploaded in 0.6 s (`attach event=complete presumed=false`), `send outcome=Submitted attachments=1`; the desktop's stored file has the same size and the agent read it (reply-attachments) | pass |
| Two permission prompts (`Write` to `/tmp`, then `Bash`) allowed from the phone: `answer kind=permission decision=allow outcome=Done open=1` (prompt-answer) | pass |
| A question with a several-choice and a single-choice part answered from the phone: `answer kind=question decision=answer outcome=Done`; the agent received exactly the two options and the one option chosen (prompt-answer) | pass |
| A plan approval: "Approve (ask before edits)" → `answer kind=plan decision=approve outcome=Done`; the agent left plan mode (prompt-answer) | pass |
| Long-press menu: rename (three times) and clear the name (`rename cleared=true`), hibernate (`hibernate force=false`), close session (`close force=false`), with no busy refusal; the maintainer checked the desktop (session-rename, session-hibernate-close, session-list) | pass (maintainer) |
| Hibernate then wake from the menu: `hibernate force=false` at 04:00:41, `wake` with no error 3.8 s later (session-hibernate-close) | pass |
| A busy session (`SESSION_BUSY`) and forcing it; "Deny", "Decline", "Keep planning"; a tool that can only be denied; a prompt typed into on the desktop or two open requests (read-only); HEIC from the phone; a dropped connection mid-upload; an EXIF-rotated camera JPEG; a ZIP refused; picking 11 photos; the desktop hibernating or closing a watched session | not run on the phone yet — carried by `SessionActionsTest`, `AnswersTest`, `UploadsTest`, `PreparerTest` and `DesktopTest` only |

## Close offers

Change `close-offer`, 2026-10-16, against the protocol of spekterm with `remote-close-offer` (working tree, not yet
committed).

| Check | Where | Outcome |
|---|---|---|
| `./gradlew testDebugUnitTest assembleDebug`: 184 JVM tests, including the new cases of `FramesTest` (`closeOffered` true only for the boolean `true`; absent, `false`, `null`, `"yes"`, `"true"`, `1`, `[]`, `{}` decode the session as not offered; the `sessions.keep` payload), `SessionActionsTest` (offered only for a running agent; Close / Keep per operation; an offer's Close starts unforced without confirming, its `SESSION_BUSY` still asks; Keep never busy, its texts; the menu's close still confirms) and `SessionsViewTest` (each row counted once: choice, then offer, then ready) | host | pass |
| `DesktopTest` (`keepSendsItsPayloadAndTheOfferFollowsTheList`): Keep detached → `NOT_CONNECTED`; `sessions.keep` carries only `sessionId`; the mark follows `sessions.changed` and not Keep's answer; a refusal is returned; and the earlier instrumented suites (59 tests in all) | emulator (AVD `Pixel_9`, Android 16), only the emulator listed by `adb devices` | pass |

On the Pixel 7 against a dev spekterm desktop (`npm run dev` in ~/git/spekterm with `remote-close-offer` in its
working tree, profile `~/.config/spekterm-dev`, remote port 47616, paired anew), the app installed with
`adb install -r`, over Tailscale. A first pass (13:18 Z) was interrupted; the run below was redone from the start at
15:21–15:37 Z, driven over adb (taps, ADB Keyboard) and the desktop's DevTools port, with phone screenshots and the
app's connection log as evidence. Each offer was made by asking a fresh Claude session in the `spekterm` folder to
close its own session and waiting for its turn to end; the desktop's card appeared 9–12 s after the prompt.

| Scenario (spec) | Outcome |
|---|---|
| The offered row: "The agent asked to close this session." with "Close" then "Keep" under the name, no "Waiting for you"; another session in the folder still "Waiting for you" (close-offer, session-list) | pass |
| The folder collapsed: "1 asks to close · 1 waiting for you" (session-list) | pass |
| The conversation screen: the card above the composer; the subtitle keeps "Waiting for you" (close-offer) | pass |
| The menu's "Close session" on an offered session still asks "Close “…”?"; Cancel left it running and offered (session-hibernate-close) | pass |
| "Keep" on the conversation screen: the screen stays, the card goes from it and from the desktop (15:22:52.652 Z `keep`) (close-offer) | pass |
| A reply sent from the phone: the offer went from both sides within 1.5 s (the agent then made another) (close-offer) | pass |
| "Keep" on the desktop's card: the phone's card went within 1.5 s (close-offer) | pass |
| One key typed into the session on the desktop: the phone's card went within 1.5 s (close-offer) | pass |
| Connection paused ("Settings › Pause"): the row and the conversation screen still show the offer with "Close" and "Keep" greyed; tapping "Keep" sent nothing (the desktop's card stayed); after "Resume" (15:32:14 Z reconnect) both buttons were usable (close-offer) | pass |
| "Close" from the conversation screen: no confirmation, back to the list, the session gone from both (15:32:31.731 Z `close force=false`) (close-offer, session-hibernate-close) | pass |
| "Close" from the list: no confirmation, the session gone from both (15:35:47.659 Z `close force=false`) (close-offer) | pass |
| "Keep" on the list's row: the offer went from both, the session kept running (15:36:56.901 Z `keep`); the first pass's row Keep (13:18:25.608 Z `keep`) did the same (close-offer) | pass |
| The connection log: `keep` and `close force=false` lines with no error and no session name or label (read from the app's log file, which "Export log" shares; the button itself not pressed) (close-offer) | pass |
| An offered session that also awaits a choice | not produced — carried by `SessionsViewTest` (2.4) |
| `SESSION_BUSY` after an offer's Close | not produced — carried by `SessionActionsTest` (2.2) |
| An older desktop (no `sessions.keep`, no mark) | not run — the installed spekterm was not paired in this run; carried by `FramesTest` and `SessionActionsTest` (1.1, 2.1) |

Seen on the desktop during the run, not in the phone's behavior (the phone showed what the list said each time):

- After the row's Keep at 15:36:56 Z, the list marked the session busy ("Working" on the phone) for about 45 s,
  though its terminal was idle, until the agent's next hook event at 15:37:40 Z set it back to ready. Not
  diagnosed.
- In the first pass, an offer made at 13:19 Z went from the desktop and the phone by 13:26 Z with no Keep in the
  phone's log; Claude Code had written a "※ recap" line in that terminal. Not reproduced in the rerun; cause unknown.
- The desktop switched a session to its conversation view by itself after the agent's turn. A prompt sent from that
  view's composer stayed in the agent's input line unsubmitted ("尚未確認"), and was submitted only after switching
  back to the terminal view and pressing Enter there.

## Appearance

Change `ui-polish`, 2026-10-17. No protocol change; checked against the installed spekterm (0.2.x, port 47615).

| Check | Where | Outcome |
|---|---|---|
| `./gradlew test assembleDebug`: 204 JVM tests, including the new `ConversationItemsTest` (18: a call with its result, parallel calls paired by id, same id paired in call order, unpaired results alone, a run of seven with thinking, a single call not a run, other entries ending a run, a run's key stable as it grows, every worked case of design D7's anchor table, every entry shown once) and `ThemeTest` (both schemes set all 48 Material 3 color slots; none left at the baseline but neutrals) | host | pass |
| `connectedDebugAndroidTest`: 61 tests, including the new `UnpairDialogTest` (Cancel does not unpair and closes the dialog; Unpair unpairs once) | emulator (AVD `Pixel_9`, Android 16), only the emulator listed by `adb devices` | pass (the first run failed both new cases: the test's own dialog state was not remembered; fixed in the test) |

Screenshots on the emulator, paired with the installed spekterm, in light and dark mode, reviewed against design
D1–D12 and spec `app-appearance` (kept outside the repository):

| Screen or state | Outcome |
|---|---|
| Cold start, light and dark: the starting window and the splash in the mode's background, status icons dark on light and light on dark from the first frame; the splash icon legible on light (the launcher icon draws its own dark circle, so no icon background was needed) | pass |
| Session list, light and dark: system bars in the app's background with contrasting icons (the earlier build showed dark icons on a dark bar in light mode); "Waiting for you" with an attention dot stands out from a column of quiet "Dormant" rows; agent icon per row, folder headings with chevron, monospace branch and count pill; the "New session" button, collapsing to its icon on scroll | pass |
| Accessibility labels (`uiautomator dump`): list — "More options", "Agent" per row, "Collapse" per heading, "New session" on the collapsed button; conversation — "Back", "More options", "Attach", "Send" | pass |
| Conversation: the agent's seven Bash calls as one "7 tool calls" line with the latest call under it; opened, seven calls each with a success mark; the user's message a quiet bubble at the end; the top bar's "Waiting for you" with its dot; "Jump to newest" a small round button in the corner; send filled in the accent only once something is typed | pass |
| Settings: top bar with back, a connection card ("Connected" with a green dot, then "Connected via … since …"), "7 addresses · port 47615" with "Show all", notifications and battery rows, pairing at the end | pass (the first build put the whole "Connected via … since …" in the card's title, which wrapped; split into a title and a detail line) |
| Unpair: the dialog "Unpair from kewang-7420?"; Cancel left the screen at "Connected via …" | pass |
| Rename dialog: "Clear name" at the start, "Cancel" and "Rename" at the end; cancelled | pass |
| Re-pairing: "Pair again" top bar, the scan button, "or", the link field with its paste icon; a refused link shows an error notice; system back returns to settings, then to the list | pass (the first build's system back left the app from this screen; a back handler was added) |
| First pairing (after the instrumented run unpaired the emulator), light and dark | pass |
| The composer with the keyboard open | not produced — the emulator's keyboard runs floating (hardware-keyboard mode) and takes no room; left to the phone |
| The prompt card, an attachment, the working indicator, the close-offer card | not produced — they need an agent driven into a prompt or an upload to the user's desktop; left to the phone |

On the Pixel 7, the debug build installed with `adb install -r` (pairing kept), used for real by the maintainer
against the installed spekterm over the LAN, 2026-10-17 04:03–04:20 Z, in light and dark mode: "all fine"
(pass, maintainer). Evidence from the app's connection log:

| What was used | Evidence |
|---|---|
| Conversations opened and left | `conversation event=watch` / `unwatch` from 04:04 Z |
| Replies sent from the new composer | `send outcome=Submitted` at 04:16:38, 04:18:46 and 04:20:11 Z |
| A photo attached, uploaded, then removed from its card | `attach event=prepared bytes=2221849` 04:18:11, `complete` 04:18:14, `removed` 04:18:22 Z |
| A question answered from the restyled prompt card | `answer kind=question decision=answer outcome=Done` 04:19:09 Z |
| A session renamed from the new dialog | `rename cleared=false` 04:05:06 Z |

Scrolling the session list stuttered at first and smoothed after a few flings. Frame stats read with
`dumpsys gfxinfo` (passively, from the maintainer's own use): the debug build had 4.78% janky frames (6918 frames;
95th percentile 26 ms, 99th 65 ms; 327 slow UI-thread frames; GPU 99th 5 ms). The same code as a non-debuggable
`profile` build, compiled `speed-profile` after install, had 1.96% (1629 frames; 95th 15 ms, 99th 30 ms; 31 slow
UI-thread frames). Most of the stutter is the debug build's (debuggable code is JIT-only). The list's floating
button now reads the scroll position through `derivedStateOf`, and its lazy items carry a content type. R8 for the
release build is left to the change that prepares publishing.

### Choosing light or dark in the app

Added after the phone run (design D13). On the emulator (AVD `Pixel_9`, API 36, unpaired: Settings needs a pairing,
so the choice was written to the app's `no_backup/theme.json` with `run-as` and the app force-stopped and relaunched;
screenshots kept outside the repository):

| Check | Outcome |
|---|---|
| `ThemeStoreTest` (default `SYSTEM`; each choice reads back; unreadable and unknown values read `SYSTEM`) and `AppearanceSectionTest` (the current choice selected; another tapped is reported, the current one is not) | pass |
| Settings rendered in a test in both themes: "Appearance" between Connection and Notifications, "System · Light · Dark" with the choice checked | pass |
| Dark on a light phone: the screens dark with light status icons; the splash dark from the second start on | pass (the first start after writing the file kept the phone's mode for the splash: the system learns the choice only in that start's `App.onCreate`; a choice made in Settings tells it at once) |
| Light on a dark phone: light screens, dark status icons | pass |
| Dark chosen, the phone's mode toggled light → dark → light: the app stayed dark | pass |
| Dark chosen, `adb reboot`, cold start: splash and screens dark | pass |
| The live path, run inside the app's process (a scratch instrumented probe calling `chooseTheme` with the activity open): Dark → the activity's configuration dark; System → light (the phone light); Light → light; System → light; no activity recreated | pass |
| `SYSTEM` after a start that removed a Dark override (file written directly): stayed dark until the next start, even across phone toggles; after that start it followed the phone both ways | expected divergence path, recorded in design Risks |
| Unpairing, pairing again, the desktop's revocation leave the choice: by reading — `theme.json` is touched by none of `MainActivity.unpair`, `onPaired`, `ConnectionService` | pass (read) |
| API 29–30 (starting window follows the phone, the first frame switches) | not run — no image for those levels here; by reading only |
| `./gradlew test assembleDebug` and `connectedDebugAndroidTest`, emulator only: 242 JVM and 74 instrumented tests in the shared tree (with another change's work in progress); this change alone, applied to a clean worktree of master: 204 JVM tests pass and everything compiles | pass |

On the Pixel 7: this change's final debug build installed with `adb install -r` at 2026-10-17 05:16 Z, pairing kept
(`no_backup/pairing.bin` present after the install), no theme choice stored yet (`SYSTEM`). The maintainer then used the
switch in Settings → Appearance: "no problems" (pass, maintainer). Afterwards the phone was in light mode while the
app's stored choice was `{"choice":"DARK"}`, i.e. the choice is kept apart from the phone's setting.

## OpenSpec

Change `openspec-browse`, 2026-10-17, against spekterm master at `7814c15` (0.2.8, which carries
`remote-openspec-browse`, `0fcdc1f`) run from source (`npm run dev`, profile `~/.config/spekterm-dev`, remote port
47616), and spekterm 0.2.7 (`99ca1ce`) run from an exported copy of its source with its own profile
(`~/.config/spekterm-027`, remote port 47617). Test folders: a scratch repository with an active change, a change
holding a 1.7 MB JSON file, an archived change, a main spec, and a second working tree on branch `feat` holding
its own change; and a repository with an empty `openspec/`.

| Check | Where | Outcome |
|---|---|---|
| `./gradlew testDebugUnitTest assembleDebug`: 242 JVM tests, including `OpenSpecTest` (each artifact kind, unknown and unreadable kinds, omitted pieces, an older desktop's pass-through answers, payloads), `OpenSpecTrackerTest` (23 cases of watch-then-read, one watch in flight, refused folders, events before answers, reconnects, the linger), `OpenSpecTextTest`, `OpenSpecGatesTest`, `SessionsViewTest`, `EventRouteTest` | host | pass |
| `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`, the Pixel detached from adb (only the emulator listed): 74 instrumented tests, including `DesktopTest`'s six OpenSpec cases (watch before read; one read per event; watch again after a reconnect; no watch on an older desktop; the stop after the linger and not within it; a vanished change found in the archive; a refused watch marks the target not live) and `AgentMarkdownTest`'s three repository cases (soft breaks joined, agent text keeps them; a relative link and an anchor plain text, a web link a link; nothing fetched, HTML as text) | emulator (AVD `Pixel_9`, Android 16) | pass |

On the emulator, paired with the dev desktop, driven over adb, with screenshots (kept outside the repository) in
light and dark mode, and the app's connection log:

| Scenario (spec) | Outcome |
|---|---|
| The session list: the OpenSpec icon in the headings of folders that have it (`content-desc` "OpenSpec for go", "OpenSpec for spekterm"); tapping it opens the folder and leaves the group expanded (session-list) | pass |
| The list's menu → "OpenSpec": every folder with OpenSpec in rail order, including `spekterm-android` and the test folders, which have no session (session-list) | pass |
| A folder: "Active" with task progress and start dates, a `feat` chip on the worktree's change, "Archived · 1" folded; expanded, the archive date; spekterm's own folder (no active change) opens with its 86 archived changes unfolded (openspec-browse) | pass |
| The empty repository: "No changes in this folder." | pass |
| Specs tab: `thing`, "1 change"; the spec: its text, "Main working tree · main", "History · 1" listing the active change without a date, which opens it | pass |
| A change: Proposal, Specs, Design, Tasks in the desktop's order; a hard-wrapped paragraph shown as one; `[the design](design.md)` and an anchor plain text, `[the site](https://…)` a link; `<b>`, `<img>` and `<script>` shown as text; the image shown as "[image: remote image]", nothing loaded; a table rendered | pass |
| Tasks: "1 of 4 done", sections, a two-line task as one item, check-box icons labeled "Done" / "Not done" | pass |
| A task ticked in `tasks.md` on disk while on screen: "2 of 4 done" seen 5.7 s after the write (polled with `uiautomator dump`, which itself takes about a second per poll); log `openspec op=change cause=event` | pass |
| A YAML file added to the change while on screen: its tab appeared by itself; shown as wrapping monospace text | pass |
| The 1.7 MB JSON file: "Left out by the desktop: too large to send with the rest (1.8 MB)." in its tab, the proposal still shown | pass |
| The change archived on disk while on screen: first the `feat` worktree's copy of the same slug was shown, labeled `feat` (the desktop's election, correct); with that copy removed too, "This change was archived." with "Open archived change", which replaced the screen with the archived copy | pass |
| spekterm's largest change (`2026-08-02-global-session`, 234 KB on disk): content within 5 s of the tap, measured by polling `uiautomator dump` (an upper bound); switching to Design, Specs and Tasks logged no skipped frames; its 11 deltas start folded | pass on the emulator; the Pixel's timing is in the phone run |
| Back to the session list: `openspec op=watch stop=true` 5 s after leaving; Home with a folder open: the stop 7 s after | pass |
| Paused (Settings › Pause): "Not connected. Showing what was read at 1:30 PM.", Refresh dimmed; Resume reconnected | pass |
| spekterm 0.2.7: the same screens read; "This desktop doesn't update OpenSpec by itself. Read at …"; a task ticked on disk not shown until Refresh, then "1 of 1 done" (log `cause=refresh`); no `op=watch` line after pairing with it | pass |
| The connection log: `openspec` lines with `op`, `cause`, `items`, `omitted`, `stop` and error codes; no slug, topic or text | pass |

Fixed during the run: Markdown headings took display sizes (now title sizes), unselected tabs took the accent
color, an unloaded image left an empty block (now its alt text), and a vanished change that was archived showed a
redundant "no longer in the folder" error above the archive notice.

Seen on the desktop, not the phone: a change's artifacts came in a different order active (Proposal, Specs, Design,
Tasks) and archived (Proposal, Design, Specs, Tasks); the phone shows the desktop's order either way.

On the Pixel 7 (Android 16), 2026-10-17 15:05–15:20 (+08:00), the build of `1bdca5a` put on with `adb install -r`,
paired for the run with the same dev desktop, reached over Tailscale and then the LAN; its earlier pairing (with the
installed spekterm) was saved from the app's storage beforehand and put back afterwards, and the app reconnected
to the installed desktop. Driven over adb, with screenshots, a screen recording and the app's connection log:

| Check (task 5.4) | Outcome |
|---|---|
| Against the installed spekterm first: the folders and a change read, with "This desktop doesn't update OpenSpec by itself" (its running process predates `0fcdc1f`, though the copy on disk is 0.2.8) | as designed |
| This repository's and spekterm's folders open (8 and 86 changes) | pass |
| spekterm's largest changes, never read before: `macos-dmg-packaging` answered 0.26 s after the tap (log), its proposal on screen about 0.3 s after the tap (screen recording, 10 frames a second); `global-session` opened as fast | pass |
| A task ticked in a test `tasks.md` on the desktop while on the phone's Tasks tab: "1 of 2 done" with no tap, log `openspec op=change cause=event` about 5 s after the write | pass |
| A new change made in the test repository's second working tree: in the list 4.5 s later with its `feat` chip, log `op=changes cause=event` | pass |
| The open change archived on disk: "This change was archived." 2 s later (log `cause=event error=NOT_FOUND`, then `op=changes cause=archived`); "Open archived change" showed it, "Archived 2026-10-17" | pass |
| The connection lost while a folder was open, a change made meanwhile, then back: "Not connected. Showing what was read at 15:17.", Refresh dimmed; once reconnected, `op=watch stop=false` then `op=changes cause=attach` and the new change listed, no tap. Airplane mode was not used: it would end the Wi-Fi debugging the run depends on, so the desktop turned remote access off for 27 s instead, which the phone saw as the desktop going away | pass, with that substitute |
| Airplane mode, done by kewang afterwards (15:49–15:51, against the installed spekterm restarted, so watching): `spekterm-android` open; airplane mode on, the connection dropped (`disconnected failure=SocketException`); a change made in the repository meanwhile (15:50:23); airplane mode off: reconnected at 15:50:42 after the backoff, `op=watch stop=false` then `op=changes cause=attach items=9`, and the new change was on screen with no tap | pass |
| Back to the session list: `stop=true` 7 s after the key press; Home with the folder open: `stop=true` 7.5 s after; reopening watched again | pass |
| In an artifact, `[the design](design.md)` tapped: nothing happened; `[the site](https://example.com)` tapped: Chrome opened | pass |
| The connection log (8015 lines, the file the export shares): no slug, folder name, topic or text from the run | pass |

## Session relations

Change `session-relations`, 2026-10-17, against spekterm master at `01cb50a` (`session-wait-indicator`, which adds
`seen`) run from source (`npm run dev`, profile `~/.config/spekterm-dev`, remote port 47616). The dev profile
already held handoffs from earlier tests (a chain spek → Log → Downloads, a same-folder child in spek, a parent in
ha-claude-assistant with a same-folder and a cross-folder child); new ones were made with `tools/drop-handoff.mjs`.

| Check | Where | Outcome |
|---|---|---|
| `./gradlew test assembleDebug`: 276 JVM tests, including `SessionTreeTest` (the rail's forest, ported), `RelationsTest`, `HandoffScreenTest`, `SeenTransitionsTest`, and the new cases of `FramesTest` (lenient `lineage` and `seen`, handoff list, brief), `SessionsViewTest` (idle rows, summary), `SessionActionsTest`, `AttentionPolicyTest` (which notifications go when seen) | host | pass |
| `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`, the Pixel detached from adb (only the emulator listed): 77 instrumented tests, including `DesktopTest`'s handoff list (asked only when offered, follows its events), brief (asked with its session id, logged without its text) and seen hook (ready+seen, busy, ready+seen in a row call it twice; the first list after a reconnect once) | emulator (AVD `Pixel_9`, Android 16) | pass |

On the emulator, paired with the dev desktop by pasted link, driven over adb, with screenshots (kept outside the
repository) in light and dark mode, and the app's connection log:

| Scenario (spec) | Outcome |
|---|---|
| The existing handoffs: a same-folder child indented under its source with "Handoff"; a cross-folder one at the top of its folder with "From spek › ✳ spek-f152 · Done"; the source "Handed off 1 session · all done"; three levels (spek-dfa5 → "Phone test: same folder" → "Phone test: grandchild here") each one step further in (session-relations) | pass |
| `drop-handoff.mjs` handoffs to the same folder and to another folder: each became a new session at once (the dev desktop accepted them on its own, no inbox step); the source "Handed off 3 sessions · 1 of 3 done" | pass |
| `drop-handoff.mjs --report` from a handed-off session while its handoff screen was open: the report and its time showed by themselves within 5 s; the row "Handoff · Done", the source's count "1 of 3 done" | pass |
| The source renamed from the phone: the cross-folder grandchild's row "From spek › Phone test: renamed" | pass |
| The source closed from the phone: its same-folder child moved to the top level and both children said "From Phone test: same folder (closed)" (the title at the handoff); no row disappeared | pass |
| The phone list next to the desktop's rail (read over CDP): the same rows in the same order in Log and spek; the rail's "→ 2" where the phone says "Handed off 2 sessions · 1 of 2 done" | pass |
| "Handoff" from a long press: From naming the source and opening it; a long note in full with its line breaks; the latest report with its time; "Handed off from here" with folder, state and summary, each opening its session | pass |
| A note with Markdown, a link and a bare URL: shown as plain text, nothing a link (D4) | pass |
| Older handoffs (ha-claude-assistant-4dc6, pg11-f947): "The desktop kept no note for this handoff.", "No report yet." | pass |
| Opening "Handoff" of a session already on the back stack (Log → child's conversation → its Handoff → From → Log's conversation → Handoff): returned to the first Handoff screen; back then went to the list | pass |
| Paused: the relations shown with the paused notice, the note "Reading the note needs the connection to the desktop."; "Resume" loaded the note by itself | pass |
| The desktop's renderer reloaded (CDP `location.reload()`) while a handoff screen was open: the screen stayed as it was, relations kept, never "no longer on the desktop". A reload keeps the window, so `window` stays `true` (as the desktop's session confirmed); a desktop without a window happens only on macOS after closing it, carried by `HandoffScreenTest` (session-relations) | pass |
| A dormant handed-off session woken from the phone: "Idle" (seen after a wake), row "Handoff"; no waiting notification | pass |
| A reply sent from the phone, then back to the list before the turn ended: "Waiting for you", row "Handoff · Not done yet", a "Waiting for your input" notification; an "Idle" row (the woken parent) next to it, light and dark | pass |
| Opening that conversation on the phone and going back: "Idle", the notification removed (log `seen kind=waiting` 0.13 s after the watch) | pass |
| The next turn ending unseen: "Waiting for you" again, a new notification that was still there 20 s later; its folder collapsed said "1 waiting for you" | pass |
| The session selected on the desktop (over CDP, the window focused): the collapsed folder stopped counting it and the notification went (log `seen kind=waiting`) | pass |
| The connection log: `handoffs count=5`, `brief hasBody=… hasReport=…`, `seen kind=waiting`; no label, note or report text | pass |

Noticed: a new same-folder session ended its first turn already seen while spek was the folder selected on the
desktop. The desktop's session confirmed it in code: a handoff accepted on arrival becomes its folder's focused
session, so with that folder selected in the focused window it is displayed, and its turn end is seen. A possible
desktop-side UX question, not a protocol one.

On the Pixel 7 (`adb install -r`, re-paired with the dev desktop by pasted link, driven over adb with kewang's
OK; one step on the desktop done by kewang), against the same dev desktop at `01cb50a` (the `seen` contract, final
and archived on spekterm as `dbc2e03`). New sessions: "Pixel source" handed off from spek-dfa5, and from it
"Pixel child here" (spek) and "Pixel child there" (Log):

| Item (task 7.1) | Outcome |
|---|---|
| A handoff within one folder: under its source, indented one step further, "Handoff". "Handoff · Working" was not caught on the Pixel (the agent finished between polls); seen on the emulator for the nested 測試：子 session | pass (Working: emulator) |
| A handoff to another folder: at the top of Log with "From spek › Pixel source · Working"; the source "Handed off 2 sessions" | pass |
| Two handoffs then their reports: the source "Handed off 2 sessions · all done". "1 of 2 done" was not caught (both children reported within seconds); seen on the emulator as "1 of 3 done" and carried by `RelationsTest` | pass ("k of N": emulator) |
| A handed-off agent stopping without a report while unseen ("Pixel source", after its children's messages): "Handoff · Not done yet", "Waiting for you"; once looked at, "Handoff" and "Idle"; on its parent's handoff screen its state followed | pass |
| The source renamed (from the phone): the cross-folder row "From spek › Pixel source renamed" | pass |
| The source closed (from the phone): both children "From Pixel source (closed)", the same-folder one at the top level; no row disappeared | pass |
| "Handoff" from a long press: From, the note in full, the report with its time | pass |
| "Handoff" from the source's conversation menu: both children with folder, "Done" and their report summaries; a child opened its conversation, back returned to the handoff screen | pass |
| Paused: the relations and the report shown, the note "Reading the note needs the connection to the desktop."; Resume loaded it by itself | pass |
| The desktop's renderer reloaded with a handoff screen open: the screen unchanged throughout | pass |
| The list next to the rail: same rows and order (read on the emulator run; the rail unchanged since) | pass |
| Agents finishing unseen: "Waiting for you" and a notification each; kewang showing "Pixel child here" on the desktop: the row "Idle", its notification gone (log `seen kind=waiting`), the collapsed spek no longer saying "1 waiting for you" | pass |
| "Pixel source" and "Pixel child there" opened on the phone and left: "Idle", notifications gone (two `seen kind=waiting` lines); the desktop's rail showed "Pixel source — 執行中 · 閒置", looked at only on the phone | pass |
| The next turn ending unseen (a reply sent from the phone, then back to the list): "Waiting for you" again, a new notification still there 20 s later | pass |
| The installed spekterm without `seen`: not paired during the run; carried by 5.1 (`seen` absent → "Waiting for you") | carried by 5.1 |
| A desktop without the handoff operations: carried by 1.1 and 3.1 (`DesktopTest`: no `handoffs.list` asked) | carried by 1.1, 3.1 |
| The connection log (1082 lines): `handoffs count=…`, `brief hasBody=… hasReport=…` / `brief error=NOT_CONNECTED`, `seen kind=waiting`; no label, title, note or report text (no "Pixel", "Phone test", "spek-", "sleep" or 測試 in it) | pass |

Noticed during the run: kewang picked up the phone and opened another app while the script was running; the run
stopped until the app was back in front, and every later step first checked that spekterm-android was in front.


## OpenSpec from a session's screen

On the emulator (Pixel_9 AVD), paired with the dev desktop at spekterm `bcd7b81` (0.2.9), the top bar's OpenSpec
icon showed on go-b8ad (folder `go`, with OpenSpec), both dormant and after waking it from the phone, in light and dark. Its
`content-desc` was "OpenSpec for go" (`uiautomator dump`). The dormant global-9699 had no icon, in light and dark.
Tapping the icon opened `go`'s changes, and back returned to the session.

On the Pixel 7 (`adb install -r`, still paired with the installed spekterm), kewang used it on this change's own
session, in `spekterm-android`, a folder with OpenSpec:

| Item (task 2.2) | Outcome |
|---|---|
| A reply sent ("寫三段話給我"), the icon tapped at once: `send outcome=Submitted` at 14:11:55.241, `conversation event=unwatch` 0.17 s later, then `openspec op=watch stop=false` and `op=changes items=10` | pass |
| A change opened (`op=change items=4`) while the agent wrote three paragraphs with tool calls between them, then back twice: `conversation event=watch resume=true` at 14:12:34; the three paragraphs and both tool calls on screen once each, nothing repeated (screenshot) | pass |
| The folder's watch stopped after leaving: `openspec op=watch stop=true` 4.9 s after the conversation came back | pass |
| A further agent message live: the next turn's tool calls and "Working" appeared while on screen | pass |
| A global session without the icon: seen on the emulator only | pass (emulator) |


## Scanning, connection and language

Change `scan-connect-zh`, 2026-10-17, on the emulator (Pixel_9 AVD, Android 16), paired with the dev desktop run
from spekterm `18db1cf` (a clean export of HEAD, profile `~/.config/spekterm-dev`, port 47616). The desktop lists
five addresses: 192.168.0.199, two global IPv6 addresses, 100.106.81.33 and fd7a:115c:a1e0::6432:5121.

### Decoding (tasks 1.3)

`QrDecodeTest` drew the pairing link as the desktop does (ECC M, border 2) and decoded it at a share of the
preview's width, in the old path's 480 px crop (ZXing Java, CameraX's default 640×480) and the new path's 1080 px
crop (zxing-cpp). The px figures are pixels per module, old/new. The test asserts the new path reads every
five-address frame at 1/5 and larger: pass.

```
five addresses: 320 bytes, 73 modules with the border
five 1/4: 0° old=✗ new=✓ (1.6/3.7 px) 0°+blur old=✗ new=✓ (1.6/3.7 px) 15° old=✗ new=✓ (1.6/3.7 px) 15°+blur old=✗ new=✓ (1.6/3.7 px) 90° old=✗ new=✓ (1.6/3.7 px) 90°+blur old=✗ new=✓ (1.6/3.7 px)
five 1/5: 0° old=✗ new=✓ (1.3/3.0 px) 0°+blur old=✗ new=✓ (1.3/3.0 px) 15° old=✗ new=✓ (1.3/3.0 px) 15°+blur old=✗ new=✓ (1.3/3.0 px) 90° old=✗ new=✓ (1.3/3.0 px) 90°+blur old=✗ new=✓ (1.3/3.0 px)
five 1/6: 0° old=✗ new=✓ (1.1/2.5 px) 0°+blur old=✗ new=✗ (1.1/2.5 px) 15° old=✗ new=✓ (1.1/2.5 px) 15°+blur old=✗ new=✗ (1.1/2.5 px) 90° old=✗ new=✓ (1.1/2.5 px) 90°+blur old=✗ new=✗ (1.1/2.5 px)
five 1/8: 0° old=✗ new=✓ (0.8/1.8 px) 0°+blur old=✗ new=✗ (0.8/1.8 px) 15° old=✗ new=✗ (0.8/1.8 px) 15°+blur old=✗ new=✗ (0.8/1.8 px) 90° old=✗ new=✓ (0.8/1.8 px) 90°+blur old=✗ new=✗ (0.8/1.8 px)
seven addresses: 432 bytes, 85 modules with the border
seven 1/4: 0° old=✗ new=✓ (1.4/3.2 px) 0°+blur old=✗ new=✓ (1.4/3.2 px) 15° old=✗ new=✓ (1.4/3.2 px) 15°+blur old=✗ new=✓ (1.4/3.2 px) 90° old=✗ new=✓ (1.4/3.2 px) 90°+blur old=✗ new=✓ (1.4/3.2 px)
seven 1/5: 0° old=✗ new=✓ (1.1/2.5 px) 0°+blur old=✗ new=✓ (1.1/2.5 px) 15° old=✗ new=✓ (1.1/2.5 px) 15°+blur old=✗ new=✓ (1.1/2.5 px) 90° old=✗ new=✓ (1.1/2.5 px) 90°+blur old=✗ new=✓ (1.1/2.5 px)
seven 1/6: 0° old=✗ new=✓ (0.9/2.1 px) 0°+blur old=✗ new=✗ (0.9/2.1 px) 15° old=✗ new=✓ (0.9/2.1 px) 15°+blur old=✗ new=✗ (0.9/2.1 px) 90° old=✗ new=✓ (0.9/2.1 px) 90°+blur old=✗ new=✗ (0.9/2.1 px)
seven 1/8: 0° old=✗ new=✓ (0.7/1.6 px) 0°+blur old=✗ new=✗ (0.7/1.6 px) 15° old=✗ new=✓ (0.7/1.6 px) 15°+blur old=✗ new=✗ (0.7/1.6 px) 90° old=✗ new=✓ (0.7/1.6 px) 90°+blur old=✗ new=✗ (0.7/1.6 px)
connections opened and dropped: 14 of 24 attempts
```

The old path read none of these frames: at 1/4 of the preview the real code gets 1.6 px per module, which is why
the code had to fill most of the frame. The new path reads every frame down to 1/5, sharp frames down to 1/8.

| Item | Outcome |
|---|---|
| Dependencies (1.1): `camera-core` resolves to 1.6.2, no `com.google.zxing:core` at runtime; `libzxingcpp_android.so` for the four ABIs; `zipalign -c -P 16 -v 4` passes | pass |
| The scanner (1.2) on the emulator's virtual scene: opens; a tap starts focus metering (`startFocusAndMetering`); leaving closes the camera (`CameraDevice-10#close`). Pinching could not be produced with `adb input`; the debug log line `zoom ratio=` is there for the Pixel run | pass; pinch on the Pixel |
| A pairing link opened while scanning (1.5 f): scanning stopped, the camera closed, the card shown | pass |

### Pairing links from other apps (1.5)

| Case | Outcome |
|---|---|
| (a) Unpaired, a real link from the dev desktop (`startPairing` over CDP): the card inline, "要與 kewang-7420 配對嗎？", five addresses; Pair paired (`attempt source=pair` ×2, `authenticated` over 192.168.0.199) | pass |
| (b) Paired, a conversation open, a link with another fingerprint giving the paired desktop's name: a dialog over the conversation, titled "Pair with a different desktop?", saying it "calls itself kewang-7420" and replaces kewang-7420; Cancel returned to the same conversation, no `source=pair` in the log, still connected | pass |
| (c) Paired, a link with the pairing's fingerprint: "Pair again with kewang-7420?", "a new pairing code for kewang-7420" | pass |
| (d) A 63-digit `fp`: "This is not a valid pairing link." | pass |
| (e) A second link while a pairing to 192.0.2.1 ran: toast "A pairing is already in progress.", no attempt to the second link's address; the first pairing went on and failed with its reason | pass |

The first emulator run titled (b) "Pair with kewang-7420?", the name the link gave. Changed so another desktop is
never titled by the name it claims.

### Racing and the desktop's throttle (2.1–2.4)

| Item | Outcome |
|---|---|
| `AddressRaceTest`, `PairerTest` (JVM, virtual time) | pass |
| `AddressRaceDeviceTest`: 10.255.255.1 then the loopback desktop: authenticated within 1.5 s, the first attempt still running when the second won | pass |
| `DesktopRaceTest` against the dev desktop over 10.0.2.2 and 192.168.0.199: twelve races at stagger 0, 14 of 24 attempts opened and dropped without a frame; then `hello` with a made-up device on each address answered `AUTH_FAILED`, not `THROTTLED` | pass |
| Reconnecting after the dev desktop started: authenticated over 192.168.0.199 0.19 s after the round began; the second address never started | pass |
| Pairing on the emulator: the LAN handshake took longer than 250 ms, so 100.106.81.33 started at +0.38 s; the LAN address won and the other was dropped | pass |

### Why the desktop cannot be reached (3.1–3.5)

| Item | Outcome |
|---|---|
| The dev desktop not running, every address refusing (all five `outcome=REFUSED`, `ConnectException`): "192.168.0.199 refused connections on port 47616. If that is your desktop, check that spekterm is running with remote access on.", each address "refused" | pass |
| Airplane mode: `round network=NONE … reason=NO_NETWORK`, "The phone has no network.", each address "no route" (`ENETUNREACH` text in `ConnectException`) | pass |
| The dev desktop quit while connected: `disconnected code=4005`, then the next round `reason=REFUSED`; the screen named the refusing address (3.3) | pass |
| Started again: "Connected via 192.168.0.199", no notice, the address list collapsed again (spec "Connected again") | pass |
| "Try now" (3.4) during a 37.6 s wait: the next round's first `attempt` 0.2 s after the tap; the following wait 40.6 s, not shorter | pass |
| Pairing failures (3.5): a link to 192.0.2.1 → "The desktop doesn't answer on any of its addresses… port 47616", "192.0.2.1 · timed out"; a link to 10.0.2.2 port 1 → "10.0.2.2 refused connections on port 1" | pass |
| A long IPv6 address pushed its outcome onto two lines ("refuse/d"); the outcome now stays on one line beside the wrapped address | fixed |

Exceptions seen on the emulator: refused and no-route both arrive as `ConnectException` with the errno text in the
chain, as D6 matched them. Timed out was produced only through pairing (192.0.2.1). The Pixel's mobile-data
case is still to be recorded.

### Language (4.x–6.x)

| Item | Outcome |
|---|---|
| `StringsTest`, `HardcodedTextTest` (JVM): pass. The emulator's zh-TW screens still showed "3 changes" under each spec and a "… of … done" line: both began with a template, which the first version of the scan missed. Both moved to resources, and the scan now also catches a lowercase word beside a number or a template | pass after the fix |
| `./gradlew test` (311 tests) and `connectedDebugAndroidTest` on the emulator only (80 tests; the Pixel was not on adb): pass. `DesktopRaceTest` without its arguments first reported its skip as a failure (the assumption was inside `runBlocking`); moved out, it reports a skip | pass |
| Switching the app to zh-TW (`cmd locale set-app-locales`) with the rename dialog open and text typed: the list and the dialog in Chinese, the dialog and its text kept, no `disconnected` in the log | pass |
| The ongoing notification in Chinese at once ("透過 192.168.0.199 連線"); the channels "與桌機的連線", "連線問題" | pass |
| ja-JP: English | pass |
| Screenshots in zh-TW and English: session list with folders and handoff lines, the overflow menu, the session menu, a dormant conversation, a conversation's menu, the handoff screen, OpenSpec (folder, change, specs, a spec), settings, pairing again, the card for an opened link, the notification shade. No English left in zh-TW except what the desktop and repositories send (session names, artifact titles, spec text). The English screens read as before | pass |
| A conversation with a run of tool calls and a prompt card: every dev session was dormant; not produced | not run |
| "Waiting for you" notification in zh-TW: needs an agent turn ending; for the Pixel run | Pixel |

### On the Pixel 7 (8.x)

`adb install -r` on 2026-10-18 (pairing kept; reconnected 0.46 s after the install, the Tailscale address that
last connected winning, the LAN address started at +0.26 s). kewang, at home, with the installed spekterm's pairing
view open:

| Item | Outcome |
|---|---|
| The Pixel's own camera app on the desktop's pairing code (8.2): it offered the link and opened the app (`START … VIEW dat=spekterm://pair/… from com.google.android.GoogleCamera`), which asked first | pass (maintainer) |
| Scanning in the app without lining it up (8.1): the camera opened at 01:11:03.68, `attempt source=pair` at 01:11:05.44 — 1.8 s; zoom stayed 1.0 (no pinch needed); authenticated 0.36 s later. kewang: "掃描超快的" | pass (maintainer) |
| Pairing again reset the attention position, as designed: the watch answer held 76 events, `complete=false`: 31 summarized in one notification, 45 suppressed | as designed |

### Follow-up for spekterm (the desktop)

The code is dense because the link carries every address, global and temporary IPv6 included (five addresses:
320 bytes, version 13; seven: 432 bytes, 85 modules), drawn at 192 CSS px with a two-module border. A larger code,
a four-module border, and leaving global and temporary IPv6 addresses out of the link would each make it easier to
scan. That is a spekterm change, not this app's.
