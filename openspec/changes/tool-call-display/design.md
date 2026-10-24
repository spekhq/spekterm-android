## Context

Today (`net/Sessions.kt`) `Entry.Tool` is `{ id, name, arg }`: `arg` is the first non-blank of `file_path`, `path`,
`pattern`, `command`, `url`, `query`, `description` in the call's input (spekterm `src/main/transcript-follow.ts`
`identifyingArg`), untruncated. `Entry.Result` is `{ id, text, error }`, `text` being the result's text blocks joined.
`Entry.User.meta` is true for anything the user did not type.

`ui/ConversationScreen.kt` shows a call as a line (`CallLine`: mark, name, `arg` on one line) that expands into two
`Mono` boxes of the same style — the argument, then the result — and a run of calls (`RunItem`) as a count plus
"name arg" of the latest. `desktop/ConversationItems.kt` pairs calls with results and forms runs, absorbing thinking
between calls; any other entry ends a run.

The desktop's side is spekterm's change `remote-tool-detail` (handed off 2026-10-24, open; its design D8 says the
final shape is sent to this session after kewang's review). This design follows that change's design as written on
2026-10-24:

- tool entry: `input?` — fields white-listed per tool (its D1 table: Bash, Edit, Write, Read, Grep, Glob,
  SendMessage, Agent/Task, Skill, SendUserFile, AskUserQuestion, ToolSearch, WebSearch, WebFetch, Monitor, TaskStop,
  ScheduleWakeup, CronCreate, Artifact, ExitPlanMode), scalars passed through as their type (`run_in_background` is
  a **string** in practice), `files` a string list, `questions` nested; strings cut at 4,000 characters and 12,000 per
  call, `inputTruncated?: { field: originalLength }`. **Tools off the list, `mcp__*` included, carry no `input`.**
- user entry: `origin?` — `{ kind: "peer", sender: "session"|"subagent"|"other", name? }` or
  `{ kind: "task-notification" }`. No sender address. The text keeps the CLI's wrapper
  (`<cross-session-message …>` / `<agent-message …>` / the hand-back preamble).
- Newly shown entries: a peer message or a task notice that arrived while the agent worked (spekterm measured 209
  peer and ~690 notice deliveries in two weeks), as `meta: true` user entries with `origin`.
- `files.read` `{ sessionId, toolUseId, index, offset?, version? }` → `{ name, size, mediaType, version, offset,
  data, complete }`, 512 KiB per chunk (base64), at most 32 MiB; `mediaType` from a fixed extension table, else
  `application/octet-stream`; errors `NOT_FOUND`, `NO_CONVERSATION`,
  `FILE_NOT_SENT`, `FILE_GONE`, `FILE_CHANGED`, `TOO_LARGE` (`detail.limit`), `BAD_PARAMS`. Listed in `operations`.
- Tool results unchanged (a SendMessage result is still the CLI's JSON text).
- SendMessage's `to` is not sent when it is a socket address (`uds:`); `recipient_kind` (`name` / `session` /
  `agent`) is sent beside it.

**Final** (spekterm-6bdb, 2026-10-24, approved by kewang; `remote-tool-detail` design D1–D8, carried into
`docs/remote-protocol.md`), which settles what follows where it differs:

- SendMessage's `to` is sent only when `recipient_kind` is `"name"`; `recipient_kind` always. AskUserQuestion keeps at
  most 4 questions of at most 8 options. `files` is present only when every item is a string (first 20; positions
  are `files.read`'s `index`).
- A session's message: the entry's `text` is the sender's message without the CLI's wrapper (the record's
  `origin.body`), except when that body is blank (1 in 794 measured), when the recorded text, wrapper included, is
  kept. A subagent's hand-back keeps its recorded text, preamble and indentation included.
- `sessions.list` `Session` gains `peerName?` (claude sessions with a name, running or dormant; never shells).
- `files.read` takes `length?` (1–524,288, default 524,288). `mediaType`: md/markdown `text/markdown`; txt/log/
  yaml/yml `text/plain`; json `application/json`; csv `text/csv`; html/htm `text/html`; png, jpg/jpeg, gif, webp,
  svg `image/svg+xml`, pdf, mp4, webm, mp3, zip; else `application/octet-stream`. Whatever the agent listed is read.
- New client obligation (§9): `input` and `origin` are agent-written text, **shown as plain text**; `files` entries
  are never opened as paths; a sent file is untrusted — Markdown with raw HTML as text, plain text and raster images
  in the app, SVG and HTML handed to the system, never rendered in a WebView.
- The phone's WebSocket client sets no cap on a received message (OkHttp has none; `RemoteClient` adds none), so a
  first update of several MB is received.

Measured on kewang's transcripts (this change's reviewer, 2026-10-10 to 10-24): every one of 528 Agent results is
the CLI's "Async agent launched successfully … agentId" text — the subagent's report arrives later as a separate
peer entry (`sender: "subagent"`, `name` = subagent type when present). SendMessage's `to` is a messaging name in about three
of four calls, a socket path (`uds:/run/…`) or an agent id in the rest. All 536 ToolSearch results have empty text. SendUserFile sent 19 files: md 7, jpg 5, mp4
5, apk 1, zip 1.

## Goals / Non-Goals

**Goals:**

- Every frequent tool reads, on its line and expanded, as what it did: who a message went to, what an edit changed,
  what a subagent was asked, which skill ran.
- In an expanded call, what the agent asked for and what came back are told apart at a glance and by a screen
  reader.
- A message from another session or a subagent's report says who sent it and reads as formatted text.
- A file the agent sent the user can be looked at on the phone when it is an image, Markdown or text.
- Nothing is lost against an older desktop: with no `input`, every call shows as today.

**Non-Goals:**

- Opening files other than those the agent sent with `SendUserFile`, or browsing the desktop's files.
- Viewing video, archives, PDFs or APKs inside the app (they are handed to another app, D9); saving a sent file
  to the phone's shared storage, or sharing it.
- Showing a subagent's own tool calls (they are not in the parent's transcript).
- Per-tool views for MCP tools: the desktop sends them no `input`.
- Linking a subagent's report back to its Agent call: no field connects them (the report names the subagent type,
  not the call).
