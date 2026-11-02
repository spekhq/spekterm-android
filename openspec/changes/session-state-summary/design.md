## Context

`desktop/SessionsView.kt` turns the desktop's `SessionList` into `Group`s of `Row`s: each row's `RowState` (`DORMANT`,
`READY`, `IDLE`, `BUSY`, `CHOICE`, `NONE`) and `offered` (`close-offer`), its place in the handoff tree (`depth`,
`nested`), and each group's `Summary` (choice / ready / offers) for its collapsed heading. `ui/SessionsScreen.kt` shows
the notices, then one `LazyColumn` with a sticky `GroupHeading` per group and a `SessionRow` per row unless the group's
key is in `collapsed` (kept by `CollapsedStore`). Each row's state is drawn by `StateLabel` in `ui/Common.kt` with the
colors of `spekColors` (attention, working, dormant).

The desktop's summary (`spekterm` `src/shared/wait/summary.ts`, `SessionSummary.tsx`) buckets its rail states as
choice + waiting → needs you, error, working, running + unknown → running, idle, dormant → hibernated, ended, listed by
urgency; a tap jumps to the next session in that bucket. The phone has no error or ended state: a session that ended is
gone from the desktop's list.

## Goals / Non-Goals

**Goals:**

- See at a glance, on the main screen, how many sessions need the user and in which state the rest are.
- Reach every session that needs the user in one tap, wherever it is in the list, collapsed folders included.

**Non-Goals:**

- A notification, a badge on the app icon, or a count in the Inbox control. The attention notifications already tell
  about each waiting agent.
- Keeping the filter across restarts of the app, or per pairing.
- A summary on other screens (conversation, OpenSpec, inbox).

## Decisions

### D1. Buckets and their order

A pure `SessionsView.Bucket` — `NEEDS_YOU`, `WORKING`, `RUNNING`, `IDLE`, `DORMANT`, in that order — and
`bucket(row)`: `CHOICE` or `offered` or `READY` → `NEEDS_YOU`; `BUSY` → `WORKING`; `NONE` → `RUNNING`; `IDLE` →
`IDLE`; `DORMANT` → `DORMANT`. `offered` is only ever true for a running agent, so a working agent that offered to
close counts as needing the user, as the collapsed heading counts it among "ask to close" (`Group.summary.offers`).
That keeps one rule for "needs the user" on the screen: what the needs-you count says equals the sum a fully
collapsed list's headings say. It departs from the desktop, whose needs-you bucket is choice and waiting only
(`session-badge.tsx` `rowStateOf` ignores close offers), so the phone's number can be higher; the proposal says so.
Such a row still shows "Working" with its offer card under it, so in the needs-you filter it reads as "working, and
asks to close", which is what it is.

`summary(groups)` returns `(Bucket, count)` for the buckets with at least one row, in order, counted over every
group's rows whether collapsed or not. `chips(summary, filter)` is what the row draws: the summary, plus the selected
bucket with 0 in its place in the order when it has no row left — pure, so the order is tested on the JVM.

The order follows the desktop's urgency (needs you, working, running, idle, dormant). The desktop's error and ended
buckets have no phone state and are left out.

### D2. Tap filters, not jumps

The desktop's controls jump to the next session in a bucket, which fits a rail that is always on screen beside the
terminal. On the phone the list *is* the screen: a jump scrolls a long list under the user's thumb, expanding a
collapsed folder (or not reaching it), and the user has to tap again to find out whether there is another. A filter
shows them all at once, in their folders, and keeps showing them while the user goes into a session and back — the
"answer every waiting agent in a row" loop.

Alternative: jump as the desktop does. Rejected for the reasons above. Alternative: a separate "Needs you" screen.
Rejected: it would duplicate the list's rows, menus and close-offer answers in a second place.

`SessionsView.filtered(groups, bucket)`: each group's rows kept when `bucket(row) == bucket`, in the order `groups()`
gives them — the handoff tree's depth-first order, which is how the user saw them a moment ago, not the desktop's raw
order — with `depth = 0` and `nested = false` (no tree: a child's source may be filtered out, and `Relations.line`
already says where a not-nested session came from), groups with no row left out. Its `Group.summary` is recomputed from the kept rows, which is harmless:
a filtered heading is never drawn as collapsed (D3).

### D3. The screen while filtered

