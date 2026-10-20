## Why

From the phone the user cannot tell which model a session runs, how full its context is, what it has cost, or how
close the account is to its rate limits — what they glance at on the desktop's status bar before deciding whether to
keep a session going, `/compact` it or leave it for later. Nor can they see which skills the agent can invoke: they
must remember each name and type it exactly, and a mistyped name stays "not yet confirmed" forever, because the agent
CLI answers an unknown command locally and it never reaches the conversation.

spekterm 0.2.15 (`remote-phone-context`, commit `6b6ebfa`) sends both to a watching device, as two new events of
`conversation.watch`: `conversation.status` (model, effort, context, cost, the account's rate limits) and
`conversation.skills` (the skills the agent was told it can invoke). It also normalizes a reply starting with `/` so it
runs as a slash command, and `r.text` says what was written. The app ignores both events today.

## What Changes

- The conversation screen shows, while it watches the session, one line under the top bar with what the agent last
  reported: model and effort, the context used (with the window's size), the session's cost, and a rate limit only
  when it is close to full. Tapping it shows every value, the rate limits with their reset times. Values the desktop
  does not send are not shown; with nothing reported, or with a desktop older than 0.2.15, there is no line.
- The composer gets a skills button, opening a searchable list of the session's skills, each with its description as
  plain text. Choosing one puts `/name ` at the start of the reply field, keeping what was typed as its arguments,
  for the user to finish and send as a reply. While the desktop does not know the list yet (no first message, or
  just after `/clear`), the list says so instead of showing none. With a desktop older than 0.2.15 there is no
  button.
- A slash-command reply that does not appear in the conversation is still marked as not in the conversation, and the
  mark also says that a command the agent's CLI does not know never appears — on a desktop of 0.2.15 or later, which
  shows a command that ran as the line typed. A slash command longer than the agent's CLI runs as one (800
  characters) is flagged in the composer before it is sent: it will arrive as a plain message.
- Nothing for the desktop's new "sent from the phone" note to the agent: it is outside the message, and `r.text`,
  the conversation's entries and the reply matching are unchanged.

## Capabilities

### New Capabilities

- `agent-usage`: the model and usage the watched session's agent last reported — what is shown, when, and what is
  left out.
- `skill-commands`: the session's skills listed on the phone, inserted into a reply as a slash command, and how a
  slash-command reply is marked.

### Modified Capabilities

(none — `conversation-reply`'s rules for a reply still hold; `skill-commands` only adds which mark a slash-command
reply gets and the long-command note.)

## Impact

- `net/Sessions.kt`: decoders for `conversation.status` and `conversation.skills`, field by field (obligation 7).
- `service/EventRoute.kt`: the two kinds routed to `Desktop`.
- `desktop/Conversation.kt`, `desktop/Desktop.kt`: the conversation holds the latest status and skills of the
  current watch, cleared whenever the watch is (re)requested, left or lost, like the wait state.
- `desktop`: new pure helpers — the usage line's parts and formatting inputs, the skill filter and insertion, the
  slash-command checks — and `Replies`/`Reply.label` gains the slash-command mark.
- `ui/ConversationScreen.kt`: the usage line and its detail dialog; the skills button and sheet in the composer (the
  reply field moves to `TextFieldValue` so the cursor lands after the inserted name); the long-command note; the reply
  row's new mark. `ui/Icons.kt`: a skills icon. Strings in `values` and `values-zh-rTW`.
- Protocol: version 1, events only, nothing in `operations`; a desktop before 0.2.15 sends neither event and the
  screen looks as before.
- `README.md`: "What it does", the list of what needs which desktop, and the testing section's release.
- Builds on `conversation-times` (archived in `4778d0e`; `ConversationScreen.kt` last changed in `68c2059`, day
  headings as sticky headers). This change has no delta on `conversation-view`.
