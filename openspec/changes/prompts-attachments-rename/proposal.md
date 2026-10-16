## Why

Using the app for real showed things the phone could not do, all because spekterm's remote protocol did not
offer them yet: send a photo or file to an agent, rename, hibernate or close a session, and answer the agent when
it asks for a choice — a permission prompt, a question it asks with its question tool, or approving a plan. The last one hurts
most: an agent that stops on a prompt while the user is away from the desk stays stopped, and the phone can only
say "answer it on the desktop". Typing an answer blindly was never an option (text and Enter typed into an open
prompt approve it), so `conversation-reply` forbade answering on the phone.

spekterm now offers them (commits 29a5d0a and 9b02f53, changes `remote-prompts-attachments-rename` and
`remote-hibernate-close`, protocol still version 1): the open prompt is stated exactly in `conversation.wait`, `conversation.answer` writes the right keys
itself and refuses when the prompt is no longer the one shown, `attachments.put` uploads a file in chunks that
`conversation.send` then hands to the agent, `sessions.rename` renames quietly, and `sessions.hibernate` /
`sessions.close` hibernate or close quietly — refusing a session that is not idle, with the reason, unless the
request forces it. A watch now ends saying why: the window went away, or the session was hibernated, closed, or
its process ended. The answers to `hello` and
`pair` list the operations a desktop accepts, so the app can offer each control only where it works.

## What Changes

- **Answering prompts on the phone.** While the agent asks for a choice, the conversation shows the open
  prompt as the desktop states it — a permission request (tool and argument), the agent's questions with their
  options, or the plan to approve — as plain text. When the desktop says the prompt can be answered from a
  device, the phone offers exactly the answers the desktop accepts for it: allow once (only for the tools whose
  prompt offers a one-time yes) or deny; the options of each question, one or several as the question allows,
  or declining; approve, approve in auto mode (when there is a plan), or keep planning. Otherwise the prompt is
  shown read-only with "answer it on the desktop", as today. How an answer went is shown: nothing to say when
  it was written; a warning that sends the user to the desktop when it was only partly written or the agent
  recorded a different answer; why, when it was refused. The app never answers by typing into the terminal.
- **Attachments.** The composer can attach photos (the system photo picker) and files (PDF or text). HEIC/HEIF
  photos — not accepted by the desktop — are converted to JPEG on the phone, and a photo over the desktop's
  10 MiB limit is re-encoded smaller. Each attachment uploads in chunks as soon as it is picked, shows its
  progress, resumes after a dropped connection from where the desktop says it stopped, and can be removed
  before sending. A reply can then carry up to 10 attachments, with or without text. The desktop's limits and
  refusals are explained.
- **Confirming replies against what the desktop wrote.** A reply is confirmed by the conversation entry that
  matches the text the desktop answers it wrote (which includes the sentence naming the attachments), not the
  text typed — otherwise a reply with attachments would never be confirmed.
- **A session's menu: rename, hibernate, close.** Long-pressing a session in the list opens its menu, for any
  listed session (agent or shell, running or dormant); the conversation screen's "⋮" offers the same. A dormant
  session's menu also offers "Wake", since a shell put to sleep from the phone has no screen to wake it from. "Rename":
  clearing the name hands naming back to the agent's title; the new label appears when the desktop's list says
  so. "Hibernate" (running sessions only) is sent at once, unforced. "Close session" asks first — it cannot be
  undone. When the desktop says the session is busy (working, waiting on a choice, a message not confirmed sent,
  a command running, or it cannot tell), the phone shows why and forces only if the user agrees. Closing from the
  session's screen returns to the list.
- **A watch that ends says why.** Today every end of a conversation watch reads as "the desktop's window went
  away". The phone now follows the desktop's reason: hibernated → dormant with "Wake"; closed, or its process ended (the desktop then removes it) → no longer on
  the desktop;
  window → no window; an unknown reason → not running.
- **Offered only where the desktop supports it.** The app remembers the operations the desktop listed at the
  last authentication; rename, hibernate, wake from the menu, close, attach and answering are shown only when their operation is
  listed. An older
  desktop lists none of them, and the app behaves exactly as now.

Nothing changes on the desktop; this repository does not edit spekterm.

## Capabilities

### New Capabilities
- `prompt-answer`: showing the agent's open prompt by kind and answering it from the phone through the
  desktop — what is offered for each kind, when answering is possible, outcomes and refusals.
- `reply-attachments`: attaching photos and files to a reply — picking, converting HEIC, size limits, chunked
  upload with resume, removing, sending with a reply, and the desktop's refusals.
- `session-rename`: renaming a session from the phone, handing naming back, and errors.
- `session-hibernate-close`: hibernating and closing a session from the phone, confirming, forcing a busy one only
  after the user agrees, and errors.

### Modified Capabilities
- `conversation-reply`: "While the agent asks for a choice, show what it asks and refuse free text" no longer
  forbids answering on the phone — it shows the desktop's stated prompt and defers answering to
  `prompt-answer`; "Free text only while the agent is ready or working" lets a reply be empty text when it
  carries attachments; "A sent reply is unconfirmed until it appears in the conversation" matches against the
  text the desktop says it wrote.
- `session-list`: new requirement for a session's long-press menu (rename, hibernate, close).
- `conversation-view`: "A dormant session shows as dormant and can be woken" follows the reason the desktop gives
  when it ends a watch (hibernated, exited, closed, window), so a hibernated or closed session is no longer
  briefly shown as "no open window".
- `desktop-connection`: new requirement to keep the operations the desktop offers and show a control only when
  its operation is listed.

## Impact

- Code: `net/Frames.kt` (`operations`, `sessions.rename`, `conversation.answer`, `attachments.put`,
  `conversation.send` with attachments, new error codes), `net/Sessions.kt` (`Prompt` decoding in
  `conversation.wait`, `SendResult.text`, answer and upload results), `desktop/Conversation.kt` and
  `desktop/Replies.kt` (prompt, answer state, attachments, confirming against the written text, the watch's end
  by reason), `desktop/SessionsView.kt` (`Screen.of` closed by reason), new pure
  modules for answers and uploads, `desktop/Desktop.kt` (operations, `answer`, `rename`, `hibernate`, `close`, the upload worker,
  `send` with attachments), a new attachment preparer (copying, HEIC → JPEG, re-encoding), `ui/ConversationScreen.kt`
  (prompt card, attachment row and pickers, the session menu and its dialogs), `service/ConnectionService.kt` (passing `hello`'s
  operations), `log/ConnectionLog.kt` (`ANSWER`, `ATTACH`, `RENAME`, `HIBERNATE`, `CLOSE` kinds — codes and outcomes, never text or
  names), `App.kt` (clearing the attachment cache at start), `ui/SessionsScreen.kt` (long-press menu on a row).
- Protocol: uses `operations`, `conversation.wait`'s `prompt`, `conversation.answer`, `attachments.put`,
  `conversation.send`'s `attachments` and `r.text`, `sessions.rename`, `sessions.hibernate`, `sessions.close`,
  `SESSION_BUSY` and `conversation.ended`'s reasons (spekterm ≥ 9b02f53 for all of them, ≥ 29a5d0a for all but
  hibernate, close and the reasons; protocol version 1). No new permission: the photo picker and the document picker need none.
- Docs: `README.md`'s feature list (replies, prompts, attachments, the session menu; "answering prompts" leaves "Not yet").
