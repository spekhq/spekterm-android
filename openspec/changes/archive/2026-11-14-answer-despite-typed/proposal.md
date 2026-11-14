## Why

A key pressed on the desktop while the agent's prompt is open — even an arrow key, even with nothing sent — makes
the desktop report the prompt as not answerable from a device, and the phone then shows only "This choice can no
longer be answered from the phone. Answer it on the desktop." On 2026-11-14 kewang hit this on a single question
with three options after touching it on the desktop and walking away: the phone gave no reason and no way on.
kewang decided that this case should warn, not block: the user cannot know why the phone stopped working, and
being sent back to the desktop defeats the phone.

The other reasons the desktop refuses a device stay hard: more than one request open, the agent doing something
else since the prompt appeared, an answer already written, or a form too large to answer with digits. A wrong
answer there cannot be taken back once the keys are written.

## What Changes

- The desktop (spekterm, a parallel change handed off on 2026-11-14) adds two fields to protocol version 1:
  - `Prompt.blockedBy`: why `answerable` is false — `open`, `size`, `answered`, `stirred`, `decisive` or `typed`,
    the first that applies in that order; so `typed` means it is the only reason, and the desktop was touched only
    by keys that move or type. `decisive` — a key that may have answered or dismissed the prompt, such as a digit,
    Enter or Esc — is never overridden (added 2026-11-14, final once kewang reviews the desktop change).
  - `conversation.answer`'s `despiteTyped: true`: the keys typed before the call do not count for this answer;
    keys typed while it is being written still withhold the rest, as today. Both ship together.
- **A prompt blocked only because it was typed into on the desktop can be answered from the phone.** It shows its
  answers with a warning that it was touched on the desktop and the answer may not land as chosen; tapping one asks
  once more, naming what can happen (it lands, it has no effect, or a question records another answer), and
  confirming sends it with `despiteTyped`. The desktop's measurements of 2026-11-14 back this: no measured key did
  another action than the one chosen, and Esc dismissed every state.
- **An answer sent despite typing that the desktop could not confirm says so**: sent, but whether it took effect is
  unknown; check the desktop. A wrong question answer is reported as today's mismatch.
- **A prompt that cannot be answered says why**: another request is open, the agent is running other tools at the
  same time, it was already answered from a device, a key on the desktop may already have answered or dismissed it,
  or it is too large.
- **The phone's own answer reads "Answering…" and then "Answered" until the prompt goes**, instead of today's "can no
  longer be answered from the phone", which the desktop's own mark of the answer currently causes. A desktop that does not send `blockedBy` gets today's text.
- How the answer went is reported as today: an answer the agent recorded differently is a warning to check the
  desktop; a refused one says nothing was sent.
- A desktop without the new fields behaves exactly as today.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `prompt-answer`: "Answer only what the desktop says can be answered" — a prompt the desktop blocks only for
  typing is offered with a warning and a confirmation, a read-only prompt says the desktop's reason, and the phone's
  own answer is not shown as a reason. "Say how an answer went" — an answer sent despite typing that the desktop
  could not confirm is a warning to check the desktop.

## Impact

- `net/Sessions.kt` (`Prompt` decodes `blockedBy`), `net/Frames.kt` (`conversationAnswer` sends `despiteTyped`).
- `desktop/Answers.kt` (`Offer.Answerable` carries `despiteTyped`; `Offer.Reason.NotAnswerable` carries the
  reason; `AnswerState` records `despiteTyped`; `AnswerOutcome.Unchecked`), `desktop/Desktop.kt` (`answer` passes the flag and logs it).
- `ui/PromptCard.kt` (warning, confirmation dialog, reason texts), `values/strings.xml`,
  `values-zh-rTW/strings.xml`.
- Tests: `PromptTest`, `FramesTest`, `AnswersTest`; `DesktopTest` (instrumented); a new instrumented `PromptCardTest`.
- `README.md` "Prompts"; `docs/conversation-findings.md` gets this change's section.
- Depends on the spekterm desktop change for the end-to-end run; until it ships, only the in-flight text changes,
  because no desktop sends `blockedBy`.
