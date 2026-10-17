## Why

A folder's OpenSpec can be opened only from the session list: the OpenSpec control in a folder group's heading, or
"OpenSpec" in the list's menu. While reading a session — usually an agent working on a change in that folder — the
user has to go back to the list, find the session's folder and tap its heading to see the change's tasks or
design, then come back. The session's own screen is where the user is when they want it.

## What Changes

- **The session's screen gets an OpenSpec control.** The top bar of a session's screen shows the same OpenSpec
  control as a folder heading, before the session menu, when the session belongs to a folder that the desktop
  marks as having OpenSpec and the desktop accepts `openspec.changes`. Tapping it opens that folder's OpenSpec
  (its changes and specs), pushed over the session's screen; back returns to the session.
- It is labeled for accessibility with the folder's name, as the heading's control is.
- It is not shown for a global session, for a session whose folder the phone does not know or that has no
  OpenSpec, or while the desktop does not accept `openspec.changes`.
- Nothing changes in the OpenSpec screens themselves, in the session list's entry points, or on the wire.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `openspec-browse`: a new way in, from a session's screen. It sits in the feature's own capability, as renaming
  and hibernating do (`session-rename`, `session-hibernate-close`). `conversation-view` covers agent conversations
  only, and the control is also shown for shell sessions.

## Impact

- `desktop/OpenSpecGates.kt`: which folder, if any, a session's screen offers.
- `README.md`: the OpenSpec entry names its ways in.
- `ui/ConversationScreen.kt`: the control in the top bar; a new `onOpenSpecFolder` callback.
- `ui/MainActivity.kt`: the conversation route navigates to `OpenSpecFolderRoute`.
- No protocol, storage or desktop change.
