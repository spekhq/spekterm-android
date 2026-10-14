## Why

The connection spike proved the ground: the phone pins the desktop's certificate, holds the connection in the
background, and is told when an agent needs the user. But a notification is all it can do — the user still has
to walk to the desktop to see what the agent said and to answer it. Reading an agent session's conversation and
replying to it with free text is the smallest step that makes the phone useful on its own, and the desktop side
(`sessions.*` and `conversation.*` in spekterm's remote protocol, version 1) already exists.

## What Changes

- **Session list** — the app's main screen once paired: the desktop's sessions grouped by folder (the global
  item included), each with its label, whether it is an agent or a shell, whether it is running or dormant, and
  for agents the wait state (waiting for you, working, asks for a choice, unknown). Kept current from the
  desktop's `sessions.changed` events; says when the desktop has no window, when the list is not current
  because the phone is not connected, and when the user paused the connection (offering Resume). The status
  screen of the spike moves behind it.
- **Conversation view** of one agent session: user messages, agent text (Markdown, loading no remote content),
  thinking, tool calls and tool results, shown whole as they arrive — no simulated streaming. It says plainly
  when the content is incomplete (catching up, older entries not loaded, unavailable) and when the agent is
  working. A dormant session shows as dormant with a Wake button (`sessions.wake`), never as an empty
  conversation. Watched only while the screen is visible; resumed from the last entry held after a reconnect.
  A shell session is listed, but opening it says shells need the terminal view, which comes later.
- **Replying with free text** while the agent is ready or working: the message shows at once as unconfirmed
  until it appears in the conversation, and the user is told when it does not appear, and when the desktop
  typed it into the agent's input but withheld Enter because a choice appeared (`filled-not-submitted`).
  While the agent asks for a choice, the screen shows what it asks (tool and argument), refuses free text, and
  says to answer on the desktop — following the desktop's own rule that a choice is never answered by guessing
  keys. An unknown wait state refuses too, with the reason.
- **Notifications lead to the conversation**: tapping an attention notification that names a session opens
  that session's conversation; and while that conversation is on screen, the phone raises no notification
  for that session (the user is already looking at it).

Not in this change: the terminal view, answering permission prompts, inbox, handoffs, OpenSpec browsing, and
creating sessions — each is a later change.

## Capabilities

### New Capabilities

- `session-list`: the list of the desktop's sessions as the app's main screen, its freshness and connection
  state, and the way to the status screen.
- `conversation-view`: watching and showing one agent session's conversation — content, incompleteness,
  dormant sessions and waking them, resuming after a reconnect, opening from a notification, and not notifying
  for the conversation on screen.
- `conversation-reply`: sending free text to the watched agent session — the wait-state gate, the unconfirmed
  state and its outcomes, and what is shown instead while the agent asks for a choice.

### Modified Capabilities

None. The spike's capabilities (`device-pairing`, `desktop-connection`, `attention-notifications`) are not yet
archived into `openspec/specs/`; where this change adds to their behavior (where a notification tap leads, not
notifying for the conversation on screen), the new requirements are written in `conversation-view`, and the
spike's own requirements stay true as written.

## Impact

- Code: a new desktop-state layer between the foreground service and the screens (session list, the watched
  conversation, requests over the service's single connection); the service routes `sessions.*` and
  `conversation.*` events to it and re-establishes them after each reconnect; new screens (session list,
  conversation) and navigation between them and the existing status and pair screens; attention notifications
  carry the session they are about.
- Dependencies: `androidx.navigation:navigation-compose`, `com.mikepenz:multiplatform-markdown-renderer-m3`,
  and for tests `androidx.compose.ui:ui-test-junit4`.
- Desktop: no change to spekterm. Testing uses spekterm run from source, as for the spike.
- Privacy: conversation content is held in memory only — never written to the phone's storage, the connection
  log, or notifications.
