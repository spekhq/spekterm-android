## Context

The desktop (spekterm `src/main/remote-answer.ts`, `clear()`) reports `Prompt.answerable: false` unless five checks
hold: exactly one request open, nothing written to the session's terminal since the prompt's tool call started
(`pty-input.ts`: any write except a device answer's own keys and the terminal's own reports), no other tool start by
the same agent after this call's start (`stirred`: in practice parallel calls, `agent-events.ts` ~650, ~684), no
answer written yet (`answeredAt`, set just before an answer's first key), and — for a question — one to four
questions of one to eight options. The phone cannot tell which failed, so it shows one text for all of them
(`prompt_not_answerable`).

On 2026-11-14 kewang touched a single three-option question on the desktop without sending, then found the phone
locked out. The session's transcript (funliday-web-next `859fd732…`) shows no tool activity between the question
and its answer, only three cross-session messages queued, and the question fits; the typing check is what failed.
kewang decided typing should warn, not block.

The desktop side is a parallel spekterm change handed off on 2026-11-14 (handoff "桌機上動過的問題，手機仍可答：
協定加 blockedBy / despiteTyped", session `spekterm-5cd0`). The handoff fixes the wire contract below so both sides
can be built at once; the desktop session also measures where keys land after each kind of desktop input, and may
stop counting harmless keys (arrows) as typing at all — a desktop-internal choice that needs nothing from the phone.

The desktop session measured it on 2026-11-14 (Claude Code 2.1.325, one run each) and keeps every key as typing,
since even ↑↓ can rest on a text row:
- **Esc** dismissed the prompt in every state measured, text in a field included (question "Other" with text,
  several-choice, the second of two questions, Bash's Tab amend field, letters typed on Bash, a plan's "Tell Claude
  what to change" with text).
- **`1` on a permission** ran the tool after ↓↓↓, after typed letters, on Write and WebFetch; once Tab opened Bash's
  amend field, it became the amend text "Yes, 1": nothing ran and the prompt stayed open.
- **Questions**: arrows resting on an option, → / Tab on a single question — right. The cursor on "Type something" —
  the digits became text and nothing was sent. → to the second of two questions — the keys answered the wrong
  question and submitted. A several-choice question with one option already ticked on the desktop — the phone's
  choice was added to it and submitted. The last two are recorded within 3 seconds, so they come back `mismatch`.
- **Plan**: ↓↓ to "Tell Claude what to change" — the digit became feedback text, nothing approved; ↑ back, then `2`
  — approved, the leftover text not sent.

(Later measurements added the `decisive` reason, D1: a key that answered the prompt on the desktop leaves it open
while its tool runs, and keys sent then do harm.) So a confirmed answer, on a prompt touched only by moving or typing
keys, has three results: right; **no effect** (keys became text, the outcome is `sent`, the prompt
stays open and then reads `answered`); **wrong** (a question comes back `mismatch`). Nothing measured did a
different action than the one chosen, except answering the wrong question of several.

What the desktop checks after an answer (`writeAnswer`, `planKeys`): only a question's `answer` and a plan's
`approve` / `approve-auto` carry an expectation, matched against what the agent records within 3 seconds
(`confirmed` / `mismatch`). `allow` and `deny` are never checked, and `deny` closes the request on the desktop's side
(`dismiss`) whether or not Esc reached the prompt.

## Goals / Non-Goals

**Goals:**
- A prompt blocked only by desktop typing can be answered from the phone after a warning and a confirmation.
- An answer sent despite typing that the desktop could not confirm tells the user to check the desktop.
- A read-only prompt says which of the desktop's reasons applies.
- While the phone's own answer is being written or waits for the prompt to go, the card says so, not a reason.
- Nothing changes against a desktop that does not send the new fields.

**Non-Goals:**
- Undoing or correcting a wrong answer from the phone; a mismatch still sends the user to the desktop.
- Overriding the other reasons (`open`, `stirred`, `answered`, `size`).
- Telling the user exactly what was typed on the desktop: the desktop does not say.

## Decisions

### D1. The wire contract

- `Prompt.blockedBy?: "open" | "size" | "answered" | "stirred" | "decisive" | "typed"`, present only with
  `answerable: false`, the first that applies in that order. `typed` therefore means every other check passed.
- `decisive` (added by the desktop session on 2026-11-14, final once kewang reviews the desktop change): a key on the
  desktop that may have answered or dismissed the prompt — a digit, Enter, Esc, Shift+Tab, a control character,
  another escape sequence, or a paste holding one. Measured (CLI 2.1.325): a Bash prompt allowed with `1` on the
  desktop stays open until its command ends; a device's Esc then interrupts that command, and a `1` stays in the
  agent's input box to go out with the next reply. `decisive` is never overridden: the desktop refuses
  `despiteTyped` on it with `PROMPT_GONE`. `typed` is left for keys that only move or type (arrows, Tab, letters,
  punctuation, spaces), which answered or dismissed no measured prompt.
- An unknown `blockedBy` value is a hard block (the desktop's protocol document says so).
- `conversation.answer` `p.despiteTyped?: true`: keystrokes before the desktop received the call do not count for
  this answer; keystrokes during it withhold the rest (`PROMPT_GONE` before the first key, `partial` after). With
  any other reason, the flag changes nothing.
- The two ship together: a desktop that sends `blockedBy` accepts `despiteTyped`. There is no `operations` entry for
  it; the phone takes the presence of `blockedBy` as the sign. (Asked of the desktop session, and to be written into
  `remote-protocol.md` there.)

The phone decodes only those six values; any other value, or a `blockedBy` next to `answerable: true`, is taken as
absent. Rejected: a boolean `typedOnly` — the read-only reasons need the full list anyway.

### D2. Every decision may override typing

All of a prompt's decisions are offered despite typing: the measurements found no case where a key did another
action than the one chosen, except a question answered in the wrong order or added to a desktop tick — which come
back `mismatch`. The other failure is no effect, which D6 reports. Esc dismissed every measured state, so the
reviewer's worry — an Esc swallowed by a field while the desktop closes the request, the next reply then typed into
the prompt — was not seen.

Rejected: offering only the checked decisions (question `answer`, plan `approve*`) — the measurements removed its
reason, and it kept permission prompts blocked, the case kewang wanted gone.

### D3. The offer

`Offer.Answerable` gets `despiteTyped: Boolean`; `decisions` are the same as without it. `Offer.Reason.NotAnswerable`
becomes a data class carrying `blockedBy`. `Answers.offer`'s order becomes:

1. the model-switch hold, operation, paused, connected, watching, `open > 1`, the phone's `fits` — as today;
2. **our own answer to this prompt**: in flight → `Answering`; done → `Answered` — moved up, before the desktop's
   word. The desktop marks a prompt answered just before writing an answer's first key and pushes a new wait, so
   during and after the phone's own answer the prompt reads `answerable: false` with `blockedBy: "answered"`; today
   that already replaces "Answering…" with "can no longer be answered" for every answer, and an allowed tool keeps
   its prompt open for minutes;
3. `!answerable`: `blockedBy == "typed"` → `Answerable(decisions, despiteTyped = true)`; anything else →
   `NotAnswerable(blockedBy)`;
4. otherwise `Answerable(all decisions)`.

The phone's own `fits` guard still wins over `typed`.

### D4. Warning and confirmation

With `despiteTyped`, the card shows a warning line above the answers (error colour): the prompt was touched on the
desktop since it appeared, so the answer may not land as chosen, and the phone asks before sending. Each answer
button opens an `AlertDialog`: title "Touched on the desktop", text naming the three results — it may land as
chosen, have no effect, or (for questions) record another answer, and the phone says when it cannot confirm —
buttons "Send anyway" and "Cancel". Cancel keeps the choices made.

The pending confirmation is remembered per prompt (`remember(prompt.id)`) and holds the prompt id, decision and
selections captured at the tap; "Send anyway" sends exactly those. The dialog closes without sending when the
prompt id changes or the offer stops being `Answerable` with `despiteTyped`, so a confirmation never applies to a
prompt or state the user did not see.

Rejected: a single "answer anyway" switch that unlocks the buttons — one more control, and a stale unlock could
carry over to a later state of the same prompt.

For testing, `PromptCard` takes an `onAnswer(promptId, decision, selections, despiteTyped)` parameter, defaulting
to `Desktop.answer`, as `ModelEffortSheet` takes `onChoose`.

### D5. Tap guard

`PromptCard` re-arms the 600 ms guard when `(prompt.id, prompt.answerable, despiteTyped)` changes, where
`despiteTyped = (offer as? Offer.Answerable)?.despiteTyped == true`, so buttons that appear because the prompt became
answerable despite typing are not tapped by a touch meant for the read-only card.

### D6. Sending

`Desktop.answer` takes `despiteTyped`; `Frames.conversationAnswer` puts `"despiteTyped": true` only when it is
true, so a plain answer's frame is unchanged. `AnswerState` records `despiteTyped`, and the connection log's `ANSWER`
line adds it.

Outcomes: `PROMPT_GONE` is "nothing was sent" (`prompt_note_gone` already names typing on the desktop), `mismatch`
the warning to check the desktop, `confirmed` nothing more — as today. New: an answer sent despite typing whose
outcome is `sent`, for any decision but `deny`, becomes `AnswerOutcome.Unchecked`, a warning kept until dismissed:
sent from the phone, but since the prompt was touched on the desktop the phone cannot tell whether it took effect;
check the desktop. That covers the "no effect" result, which the desktop reports only as `sent`. A `deny` is left
out: Esc dismissed every measured state. A plain answer's `sent` stays `Done`.

### D7. Read-only reasons

| `blockedBy` | Shown |
|---|---|
| `open` | today's count (`prompt_open_requests`); the phone's `open > 1` check reaches it first anyway |
| `size` | today's `prompt_too_large` |
| `answered` | already answered from a device; check the desktop (never for the phone's own answer: D3 step 2) |
| `stirred` | the agent is running other tools at the same time; answer on the desktop |
| `decisive` | it may already have been answered or dismissed on the desktop; check the desktop |
| absent / unknown | today's `prompt_not_answerable` |

The composer's own text (`blockedText`) needs no change: it says "answer above" whenever the offer is `Answerable`.

## Risks / Trade-offs

- [A confirmed answer lands wrong — the wrong one of several questions, or added to a desktop tick] → the desktop's
  `mismatch` check reports it and the phone warns to check the desktop; the dialog names it up front.
- [A confirmed answer has no effect — keys typed as text into a field] → `Unchecked` tells the user to check the
  desktop; the prompt stays open there.
- [A permission allowed despite typing in a state not measured] → the measured states either ran the chosen tool or
  did nothing; `Unchecked` sends the user to the desktop either way.
- [The desktop ships `blockedBy` without `despiteTyped`] → D1 requires them together; otherwise every confirmed
  answer is `PROMPT_GONE`, "nothing was sent" — safe, but useless.
- [The desktop ships later than the phone] → without `blockedBy` the phone behaves as today except for D3 step 2,
  which only corrects the in-flight text; the end-to-end task waits for it.
