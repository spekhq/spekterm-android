## Why

An agent on the desktop often keeps working after its turn ends: a background shell (a dev server, a test run, the
`Monitor` tool's wait), a background subagent, or an MCP subscription. The CLI's own screen lists them, but the phone
shows only the session's wait state, so a session whose turn ended reads "Waiting for you" or "Idle" while it is still
busy, and the user is led to think nothing is happening. spekterm now sends this work to paired devices (change
`remote-background-tasks` in spekterm, protocol still version 1, additive): an optional
`background: [{ id, kind: "agent" | "shell" | "monitor" | "other", label: string | null }]` on each running agent
session in `sessions.list` / `sessions.changed`, as of the agent's last report, and a client obligation to show labels
as plain text, never as links.

## What Changes

- **The work is read from the session list.** A running agent session's `background` is decoded leniently: a field
  that is not an array is none, an entry that is not an object with a non-empty string `id` is skipped, an unknown or
  missing `kind` reads as other, a `label` that is not a string with something other than whitespace is none. The phone keeps nothing of its own: what it
  shows follows the latest list, and a dormant session or a shell shows none even if the field were sent.
- **The session list shows it.** A row with background work gets one more line under its label, counting the work by
  kind in a fixed order ("Background: 1 subagent, 2 shells"). The wait state stays as it is: a session that is idle with
  a shell running shows both.
- **The conversation screen shows it.** Above the conversation, a collapsed bar says how much runs in the background;
  expanding it lists each task — its kind and its label as plain text (no link, no markdown), or the kind alone when
  there is no label — in a region of bounded height that scrolls, so the conversation, the prompt card and the
  composer always keep their place, with a note that this is what the agent last reported. It is shown also while the connection is
  paused or down (from the last list held), as the close offer is.
- **New texts in both languages** (`app-language`), counts with plural forms.
- **No notification, no log entry.** Background work raises nothing; labels are never logged.

## Capabilities

### New Capabilities

- `background-tasks`: showing, on the session list and on a session's conversation screen, the background work a
  running agent session has as of its agent's last report.

### Modified Capabilities

- `session-list`: a running agent session's row also says how much background work it has, by kind.

## Impact

- `net`: `Session.background` (a lenient serializer), `BackgroundTask` with `Kind`.
- `desktop`: `SessionsView` — which tasks a session shows and the per-kind counts.
- `ui`: `UiText.JoinedRes` (a separator from resources), the row's line in `SessionsScreen`, the bar in `ConversationScreen`, strings in `values` and `values-zh-rTW`.
- Tests: `FramesTest`, `SessionsViewTest`, a Compose test of the screen's item (emulator); screenshots on the emulator; a phone run recorded in
  `docs/conversation-findings.md` against a dev spekterm desktop with `remote-background-tasks`.
- Depends on spekterm's `remote-background-tasks` being implemented for the phone run; the code and its unit tests do
  not. No new dependency; nothing changes with a desktop without the field.