`SessionsScreen` keeps `filter: Bucket?` in `rememberSaveable` (saved as the enum's name), so it survives opening a
session and coming back, and the activity being recreated. While it is set:

- the list draws `filtered(groups, filter)`; every heading is drawn expanded and is not clickable (no chevron, no
  collapse semantics); the OpenSpec control in a heading stays;
- `collapsed` is left as it is and is not written;
- the menu leaves out "Collapse all" / "Expand all";
- a "Show all" line under the summary clears the filter while the filtered list has rows; when it has none, a
  centered message names the state ("No session needs you", "No session is working", …) with the "Show all" action
  instead, so there is one "Show all" on screen;
- choosing a chip scrolls the list to its top (`listState.scrollToItem(0)`), which also expands the new-session button.

The filter is cleared by `picking` a folder in the new-session sheet (before `Desktop.create`), so the session that
opens by itself is in the list on the way back, and by a `LaunchedEffect` when the list has no session to show
(`list.sessions` empty or `list.window == false`): the summary is drawn only while `groups` is not empty, so it never
shows for those lists (also not for the frame before the effect runs), and an invisible filter would otherwise greet
the next session with "No session needs you". A list that is merely not current keeps the filter.

The summary row is drawn outside the `LazyColumn`, below the notices and the creation notice, so it does not scroll.
When the selected bucket has no session left it is still drawn, with 0, so the selection the user made stays visible
(the spec's "Nothing left").

`MainActivity` declares `configChanges` for orientation, screen size, UI mode and density, so rotating does not recreate
the activity; switching the app's language in the system's per-app language settings (`app-language`) does, and is how 3.1 checks the saved filter.

### D4. The summary row

Not Material 3 `FilterChip`s: with their 8 dp + 18 dp icon + 8 dp + 16 dp padding, five of them with words come to
~550 dp, more than the Pixel 9 emulator's ~411 dp. Instead compact pills, like the desktop header's buttons: a
`Row` of `Surface`s (`shape = CircleShape`, 32 dp tall inside a 48 dp touch target, 10 dp side padding, 6 dp apart,
12 dp from the screen's edges), each holding the bucket's dot (the `StatusDot` its rows' `StateLabel` draws: attention
for needs you, working filled for working and running, working hollow for idle, dormant hollow), its count in tabular
figures and the state word in `labelMedium`. The selected pill is filled with `secondaryContainer` and outlined;
the others have no fill. Needs you's word and count are `SemiBold` in the attention color when its count is not
zero, as "Waiting for you" is on a row; dormant's word and count are in `outline`, below working and idle, as
`app-appearance` ("State is not told by color alone") ranks them.

Fit: the pills sit in a `FlowRow` (6 dp apart both ways) and wrap onto a second line when they do not fit. The
desktop's header drops words when short of room (`SessionSummary.tsx` D9), but on the phone that would leave working,
running, idle and dormant told apart by dot color or order alone, which `app-appearance` ("State is not told by color
alone") forbids; and a horizontally scrolling row would hide the last states with no sign they are there. By estimate
(not measured) five English pills come to ~470 dp and the Chinese ones to ~400 dp on a ~411 dp screen, so English
wraps when four or five states show; the second line costs ~38 dp. Each pill's content description is "Needs you: 3"
(`sessions_bucket_count`), with selected semantics (`selected = …`). Running and working share a dot, as on the
rows; their words tell them apart.

"Working" stays 「處理中」 in Chinese, the word the rows already use (`state_busy`), though the desktop's summary
says 「工作中」: a pill and the rows it filters to should read alike; aligning the row word with the desktop is a
separate question.

Words: `sessions_bucket_needs_you` "Needs you" / 「等你」 is new; the others reuse `state_busy`, `state_running`,
`state_idle`, `state_dormant` so a chip and the rows it filters to read the same. The empty messages are five new
strings (`sessions_filter_empty_*`), "Show all" is `sessions_filter_show_all`, the content description is
`sessions_bucket_count` (kept apart from the collapsed heading's `sessions_summary_*` plurals).

## Risks / Trade-offs

- [A filter left on hides sessions the user forgot about] → the selected chip is drawn selected and "Show all" sits
  right under it; the filter is not kept across restarts of the app. `rememberSaveable` does bring it back when Android
  recreates the activity or restores the process it reclaimed, which with the foreground service is rare and shows the
  same screen the user left.
- [Opening a waiting session only to read it takes it out of the needs-you filter] → intended: reading on the phone
  marks it seen (`Read on the phone`), and it then shows idle; the desktop does the same. The spec's "Only reading one"
  says so, and 3.1 sees it.
- [The row takes ~48 dp of a phone's height] → only while there are sessions; the list's sticky headings remain the
  rest of the screen's structure. Looked at on the emulator (task 3.1) next to a long list.
- [The width estimate is not measured] → 3.1 screenshots all five states in both languages; a second line in Chinese
  on a ~411 dp screen is the designed fallback, not a failure.
- [Rows moving out of the filtered list while the user reaches for one] → the list follows the desktop as it does
  today; a row leaves only when its state changes, which is what the user asked to see.

## Migration Plan

None: nothing stored, nothing on the wire.
