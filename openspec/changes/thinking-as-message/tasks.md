## 1. Grouping and times

- [x] 1.1 `desktop` and `ui`: thinking ends a run (D1): drop the branch that holds thinking in `ConversationItems.group`, remove the `Thinking` part, and remove its case from the run's part list in `ConversationScreen` (so the source still compiles); update the KDoc and comments that still describe thinking inside runs (`Plain`, `held`, `Part`). Verify: `ConversationItemsTest` updated:
  - call, thinking, call, call → `C(c1) P(t) R(c2,c3)`;
  - seven calls with thinking between each → seven single calls and six thinking items, none in a run;
  - a reply anchored on thinking between calls → the anchor goes after the thinking's item;
  - a task notice between calls still joins the run;
  - a notice then thinking between two calls → `C(c1) P(n) P(t) C(c2)`;
  - the test that checks every entry is shown once still passes with thinking between calls.
- [x] 1.2 `desktop`: `ConversationTimes.timeOf` gives thinking its time (D3); its KDoc follows. Verify: `ConversationTimesTest`: the thinking case now expects the time and the test's name says which items show one; a new case, c1 23:58, thinking 00:01, c2 00:02, c3 00:03, puts the new day's heading before the thinking. `./gradlew testDebugUnitTest` passes.

## 2. Interface

- [x] 2.1 `ui`: thinking's row (D2): `AgentMarkdown` gets an optional `textColor`, also used by its plain-text fallback; `PlainRow` draws thinking as label + Markdown text in `onSurfaceVariant` + time; `Muted`'s KDoc no longer names thinking; unused imports removed. Verify: an instrumented test renders thinking between calls under a `ToolContext` whose `runCommand` records its calls, and checks that the "Thinking" label and the text are shown without a tap, that `` `remote-server.ts` `` and `` `! npm run deploy` `` are shown without backticks, and that tapping the latter records no call. Light and dark screenshots on the `Pixel_9` emulator in English and 中文（台灣）, looked at.
- [x] 2.2 zh-TW label stays 「思考中」 (D4): `conversation_thinking` back to the committed value. Verify: `git diff` shows no change to `values-zh-rTW/strings.xml`, and the zh-TW screenshots of 2.1 show 「思考中」.
- [x] 2.3 `README.md`: the conversation entry of "What it does" says thinking is shown as a quieter note with its time, rendered as Markdown. Verify: read back.
- [x] 2.4 `./gradlew test assembleDebug` passes, and `connectedDebugAndroidTest` passes on the emulator only (`ANDROID_SERIAL=emulator-5554`, the Pixel disconnected and checked with `adb devices`). Verify: the commands' output, recorded in `docs/conversation-findings.md`'s new section "Thinking as a message".
- [ ] 2.5 On the Pixel with kewang's OK, the debug build against the installed desktop: open a session whose transcript holds thinking with text (found by counting non-blank `thinking` blocks in its `.jsonl`, in `type: "assistant"` records only — the CLI also copies thinking into `api-request-blob` records, which the desktop skips), and check each one is shown open, in place between its calls, with its time. Verify: screenshot and the transcript comparison recorded in the findings section.
