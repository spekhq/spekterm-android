## Why

From the phone, the user can see that an agent works and answer it, but not what it works on: the change it is
writing, how far its `tasks.md` has got, or what the main specs say. On the desktop that is the side panel. spekterm
now gives paired devices a pinned, read-only view of each folder's OpenSpec (change `remote-openspec-browse`, commit
`0fcdc1f`, protocol still version 1, additive): `openspec.changes`, `openspec.change`, `openspec.specs`,
`openspec.spec`, and `openspec.watch` with its event `openspec.changed`, plus client obligation 14 (OpenSpec text is
untrusted). Desktops 0.2.6 and 0.2.7 already answer the four reads, without the watch (0.2.5 lists no operations, so the app
offers it nothing); 0.2.8 is the first release with the watch.

## What Changes

- **A way in from the session list.** A folder whose `sessions.list` entry carries `hasOpenSpec` gets an OpenSpec
  button in its group heading. The list's menu gains "OpenSpec", which lists every folder with OpenSpec, including
  folders that have no session and so no heading. Both are shown only while the desktop lists `openspec.changes`.
- **A folder's OpenSpec screen** with two tabs:
  - "Changes": the active changes, then the archived ones, in the desktop's order. Each row shows the change's
    readable name, its task progress, its start or archive date, and the branch of the working tree it is in when
    that is not the main one.
  - "Specs": the main specs by topic, each with the number of changes that touched it.
- **A change's screen**: its artifacts as tabs, in the order the desktop sends them.
  - Markdown artifacts (proposal, design) are rendered as Markdown.
  - The tasks artifact is shown as its sections and tasks, done or not, with the progress.
  - The specs artifact is shown as one section per capability the change touches.
  - Data files are shown as plain monospace text.
  - An artifact of a kind the app does not know is shown by its title.
- **A spec's screen**: the spec, and the changes that touched it. Each of those changes opens its change screen.
- **Live refresh where the desktop offers it.** With `openspec.watch` listed, the app watches the folder on screen
  and reads again what is shown when the desktop says the folder changed (an agent ticking a task), also after a
  reconnect. Every screen also has a manual refresh. A desktop without the watch is read the same way, and the
  screen says that it does not update by itself.
- **A change that was archived while open.** When an active change on screen is no longer found and the folder's
  archive holds a change with the same name, the screen offers to open the archived one.
- **Untrusted text, shown safely** (obligation 14):
  - raw HTML is shown as text;
  - a relative link or image reference is plain text and leads nowhere;
  - an `http(s)` link opens only on tap, in the browser;
  - no image is loaded.
  Repository Markdown is rendered as the desktop renders it: hard-wrapped lines are joined into paragraphs, unlike
  agent text.
- **Content too large for the phone.** A piece the desktop left out to fit its answer is shown as left out, too large to
  send, with its size. A list the desktop refuses as too large (`TOO_LARGE`) says so.
- **Nothing is written and nothing is kept.** OpenSpec content is held in memory only, for the screens open, and
  dropped on unpairing or pairing again. The connection log records each read's operation, cause, error code,
  number of items and number of left-out pieces, and each watch. It never records a slug, a topic or any content.

## Capabilities

### New Capabilities

- `openspec-browse`: browsing a folder's OpenSpec from the phone, read-only: changes, a change's artifacts, main
  specs with their history, live and manual refresh, and the safe display of repository text.

### Modified Capabilities

- `session-list`: a folder's group heading offers its OpenSpec, and the list's menu offers the OpenSpec of every
  folder that has one.

## Impact

- `net`: OpenSpec payloads in a new `net/OpenSpec.kt` (lenient, element by element), `Operations` for the five
  operations, request builders in `Frames`, `openspec.changed` routed to `Desktop` (`EventRoute`).
- `desktop`: a pure `OpenSpecTracker` for watch-then-read, coalesced re-reads and reconnects; `Desktop` gains the
  OpenSpec focus, reads, cache and event handling; log kind `OPENSPEC`.
- `ui`: the attachment size formatter moves to a shared `ui/Format.kt`; new `OpenSpecScreens.kt` (folders, folder, change, spec), new routes in `MainActivity`'s navigation graph,
  the heading button and menu item in `SessionsScreen`, a soft-break mode and inert relative links in
  `AgentMarkdown`, a few icons in `Icons.kt`. These build on `ui-polish`'s theme, top bar, notices and icons, and are
  implemented after `ui-polish` is committed.
- Tests: `OpenSpecTest` (decoding), `OpenSpecTrackerTest`, `OpenSpecTextTest`, `OpenSpecGatesTest`,
  `SessionsViewTest`, `EventRouteTest`, `DesktopTest` (scripted desktop), `AgentMarkdownTest`. Also a phone run
  against a dev spekterm desktop at or after `0fcdc1f`, and against spekterm 0.2.7 run from source (the reads but
  not the watch), recorded in `docs/conversation-findings.md`.
- No new dependency. Nothing changes for a desktop without the OpenSpec operations.