- Syntax highlighting.

## Decisions

### D1. Decoding

`Entry.Tool` gains `input: Map<String, JsonElement>` (empty when absent or not an object) and
`cut: Map<String, Long>` from `inputTruncated` (non-numeric values dropped). Typed reads go through `ToolInput`:
`string(key)`, `strings(key)` (string items of an array), `int(key)` and `bool(key)` — the last two also accept a
string (`"true"`, `"42"`), since the CLI writes some of these as strings. A missing or mistyped field reads null and
never drops the entry.

`Session` (session list) gains `peerName: String?`, the session's messaging name. `SessionNames.label
(name, list)` gives the `label` of the listed session whose `peerName` equals `name`, or null: one place, used by
D2's recipient and D7's sender.

`ToolInput.objects(key)` reads an array's object items as `ToolInput`s (AskUserQuestion's `questions` and their
`options`).

`Entry.User` gains `origin: Origin?`: `Peer(sender: SESSION | SUBAGENT | OTHER, name: String?)` or `TaskNotice`;
an unknown `kind` or `sender` reads `Peer(OTHER)` for `kind: "peer"` and null otherwise. A `name` is used as given
(the desktop already strips control characters and clips it).

`files.read` is asked with `length` 65,536 (the phone's chunk for `attachments.put`, whose 30 s timeout it shares). Answers decode into `FileChunk(name, size, mediaType, version, offset, bytes, complete)`; base64 that
does not decode, or a missing `size`/`version`, is a malformed answer. `Operations.FILES_READ` gates the feature.

This follows the final protocol (Context); `docs/remote-protocol.md` is checked against it when task 1.1 starts.

### D2. One description per call: `ToolView`

A pure `desktop/ToolView.kt` turns a call (and its result) into what is shown, tested on the JVM:

| Tool | Line | Expanded |
| --- | --- | --- |
| Bash | `description`, else `command` | "Command" (`command`), then "Output"/"Error"; "Runs in the background" when `run_in_background` |
| SendMessage | "→ recipient" (below) · `summary`, else the message's first line | "Message" (plain, D5); "Sent" / "Not sent" (below) |
| Edit | file name (last path segment), "· all" when `replace_all` | path; "Change" as a line diff (D4); result only when failed |
| Write | file name | path; "Content"; result only when failed |
| Read | file name, "lines a–b" from `offset`/`limit` | path; "Output" |
| Grep, Glob | `pattern` | `path`, `glob`; "Output" |
| Agent, Task | `subagent_type` · `description` | "Prompt" (plain, D5); the result hidden when it is the CLI's launch notice (below) |
| Skill | `/skill args` | "Output" |
| SendUserFile | `caption`, else the file count | caption; one row per file (D6) |
| AskUserQuestion | first question | each question with its options; "Answer" |
| ToolSearch, WebSearch | `query` | "Output" |
| WebFetch | `url` | "Prompt" (`prompt`, plain); "Output" |
| Monitor | `description` | "Command"; the result hidden when it is the CLI's start notice (below) |
| TaskStop | `task_id` | "Output" |
| ScheduleWakeup | `reason` | the delay in minutes; no result block unless failed |
| CronCreate | `cron` | "Prompt"; "Output" |
| Artifact | `action` · `file_path` or `url` | "Output" |
| ExitPlanMode | "Plan" | "Plan" (plain, D5); "Output" |
| any other, or no `input` | `arg` | `arg` block, then "Output" (today's view) |

Rules across the table:

- A result block whose text is blank is not shown (ToolSearch's results are all blank). An Agent result starting
  `Async agent launched` and a Monitor result starting `Monitor started` are the CLI's notices, not output, and are
  not shown; any other text is (a subagent run in the foreground by a later CLI would return its report there). A
  failed call always shows its result, labeled "Error".
- A missing field falls back: the line to `arg`, an expanded part to nothing. With no `input` at all (an older
  desktop, or a tool off the desktop's list) the last row applies, which is today's view.
- `title` is the tool's name; an `mcp__server__tool` name shows as `server · tool` — on any desktop, a deliberate
  change from today's raw name (the name is not lost, only spaced).
- **Recipient**: a `to` that names a session in the current list (by `peerName`) shows that session's label — what
  kewang sees in spekterm, e.g. "→ 自動更新功能" — and follows a rename at the next list; a `to` no listed session
  has is shown as given. Without `to`: `recipient_kind` `session` → "another session", `agent` → "a subagent", none
  → no arrow. A `to` starting with `uds:` or of the form `a` and 16 hexadecimal digits (never sent by the final
  protocol; guarded anyway) reads "another session" / "a subagent". A socket path is never shown.
- **SendMessage result**: when the text parses as a JSON object with a boolean `success`, it shows "Sent" or "Not
  sent", plus the object's `message` text when not sent; otherwise the text as is. The call's mark still follows
  `error`. A SendMessage with no message (a pure `notify_when_idle` subscription) shows "Asked to be told when it is
  idle" instead of a message.
- **AskUserQuestion**: each question's text and its options' labels, from `questions[].options[]`.
- **Run summary**: `RunItem` collapsed shows the latest call's title and line, as today shows name and `arg`.

### D3. Telling what was asked from what came back

An expanded call shows labeled parts. Each part has a small label above it (the names in D2's table) and a style by
role:

- **Input** (what the agent supplied — a command, a prompt, a content, a message): a left accent bar in the primary
  color on the `surfaceContainerHigh` background; Bash's command is prefixed with `$ `.
- **Output** (what came back): the `surfaceContainer` background as today, no bar; a failed call's output in the
  error color under the label "Error".

The label, the bar and the `$ ` prefix differ in shape and text, not only color. Each part is one accessibility
node whose description starts with its label ("Command, ls -l …"); the label `Text` is cleared of semantics so it is
not read twice. A field named in `cut` ends with the muted line "Cut short on the desktop (N characters)"; for `files` and
`questions`, whose value is a count of items, "Cut short on the desktop (N items)".

### D4. Edit's diff

`old_string` and `new_string` are split into lines (`\r\n` and `\n`; an empty string is zero lines, not one empty
line). The common prefix `p` is counted first; the common suffix `s` is counted only over what remains after the
prefix (`s ≤ min(old, new) − p`), so repeated lines never count twice. Up to three prefix and suffix lines are shown
as context (more fold into one "⋯" line); the old middle is shown as removed (`- `, danger color at low alpha
behind), then the new middle as added (`+ `, working color at low alpha behind). Identical strings show the context
alone with "No change". An Edit's strings are fragments, not whole files, and need not start at a line boundary; the
lines are shown as fragments, which reads correctly. A cut field shows the diff with the "cut short" line, and no
"No change" claim.

### D5. Which text is Markdown

The protocol's new obligation makes every `input` field and `origin` plain text: a sent SendMessage message, an
Agent prompt and an ExitPlanMode plan are shown as plain, selectable text, as are commands, file content, results
and task notices.

The one text formatted is a received peer message's body (another session's message, a subagent's report),
expanded: it is the entry's `text`, as an agent's own text is, and a report is written as Markdown for a reader. It
is rendered with `AgentMarkdown` — the same rules as agent text: nothing remote is loaded, HTML is shown as text,
links open only on tap, only `http(s)`. This changes `conversation-view`'s sentence on plain text (delta spec).

### D6. Sent files

The SendUserFile call lists `files` as rows: an icon by kind, the file name (last segment), and the parent
directory shortened, muted. Kind by the name's extension (case-insensitive): image `png jpg jpeg gif webp`; Markdown
`md markdown`; text `txt log json yaml yml csv`; everything else other (including `svg`, `pdf`, video, archives). A
row of an in-app kind is a button opening the viewer; a row of any other kind is a button "Open with…" that hands
the file to another app (D9). With
`Operations.FILES_READ` not offered, no row is a button and the call says the desktop cannot send files to the phone
(a desktop that sends `input` but not the operation; `remote-tool-detail` ships both, so this guards a mismatch). A
desktop sending no `input` lists no files: the call shows today's view.
A file whose delivery the CLI reported as failed is still listed (the call lists it); reading it gives what the
desktop answers.

**Reading** (`desktop/SentFiles.kt`, run from `Desktop`): `files.read` with `toolUseId` = the call's `id` and
`index` = the row; then chunks from each answer's `offset + bytes`, naming the first answer's `version`, until
`complete`. Each chunk has its own 30 s timeout, as `attachments.put`'s chunks do; a timeout ends the connection (the
client's rule), so it is shown as "the phone is not connected". After the first chunk, a `size` over the phone's
limit for the row's kind stops the download with "too large" — Markdown 256 KiB and text 1 MiB (every Markdown file
sent so far is under 8 KB; parsing 1 MiB of Markdown takes most of a second even on a desktop JVM), images 16 MiB (a
photo is a few MiB; more compressed image than that is more than a phone screen shows), the desktop's own 32 MiB
above all. `FILE_CHANGED`
starts once more from 0, then reports "changed while reading". For the in-app kinds, bytes are gathered in one `ByteArray`
in memory; for the other kinds they are written chunk by chunk to the hand-off file (D9), never held whole. Leaving
the viewer cancels the download and drops the bytes (and a partly written hand-off file). The connection log gets one line per read: the
operation, the outcome code and the size — never the name, path or content (`ConnectionLog` gains a `FILES` kind).

**Viewer**: a full-screen `Dialog` drawn over the conversation screen, not a navigation route, so the conversation
keeps its expanded items, its scroll position and its follow state when the viewer closes. Its state (which file —
session, call id and index — and the download) lives in `ConversationScreen`, not in the row, so a row scrolled out
of composition while the list follows the newest neither closes it nor cancels the download. A list that was
following the newest is still following it when the viewer closes. The conversation stays watched while the viewer
is open, deliberately: the viewer is part of that screen, and closing it returns to a live conversation.

Which view: `mediaType` `image/svg+xml` and `text/html` → handed to another app (D9), never rendered; other `image/*` → image; `text/markdown` → Markdown; other `text/*` and `application/json` →
text; anything else (`application/octet-stream` included) → the row's kind by extension. It shows progress (bytes
of size), then:

- image: `BitmapFactory` with `inSampleSize` so the decoded size is at most the screen's longer side (a 2400-pixel
  screen decodes a 4:3 photo to at most 2400 × 1800, ~17 MB ARGB); pinch to zoom. A decode failure says the file
  cannot be shown;
- Markdown: `RepositoryMarkdown`, loading nothing remote, raw HTML shown as text, parsed off the main thread before
  it is shown;
- text: decoded as UTF-8 (malformed bytes replaced), split into lines, each line over 2,000 characters split into
  pieces of 2,000, shown in a `LazyColumn` of selectable monospace items that wrap (no horizontal scrolling: a 1 MiB
  one-line JSON would otherwise be measured as one line wider than Compose's largest constraint).

Errors say what happened: not sent in this conversation (`FILE_NOT_SENT`, also after `/clear`), no longer on the
desktop (`FILE_GONE`), changed while reading, too large (the size, or the desktop's limit for its `TOO_LARGE`),
cannot be shown, the phone not connected (also after a timeout), the session gone (`NOT_FOUND`); any other code
(`NO_CONVERSATION`, `BAD_PARAMS`, one not known) reads "The desktop could not send this file" with the code.

### D7. Where a message came from (labels kept for D11's header; the collapsed rows are replaced by D11)

`PlainRow` labels an injected `Entry.User` by `origin`:

- `Peer(SESSION, name)` → "From 自動更新功能" — the label of the listed session whose `peerName` is `name`; a name no
  listed session has (a Claude session outside spekterm, or an older desktop) → "From spekterm-e8d8"; without a name
  → "From another session";
- `Peer(SUBAGENT, name)` → "Report from openspec-change-reviewer"; without a name → "Report from a subagent";
- `Peer(OTHER, name)` → "From name", or "Message from another agent";
- `TaskNotice` → "Background task notice";
- no `origin` → today's "Injected".

The body stays collapsed until expanded, and is rendered as Markdown (D5) after the CLI's wrapper is removed. The
wrapper holds the sender's socket path or internal agent id, and its HTML-like tag line would swallow the report's
first heading (a lone tag line opens an HTML block in the Markdown parser). A pure `desktop/PeerText.kt` strips
exactly the known shapes, measured in kewang's transcripts:

- a leading line `Another Claude session sent a message:`;
- an opening `<cross-session-message …>` or `<agent-message …>` line and its closing tag;
- a hand-back's `[Subagent hand-back] …` preamble up to and including the line `The report follows:`, and the
  two-space indentation the harness adds to each report line (with any `[harness: …]` note line);
- the instruction paragraph after the closing tag that starts `This came from another Claude session`.

Text that matches none of these is shown as it is; an unwrapped message has nothing to strip. The label never reads the text.

### D8. Runs and the newly shown deliveries

A `TaskNotice` entry is treated exactly as thinking is in `ConversationItems.group`: held while a run forms, joined
to the run as a part when another call follows, and shown on its own after the run when something else follows.
Notices arrive while the agent works (most of the ~690 measured mid-turn ones), and between calls each would
otherwise split a run; a notice that is the last thing before the agent writes is often why it writes, and stays in
view. A peer message ends the run, like a reply: a message from another session or a subagent's report is what the
user most wants to see, and must not hide inside a collapsed run. The run's count and failures count calls only, as
today; a run still needs two calls.

### D9. Handing a file to another app

kewang chose (2026-10-24) that a sent file the app cannot show — video, PDF, archive, APK, SVG — is handed to
another app rather than only pointing to the desktop. Another app can only read a file, so this is the one place
conversation content touches the phone's storage, kept as small as it can be:

- The file is written to the app's cache, `cacheDir/sent/<random>/<name>` (a directory per hand-off, the name being
  the desktop's `name`, the base name only), chunk by chunk as it arrives — up to the desktop's 32 MiB. The cache is
  private to the app, never backed up (Android's backups leave the cache out), and the OS may
  clear it at any time.
- Once complete, it is offered with `Intent.ACTION_VIEW` and the desktop's `mediaType` (`application/octet-stream`
  falls back to the type Android's `MimeTypeMap` gives the extension), through the existing `FileProvider`
  (`file_paths.xml` gains `<cache-path name="sent" path="sent/" />`), with read permission granted to that one URI
  only, in a chooser. No app to open it → "No app on this phone opens this kind of file", and the file is deleted at
  once.
- Deleted: everything under `cacheDir/sent/` when the conversation screen is left (its dispose), when the app starts
  (a hand-off left behind by a killed process), and when the pairing is removed. Not deleted when the user returns
  from the other app to the conversation: they may go back to it.
- Not in the connection log beyond D6's line (outcome and size); never in a notification.

The download shows in the same viewer `Dialog` (progress, errors) and closes it on hand-off.

### D10. Skills from a typed slash

kewang (2026-10-24, on the phone): the skills button (`agent-usage-and-skills` D4) narrows an already small reply
field, and reaching a skill takes a tap, a sheet and a search. Typing `/` is where the user already is. The button and
`SkillsSheet` go; the list appears from the field.

- **The word** (`desktop/SlashQuery.kt`, pure): the first word of the field's text is from index 0 to the first
  white-space character, white space being `Replies.jsSpace` (the agent CLI's `\s`, U+3000 and U+00A0 included) — not
  a regex `\s`, whose set differs between the JVM and Android's ICU. It is a command word when it starts with `/` and
  holds no other `/`. Leading white space is not skipped: a list appearing for " /x" would surprise, and
  `Replies.command` already treats both as commands when sent.
- **When** (`SlashQuery.query(text, selection)`): non-null when the selection is collapsed, the first word is a command
  word, and the cursor `c` satisfies 1 ≤ c ≤ the word's end. The query is the text from after the slash **to the
  cursor** (`text[1, c)`), so `/` typed before existing text ("/fix the login bug", c = 1) queries "" and lists every
  skill. The panel shows when the query is non-null, the field has focus, the watch is active and `skills` is not
  null, and the app is connected, not paused, and the session not closed (the old button's conditions).
- **Closing it**: besides the word ending (white space typed, choosing), the cursor leaving it or the word becoming a
  path, Back closes the panel first (a `BackHandler` while it shows); it stays closed until the field's text changes.
  A draft restored as "/op" shows the panel only once the field is focused.
- **Order** (`SkillFilter`): matches as today (the query in the name or the description, ignoring case); names that
  start with the query, ignoring case, first, then the other matches; each group by name, ignoring case. An empty
  query lists all by name.
- **Choosing** (`SkillInsert.atCursor(text, cursor, name)`, new beside `insert`): with `c` = 1 (a slash typed before
  other text) the text after the slash is kept as arguments — "/fix the login bug" → "/simplify fix the login bug";
  with `c` > 1 the whole command word is replaced and the rest, its separator first, kept — "/review 123" (c anywhere in
  "/review") → "/code-review 123", "/op" → "/opsx:apply "; the result always has a separator after the name, and the
  cursor goes after it, which ends the word and hides the panel. Written with `Desktop.setDraft`; focus stays in the
  field and the keyboard up. `insert` is kept only if something else still calls it, else removed with its tests moved.
- **Where**: inside the composer, after its divider and directly above the attachments row (when there is one) and the
  field — below the notes the composer shows above its divider (attachment note, unsent first prompt, blocked text,
  pasted command), so nothing sits between the list and the field. At most `min(240 dp, the screen column's maxHeight
  / 4)` high — the `backgroundMax` rule's `maxHeight`, passed into `Composer` — and scrolling, so on a landscape
  screen with the keyboard up (about 120 dp left) the field row is still measured. When it appears while the list
  follows the newest, the list scrolls to the newest once, so the panel does not push the latest entry out of view.
- **Rows**: `/name` in `titleSmall` and the description in `bodySmall`, one line, ellipsized, both plain `Text`; each
  row a button (`Role.Button`, an `onClickLabel` "Insert"), at least 48 dp high. A header line, "N skills" or the
  state text, is a polite live region so a screen reader announces the panel when it appears. Under the rows, in
  `labelSmall`, D4's footnote (built-ins not listed; a few skills only the agent may use). `Unknown` → "The agent lists
  its skills with the session's first message, and again after /clear. Send a message, then type / again." and no
  rows; `Known(empty)` → "No skills in this session"; no match → "No skill matches" (a built-in such as `/compact` is
  typed and sent as is).
- **Hint**: the placeholder reads "Reply to the agent · / for skills" while the panel would be offered on a `/` (the
  watch active, `skills` not null), else today's "Reply to the agent".
- **Removed**: `SpekIcons.Skills`, the `IconButton`, `SkillsSheet`, and the strings `conversation_skills`,
  `skills_title`, `skills_search`. `SkillsSheetTest` becomes `SlashSkillsTest`.

### D11. Messages shown as chat (kewang's layout review, 2026-10-24)

On the Pixel kewang found D7's collapsed lines and the SendMessage / SendUserFile calls inside runs "too much like a
tool": what is exchanged between parties should read as a chat app does. Anything the user did not type themselves
is "someone else": a peer's message, a subagent's report, the files the agent sent, and the agent's own SendMessage
look the same. Mockup agreed with kewang: the Design canvas "對話裡的訊息與檔案"
(https://claude.ai/artifact/NBtZZz8setN2pfvrVoTKjw, version 6). This supersedes D7's collapsed rows and D2's
SendMessage and SendUserFile rows for a desktop that sends `input`.

- **Which items** (`ConversationItems`): a new shown item `Message(key, from, to, body, files, status, at)`, made from
  - a `user` entry with `origin` `Peer` → from the sender (name resolved by `SessionNames`), body `PeerText.strip(text)`;
  - a `SendMessage` call with `input` → from this session, to the D2 recipient, body `message` (blank: D2's "asked to be
    told when idle" line), status from its result (sending until the result, sent, not sent with the reason, or the
    error text);
  - a `SendUserFile` call with `input.files` → from this session, body `caption`, `files`, status only when the result is
    an error.
  A message ends a forming run and is never a part of one. A `TaskNotice` keeps D8's grouping (held like thinking, a
  part when another call follows); standing alone it is a `Notice` line. Calls without `input` stay calls (today's
  view).
- **Look** (`ui/MessageViews.kt`): every message is the same row, on the left (the user's own messages stay on the
  right): a 32 dp round avatar (the name's first character on a colour picked from a fixed palette of six by the name's
  hash; a subagent with no name, the person icon), a header line ("自動更新功能 傳來", "openspec-change-reviewer 傳來報告",
  "A 傳來 3 個檔案", "A 傳給 測試收件"), the bubble (`surface` with an `outlineVariant` border, a 4 dp top-left corner,
  18 dp elsewhere), then the time and, for an outgoing message, "已送出" or, in the error colour with an icon, "沒送出：
  reason". This session's name is its `label`, else "agent". One accessibility node per message: header, body, status.
- **Body**: Markdown through `AgentMarkdown` for a received message or report (D5); plain text for what the agent sent
  (protocol obligation 18). Longer than 12 lines or 1,200 characters: the first 10 lines (or 1,000 characters) and a
  "顯示全部" / "收合" button, its state kept by item key like expansion.
- **Files** (in the bubble, under the caption): an image is a picture, 298 dp wide at most, its height by its aspect,
  read by itself when the message is composed: one `files.read` of the first chunk tells the size; at most 2 MB it reads
  the rest and decodes it to at most 600 px on the longer side (`SentFileViewer.sampleSize`); over 2 MB it stops and
  shows a card "照片 · 6.1 MB · 點一下載入". Thumbnails are cached in memory per conversation screen by (call id, index),
  never written to storage; a failure shows the card with the reason. Tapping a picture or a card opens D6's viewer (or
  D9's hand-off). Other files: a card with an icon by kind, the name, and "Markdown · 點一下閱讀" / "文字檔" / "用其他
  App 開啟". With `files.read` not offered, the names and D6's note, no picture.
- **Notice line**: centred, `labelMedium`, a check icon, the text of the notice's `<summary>` element (the CLI's
  "Background command "X" completed (exit code 0)"), else "背景工作通知"; tapping it shows the whole notice as before.

## Risks / Trade-offs

- [`docs/remote-protocol.md` differs in a detail from the message that announced the final shape] → task 1.1 reads
  the document first; the document wins.
- [The CLI renames a field] → the desktop drops it (its white-list), the view falls back to `arg`; a test per tool
  pins the fields read.
- [A text file just under 1 MiB on a slow phone] → lazy items of at most 2,000 characters, not one `Text` for the
  file.
- [Markdown in received peer messages] → same renderer and rules as agent text, already covered by its tests.
- [A sent file in the phone's cache while another app has it open] → only in the app's private cache, one URI
  granted read-only, deleted when the conversation is left or the app starts; the OS may clear it sooner, which the
  other app then reports.
- [Report and Agent call far apart in the conversation] → the report's label names the subagent type, which the call's
  line also shows.

## Open Questions

- None: the protocol is final (Context).
