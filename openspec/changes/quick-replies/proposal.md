## Why

The maintainer types the same few sentences to agents over and over, much of it from this app. Counted in the
local Claude Code transcripts (2026-10-16 to 2026-11-21, deduplicated): "can this session be closed?" about 357
times, "will this carry any risk, or is there anything I need to decide?" about 73 times, "is there anything left to
do in this session?" about 25 times. On a phone keyboard each costs more than on the desktop. A sentence sent
verbatim dozens of times a week should be one tap.

## What Changes

- **Quick replies**: a short list of sentences kept on the phone, each sent to the agent with one tap.
- **Where they show**: a row of chips just above the reply field of an agent session, on the conversation screen
  and in the reply bar of the screens opened from it. A tap sends that sentence at once — no fill, no send button.
- **Gates**: a quick reply is sent only when it could be sent as a typed reply with no attachment (connected,
  watching, the agent ready or working, no inbox prompt or shell command waiting, no model switch holding, no reply
  in flight); chips are disabled otherwise, and the composer already says why. The draft, attachments and notes in
  the field are never touched — **a refused quick reply does not come back to the field**, unlike a typed one; the
  screen says it was not sent and why.
- **How it is sent**: `conversation.send` with the sentence and no attachments, tracked as any reply (shown at once,
  unconfirmed until it appears; a kept `/model x` / `/effort x` held and confirmed as a typed one). **No protocol
  change.**
- **The list**: kept on the phone, edited in the app's Settings screen (add, edit, remove, reorder). One line each,
  at most 200 characters, at most 12, no duplicates, no bare `/model` or `/effort`. Until the user edits it, three defaults in the app's language
  (the three sentences above). An empty list hides the row.
- **Phone only.** The desktop keeps its own list (the user's decision: each device manages its own); the desktop's
  change is spekterm's `quick-replies`.

## Capabilities

### New Capabilities

- `quick-replies`: the list, where its chips show, what a tap sends and when it cannot.

### Modified Capabilities

- `conversation-reply`: a refused quick reply does not come back to the reply field ("A sent reply is unconfirmed
  until it appears in the conversation", "Say when an inbox item's first prompt waits unsent"). The reply bar holds
  the composer and the row is part of it, so "Reply from the screens opened from a conversation" is unchanged.
- `reply-attachments`: a quick reply carries no attachment and leaves the composer's ("Send attachments with a
  reply").

## Impact

- `store/Stores.kt`: a `QuickReplyStore` (an atomic JSON file, as `ThemeStore`).
- `App.kt`: the list as a `StateFlow`, as the theme.
- `desktop/Desktop.kt`, `desktop/Replies.kt`: a `sendQuick` beside `send` and `runCommand`, and the gate for it.
- `ui/ConversationScreen.kt` (composer, `ReplyArea`), `ui/ReplyBar.kt` (passes the room and the list), `ui/StatusScreen.kt`
  (the editor section), `MainActivity` / `App` (the list's flow).
- `res/values/strings.xml`, `res/values-zh-rTW/strings.xml`: labels and the defaults.
- README; JVM tests; instrumented tests in `androidTest` (`DesktopTest`, a store test, Compose UI tests); a recorded run against a desktop started from source, and a run on the real phone.
