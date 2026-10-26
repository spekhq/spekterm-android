## Why

The reply field lives only on the conversation screen. While reading a change's artifacts, a folder's change list, a
main spec or a session's handoff — screens the user usually opens *from* a conversation to check what the agent is
doing — answering the agent means going back to the conversation, typing, and finding the place again. kewang asked
(2026-10-26, from the phone, over a change's proposal) to type and attach files at the bottom of those screens too.

## What Changes

- The OpenSpec screens (a folder's lists, a change, a main spec) and the handoff screen, when opened from a
  conversation — directly or through one another — carry a **reply bar** at the bottom for that conversation's
  session. Opened from the session list, they carry none, as today.
- The bar is the conversation's own reply area: the same composer (text, photo and file attachments, the skills
  offered from a typed slash, every reason sending is blocked, the model-switch line), the same draft and attachments
  (shared with the conversation screen, both ways), the prompt card while the agent asks for a choice, and the inbox
  note while an inbox item's first prompt waits.
- Above the composer, one header line names the session and its state (working, waiting, idle, asking) and the reply
  not yet in the conversation that the user waits on, with its state (sending, not yet confirmed, not submitted…). Tapping it returns to the
  conversation.
- For a session that is closed or dormant, the bar is only that header line saying so; tapping it returns to the
  conversation, where a dormant session can be woken. A shell session gets no bar.
- A screen carrying a bar watches its session as the conversation screen does, so the bar knows when the agent is
  working, waiting or asking; leaving every such screen stops watching, as leaving the conversation does today. As
  on the conversation, the user counts as looking at the session: a turn that ends meanwhile reads "Idle", and no
  notification is raised for it (kewang's choice, 2026-10-26; revisited if it misleads in use).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `conversation-reply`: replying from the screens opened from a conversation — which screens, what the bar holds,
  the header line, closed and dormant sessions.
- `conversation-view`: a session is watched while its conversation, or a screen carrying its reply bar, is visible.
- `attention-notifications`: no notification for a session while a screen carrying its reply bar is visible, as
  while its conversation is.

## Impact

- `ui/MainActivity.kt`: `OpenSpecFolderRoute`, `OpenSpecChangeRoute`, `OpenSpecSpecRoute` and `HandoffRoute` take an
  optional `replyTo` session id, set by the conversation screen and passed on between those screens; a way back to the
  conversation below on the back stack.
- `ui/ConversationScreen.kt`: the reply area below the entries (answer note, prompt card, inbox note, composer)
  becomes one composable shared with the bar; `Composer` unchanged in behavior.
- `desktop/Desktop.kt`: the focus becomes an ordered list of owners; "reply anyway" is kept per session.
- A new `ui/ReplyBar.kt`: the header line, the closed / dormant line, the watch while visible.
- `ui/OpenSpecScreens.kt`, `ui/HandoffScreen.kt`: the bar below their content.
- A new pure `desktop/ReplyBarView.kt`: what the bar shows for a session, tested on the JVM.
- Strings in `values` and `values-zh-rTW`. `README.md`: what the conversation offers, and what counts as looking at a session.
- Protocol: nothing new; the same `conversation.watch` / `conversation.send` / upload operations.
