## Context

The conversation screen (`ui/ConversationScreen.kt`) ends with its reply area: the answer note, the prompt card
(`prompt-answer`), the close offer (`close-offer`), the inbox note (`inbox` D7) and `Composer` — the field, the
attach menu, the skills panel (`skill-commands`), the switch line (`model-effort-switch`) and every blocked reason
(`Replies.blocked`). Everything `Composer` reads lives in `Desktop`, keyed by session: the held `Conversation`
(`Desktop.conversation`, one session at a time), its draft (`setDraft`, kept across leaving the screen), its
attachments (`Attach.add(context, sessionId, …)`), its replies and its pending switch. `Composer` itself holds only the
`TextFieldValue` and the picker launchers.

A session is watched while it holds the focus: `Desktop.focus(owner, sessionId)` on `ON_START`, `Desktop.unfocus(owner)`
on `ON_STOP` and dispose, where `owner` is the back-stack entry id. `unfocus` is ignored unless that owner holds the
focus. Focusing the session already held keeps the conversation and its watch (`focus`: only "another session" drops
what is held); `reconcile()` unwatches as soon as no focus names the held session. The focused session is also
`Desktop.visibleSession`, which the attention notifier treats as on screen (`AttentionPolicy.onLive`, `onAnswer`).

The OpenSpec screens are reached from the session list (folder list, a group heading) and from the conversation's
top bar (`session-detail-openspec`), and open one another (folder → change / spec, spec → change, change → archived
change). The handoff screen is reached from the session list and from the conversation's menu, and opens other
sessions' conversations. Navigation is type-safe Compose routes in `ui/MainActivity.kt`.

## Goals / Non-Goals

**Goals:**

- Reply, attach and answer a choice from the screens opened from a conversation, with nothing different from doing it
  on the conversation screen: same draft, same attachments, same blocks, same sends.
- Know without leaving the screen whether the agent is ready, working or asking, and how the last reply went.
- Return to the conversation in one tap.

**Non-Goals:**

- A bar on screens not opened from a conversation (session list, OpenSpec reached from the list, inbox, status,
  about). Without a conversation below there is no session the user is replying to; choosing one is a separate
  question.
- Showing the conversation's entries, the usage line, the background tasks, the close offer or the model-and-effort
  sheet in the bar.
- Quoting or inserting text from the screen into the reply.

## Decisions

### D1. The session comes from the route

`OpenSpecFolderRoute`, `OpenSpecChangeRoute`, `OpenSpecSpecRoute` and `HandoffRoute` gain `replyTo: String? = null`.
The conversation screen's top-bar OpenSpec button and its handoff menu item set it to its session; each OpenSpec
screen passes its own `replyTo` on to the OpenSpec screens it opens (including the archived change that replaces a
vanished one). The handoff screen's links to other sessions open their conversations as today, which carry their own
session. The session list and the folders list leave it `null`.

Alternative: find the nearest `ConversationRoute` on the back stack at composition. Rejected: the back stack is not
observable from a screen without passing the controller around, and a notification tap that replaces the stack would
change the target under a typed draft.

`openHandoff` today returns to a `HandoffRoute` already on the back stack instead of stacking one. It now scans the
back stack for that session's handoff entry: one with the same `replyTo` is returned to; when there is none, or only
one with another `replyTo`, the handoff is opened on top with the `replyTo` asked for, so a handoff opened from a
conversation always carries the bar and going back from it returns to that conversation. Popping the other entry
instead would also take away the conversation above it. `HandoffScreen` takes the entry id as `owner`, as the OpenSpec screens do.

### D2. One reply area, two hosts

The reply area of the conversation screen — answer note, prompt card, inbox note, `Composer` — moves into one
composable, `ReplyArea(sessionId, onOpenInboxItem, …)`, which the conversation screen and the bar both call; it computes
the prompt offer, `answerAbove` and the inbox hold from `Desktop`'s flows. The inbox note's "Review" opens the inbox at
the item from every host (`nav.openInbox`). The close offer stays on the conversation screen only: answering it can
close the session the user is reading about, and it is an offer, not a reply. The model-and-effort sheet stays there
too: the bar shows the switch line and hold the composer already shows.

"Reply anyway" (an older desktop's hold on replies lifted by the user, `inbox` D7) moves from the conversation
screen's `remember` into `Desktop`, per session, holding the `InboxView.Linked` kind and item key it was lifted for —
the same keys the `remember` had — in memory only. Lifting it in the conversation lifts it in the bar and the other
way round, and the model-and-effort sheet reads it from there as before.

`Composer`'s `field` is `remember(sessionId)` and starts from `c.draft`, and every edit goes to `Desktop.setDraft`; a
covered conversation screen is not composed, so the draft written on one screen is what the other starts from — no
new state. Attachments are per session in `Desktop` and so already shared.

### D3. The bar watches its session, and counts as looking at it

The bar's host focuses `replyTo` with its own back-stack entry id on `ON_START` and unfocuses on `ON_STOP` and dispose,
as the conversation screen does, and like it clears the session's notification on start. A screen with no bar (no
`replyTo`, or a shell session) focuses nothing; whether the session is a shell is part of the effect's keys, as on the
conversation screen.

