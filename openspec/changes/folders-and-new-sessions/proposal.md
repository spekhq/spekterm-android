## Why

With many sessions open on the desktop, the phone's session list is one long scroll in which the folder (repo)
headings are small labels that are easy to miss, so it is hard to tell which repo a session belongs to or to
find the one wanted. And to start new work the user still has to walk to the desktop: the phone can read and
answer sessions but cannot start one, although the desktop already offers that to paired devices
(`sessions.create` in spekterm's remote protocol).

## What Changes

- Folder groups in the session list get a heading that stands apart from the rows — folder name, branch,
  number of sessions — and stays at the top of the screen while its sessions scroll under it.
- Each group, the global one included, can be collapsed and expanded by tapping its heading. A collapsed
  heading still says how many of its sessions wait for the user or ask for a choice, so collapsing never hides
  what needs attention. What is collapsed is remembered on the phone across restarts, per pairing.
- A "⋮" menu on the session list with "collapse all / expand all" and "Settings". The "Connection" button
  leaves the top bar: the connection status screen, which already holds the app's settings (pause, notifications,
  battery, log export, pairing again, unpairing), is reached as "Settings" and titled so.
- A "New session" action on the session list: the user picks a folder (every folder of the desktop, including
  ones that have no session now, and the global item), the phone asks the desktop to create an agent session
  there — quietly, without changing the desktop's selection or keyboard focus — and opens its conversation once the desktop
  lists it. When the desktop cannot do it, the phone says why.
- A conversation the desktop has delivered with no entries yet (a new session) says there are no messages
  yet, instead of showing a blank screen.

Shell sessions are not created from the phone (the app has no terminal view). Nothing changes on the desktop.

## Capabilities

### New Capabilities
- `session-create`: starting a new agent session on the desktop from the phone — choosing the folder, the
  request, what is shown while it is created, the errors, and opening the new session.

### Modified Capabilities
- `session-list`: "The session list is the paired app's main screen" reaches the status screen as settings from
  a menu; "Show the desktop's sessions" changes how groups are shown (headings that stand apart and
  stay on screen, branch and count); new requirement for collapsing groups and remembering it.
- `conversation-view`: "Say when the content is incomplete" gains the case of a conversation with no entries
  yet.

## Impact

- Code: `ui/SessionsScreen.kt` (headings, collapse, new-session sheet), `desktop/SessionsView.kt` (group
  summaries, folder choices, whether starting is possible), a new create tracker and `desktop/Desktop.kt` (a
  `create` call that waits for the list), `net/Frames.kt` (`sessions.create` params), `store/Stores.kt`
  (collapsed folders, per pairing), `ui/MainActivity.kt` and `service/ConnectionService.kt` (navigation after
  create; clearing the new store on pairing / unpairing / revocation), `ui/Common.kt` (a notice with two
  actions), `ui/StatusScreen.kt` (titled Settings), `ui/ConversationScreen.kt` (empty-conversation text),
  `log/ConnectionLog.kt` (a `CREATE` kind).
- Protocol: uses `sessions.create` (protocol version 1, already served by spekterm 0.2.5); no new frames.
- Docs: `README.md`'s feature list.
