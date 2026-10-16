## Context

Built on `session-conversation`, `addresses-and-mid-turn-replies` and `folders-and-new-sessions` (archived).
`Desktop` (process-wide object, one single-threaded dispatcher) holds the session list, the conversation of the
focused session (`Conversation`: entries, `wait`, `pending`, `draft`, `replies`) and makes requests over the
attached connection; everything started for a connection runs in that connection's scope. `ConversationReducer`
and `Replies` are pure and tested on the JVM; `DesktopTest` drives `Desktop` against a scripted desktop on
loopback (instrumented, emulator only). The composer is gated by `Replies.blocked`, which today refuses free text
while the agent asks for a choice and says "Answer it on the desktop", showing `pending` (`tool`, `arg`). A reply is
confirmed when a new non-meta user entry's text equals the typed text after the desktop's cleaning
(`Replies.confirm`). `ConnectionService.session` reads `hello`'s answer (only `addresses` today) and then calls
`Desktop.attach(connection)`. The connection log records kinds, codes and outcomes, never text. The app has no
icon library; actions are text buttons; the conversation screen's top bar has no menu yet.

Facts about the desktop that shape the design (spekterm 29a5d0a and 9b02f53 — `docs/remote-protocol.md` sections
3–5 and 9, `src/main/remote-conversation.ts`, `remote-answer.ts`, `remote-attachments.ts`, `scripts/probe-remote.mjs`
`runRename` / `runAttachments` / `runAnswer` / `runHibernateClose` / `runHibernateCloseConversation`, and the
archived changes `remote-prompts-attachments-rename` and `remote-hibernate-close`):

- **`operations`** in the answers to `hello` and `pair` lists what the desktop accepts besides authentication.
  A desktop before 29a5d0a omits it and answers the new operations `UNKNOWN_OPERATION`; one at 29a5d0a lists
  rename, attachments and answers but not `sessions.hibernate` / `sessions.close` (added in 9b02f53).
- **`conversation.wait` carries `prompt`** — the first open request, `null` when none — and is sent whenever the
  prompt's identity, `open` or `answerable` changes, also without an agent event. `pending` is unchanged. Every
  wait event states the whole current prompt, so the latest one replaces what is held.
- **An answer is a sequence of keys the desktop writes itself**, 250 ms apart, re-checked before each; the first
  key marks the request answered (a second tap, or a resend after a reconnect, is refused `PROMPT_GONE`). No
  answer contains Enter. `deny` writes Esc; for the agent's own prompt the state then becomes `ready`. "Tell
  Claude what to change" and a question's free-text "Other" need typed text and are not offered by the desktop:
  the phone denies, then replies. `confirmed` / `mismatch` are known only for questions and plans (up to 3 s);
  otherwise `sent`. Answers and replies to one session queue on the desktop. Worst case: four questions of eight
  options, all several-choice — 4 × (8 + 1) + 1 = 37 keys ≈ 9 s, plus 3 s of confirmation, plus a queued reply.
- **Uploads**: chunks of at most 512 KiB decoded (699,052 base64 characters — under the desktop's 1 MiB frame
  limit); smaller chunks are allowed. Later chunks must start exactly at `received`, else `BAD_PARAMS` with
  `detail.received`. An unknown or foreign `attachmentId` is `NOT_FOUND` (so is an unknown session) — and so is
  an upload that already **completed**: the desktop forgets the upload id once its last chunk arrived, so a phone
  that lost the answer to its last chunk gets `NOT_FOUND` when it resumes. A lost answer to the first chunk leaves
  an upload the phone has no id for, counting towards the unsent limit until it expires.
- **The phone's own call timeout ends the connection** (`RemoteConnection.call` cancels the socket), and the
  desktop pings every 20 s and drops a connection that misses two pongs, which a long frame being written can
  delay. A chunk must therefore be small enough to go out well within both on a slow mobile link.
- **Content and limits**: the content is checked only once complete, by signature (PNG, JPEG `FF D8 FF`, GIF87a/89a, RIFF…WEBP, `%PDF-`) or as UTF-8 text without NUL (strict
  decoding) — `kindOf` in `remote-attachments.ts`; anything else is `UNSUPPORTED_TYPE` and the upload is removed.
  10 MiB per file, 200 MiB stored per session, 10 unsent per session including uploads in progress. **There is no
  operation to delete an upload**: one the phone abandons stays on the desktop, counting towards the 10, until
  it is removed after 24 h unsent (or the desktop quits, for one in progress).
