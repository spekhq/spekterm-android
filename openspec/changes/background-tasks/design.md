## Context

Built on `scan-connect-zh` (archived). `Desktop` holds the session list (`Desktop.sessions`), replaced whole by every
`sessions.list` answer and `sessions.changed` event. `Session` is decoded with kotlinx serialization
(`ignoreUnknownKeys`), element by element, so one malformed session is skipped, never the list — but a known field of
the wrong type drops that session, which is why `closeOffered`, `seen` and `lineage` have lenient serializers
(`OnlyTrue`, `OnlyBoolean`, `LenientLineage`). The row (`SessionsScreen.SessionRow`) shows the label and, under it, up
to two secondary lines (`Relations.line`, `Relations.childrenText`), with the state label at the end; the conversation
screen (`ConversationScreen`) shows connection notices, `ContentNotices`, the entries, then the prompt card, the close
offer card and the composer. Texts live in `values/strings.xml` and `values-zh-rTW/strings.xml`, with plurals.
Agent text that is not markdown is shown with plain `Text`, which makes no links.

Facts about the desktop (spekterm, change `remote-background-tasks`, implemented in its working tree, not yet
committed — its `design.md` D2–D5 and `docs/remote-protocol.md` §5 `sessions.list` / §9 obligation 15; the desktop
session's message of 2026-10-18):

- `background?: [{ id: string, kind: "agent" | "shell" | "monitor" | "other", label: string | null }]`, in the agent's
  order. Present only on an agent session (`target: "claude"`) whose process is live (`running: true`) with at least
  one task in flight; absent otherwise, never `[]`. `label` is always present, `null` when there is no text.
- The CLI's `subagent` (and `local_agent`) → `"agent"`; `shell` → `"shell"`; `monitor` → `"monitor"`; anything else →
  `"other"`. **The `Monitor` tool's tasks are reported as `"shell"`** (with a description); `"monitor"` has been seen
  only for MCP artifact subscriptions. "In flight" is any status but completed / failed / killed.
- The list is **the agent's last report**, not the moment: the agent's `Stop` or a `SubagentStop` replaces it whole;
  a task started inside a turn shows when that turn or a subagent ends; a task that ends with no later report stays
  until the next report or the process ends. `/clear`, compaction and resume do not change it; a process end
  (exit, hibernation, close) clears it. Every change is sent at once in `sessions.changed`.
- `label`: the task's description, else a shell's command; control characters and whitespace runs collapsed, trimmed,
  clipped to 200 UTF-16 code units. Written by the agent or its CLI, so it can hold absolute paths and URLs. New client
  obligation 15: show it as plain text, never as a link.
- Protocol version 1, no new operation; older clients ignore the field (obligation 7).

## Goals / Non-Goals

**Goals:**

- A user glancing at the list sees that a session whose turn ended still has work running, and of what kind.
- On the session's screen, the user can see what that work is, to judge whether to wait for it.
- The phone holds nothing of its own: it shows the latest list.

**Non-Goals:**

- Changing the wait state shown, the collapsed heading's counts, notifications or the attention policy. A session with
  background work but no turn is not "working" on the desktop either, and the counts are about what waits for the user.
- Stopping a task from the phone, or showing its output or progress (the protocol carries neither).
- Telling a `Monitor` tool's wait from another shell: the desktop sends both as `"shell"`.
- Making the list fresher than the agent's last report.

## Decisions

### D1. `Session.background`, decoded leniently

`Session` gains `background: List<BackgroundTask> = emptyList()`, with `BackgroundTask(id: String, kind: Kind,
label: String?)` and `enum Kind { AGENT, SHELL, MONITOR, OTHER }`. A serializer (`LenientBackground`) reads the JSON
element:

- not an array → empty;
- each element: kept when it is an object whose `id` is a non-empty string; `kind` is mapped from the strings
  `"agent"`, `"shell"`, `"monitor"`, and any other value — another string, absent, not a string — is `OTHER`; `label` is
  the string if it is one with something other than whitespace, else `null` (the desktop never sends `""`, but a blank
  label would show as a kind followed by nothing); an entry that is not kept is skipped, the others stay;
- duplicate ids are kept as sent (the desktop drops them; the phone does not need ids to be unique, they only key list
  items, see D4).

Same reason as `closeOffered` (close-offer D1): obligation 7 says an unexpected value of a known field is not an
error, and the plain decoder would drop the whole session. Encoding (tests only) writes the protocol shape.

Alternative: decoding `kind` as a string and mapping in the view. Rejected — every reader would need the mapping;
an enum makes "unknown is other" one place.

### D2. Which sessions show it

`SessionsView.background(session): List<BackgroundTask>` = `session.background` when `session.running &&
session.isAgent`, else empty. The desktop promises the field only there; the phone checks again so a dormant row
never says something runs (the desktop clears the list on a process end, but the check costs nothing).

`SessionsView.backgroundCounts(tasks): List<Pair<Kind, Int>>` — one entry per kind present, in the order AGENT,
SHELL, MONITOR, OTHER, never a zero count. A fixed order rather than the agent's: the row is read at a glance, and
the same session should not reorder its line between lists.

### D3. The row's line

When `backgroundCounts` is not empty, `SessionRow` adds one more secondary line after the handoff lines, in the same
style (`bodySmall`, `onSurfaceVariant`, one line, ellipsis): "Background: 1 subagent, 2 shells" /
「背景：1 個 subagent、2 個 shell」. The text is built by a pure `SessionsView.backgroundLine(counts): UiText?` —
`Res(sessions_background, JoinedRes(parts, sessions_background_separator))`, each part a `Plural`
(`sessions_background_agent`, `_shell`, `_monitor`, `_other`), the separator a string resource (`", "` / `"、"`) —
so its order, plurals and separator are tested on the JVM, as `Relations` texts are. `UiText.Joined` takes a plain
`String` separator ("which holds no words"), and a pure function has no `Context` to read a resource, so `UiText` gains
`JoinedRes(parts, @StringRes separator)`, resolved like `Joined` with the separator read from resources. The state label, the close offer and the long press are unchanged.

Kind words: subagent, shell, monitor, and "other" / 「其他」. "subagent", not "agent": on a row that already is an
agent session, "1 agent" reads as the session's own agent; the CLI calls them subagents. zh-TW keeps subagent, shell
and monitor in English, as the app keeps "session" and its vocabulary note keeps "task" (`values-zh-rTW/strings.xml`);
the desktop shows no such list, so there is no desktop term to follow. 「其他」 rather than 「其他 task」: on the
desktop "Task" names an OpenSpec task, a different thing.

Alternative: a chip or icon next to the state label. Rejected — the end of the row already holds the state, and a
second badge there competes with it; a count needs words anyway.

### D4. The conversation screen's item

When `SessionsView.background(session)` is not empty, `ConversationScreen` shows a `BackgroundTasks` item at the top of
the content — after the connection notices and `ContentNotices`, before the entries — in the branch that shows the
conversation (`Content`, `Paused`, `NotConnected`, `NoWindow`), from the last list held, as the close offer is: so it
is there while paused or down, and in `NoWindow` it can show briefly from the list held until the desktop's empty list
arrives. It is not shown for `Closed`, `Shell` or `Dormant`.

Collapsed, it is one line: an icon, "3 running in the background" / 「背景執行中：3 個」 (a plural), and a chevron; the
whole line toggles through the screen's existing `Modifier.toggles(open, onToggle)` (`common_expanded` /
`common_collapsed` as its state description), as a run of tool calls does.
Expanded, it adds one line per task in the desktop's order — the kind word, then the label in `bodySmall` plain
`Text`, up to four lines with ellipsis (a 200-unit label is about four lines on a phone, and a command's useful part
is often at its end); a task without a label shows its kind word alone — and a closing note "As the agent last
reported." / 「這是 agent 最後一次回報的狀態。」. The tasks and the note sit in a region with `verticalScroll` and `heightIn(max = …)`, the bound a
quarter of the content area's **actual** height, read from a `BoxWithConstraints` around the content `Column`. The
content is a `Column` whose `Entries` takes the remaining weight and whose other children are measured first, in
order, so an unbounded item before the prompt card, the close offer and the composer would push them out of view (the
desktop puts no cap on the number of tasks). The content area shrinks when the keyboard opens (`adjustResize`,
`SpekScaffold`'s `safeDrawing` insets), and so does the bound: the screen's height from `LocalConfiguration` would
not, and a third of it could leave the composer no height with the keyboard open. A quarter leaves, on a phone with
the keyboard open (about 500 dp of content), about 125 dp for the tasks and the rest for the cards, the composer and
the entries.

Rejected: giving the region `weight(1f, fill = false)` beside `Entries`' weight. A `Column` splits weighted space
before measuring, and space a `fill = false` child leaves unused is not handed to the others, so a short list would
leave a gap between the entries and the composer. Items are keyed by
position, not id (D1).

Expanded or not is a `rememberSaveable` held in `ConversationScreen`, not in the item, so it survives the item leaving
composition when the work ends and returning; it starts collapsed and does not change when a new list arrives.

At the top rather than by the composer: it is about the session, not about answering it, and the bottom already holds
the prompt and offer cards. Rejected: the first item of the entries' `LazyColumn` — it would scroll away with the
conversation and interfere with following the newest entry.

The note matters because the list can be out of date in a way the phone cannot see (a task whose end the agent has not
reported). The spec's "says that the list is what the agent last reported" is this line.

### D5. Labels are plain text, never logged

The label goes into a Compose `Text` with a plain `String`: no `AnnotatedString` links, no `AgentMarkdown`, no
`autoLink`. The connection log already logs no session data of this kind; nothing is added to it.

## Risks / Trade-offs

- **A stale entry** when the agent reports nothing after a task ends (an interrupted turn) → the screen's note says
  it is the agent's last report; the next report or the process end clears it.
- **A task hidden until a report**: one started inside a turn shows at the turn's end → the turn itself shows as
  working.
- **"shell" for a `Monitor` wait** reads oddly to a user who started a Monitor → the label is the Monitor's
  description, which says what it waits for.
- **The desktop's change is not yet committed**: the shape could still change before it is. → The unit tests pin the
  shape in its design; the phone run (tasks 4) is against the desktop as implemented, and a change of shape is fixed
  here before archive.

## Migration Plan

None: an additive, optional field. Rollback is removing the row's line and the screen's item.

## Open Questions

None.
