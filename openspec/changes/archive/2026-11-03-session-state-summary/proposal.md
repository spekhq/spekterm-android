## Why

The desktop's main-stage header counts the workspace's sessions by state — needs you, working, running, idle,
hibernated — and each count leads to those sessions. The phone's session list has no such summary: to find what waits
for the user, kewang scrolls through every folder and reads each row's state, and a collapsed folder only says so in its
own heading. kewang asked (2026-11-03) for a way on the phone to find the sessions that need them without that.

## What Changes

- Above the session list, a **summary row** counts the listed sessions by state, most urgent first, in the desktop
  header's order: **needs you** (asks for a choice, asks to close, or waits for input and not yet seen), **working**,
  **running** (shells, and agents whose wait state is unknown), **idle**, **dormant** (hibernated). Only states with at least one
  session are shown, each with its state's dot and its count. It stays in place while the list scrolls. Unlike the
  desktop's header, needs you also counts agents that offered to close their session (as the phone's collapsed
  headings do), so the phone's number can be higher than the desktop's.
- Tapping a state **filters** the list to the sessions in that state, still grouped by folder, every matching group
  shown even when collapsed, handoff children shown flat with where they came from. Tapping the selected state again,
  or "Show all", shows the whole list again. The folders' collapsed state is not changed by the filter. Starting a new
  session, or the list emptying, clears the filter.
- While a filter is on and its last session leaves the state (the user answered it, or it started working), the list
  says that no session is in that state any more and offers to show all, instead of switching back by itself.
- The filter stays while the user opens a session from it and comes back, so answering several waiting agents in a row
  is: tap "needs you", open one, answer, back, open the next.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `session-list`: a new requirement for the summary by state and its filter; "Show the desktop's sessions" (the
  heading's number and the handoff tree while filtered), "Collapse folder groups" (no collapsing while filtered, a
  collapsed group's matching sessions shown) and "Open a folder's OpenSpec from the session list" (the heading's tap
  while filtered) gain the filtered exception.
- `session-relations`: "Nest handed-off sessions under their source in the list" does not apply while filtered.

## Impact

- `desktop/SessionsView.kt`: a pure summary of a list's sessions by bucket, and the groups filtered to a bucket.
- `ui/SessionsScreen.kt`: the summary row under the notices, the filter state, the filtered list and its empty state;
  the menu's "Collapse all" hidden while filtered.
- Strings in `values` and `values-zh-rTW`: "Needs you" / 「等你」 is new (the desktop's word); the other states reuse
  the words the rows already show or speak (Working / 處理中, Running / 執行中 — on a row only its dot's spoken label, Idle /
  閒置, Dormant / 休眠中), so a count and the rows it filters to read alike — except an agent that offered to close while working, which is counted under
  needs you and still shows "Working", with its offer, on its row.
- `README.md`: the Sessions item mentions the summary and the filter.
- Protocol: nothing new; computed from the session list the phone already receives.
