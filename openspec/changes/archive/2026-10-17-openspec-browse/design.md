## Context

Built on `close-offer` (archived) and on `ui-polish`, which is still in progress when this is written: its
artifacts are written and no code has landed. This change is implemented only after `ui-polish` is committed.
Until then its files belong to that change (agreed with the session doing it, 2026-10-17). From `ui-polish` this
change uses:

- the theme: `SpektermTheme`, `MaterialTheme.colorScheme` roles, `LocalSpekColors`;
- `TopBar(title, scroll, onBack, subtitle, actions)` inside `SpekScaffold(topBar = { scroll -> … }) { content }`
  per screen, which applies the insets, and `MoreButton(onClick)` (signatures as the `ui-polish` session reported
  them on 2026-10-17; read them from the tree when implementing);
- `Notice(text, tone, …)`, with a required `Tone` (`Info | Warning | Error`);
- the app's own icons, `SpekIcons.*` in `ui/Icons.kt`;
- the folder heading layout of its D6: chevron, name, monospace branch, count pill.

The app today:

- `Desktop` holds the session list, the operations the desktop listed at authentication (`Desktop.operations`,
  kept across a detach), and the focused conversation. It does all of this on one single-threaded dispatcher, with
  a scope per attached connection whose requests die with it.
- Screens take and release a focus by their back-stack entry id on `ON_START` / `ON_STOP`. A newer screen's
  focus wins over an older screen's late release.
- `ConnectionService` routes events by kind (`EventRoute`). `Folder.hasOpenSpec` is already decoded and unused.
- `SessionsView.groups` leaves out folders without sessions, so not every folder has a heading.
- `AgentMarkdown` (mikepenz multiplatform-markdown-renderer 0.46.0) already loads no image, opens only
  `http`/`https` links on tap in the browser, and puts HTML back as text. It keeps every line break
  (`eolAsNewLine = true`), because agents do not hard-wrap. The library also has a lazy renderer
  (`LazyMarkdownSuccess`) and GFM tables.

From spekterm (`docs/remote-protocol.md` §2, §4, §5 "OpenSpec", §9 obligations 7, 8, 14; reference client
`scripts/lib/remote-client.mjs`; `scripts/probe-remote.mjs` `runOpenSpec`; `src/main/remote-openspec.ts`; the
archived change `2026-10-17-remote-openspec-browse`):

- The five operations and the shapes `Change`, `Worktree`, `Artifact` by `kind` (`markdown`, `data`, `tasks`,
  `specs`), and `omitted: { bytes }` standing in for left-out content.
