## Context

The OpenSpec screens (`openspec-browse` D6) are pushed over the session list, from a folder heading's OpenSpec icon
or the list menu's "OpenSpec" (`openspec-browse` D8). Both are gated by `OpenSpecGates` on `openspec.changes` and
the folder's `hasOpenSpec`. The session's screen (`ConversationScreen`) has a top bar with the session's label, its
wait state and the session menu (`SessionActions`). Each `Session` carries its `folderId`; the folders, with
`hasOpenSpec`, come with the same `sessions.list`.

## Goals / Non-Goals

**Goals:**

- One tap from a session's screen to its folder's OpenSpec, gated exactly as the folder heading's control.

**Non-Goals:**

- Opening the change the session's agent works on. Neither the session list nor the conversation says which
  change that is, so the phone would have to guess.
- Any change to the OpenSpec screens, the session list or the protocol.

## Decisions

### D1. An icon in the top bar, not a menu item

The control is an `IconButton` with `SpekIcons.File`, the heading's icon, placed before the ⋮ button, labeled
"OpenSpec for <folder name>". It is visible on the screen itself, so it takes one tap.

*Alternative:* an "OpenSpec" item in the session menu. That takes two taps, and the same menu is shown on a
long press in the list, where the folder heading already offers OpenSpec. Rejected.

### D2. The gate: `OpenSpecGates.folder(session, folders)`

A new pure function takes a `Session?`, which is `null` once the session has left the list. It returns the session's `Folder` when `browse` is set, the session has a `folderId`, the folder
is in `folders` and it has `hasOpenSpec`, otherwise `null`. The screen calls it with the session it already looks
up and `sessions.list?.folders`, the last list the phone holds, current or not. The heading reads that same list,
so both controls appear and disappear together. A test checks this against `heading(group)` for every session
of a list (D2 holds only as long as `SessionsView.groups()` puts each session in its folder's group). The returned
folder's name labels the button. Since the icon is not a session action, it sits outside the
`if (actions.isNotEmpty())` that guards the ⋮ button.

It ignores the session's kind, its running state and the connection, as the heading does (`openspec-browse` D8):
the OpenSpec screens show what is cached and say they are not connected.

### D3. Navigation: push `OpenSpecFolderRoute` over the conversation

`ConversationScreen` gets `onOpenSpecFolder: (String) -> Unit`. `MainActivity` wires it to
`nav.navigate(OpenSpecFolderRoute(folderId))`. That is the route the heading opens, and back pops to the
conversation. No back-stack deduplication is needed: no OpenSpec screen leads to a session, so the stack
cannot loop.

Watches need no change. Pushing a screen stops the conversation entry, so its `ON_STOP` unfocuses the
conversation watch, and back restarts it from the last entry it holds (`conversation-view`, "Watch only while on
screen"). The OpenSpec screens focus and unfocus their own target, and the folder watch lingers for
`openspecLingerMs` after the last one is left. A notification tap still navigates with `popUpTo<SessionsRoute>()`,
which also drops OpenSpec screens stacked over a conversation.

## Risks / Trade-offs

- [The top bar gets crowded on a long label] → the title already ellipsizes, and the bar shows two icons at most
  (OpenSpec, ⋮).
- [While an OpenSpec screen is over the conversation, no session is visible, so the session's waiting notification
  can be shown. Tapping it opens the conversation and drops the OpenSpec screens.] → This is what pressing Home
  does today. It is intended and needs no change.
- [Tapping the icon twice quickly stacks two folder screens] → the heading does the same today
  (`nav.navigate` without `launchSingleTop`). Left as it is, so the two ways in stay alike.
- [A folder with an empty name gives the label "OpenSpec for "] → the heading has the same label. Left as it is.