- **`conversation.send`** with `attachments` writes `cleaned` + `"\n\n"` + a sentence naming each stored file in
  backticks (or the sentence alone for empty text) and answers `{ outcome, text }` with exactly what it wrote. On
  `WAIT_STATE` (also when found only in the queue) and `NOT_FOUND` the attachments are released, still unsent;
  a `NOT_FOUND` on send is most often a session with no running process, and its attachments stay valid on the
  desktop (also while it is dormant). The sentence names each file by a path holding its `attachmentId`.
  An attachment counts as sent once the text is written, `filled-not-submitted` included. A user record that is
  exactly one paste is shown by the desktop as its text (its D7), so long replies with attachments still match.
- **`sessions.rename`** is carried out by the desktop's interface, quietly; the new label arrives in
  `sessions.changed`. Control characters removed, 200 characters, then trimmed; empty hands naming back.
  `NOT_FOUND`, `NO_WINDOW`, `TIMEOUT` (15 s), `BAD_PARAMS`.
- **`sessions.hibernate` / `sessions.close`** `{ sessionId, force? }` → `{ sessionId }` are the desktop's own
  hibernation and close, carried out quietly. Hibernating ends the process and keeps the session dormant and
  wakeable (attachments kept); a dormant session answers at once with no effect. Closing removes the session from
  the workspace for good, removes its attachments, and works on a dormant session with no check. The answer only
  says it was done; the list then shows `running: false`, or no longer holds the session. A running session that
  is not idle is refused **`SESSION_BUSY`** unless `force` is `true` — nothing happened — with `detail.reason`
  `working` / `choice` / `unsent` (agent), `job` (shell), or `unknown` (the desktop cannot tell: every agent when
  agent events are off, every shell on macOS). Other errors: `NOT_FOUND` (also a session whose process exited
  and left the list), `NO_WINDOW`, `TIMEOUT` (15 s), `CREATE_FAILED` (`message`), `BAD_PARAMS`. **Obligation
  12**: confirm with the user before `sessions.close` and before any request with `force: true`; send without
  `force` first, and only after `SESSION_BUSY` and the user's agreement — shown the reason — send it again with
  `force`. The desktop's own hibernate and close never ask, so the phone must.
- **`conversation.ended.reason`** is `"window"` (the desktop's interface reloaded or closed), `"exited"` (the
  agent's process ended on its own), `"hibernated"` or `"closed"` (whoever did it — the phone or the desktop's
  user; automatic hibernation never ends a watch, since a watched session counts as displayed). A session whose
  process exited on its own is removed from the desktop's list at once (spekterm `ipc/terminal.ts`: the end is
  sent, then the session is removed), so `"exited"` means the session is gone, not dormant. On hibernation the
  end is always sent before the list; on close the order is not guaranteed (the session leaves the store at once,
  the process ends asynchronously), so the list without it may arrive first and the phone may unwatch before the
  end arrives. Closing a dormant session sends no end at all (it has no watch). A conversation watch lasts only while the process runs. Obligation 7 now also
  covers unknown values of known fields, such as a future `reason`. The app today maps every `conversation.ended`
  to "the desktop's window went away" (`ConversationReducer.ended` → `Phase.WINDOW_GONE`), so against 9b02f53
  each hibernation or close would briefly say "no window" until the next list (debounced ~150 ms).