- `openspec.watch` in `operations` means the pinned shapes, artifacts in display order, answers within 1 MiB and
  `openspec.changed` events. Desktops 0.2.6 and 0.2.7 have only the four reads (0.2.5 sends no `operations`
  at all, so the app shows it no way in). They send the same names plus extra
  fields (for example `relPath`, `schema`, and a tasks artifact's raw `content` next to `tasks`), artifacts in the
  order found, answers of any size, and no events.
- `description` is the slug without its date prefix, made readable (`2026-10-16-close-offer` → `close offer`;
  core's `parseSlug` drops `^\d{4}-\d{2}-\d{2}-` and turns runs of `-` and `_` into spaces). It is in the change
  list and in a spec's history, not in `openspec.change`'s answer.
- An archived change's directory is always `YYYY-MM-DD-<slug>`. `archivedDate` and a history entry's `date` are
  that prefix, and an active change's history `date` is `null`.
- An older desktop's artifact order is core's discovery order, newest file first.
- The desktop's side panel renders Markdown with GFM and standard soft line breaks (`remark-gfm`, no
  `remark-breaks`). It shows a relative link as text.
- Measured on spekterm's own repository (85 changes, 58 specs): change list 34 KB, one change 60 KB median and
  213 KB max, the largest spec 93 KB. Deflate is offered.

## Goals / Non-Goals

**Goals:**

- Following an agent's change from the phone: its tasks fill in on screen without touching anything, on a desktop
  that offers the watch.
- One parser and one set of screens for both kinds of desktop. The only difference is live refresh.
- Repository text can never make the app fetch, open or run anything by itself.

**Non-Goals:**

- Writing anything: ticking a task, archiving, editing an artifact. None of these is an operation.
- Search, or a diff between versions of an artifact.
- Showing `timestamp` (the first commit that touched the change's directory). The protocol says not to present it
  as "created" or "modified", and the lists already come sorted by it.
- Keeping content across app restarts or offline. Content is in memory only.
- A notification when OpenSpec changes.
- Text selection and copying.
- Localization. Text stays English, as `ui-polish` keeps it.

## Decisions

### D1. Wire shapes, decoded leniently in `net/OpenSpec.kt`

- **`OsChange`**: `slug`, `status` (a string; anything other than `"archived"` counts as active), `description`,
  `createdDate?`, `archivedDate?`, `taskStats?` (`total`, `completed`), `worktree?` (`branch?`, `isMain`). The
  `has*` flags and `timestamp` are decoded but not shown.
- **`OsChangeDetail`**: `slug`, `status`, `createdDate?`, `archivedDate?`, `worktree?`, `artifacts`.
- **`OsSpecSummary`**: `topic`, `relatedChangeCount`.
- **`OsSpec`**: `topic`, `content: Piece`, `history` (`slug`, `status`, `description`, `date?`), `origin?`.
- **`Piece<T>`**: content that may be left out. It is `Present(value)` or `Omitted(bytes)`, read from the holder's
  key, or from its `omitted` object when the key is absent.
- **`OsArtifact`** is a sealed type, decoded by hand from the JSON object:
  - `id` must be a string, or the artifact is skipped. `title` falls back to `id`.
  - `kind` `markdown` → `Markdown(content: Piece<String>)`.
  - `kind` `data` → `Data(content: Piece<String>)`.
  - `kind` `tasks` → `Tasks(Piece<TaskList>)`. `TaskList` has `total`, `completed`, and `sections` of
    `{ title, tasks: [{ text, completed }] }`. A raw `content` next to it (older desktops) is ignored.
  - `kind` `specs` → `Specs(deltas: [{ topic, content: Piece<String> }])`.
  - Any other `kind`, or a known kind whose content is neither present with the right type nor `omitted`, becomes
    `Other(id, title)`. The screen then shows only the title.
- **Lists are decoded element by element**, as `Sessions.kt` does: a malformed change, artifact, delta, history
  entry, section or task is skipped, never the answer (obligation 7). Unknown keys are ignored, so an older
  desktop's extra fields need nothing.

Over `@Serializable` classes for artifacts: the polymorphic decoder would fail an unknown `kind` or a wrong-typed
field for the whole answer, and `omitted` replaces a field of a different type.

### D2. Gating by operation

`Operations` gains `OPENSPEC_CHANGES`, `OPENSPEC_CHANGE`, `OPENSPEC_SPECS`, `OPENSPEC_SPEC` and `OPENSPEC_WATCH`.

| Control | Shown when listed |
|---|---|
| The heading button and the menu's "OpenSpec" | `openspec.changes` |
| The folder screen's "Specs" tab | `openspec.specs` |
| Opening a change from a row or from a spec's history | `openspec.change` |
| Opening a spec from a row | `openspec.spec` |
| Live refresh | `openspec.watch` |

Rows that cannot be opened are still listed, without the chevron and without a tap action. The conditions are one
pure function, `OpenSpecGates.of(operations, folders)`, giving what the session list and the screens may offer, so
they are tested on the JVM like `SessionActions.offerActions`.

### D3. Watch, then read: a pure `OpenSpecTracker`

`desktop/OpenSpecTracker.kt` is a pure state machine, unit-tested. Its inputs are focus, unfocus, idle, attached,
detached, the watch's answer, the read's answer, `openspec.changed` and refresh. Each input returns the next state
and the commands to send now: at most one `Watch(folderId?)` and at most one `Read(target)`. `Desktop` carries them
out.

- **Targets**: `Changes(folderId)`, `Specs(folderId)`, `Change(folderId, slug)`, `Spec(folderId, topic)`.
  The folder list has no target: it is read from the session list.
- **State**:
  - `attached`, and `live`: `openspec.watch` is listed on this connection;
  - `focus`: owner and target, or none;
  - `wanted`: the folder the phone wants watched (`null` for none);
  - `confirmed`: the folder the desktop watches for this connection, by its last watch answer (`null` at first);
  - `refused`: the set of folders whose watch was refused on this connection;
  - the watch in flight (with its folder), the read in flight (with its target), and `stale`: the focused target
    needs a read. `stale` is cleared when the read is sent, so an event during the read sets it again. The target
    is pending (below) while `stale` is set or the read in flight is the focus's target.
  - What a screen needs to know is exported per target: `pending`, and `notLive`, which is true while attached to a
    live desktop with the target's folder in `refused`. It is false after a successful Refresh or a new connection.

The rules follow from one fact about the desktop: it handles requests concurrently. It records a watch only when
the folder's scan is done, and a stop at once (`remote-protocol.ts`). So a stop or another watch sent while a slow
watch is in flight can be undone by that watch's answer. The rules:

- **Commands only while attached.** While detached, inputs update `focus`, `wanted` and `stale`, and nothing is
  sent. `Desktop.operations` outlives a connection, and the tracker must not act on it then.
- **At most one watch in flight.** The folder to watch is `wanted`, or `null` when `wanted` is in `refused`, so the
  desktop does not keep sending events for a folder the phone no longer shows. Whenever no watch is in flight,
  `live` is set, and that folder differs from `confirmed`, the tracker sends `Watch(folder)`. It re-checks after
  every answer, so the last wish always wins.
- **A read waits for its folder's watch.** On a live connection, a target whose folder is in `refused` is read at
  once, whatever watch is in flight. Any other target is read only when no watch is in flight and `confirmed` is
  its folder. On a connection that is not live, it is read at once. At most one tracked read is in flight. A read wanted meanwhile only sets `stale`, and is sent when
  the read in flight answers. A burst of events therefore costs at most one extra read.
- **Focus** sets `focus`, `wanted = focus's folder` and `stale`. Switching tabs in the same folder sends no watch.
  Moving to another folder sends `Watch(B)` once the watch in flight, if any, has answered. The read of B follows
  B's answer.
- **The watch's answer.**
  - On success, `confirmed` becomes its folder, and when that is the focus's folder, `stale` is set. This is a
    safeguard on top of the previous rule: any read that could have left before the desktop's watch took effect is
    followed by another.
  - On an error (`NOT_FOUND`, `INTERNAL`, …), `confirmed` stays, because the desktop keeps the previous watch. The
    folder joins `refused`, and its reads proceed. A `NOT_FOUND` read then shows the error.
  - The answer to `Watch(null)`, of any kind, sets `confirmed = null`: the desktop stops before anything that can
    fail.
  - A scan can fail for a passing reason (a directory moved while an agent archives). `refused` is therefore
    emptied by Refresh, which then watches before it reads, and by every attach.
  - While the focus's folder is in `refused` on a live desktop, the screen says that it does not update by itself
    right now (D6).
- **`openspec.changed { folderId }`** for `confirmed` with a focus in that folder sets `stale`. Any other folder,
  or no focus, is ignored. On the desktop, a watch's answer always leaves before its folder's events. On the phone,
  answers and events take different paths (`RemoteConnection`'s pending answers, and the event channel through
  `ConnectionService`), so an event can be handled before the answer. It is then ignored, and the read that
  follows the answer covers it.
- **Refresh** (while attached) empties `refused` and sets `stale`.
- **Unfocus** by the focus's owner clears `focus` and `stale`, and leaves `wanted` as it is. `Desktop` starts the
  linger (`openspecLingerMs`, 5 s, shortened by tests). Every unfocus restarts it, and a focus cancels it. When it
  ends, `Desktop` sends idle.
  - This covers going back from a change to its folder (one screen's `ON_STOP` and the next one's `ON_START`
    arrive in either order) without stopping and restarting the watch.
  - It also covers the app going to the background: the held connection keeps running for notifications, and it
    should not receive OpenSpec events nobody looks at.
- **Idle** with no focus sets `wanted = null`, which sends `Watch(null)` once `confirmed` is set and no watch is in
  flight.
- **Attached** (every new connection) resets `confirmed`, `refused` and everything in flight. `wanted` becomes the
  focus's folder, or `null`. `stale` is set when there is a focus. This is "watch again after every reconnect": a
  change made while disconnected is caught by the read that follows the new watch.
- **Detached** drops what is in flight. Answers of an old connection never reach the tracker: requests run in that
  connection's scope.
- **Pending.** The focused target counts as pending from when it becomes `stale` until its read answers, including
  the wait for a watch. That can take seconds: the first watch of a big folder scans it, git history included.
  The screen shows its progress bar and disables Refresh while pending. It shows an empty state ("No changes in
  this folder.") only after a successful read, never for a target not read yet.
- **Timeouts.** A watch or a read waits up to 60 s (`OPENSPEC_TIMEOUT_MS`), not the default 20 s. A call with no
  answer in time ends the whole connection (`RemoteConnection.call`). An older desktop's unbounded answer may also
  be several megabytes on a mobile link.
- **D5's extra read** of `openspec.changes`, made to find an archived change, is a request of its own, outside the
  one-read rule. It is made once per `NOT_FOUND` and has no effect on the tracker.

Over re-reading every target on the back stack on each event: only the screen on top is visible, and every screen
reads again when it comes back on top (focus always reads).

### D4. What the screens observe: an in-memory cache keyed by target

`Desktop.openspec: StateFlow<Map<Target, Loaded>>`.

- `Loaded` has `value?` (the last decoded result), `readAt?`, `error?` (the last read's error, cleared by a
  successful read), `pending` and `notLive` (D3) and, for a change, `archivedAs?` (D5).
- A screen shows the cached value at once, then the new one when it arrives. A failed re-read keeps the value and
  adds the error.
- The cache holds at most 8 targets. The least recently focused target goes first, and the focused one never does.
  Worst case is 8 MiB, under the 1 MiB bound of a desktop with the watch.
- `Desktop.clear()` (unpair, pair again) and `resetForTest()` empty it and reset the tracker and the linger timer.
- `pending` counts only while attached, so a detach ends it and the progress bar with it.
- Lazy lists key their items by slug, topic, artifact id, or section and task index, so a re-read keeps the scroll
  position.
- Errors, as text, each in an Error notice above the content (the value, if any, stays under it):
  - `NOT_FOUND` on a list: "This folder is no longer in the desktop's workspace."
  - `NOT_FOUND` on a change: "This change is no longer in the folder." See D5.
  - `NOT_FOUND` on a spec: "This spec is no longer in the folder."
  - `TOO_LARGE`: "Too large for the phone: the desktop sends at most N MB." N comes from `detail.limit`.
  - `INTERNAL`: "The desktop could not read it."
  - `UNKNOWN_OPERATION`: "This desktop does not offer it."
  - No answer, or not connected: "Not connected."
  - Anything else: "The desktop answered <code>."

### D5. A change archived while it is on screen

Following an agent to the end of its change means the slug disappears: archiving moves `x` to `YYYY-MM-DD-x`. So a
change read that answers `NOT_FOUND` for a slug without a date prefix makes `Desktop` read `openspec.changes` for
the folder once. The pure `archivedAs(slug, archived)` then picks the first archived change whose slug is
`<YYYY-MM-DD>-<slug>`, in the desktop's order (newest first). When there is one, the change screen shows "This
change was archived." with "Open archived change", as an Info notice. That button replaces the screen with the archived change's
screen. When there is none, the screen shows the D4 text.

Over following the archive by itself: the user may still be reading, and the archived copy can differ (its specs
were merged into the main specs).

### D6. Screens and navigation

New routes in `MainActivity`'s graph:

- `OpenSpecFoldersRoute`
- `OpenSpecFolderRoute(folderId)`
- `OpenSpecChangeRoute(folderId, slug)`
- `OpenSpecSpecRoute(folderId, topic)`

They are pushed over the session list, and back pops them. The screens live in `ui/OpenSpecScreens.kt`. Each is a
`SpekScaffold` with `TopBar`, and every screen that reads from the desktop (all but the folder list, which comes from the session list) has a refresh icon button labeled "Refresh". It is shown
always, and enabled while connected, not paused, and the target is not pending (D3). A thin
`LinearProgressIndicator` under the bar shows that the target is pending.

- **Status line**: fixed between the bar (or the tabs) and the scrolling content, not an item of it, because a
  lazy Markdown artifact is its own list:
  - paused or not connected: a Warning notice, "Not connected. Showing what was read at HH:MM.", or "Not
    connected." when nothing was read;
  - on a desktop without the watch: an Info notice, "This desktop doesn't update OpenSpec by itself. Read at HH:MM."
  - on a desktop with the watch, while `notLive` (D3): an Info notice, "Not updating by
    itself right now. Refresh to try again. Read at HH:MM."
- **Folders** ("OpenSpec"): the folders of the session list with `hasOpenSpec`, in the desktop's rail order. Each
  row shows the name and the branch in monospace. With none: "No folder on the desktop has OpenSpec."
- **Folder**: the title is the folder's name from the session list, or "OpenSpec" when the folder is not listed.
  The subtitle is its branch. Two tabs, "Changes" and "Specs", with the selection kept across rotation; each tab is
  one target.
  - **Changes**: "Active" with its rows, then an "Archived · N" heading. The archived rows are collapsed while there
    are active changes and expanded while there are none, until the user toggles the heading. A change row shows:
    - its `description` (`bodyLarge`, up to two lines);
    - "5/12 tasks" with a 48 dp progress bar, when it has `taskStats` with a total above 0;
    - for an archived change, "Archived 2026-10-16" from `archivedDate`; for an active one, "Started 2026-10-17"
      from `createdDate` when it has one (an archived change usually has both; its archive date is shown);
    - a monospace branch chip when `worktree` is present and not the main tree ("detached" for a null branch).
    With no change at all: "No changes in this folder."
  - **Specs**: rows with the topic (monospace) and "N changes". With none: "No specs in this folder."
- **Change**: the title is the slug made readable on the phone by `readableSlug`, the same rule as core's
  `parseSlug`, since `openspec.change` sends no description. The subtitle is "Active" or "Archived 2026-10-16", plus the branch
  chip under the same rule. Under them, a `PrimaryScrollableTabRow` of the artifacts' titles, in the order received. The
  selected artifact is kept by id across re-reads, and falls back to the first when its id goes. Per kind:
  - **Markdown**: the lazy renderer (D7) fills the tab.
  - **Tasks**: "7 of 12 done" with a progress bar, then each section. The title is plain text, `titleSmall`. Each
    task is a check-box icon, read-only, labeled "Done" / "Not done", next to its `text` as Markdown (D7). There is
    no checkbox control, so nothing invites a tap.
  - **Specs**: one collapsible block per delta. Its heading is the topic in monospace; its body is Markdown.
    Blocks start collapsed when there are more than three deltas and expanded otherwise.
  - **Data**: the text in monospace, one lazy item per line, wrapping. Never parsed.
  - **Other**: "This kind of artifact can't be shown on the phone."
  - An omitted piece, wherever it is (an artifact, a delta, the task list, a spec's text): "Left out by the desktop:
    too large to send with the rest (585 KB).", as an Info notice in the piece's place. The desktop leaves out the largest pieces until the answer fits, so
    a left-out piece can be well under 1 MiB, and the text says why rather than claiming a limit of its own. The
    size uses one shared formatter, moved out of `ConversationScreen`'s private `sizeText` into `ui/Format.kt`.
  - D5's "This change was archived." sits above the tabs.
- **Spec**: the title is the topic. The subtitle is "Main working tree · <branch>" when `origin` is present ("detached" for a
  null branch). Two
  tabs: "Spec" (lazy Markdown, or omitted) and "History · N". History rows show the description, the `date` when it has one (an active change has none), and
  "Active" or "Archived", in the desktop's order. They open the change when `openspec.change` is listed.

### D7. Repository Markdown

`AgentMarkdown.kt` is split so that its safety settings are shared by both kinds of text: the image transformer,
the link handler, HTML as text, and no animation. A new `RepositoryMarkdown(text, lazy)` uses them with two
differences from agent text:

- **Soft line breaks join** (`eolAsNewLine = false`), as on the desktop. OpenSpec files are hard-wrapped at about
  120 columns, and keeping every break would leave ragged half-lines on a phone.
- **An inline link whose destination is not `http`/`https` is its text**: no link style, no tap target. This
  covers the common `[design](design.md)` and anchors. The desktop shows a relative link as text. A reference-style
  link keeps the link style but still opens nothing, because the handler refuses every scheme other than
  `http`/`https`. A relative path has no scheme. Images load nothing, as for agent text.

`lazy = true` (artifacts, a spec) renders its blocks as items of a lazy list (the library's
`LazyMarkdownSuccess` in `Markdown`'s success slot), so a 100 KB design only lays out what is on screen. Its state
is parsed with `immediate = true`, as agent text is, so a re-read never passes through a loading state that would
replace the list and lose its position. Its items are keyed by block offset, so the position holds when the text
above it does not change; the list's state is the library's, not ours. `lazy = false` is for a task's text and a delta's body inside an existing lazy list. Agent text
keeps today's behavior.

### D8. The way in, against `ui-polish` D6

- **The heading.** `SessionsView.Group` gains `hasOpenSpec` (from the folder; `false` for the global and the
  unknown groups). The heading gets a trailing icon button (`description` icon) left of the count pill when
  `hasOpenSpec` is set and `openspec.changes` is listed. It is labeled "OpenSpec for <folder name>". It has its own
  click target and does not toggle collapsing, which stays the heading's own tap (agreed with `ui-polish`).
- **The menu.** The session list's ⋮ menu gets "OpenSpec" under the same operation condition, when at least one
  folder has `hasOpenSpec`. It opens the folders screen, which also reaches folders with no session.
- Both work while not connected. The screens then say so and show what is cached.

### D9. Connection log

New kind `OPENSPEC` with these fields:

- `op`: `changes`, `change`, `specs`, `spec` or `watch`; with `stop: true` for `Watch(null)`;
- `error`: the code;
- `items`: active plus archived changes for `changes`, specs for `specs`, artifacts for `change`, history entries
  for `spec`;
- `omitted`: how many pieces were left out;
- `cause`: `focus`, `event`, `refresh` or `archived`, for reads.

Never a slug, topic, title or content. The phone run reads the log for evidence that an event caused a read.

## Risks / Trade-offs

- **Large Markdown may render slowly on the phone.** spekterm's largest change has 87 KB of deltas. →
  Artifacts and specs use the lazy renderer, and deltas start collapsed beyond three. The phone run opens the
  largest change of spekterm's repository and records the time to first content. A delta body that is still slow
  is recorded, and a follow-up would render it lazily too.
- **A re-read of a lazy Markdown artifact re-parses it**, and the scroll position inside it may move when blocks
  above change. → Accepted: an agent mostly writes `tasks.md`, whose items are keyed.
- **Inline-link handling depends on the renderer's annotator** seeing `INLINE_LINK` nodes. → `AgentMarkdownTest`
  checks that a relative link has no link annotation and that an `https` link keeps one.
- **An older desktop's unbounded answers** (a huge `data` file) arrive whole. → Decoding is in memory either way.
  Such a desktop is rare and being replaced, and the cache bound counts targets, not bytes.
- **Reading a 200 KB change once a second while an agent writes**, on a mobile link. → One read at a time
  (D3), and the desktop throttles events to one a second. Reads stop when the screen is left.
- **A five-second linger** keeps events coming briefly after leaving the screens. They are ignored without a focus.
- **A short blind spot after a watch.** The desktop's file watchers become ready a moment after the watch's answer
  (`probe-remote.mjs` `runOpenSpec` retries its write for that reason). A write between the read that follows the
  answer and that moment is not announced. → Accepted: the next write, or a manual refresh, shows it.
- **On an older desktop the artifact tabs can reorder** on a re-read, because its order is newest file first: an
  agent ticking `tasks.md` moves Tasks to the first tab. → The selection is kept by id, so the shown artifact does
  not change. Accepted for a desktop being replaced.

## Migration Plan

None. Nothing is stored, and a desktop without the operations shows no way in.

## Open Questions

None.
