## Why

Thinking with text is uncommon: Claude Code records most thinking blocks with an empty text and only a signature,
and the desktop drops those (spekterm `src/main/transcript-follow.ts`). Over all 872 local transcripts of 2026-10 and
2026-11, 2,299 of 48,797 thinking blocks (4.7%) have text, counting assistant records only; a busy session has 30 to
43 of them. Those are short progress notes written for the user (at most 431 characters, no line breaks, 94% in
Chinese; e.g. "dev 的切換已完成，… 接著要刪除那 8 個檔"),
yet the phone folds them into a collapsed run of tool calls behind a collapsed "Thinking" line, two taps away. kewang
asked on 2026-11-08 to show them like the conversation's other messages; on the phone they are what tells the user
what the agent is about to do.

## What Changes

- **Thinking is no longer part of a run.** A thinking entry ends a run of tool calls, as the agent's text does; the
  calls before and after it form separate items.
- **Thinking is shown open, like the agent's text.** No collapsed line and no chevron: a small "Thinking" label, then
  the text, laid out as the agent's text is (full width, no bubble) in a quieter colour.
- **Thinking is rendered as Markdown**, under the same no-remote-content rules as the agent's text, because these
  notes use code spans (`remote-server.ts`, `RELAY_DISABLED`). A `! command` code span in thinking does not go into
  the command field.
- **Thinking shows its recorded time** under it, as the agent's text does.
- The labels stay "Thinking" and 「思考中」, the desktop's words (design D4).
- No protocol or desktop change. The desktop still sends only thinking with text.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `conversation-view`: "Show the conversation of an agent session" (thinking no longer joins runs and is not
  collapsed), "Agent text loads no remote content" (thinking is rendered as Markdown under those rules), and "Show
  when messages were recorded, and which day" (thinking shows its time).
- `shell-commands`: "Put a command into the field from the conversation" lists thinking among the texts whose
  `! command` code stays as it is.

## Impact

- `desktop/ConversationItems.kt`: thinking ends a run; the `Thinking` part goes away.
- `desktop/ConversationTimes.kt`: thinking has a time.
- `ui/ConversationScreen.kt`: thinking's row; the run's expanded list no longer renders thinking.
- `ui/AgentMarkdown.kt`: an optional text colour.
- `README.md`: what the conversation shows.
- Tests: `ConversationItemsTest`, `ConversationTimesTest`, an instrumented check of the row.