`Desktop` holds one focus today, and `unfocus` of its owner drops it. With two screens on the same session that is not
enough: a predictive back gesture started on a bar screen starts the conversation below (its `ON_START` focuses it),
and a cancelled gesture stops it again (its `ON_STOP` unfocuses it) while the bar screen only pauses and resumes — the
bar would be left unwatched. So `Desktop` keeps the focus owners in the order they focused: `focus` appends (or moves)
the owner to the end, `unfocus` removes it. The held conversation and `visibleSession` change to another session only
when every owner left names that session; while owners name different sessions — the moment of a transition between
two sessions' screens — what is held stays. With no owner left, nothing is focused and `reconcile()` unwatches.
Checked against the moves: a push from A's screen to B's ([oA, oB]) switches to B once oA leaves; a pop back
([oB, oA]) switches to A once oB leaves; a back gesture cancelled on B's screen ([oB, oA], then oA leaves) changes
nothing, so B keeps its watch and its composer's attachments, which switching the held conversation would drop. Two
owners on the same session keep the watch whichever leaves first. The cost is a switch one transition later than today.

Measured on the emulator (task 3.2), the assumed order does not hold: the leaving entry stops before the entering
one starts, so every move between the conversation and a bar screen logged an `unwatch` and a `watch` about 0.1 s
apart. So the last owner leaving does not end the focus at once: it lingers for `focusLingerMs` (1 s), and a focus
arriving meanwhile takes it over with nothing unwatched. Leaving to the session list or another app unwatches 1 s
later than before.
`clear()` and `resetForTest()` empty the list along with `visibleSession`.

The OpenSpec screens keep their own folder watch (`openspec.watch`, owner = entry id) unchanged; the two are
independent.

A watched session is one the user is looking at, on the desktop's word (`remote-protocol.md`, "Looking is … a device
watching its conversation"): a turn that ends while a bar is on screen is marked seen, the session reads "Idle" in
the bar, the list and on the desktop, and no notification is raised for it. kewang chose this (2026-10-26) over
showing "waiting" in the bar alone or keeping an unread mark until the conversation is opened, to be revisited if it
misleads in use.

### D4. The header line

One line above the reply area: the session's label (or the fallback title), its state label as the conversation's top
bar shows it (`SessionsView.screenState` → `StateLabel`: working, waiting for you, idle, asking), and the reply the
user is waiting on, by the label the conversation shows for it (`Reply.label`), in the error color when settled. The
reply shown is the newest one in `c.replies` that is not settled; when there is none, the newest settled one while no
entry came after the point it was sent at (its `anchor` is still the last key of `ConversationReducer.keys(c.entries)`,
or both are empty) — so an old failure does not stay in the header for the rest of the session. A settled reply whose
anchor is no longer among the keys (the desktop replaced the conversation) is not shown. An entry the conversation
groups out of sight still counts as coming after, so the label can go without a visible change; accepted. The labels change
with time (`NOT_YET_CONFIRMED` → `NOT_IN_CONVERSATION_YET` after 30 s), so the bar runs the same one-second clock as
the conversation's entries while it shows a reply. The label → text mapping of `ReplyRow` becomes one function both
use. The whole line is one button, "Open the conversation", that pops back to that session's `ConversationRoute` when
it is on the back stack, and navigates to it otherwise. A trailing chevron marks it as a link.

### D5. What the bar shows

A new pure `ReplyBarView.of(session, screen, c, now)`, with `screen` from the existing `Screen.of(connected, paused,
sessions, c, sessionId)`; a host without `replyTo` shows no bar and does not call it:

| Case                                                   | Bar                                                             |
| ------------------------------------------------------ | --------------------------------------------------------------- |
| a shell session (checked first, whatever the `Screen`) | none                                                            |
| `Closed`                                               | header saying the session was closed; no reply area             |
| `Dormant`                                              | header saying it is asleep; tapping opens the conversation to wake it |
| `Content`, `Paused`, `NotConnected`, `NoWindow`        | header + reply area (the composer blocks itself and says why)   |

It also gives the header's reply (D4).

### D6. Space

The bar sits below the screen's content in a `Column` (content `weight(1f)`), inside `SpekScaffold`, whose
`safeDrawing` insets already include the keyboard: the content shrinks while typing, as the conversation's entries
do. The skills panel keeps its `panelMax` (a quarter of the content's height, at most 240 dp). The OpenSpec change
screen's tabs and the top bar stay in place.

## Risks / Trade-offs

- [A turn that ends while a spec is on screen reads as looked at, though its text was not shown] → kewang's choice
  (D3); the header shows the state change while the bar is up.
- [Predictive back is assumed on by default for this app (target SDK 36, Android 16)] → D3's owner list does not
  depend on it; 3.2 cancels a back gesture on a bar screen and checks the watch.
- [Less room for the artifact text] → the bar is the conversation's own size (one field line plus the header); the
  user asked for it.
- [The handoff screen of session X opened from X's conversation replies to X, while its content is about X's
  relations] → named in the header line.
