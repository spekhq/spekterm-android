## 1. Protocol and rules

- [x] 1.1 `net`: `Prompt` decodes `blockedBy` (D1) — the six known values only; an unknown value, or one next to `answerable: true`, is `null`. Verify: `PromptTest` cases for each value on a permission, a question and a plan; missing → `null`; `"later"` → `null`; `blockedBy: "typed"` with `answerable: true` → `null`.
- [x] 1.2 `net`: `Frames.conversationAnswer` takes `despiteTyped` and puts `"despiteTyped": true` only when true (D6). Verify: `FramesTest`: with it the frame has the field; without it the frame equals today's.
- [x] 1.3 `desktop`: `Answers.offer` in D3's order, `Offer.Answerable.despiteTyped`, `Offer.Reason.NotAnswerable(blockedBy)`. Verify: `AnswersTest`:
  - `typed` on a permission, a question and a plan → `Answerable(despiteTyped = true)` with the same decisions as a plain offer;
  - `typed` while paused / not connected / not watching / without the operation / during a holding switch → today's offer;
  - `typed` on a question the phone's `fits` refuses → `TooLarge`;
  - `answered` (and `typed`) with our own answer in flight → `Answering`; with ours done → `Answered`;
  - `answered` with no answer of ours, or ours to another prompt → `NotAnswerable("answered")`;
  - `stirred`, `decisive`, missing → `NotAnswerable` carrying it;
  - `answerable: true` → all decisions, `despiteTyped = false`.
- [x] 1.4 `desktop`: `AnswerState.despiteTyped` and `AnswerOutcome.Unchecked` (D6): an answer sent despite typing whose outcome is `sent`, any decision but `deny`, is `Unchecked`, a kept warning; `confirmed`, a plain `sent` and a `deny`'s `sent` stay `Done`. Verify: `AnswersTest` cases for each of those. `Desktop.answer` takes `despiteTyped`, records it, passes it to the frame, and logs it on the `ANSWER` line. Verify: `DesktopTest` (instrumented, run in 2.4): `anAnswerNamesThePromptAndRecordsItsOutcome` asserts no `despiteTyped` key; a new case answers with it and asserts `sent["despiteTyped"] == true`. `./gradlew testDebugUnitTest` passes.

## 2. Interface, docs and runs

- [x] 2.1 `ui`: `PromptCard` (D4, D5, D7) — the warning line; the confirmation dialog on every offered answer (Send anyway / Cancel), remembered per prompt id with the prompt id, decision and selections captured at the tap, closed without sending when the prompt id changes or the offer stops being `despiteTyped`; the `onAnswer` parameter defaulting to `Desktop.answer`; the tap guard keyed on `despiteTyped`; the read-only texts for `answered`, `stirred` and `decisive`; `answerNote` / `answerTone` for `Unchecked`; strings in `values` and `values-zh-rTW`. Verify: a new instrumented `PromptCardTest` on the emulator, recording `onAnswer` calls:
  - a `despiteTyped` question shows the warning and both buttons; tapping send opens the dialog and records nothing; Cancel records nothing and the choice stays selected; Send anyway records one call with the tapped prompt id, the selections and `despiteTyped = true`;
  - a `despiteTyped` permission's "allow once" and a plan's "keep planning" open the dialog too;
  - with the dialog open, replacing the prompt (new id) closes it and records nothing; turning the offer into a plain `Answerable` closes it too;
  - right after the offer turns `despiteTyped`, a tap within 600 ms records nothing;
  - `stirred`, `decisive` and `answered` show their texts and no answer buttons.
- [x] 2.2 Screenshots: light and dark on the `Pixel_9` emulator, in English and 中文（台灣）, of the warning card, the dialog, the `stirred` card and the `Unchecked` note, from an uncommitted screenshot test as in `thinking-as-message` 2.1 (`docs/conversation-findings.md` ~line 1340), looked at.
- [x] 2.3 `README.md` "Prompts": a prompt touched on the desktop can still be answered after a warning and a confirmation, an answer the desktop could not confirm says to check the desktop, and a prompt that cannot be answered says why. Verify: read back.
- [x] 2.4 `./gradlew test assembleDebug` passes, and `connectedDebugAndroidTest` passes on the emulator only (`ANDROID_SERIAL=emulator-5554`, the Pixel disconnected and checked with `adb devices`). Verify: the commands' output, recorded in `docs/conversation-findings.md`'s new section "Answering despite typing (`answer-despite-typed`)".
- [ ] 2.5 Once the spekterm desktop change sends `blockedBy` and takes `despiteTyped` (from source with `npm run dev`, or its installed build), on the Pixel with kewang's OK:
  - an agent asks a single question; press ↓ once on the desktop (resting on an option); the phone shows the warning; pick an option, send, confirm; the agent records it;
  - an agent asks a single question; move the desktop's cursor to "Type something"; send from the phone and confirm; nothing takes effect and the phone shows the `Unchecked` warning;
  - an agent asks to run a Bash command; press ↓ on the desktop; allow once from the phone and confirm; the command runs;
  - an agent asks to run `sleep 20`; allow it with `1` on the desktop; while it runs the phone shows the `decisive` reason read-only;
  - an ordinary answer from the phone reads "Answering…" then "Answered", never "already answered";
  - ask the agent to call Bash `sleep 30` and AskUserQuestion in one message (parallel calls): the question shows the "other tools at the same time" reason read-only.
  Verify: screenshots, the connection log's `ANSWER` line with `despiteTyped`, and the transcript's recorded answer, in the findings section.