- **Desktop gap (spekterm issue #82)**: a session hibernated within about 0.5 s of being created, before the
  desktop saved it, cannot be woken (`CREATE_FAILED` "unknown session").

## Goals / Non-Goals

**Goals:**
- Answer the agent's open prompt from the phone, exactly as the desktop offers it, never by guessing keys.
- Send photos (HEIC included) and PDF or text files to an agent with a reply, over unreliable mobile links.
- Rename a session, and hand naming back.
- Hibernate and close a session from the phone, asking before anything that cannot be undone or that stops work.
- Behave exactly as today against a desktop that does not offer these.

**Non-Goals:**
- Answering from a notification's actions (the user opens the conversation; the prompt is there).
- "Tell Claude what to change" on a plan and a question's free-text "Other": the desktop does not offer them; deny
  and then reply does the same.
- Taking a photo with the camera inside the app, video, or other file kinds the desktop does not accept.
- Deleting an abandoned upload on the desktop (no such operation).
- The terminal view's `terminal.ended` reasons: the app has no terminal view yet.
- Creating sessions with a name.
- Showing sent attachments as thumbnails in the conversation: the user entry shows the desktop's sentence with
  the stored paths as text.

## Decisions

### D1. Operations: remembered per process, last authentication wins

`Frames.operations(result): Set<String>` reads `operations` by hand (like `addresses`): absent or not an array →
the empty set; non-string elements skipped. `ConnectionService.session` passes it to
`Desktop.attach(connection, operations)`, which publishes `Desktop.operations: StateFlow<Set<String>>`. It is kept
after a detach — the controls stay in place, disabled by the connection gate, instead of vanishing on every drop
— replaced at the next authentication, and emptied by `clear()` (unpairing, pairing again) and `resetForTest()`.
Not persisted: before the first authentication in a process, nothing new is offered. Constants `RENAME`,
`ATTACH`, `ANSWER`, `HIBERNATE`, `CLOSE`, and `WAKE` (for the menu's "Wake"; the dormant screen's "Wake" stays as today) name them. Alternative considered: probing with a request and reading
`UNKNOWN_OPERATION` — rejected, the protocol says to read `operations` (obligation 8).

### D2. Decoding the prompt

`ConversationWait` is decoded by hand: `sessionId`, `state`, `pending` as today, and `prompt` through
`Prompt.decode(element): Prompt?`, so a malformed or unknown prompt never drops the wait event that carries it.
`Prompt` is sealed — `Permission(id, tool, arg, canAllow)`, `Question(id, questions)`, `Plan(id, plan, choices)` —
each with `answerable` and `open`. Rules: `id` must be a non-empty string; an unknown `kind` → `null`; missing
booleans → `false` (so a doubtful prompt is not answerable); `open` defaults to 1; question options decode
element by element with `description` defaulting to empty; `choices` keeps only the three known values. The
phone adds one guard of its own: a `Question` with no questions, more than four, or a question with no options
or more than eight is treated as not answerable (the desktop says the same; the phone must not render an
unbounded form if it ever does not).

`Conversation.prompt: Prompt?` is replaced by every wait event (and cleared wherever `wait` / `pending` are cleared
today: requested, unwatched, disconnected, ended). Whether free text is allowed still follows `wait` alone.

### D3. What the phone offers, per kind — a pure `Answers` module

`Answers.offer(prompt, operations, connected, paused, watch, answering): Offer` decides, in this order:
`ReadOnly(reason)` when the prompt is `null`-equivalent, `conversation.answer` is not in `operations`, the phone is
not connected or is paused, the conversation is not `Watching`, the prompt is not `answerable` (reason: `open > 1`
→ "N requests are open"; the phone's own question guard → "Too many questions or options to answer on the
phone"; else "This choice can no longer be answered from the phone"), or an answer to this prompt (`promptId`)
is in flight;
otherwise `Answerable` with the decisions to show:

| Prompt | Buttons (decision) |
|---|---|
| permission, `canAllow` | "Allow once" (`allow`), "Deny" (`deny`) |
| permission, not `canAllow` | "Deny" (`deny`), with "To allow it, answer on the desktop." |
| question | per question its header, text and options (radio for single choice, checkboxes for several; label and description as plain text); "Send answer" (`answer`), enabled when `Answers.selections(prompt, chosen)` is valid; "Decline" (`deny`) |
| plan | the plan text; "Approve (ask before edits)" (`approve`); "Approve in auto mode" (`approve-auto`) when `choices` holds it; "Keep planning" (`deny`) |

Under "Deny" / "Decline" / "Keep planning": "The agent stops and waits for your reply." `Answers.selections`
returns the `selections` array (one list per question, option indexes ascending) or `null` unless every
single-choice question has exactly one and every several-choice question at least one. Choices made in the form
are UI state keyed by `prompt.id`, so a new prompt starts empty.

Plain text throughout (obligation 10): `Text`, never `AgentMarkdown`; selectable. The plan is shown in a box of at
most ~240 dp height that scrolls, with "Show all" opening it full-screen; the whole card is capped at about half
the screen and scrolls, so the composer stays visible.

**Tap guard.** The buttons of a prompt are enabled only 600 ms after that `prompt.id` (and its `answerable`) was
first shown, and disabled from the tap until the answer comes back. A prompt replaced under the user's finger
then cannot take a tap meant for the previous one; the desktop's `promptId` check remains the real guard.

The composer's blocked text for `AwaitingChoice` becomes "Answer the choice above." when the offer is
`Answerable`, and stays as today ("…Answer it on the desktop.", with the stated prompt in the card) otherwise.
Against a desktop that sends no `prompt`, nothing changes: no card, `pending` text as today.

### D4. `Desktop.answer`

`answer(sessionId, promptId, decision, selections)` runs on the dispatcher like `send`: it checks the held
conversation (same session, `Watching`, its `prompt.id == promptId`, no answer to that `promptId` in flight,
operation listed), records `Conversation.answer = AnswerState(promptId, decision, InFlight)`, and requests
`conversation.answer` in the connection's scope with a 30 s timeout (`ANSWER_TIMEOUT_MS`, above the worst case in
Context). The outcome is written back with `requireWatch = false`, as `send` does: the user may leave the screen
right after tapping, which unwatches, and the outcome must still replace `InFlight`. An `InFlight` state for a
`promptId` other than the held prompt's never blocks answering the held one. Nothing is
ever retried: once a first key is written the desktop refuses any further answer to that prompt, and after an
unknown outcome only the desktop shows what happened.

`AnswerOutcome.of(outcome, errorCode)` (pure):

| Answer | Outcome | Shown |
|---|---|---|
| `sent`, `confirmed` | `Done` | nothing; the card goes when the wait event closes the prompt |
| `partial` | `Partial` | warning: "Only part of the answer was written. Finish it on the desktop." |
| `mismatch` | `Mismatch` | warning: "The agent recorded a different answer than chosen. Check the desktop." |
| `PROMPT_GONE` | `Gone` | "This choice could no longer be answered from the phone — it changed, or was typed into on the desktop. Nothing was sent." |
| `NOT_ANSWERABLE`, `BAD_PARAMS`, `UNKNOWN_OPERATION` | `Refused(code)` | "The desktop did not take that answer from the phone. Answer it on the desktop." |
| `NOT_WATCHING` | `Refused(code)` | same; nothing was written |
| `NOT_FOUND` | `Refused(code)` | "The session is not running. Nothing was sent." (raised only before the first key) |
| no answer (connection ended), `TIMEOUT`, other | `Unknown` | warning: "Can't tell whether the answer was written. Check the desktop." |

Warnings stay until dismissed; `Gone` and `Refused` also go when the held prompt's id changes. A detach turns an
in-flight answer into `Unknown`. Log kind `ANSWER`: `kind`, `decision`, `outcome`, `error`, `open` — never text.

### D5. Attachments: preparing on the phone

The composer gets "Attach" (shown when `attachments.put` is listed and the session is an agent session) with two
choices: "Photo" — `PickMultipleVisualMedia(maxItems = 10)` with `ImageOnly` (the system photo picker; no permission; a
fixed 10, because the contract refuses a maximum below 2) — and "File" — `OpenMultipleDocuments(["*/*"])`. The
phone holds at most 10 attachments per composer (the desktop's unsent limit); picks beyond the free places are
dropped with a message.

A `Preparer` (needs a `Context`; app-wide scope on `Dispatchers.IO`, so leaving the screen does not cancel it)
turns each picked `Uri` into a file under `cacheDir/attachments/` and reports it to `Desktop`:

1. Read the display name (`OpenableColumns.DISPLAY_NAME`; fallback `photo` / `file`) and the first bytes.
2. `AttachmentKind.of(head)` — a pure mirror of the desktop's signatures — says PNG, JPEG, GIF, WebP or PDF.
   - Accepted image or PDF of ≤ 10 MiB: copied as is — except a JPEG whose EXIF orientation is not "normal"
     (`android.media.ExifInterface`), which is converted below, so the agent sees it upright whether or not its
     tools honor EXIF.
   - Anything `ImageDecoder` can decode that is not accepted (HEIC/HEIF, AVIF, BMP…), a rotated JPEG, or an
     accepted image over 10 MiB: decoded with a target size of at most 4096 px on the long edge (keeps a 50 MP photo from needing
     200 MB of bitmap; the agent's image input is far smaller), EXIF orientation applied by the decoder, encoded
     as JPEG quality 90; while over 10 MiB, scaled by 0.75 and encoded again. The name's extension becomes
     `.jpg`. A converted photo carries no EXIF (no location) — an unchanged one keeps its own.
   - Otherwise, a file of ≤ 10 MiB is accepted as text only when it holds no NUL and decodes as strict UTF-8
     (`AttachmentKind.isText`, same rule as the desktop); else refused on the phone: "Only photos, PDF and text
     files can be sent." Over 10 MiB: "Larger than the desktop's 10 MB limit." An empty file: "The file is
     empty." (the desktop requires a size of at least 1).
   Copying stops as soon as more than 10 MiB were read, so a huge pick costs nothing. The preparing step takes an
   `InputStream` supplier, so a test can count what was read.
3. Files of the cache directory are deleted when their attachment is sent, removed or dropped; a preparation that
   finishes for an attachment no longer held (removed, or its conversation replaced) deletes its own output; a
   reply removed by confirmation while still sending deletes its attachments' files. The whole directory is
   emptied at app start (`App.onCreate`) — memory state does not survive a process.

### D6. Attachments: uploading, owned by `Desktop`

`Conversation.attachments: List<Attachment>` — `localId`, `name`, `size`, `file`, `converted`, and `state`:
`Preparing`, `Queued`, `Uploading(attachmentId?, received)`, `Ready(attachmentId)`, `Failed(reason)`. Like the
draft, they belong to the held conversation: opening another session's conversation drops them (cancelling
their upload and deleting their files); leaving and coming back keeps them.

Uploading starts as soon as an attachment is prepared, one at a time per conversation in pick order, in a job in
`Desktop`'s own scope (not the connection's), so it survives screens and pauses across reconnects. Each step:
wait until a connection is attached (the current state, read on the dispatcher — not the next attach event, which
may already have happened), read the chunk at `received` from the file on IO, request `attachments.put` with a
30 s timeout. **Chunks are 64 KiB** (87,384 base64 characters), not the desktop's 512 KiB maximum: on a link of
50 kbit/s a chunk goes out in about 14 s, well within the 30 s call timeout and between the desktop's pings
(128 KiB would take about 28 s there — too close to the timeout); a 10 MiB file is 160 chunks. A file that can no longer be read → `Failed(FILE)`. A pure
`Uploads.next(attachment, answer)` decides:

- `{ received, complete: false }` → continue from `received`; `complete: true` → `Ready(attachmentId)`.
- `BAD_PARAMS` with a numeric `detail.received` → continue from there (the desktop got more, or less, than the
  phone believes — after a reconnect). At most three such resumes in a row without progress, then `Failed`.
  `BAD_PARAMS` without it → `Failed(BAD_PARAMS)`.
- `NOT_FOUND` while holding an `attachmentId`:
  - when the last chunk was sent and its answer never came → the desktop most likely completed it and forgot
    the id: `Ready(attachmentId)` (D7 handles a send that then finds it missing);
  - otherwise the upload is gone on the desktop (it quit, or 24 h passed): start again from 0 without an id,
    once; a second such `NOT_FOUND` → `Failed(GONE)`.
- `TOO_LARGE` (`detail.limit`), `TOO_MANY` (`detail.limit`), `UNSUPPORTED_TYPE`, `NO_CONVERSATION`,
  `NOT_FOUND` without an id, `UNKNOWN_OPERATION`, other codes → `Failed(code, limit)`.
- No answer (the connection ended, or the call timed out, which ends the connection) → stay `Uploading` and
  resume once a connection is attached, with the same `attachmentId` and offset. **Three chunks in a row without
  an answer at the same offset → `Failed(NO_ANSWER)`** ("The connection is too slow or unstable to upload this.
  Retry when it is better."), so a link too slow for even one chunk does not drop the connection — and with it
  the conversation, the notifications and answers — every 30 s indefinitely.

`Failed` attachments offer "Retry" (back to `Queued`, from 0, without an id) and "Remove". "Remove" on any state
cancels its upload and deletes its file; one already `Ready` or partly uploaded stays on the desktop unsent until
it expires — the `TOO_MANY` message says so ("The desktop holds 10 attachments not sent yet for this session;
removed ones count for 24 hours."). Log kind `ATTACH`: `event` (`prepared`, `converted`, `complete`, `failed`,
`removed`, `resumed`), `bytes`, `error`, `limit` — never names.

### D7. Sending with attachments, and confirming against the written text

`Replies.blocked` gains the composer's attachments: `Blocked.Attaching` while any is not `Ready` or `Failed`;
`Blocked.AttachmentFailed` while any is `Failed` (retry or remove it first); text that is empty after cleaning is
sendable when at least one attachment is `Ready`. `tooLong` is unchanged (ten ids are a few hundred bytes).

`Desktop.send` sends `Frames.conversationSend(sessionId, text, ids)` (the key `attachments` only when there are
ids). The `Reply` takes the attachments out of the composer (`Reply.attachments`: names and ids, for display:
"📎 photo.jpg, notes.txt") and records the answer's `text` as `Reply.written`:

- `Submitted`, `NotSubmitted`, `Unknown` → the attachments are spent (their files deleted).
- `Refused` (`WAIT_STATE`, `NOT_WATCHING`, `UNKNOWN_OPERATION`, `BAD_PARAMS`) → text and attachments go back to
  the composer, still `Ready` (the desktop released them).
- `Refused(NOT_FOUND)` with attachments → text back; attachments back still `Ready`, with "The session is not
  running, or an attachment is no longer on the desktop." Most often the session has no process and the
  attachments are still valid, so re-uploading them would only use up the desktop's unsent places. Each
  attachment counts the `NOT_FOUND` sends it was part of. At its second, only the doubtful attachments of that
  send are marked `Failed(GONE)` ("Retry" uploads them again): those whose `Ready` was presumed after an
  unanswered last chunk (D6), or that completed more than 23 hours ago (the desktop removes unsent ones after
  24). The others stay `Ready`. Only when none is doubtful are all of them marked `Failed(GONE)` — the session is
  then most likely not running, and re-uploading costs unsent places (see Risks).

Confirmation (`Replies.confirm`), per reply:

- with `written` (the desktop answered with `text`): a new user entry equal to `jsTrim(written)`;
- without `written` and without attachments (still sending, unknown, or a desktop before 29a5d0a): equal to
  `jsTrim(clean(text))`, as today;
- without `written` but with attachments (still sending, or unknown because the connection ended): an entry whose
  `jsTrim(entry.text)` starts with `jsTrim(clean(text))` (any entry when the text is empty) **and contains every attachment's id** —
  the desktop's sentence names each stored file by a path holding its id. An empty-text reply is never confirmed
  by an entry that merely has empty text.

Because the entry can in principle arrive before the answer, `Replies.answered` also runs the match over entries
held now that were not held when the reply was sent — **only for a reply with attachments**: its ids make the
match unique, whereas re-matching a plain reply could take an entry that already confirmed an earlier identical
one. `SendResult` gains `text: String? = null`.

### D8. Rename, and the session's menu

A session's actions live in a **session menu**, reached two ways: long-pressing its row in the session list
(`combinedClickable` on `SessionRow`: tap opens as today, long press opens a `DropdownMenu` anchored to the row,
with haptic feedback), and "⋮" in the conversation screen's top bar. Both list the same actions from one pure
`SessionActions.of(session, operations)`, in this order: "Rename" when `sessions.rename` is listed; "Hibernate" when
`sessions.hibernate` is listed and the list shows the session running (a dormant one would answer with no effect);
"Wake" when `sessions.wake` is listed and the list shows the session dormant; "Close session" when `sessions.close`
is listed. "Wake" is there because the phone can now put a shell to sleep, and a shell's screen has no "Wake" (the
app has no terminal view); it calls the existing `Desktop.wake` and shows its errors (`wakeError`) in the session
action dialog (D9), without confirming — waking stops nothing. A session with no action offered gets no menu and a long press does
nothing (an older desktop). Every listed session qualifies — agent or shell, running or dormant (a dormant row
long-presses like any other; tapping it still opens its screen to wake). Every item is enabled under the same
conditions as "Rename" below.

"Rename" opens a dialog with a
single-line field (newlines cannot be typed; at most 200 characters) prefilled with the current label, and
"Rename", "Clear name" (sends `""`: "The session's own title is used again."), "Cancel". Enabled while connected,
not paused, the list current and the desktop has a window. `Desktop.rename(sessionId, name): RemoteError?` is a
`suspend` call shaped like `wake`. On success the dialog closes and nothing is changed locally — the label changes
when `sessions.changed` arrives. Errors keep the dialog open with the reason: `NOT_FOUND` "This session no longer
exists.", `NO_WINDOW` "spekterm has no open window on the desktop.", `TIMEOUT` "The desktop did not answer in
time; the name may still change.", not connected, other "Could not rename it (CODE)." A "Rename" with text equal
to the label after the desktop's cleaning (`Rename.clean`: control characters removed, the first 200 **code
points** kept — the desktop counts code points, not UTF-16 units — then JavaScript's trim) sends nothing and
closes. "Clear name" is always sent: the phone cannot tell a given name from the agent's own title. Log kind `RENAME`: `cleared`, `error`.

### D9. Hibernate and close: confirm, send without force, ask again only when busy

`Desktop.hibernate(sessionId, force)` and `Desktop.close(sessionId, force)` return `RemoteError?`, shaped like
`wake` (default 20 s request timeout, above the desktop's 15 s; no answer → `NOT_CONNECTED`); `force` is sent only
when true. On success nothing is changed locally: the list, and the watch's end (D10), say what happened. The menu
item opens one **session action dialog**, shared by the list and the conversation screen, driven by a pure
`SessionAction` state machine (`Confirm`, `Working(force)`, `Busy(reason)`, `Failed(text)`, `Done`):

- **Hibernate** starts at `Working(false)` ("Hibernating…"): hibernating without force stops nothing that is
  under way (the desktop refuses a busy session), and the session can be woken again from the phone (its screen
  for an agent, the menu's "Wake" for either), so it is not confirmed.
- **Close session** starts at `Confirm`: "Close “<label>”? It leaves the desktop for good and can't be reopened.
  Attachments not sent yet are removed." — "Close session" / "Cancel"; confirming → `Working(false)`.
- `SESSION_BUSY` → `Busy(reason)`: "“<label>” is busy: <reason text>" and "Hibernate anyway? What it is doing is
  stopped." (or "Close anyway? What it is doing is stopped, and the session leaves the desktop for good.") —
  "Hibernate anyway" / "Close anyway" and "Cancel"; agreeing → `Working(true)`. A forced request is never the
  first one, and never sent without this second answer (obligation 12).
- `SessionAction.busyText(reason)` (pure): `working` "The agent is working."; `choice` "The agent is waiting for
  an answer to a choice."; `unsent` "A message to the agent has not been confirmed sent yet."; `job` "A command
  is running in it."; `unknown`, absent or any other value (obligation 7) "The desktop can't tell whether it is
  idle."
- Errors → `Failed`, with "OK": `NOT_FOUND` "This session no longer exists."; `NO_WINDOW` "spekterm has no open
  window on the desktop."; `TIMEOUT` "The desktop did not answer in time; it may still happen."; `CREATE_FAILED`
  "The desktop could not do it." plus its `message`; not connected (also no answer) "Not connected to the
  desktop. The session list shows whether it happened."; other "Could not hibernate it (CODE)." / "Could not close
  it (CODE)." A `SESSION_BUSY` to a forced request (not expected) is shown as an error, never forced again.
- Success → `Done`: the dialog closes. **Closing from the conversation screen then returns to the session list**;
  hibernating from it leaves the user on the screen, which turns dormant (D10).

The dialog keeps the label it was opened with (the list may drop the session meanwhile), so it is held at the
screen's level (the session list screen, the conversation screen), never inside a `SessionRow` or a lazy list
item, which would disappear — with the dialog and its coroutine — when the list drops the session. Leaving the screen while
a request is out does not cancel it (it runs in the connection's scope); its outcome is then only visible in the
list. Log kinds `HIBERNATE` and `CLOSE`: `force`, `error`, `reason` (the busy reason code) — never labels.

### D10. How a watch ends

`ConversationReducer.ended(c, reason)` branches on `conversation.ended.reason`, and `Desktop` logs the reason as
today:

| `reason` | Held conversation | Screen |
|---|---|---|
| `window` | as today: `Phase.WINDOW_GONE` until a list with a window | "no open window" |
| `hibernated`, absent, any other (obligation 7) | watch `Idle`, `notRunning = true`, phase `IDLE` — as a watch answered `NOT_FOUND` | Dormant with "Wake"; or Closed when the list no longer holds the session |
| `closed`, `exited` | watch `Idle`, `closed = true` (which, like `notRunning`, keeps `reconcile` from sending a watch); the session is **gone** (below) | Closed at once |

All of them clear `wait`, `pending` and `prompt` as today. `notRunning` already keeps the watch from being retried
and is cleared when the list shows the session turning to running or after a successful wake, so waking a
hibernated session watches again from the last entry held (`afterUuid`) — the existing resume. `Screen.of`
returns `Closed` also for `conversation.closed`, at the same precedence as a session missing from the current
list. An unknown reason is taken as "the process is not running", the one reading that never retries a watch in a
loop: a still-running session wakes at once (`sessions.wake` on a running session answers at once) and is watched
again.

**A gone session.** The held conversation's session is gone when any of these happens first: `conversation.ended`
`closed` or `exited`; a current list with a window that no longer holds it (this covers a close whose list
arrived before the end, which `Desktop` then drops because the watch is no longer active, and a close of a
session that was not watched); or the phone's own `sessions.close` succeeding. Then its attachments are dropped —
uploads cancelled, files deleted; the desktop removed them — and a reply's attachments coming back from a send
answered after that are deleted instead of being returned to the composer. The Closed screen says "This session
is no longer on the desktop." (today "…was closed on the desktop.", which is wrong for one closed from the phone or
one whose process ended).

### D11. Test carriers

- JVM: `FramesTest` (operations; the new request payloads, `attachments` key only with ids; `explicitNulls`),
  `PromptTest` (decoding every kind, unknown kind, malformed fields, the phone's own guard, a wait event whose
  prompt is malformed still decoded), `AnswersTest` (offers per kind and gate, selections validity, outcome
  mapping), `SessionActionsTest` (items per operation and running state; the action state machine: hibernate
  straight to working, close confirmed first, busy → force only after agreeing, busy texts, unknown reason, error
  texts), `ConversationReducerTest` (each `conversation.ended` reason), `AttachmentKindTest` (every signature, text with NUL, invalid UTF-8, BOM), `UploadsTest` (every row
  of D6), `RepliesTest` additions (empty text with attachments, blocked states, confirm against `written`, the
  entry arriving before the answer, refused with and without `NOT_FOUND`), `ConversationReducerTest` (prompt
  replaced and cleared).
- Instrumented on the emulator: `DesktopTest` with the scripted desktop — operations kept across a detach and
  emptied by `clear()`; `sessions.hibernate` / `sessions.close` payloads with and without `force`, and
  `conversation.ended` with each reason reaching the held conversation; an answer's request and outcome; a detach during an answer → `Unknown`; an upload in
  three chunks; resume from `detail.received` after a reconnect; `NOT_FOUND` restart; send with ids and `r.text`
  confirming; rename's request. `PreparerTest` — a HEIC sample (committed under `androidTest/assets`, made with
  `pillow-heif` in a scratch venv; fallback `androidx.heifwriter` on the emulator) becomes a JPEG under 10 MiB with
  the right orientation; an oversized PNG is re-encoded; a binary is refused; a text file passes.
- On the Pixel against a dev desktop at ≥ 9b02f53: each kind of prompt answered, outcomes, attachments from the
  photo picker and the file picker, a HEIC photo, a dropped connection mid-upload, rename and clear, hibernating
  and closing (idle and busy, from the list and from the screen), the desktop hibernating or closing a watched
  session, and an older desktop (or `operations` missing) showing none of the new controls.

## Risks / Trade-offs

- [A prompt dismissed without a key still looks open on the desktop] → an answer types a digit into the agent's
  input (stays unsent) or Esc interrupts a working agent. Desktop behavior, documented there; the phone cannot
  detect it. Accepted.
- [The user removes uploaded attachments and picks again] → they count towards the desktop's 10 unsent for 24 h;
  `TOO_MANY` explains it. No delete operation exists.
- [Opening another session's conversation drops picked attachments] → same as the draft today; uploads already
  done stay on the desktop unsent. Accepted for symmetry; noted in the screen only through the empty composer.
- [Slow links] → 64 KiB chunks (~87 KB of JSON each, 160 for 10 MiB) go out within about 20 s down to about
  35 kbit/s; much below that each chunk times out, which ends the connection, and after three such attempts at one
  offset the attachment fails instead of cycling. Base64 costs a third more bytes.
- [Lost answers during an upload] → a lost answer to a first chunk leaves an upload the phone cannot name, and a
  restart after `NOT_FOUND` can duplicate one; both count towards the desktop's 10 unsent for up to 24 h.
  Accepted: rare, and explained by the `TOO_MANY` message. The same holds when a send is refused `NOT_FOUND`
  twice with no doubtful attachment and the user retries them all.
- [Converting a large HEIC on a low-end phone] → decoding at ≤ 4096 px bounds memory; failures show as a failed
  attachment.
- [`approve-auto` on an account where auto mode is not offered] → the desktop answers `mismatch`; the warning
  sends the user to the desktop.
- [The plan or question text holds paths] → shown as plain text, never as links; nothing is loaded.
- [A stale `operations` set after the desktop was downgraded] → the next authentication replaces it; until then
  a request answers `UNKNOWN_OPERATION`, shown as a refusal.

- [Closing cannot be undone] → confirmed every time, and forced only after a second answer that shows why the
  desktop thinks the session is busy. `unknown` (agent events off; every shell on macOS) makes every hibernate or
  close of a running session ask twice there. Accepted: the protocol requires it.
- [Hibernating right after creating (spekterm #82)] → the session cannot be woken. Desktop gap; rare from the
  phone (creating, then opening the menu and hibernating, takes well over 0.5 s). Not guarded on the phone.
- [A hibernate or close left running when the screen is left] → its outcome is not shown; the list shows the
  result. Accepted.

- [An unknown `reason` on a session still running] → the screen shows it dormant until the user taps "Wake",
  which answers at once and watches again. Chosen over watching again on its own, which could loop.

## Migration Plan

None: no stored data changes. The attachment cache directory is new and emptied at start. Rollback is
reinstalling the previous build.
