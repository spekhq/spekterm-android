## Context

`ConversationItems.group` holds thinking that follows a call and, when another call comes, makes it a `Thinking`
part of the run (`ui-polish` D7; `tool-call-display` D8 treats task notices the same way). Thinking outside a run is
a `Plain` item, rendered by `PlainRow` as a collapsed `Muted` "Thinking" line with italic plain text; inside an
expanded run, the same `Muted` line is drawn by the run's part list. `ConversationTimes.timeOf` gives a time only to
the user's own messages and the agent's text.

The desktop sends a `thinking` event only when its text is not blank (spekterm `transcript-follow.ts`), so every
thinking entry the phone gets has text. See proposal.md for why they should be shown.

A prototype (2026-11-08, emulator screenshot sent to kewang) showed thinking as its own open item between two runs;
kewang approved that layout over a bubble.

## Goals / Non-Goals

**Goals:**

- Thinking reads as part of the conversation, between the calls it came between, without a tap.

**Non-Goals:**

- The desktop's own conversation view, which keeps thinking collapsed. It shows the conversation next to the
  terminal; the phone is where the user reads it on its own.
- Task notices: they still join runs (`tool-call-display` D8).
- Empty thinking: the phone never receives it.

## Decisions

### D1. Thinking ends a run, as the agent's text does

`group` drops the branch that holds thinking while a run forms, so a thinking entry falls to the default branch:
`close()` the forming item, then emit `Plain(thinking)`. The `Thinking` part type and the run's rendering of it go
away; `held` keeps only `Notice`s. A reply anchored on thinking now goes after the thinking's own item, which is
what `close()` plus the anchor rule already do for any `Plain`.

*Alternative:* keep thinking inside the run but show the latest one on the run's collapsed line. That still hides
all but one note, and a finished run would show a note about work that came after it. Rejected.

### D2. Laid out as the agent's text, quieter, with a label

`PlainRow` draws thinking with the agent text's padding and full width: a `labelSmall` "Thinking" label in
`onSurfaceVariant`, the text below it, then the time. The text uses `AgentMarkdown` with the agent text's typography
and `onSurfaceVariant` as its text colour; `AgentMarkdown` gets an optional `textColor` parameter passed to
`markdownColor(text = …)`. No `onCommand` is passed, so a `! command` code span in thinking stays inert. No
italics: the colour and the label set it apart, and long italic CJK text is hard to read.

*Alternative:* a bubble on the agent's side. In this app a bubble means a message between parties (the user's own,
a peer's, a message the agent sent, `tool-call-display` D11); the agent's own text has none. Rejected with kewang.

### D3. Thinking has a time

`timeOf` includes `Entry.Thinking`. Day headings can move: inside a run, thinking and the calls after it did not
count for headings, since a run counts by its first call (`startOf`). Now the thinking item and the item after it
count by their own times, so a day that starts within what was one run is headed where it starts. For c1 23:58,
thinking 00:01, c2 00:02, c3 00:03, the "Today" heading moves from after the whole run to before the thinking. That
is more accurate, and what the requirement already says for items with a recorded time.

### D4. The zh-TW label stays 「思考中」

`app-language` ("Use the desktop's words") requires the desktop's zh-TW term for what both show, and the desktop
labels thinking 「思考中」 (spekterm `zh-TW.json` `conversation.thinking`). The prototype's 「思考」 is reverted;
kewang chose this on 2026-11-08 over changing both apps or adding an exception to `app-language`.

## Risks / Trade-offs

- [A session with many notes splits its tool calls into many short runs] → in the busiest transcript, 437 calls
  become 44 items, about one per 10 calls, each with a note saying what comes next. Most sessions have far fewer;
  a run of one call is shown as a single call, as today.
- [If Claude Code starts recording thinking text again, long reasoning would fill the screen] → the
  thinking would be long English reasoning, not notes. Then a length cut-off or a return to collapsing would be a
  new change; nothing here prevents it.
