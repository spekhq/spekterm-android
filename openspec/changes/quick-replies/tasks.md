## 1. Store and list

- [ ] 1.1 `QuickReplies` (pure): the sentence and list rules and `parse(json: String): List<String>?` (design D2); `QuickReplyStore` in `store/Stores.kt` (atomic JSON in `noBackupFilesDir`): `load()`, `save(list)` (trimmed), `reset()`
- [ ] 1.2 The three defaults in `res/values/strings.xml` and `res/values-zh-rTW/strings.xml` with the exact texts of design D2; `App` exposes `StateFlow<List<String>?>` (`null` = not set), resolved to the defaults in the UI
- [ ] 1.3 JVM tests for `QuickReplies`: each malformed form (not JSON, not the shape, a non-string, empty after trim, a line break, a tab, 201 units, a bare `/model`, a bare `/effort`, 13 entries, two equal after trimming) → `null`; a valid list round-trips; `[]` kept; `/model opus` accepted. Control group: accept 201 units (that case turns red)
- [ ] 1.4 Instrumented `QuickReplyStoreTest` (saving and restoring the device's own file around each test, as `ThemeStoreTest`): save → load round-trip, reset, a damaged file → `null`

## 2. Sending

- [ ] 2.1 `Reply.quick`; `Replies.quickBlocked` (design D1); `Replies.sent(..., quick = true)` appends with `commandsShown`, clears `quickRefused` and `prefillRefused`, leaves draft, attachments, attachNote, commandRefused; typed and command `sent` clear `quickRefused`; `Replies.answered` quick branch: `Refused` (any code, `PREFILL_PENDING` included) drops the reply, leaves draft and `draftReturns`, sets `quickRefused`; other outcomes as a typed reply
- [ ] 2.2 `Desktop.sendQuick(sessionId, sentence, inboxPrompt)`: `attached` checked and `Replies.quickBlocked`, `Replies.sent(quick = true)`, `conversation.send` with no attachment ids, model/effort tracked as `send` does
- [ ] 2.3 JVM tests of `Replies.quickBlocked` and the reducer: the gate refuses for not connected, paused, not watching, awaiting choice, unknown, in flight, a model switch holding, `InboxPrompt`, `CommandWaits`, a bare `/model`; allows with an empty draft and with an attachment uploading or failed in the field; after `sent(quick)` and after a refused `answered`, `draft`, `attachments`, `attachNote` and `draftReturns` are unchanged; a refused one (also `PREFILL_PENDING`) sets `quickRefused`; `sent(quick)` clears `quickRefused` and `prefillRefused`; a typed `sent` clears `quickRefused`; a quick `/simplify` carries `commandsShown`. Control groups: return the refused quick reply to the draft (the refused case turns red); let `quickBlocked` read `c.attachments` (the uploading case turns red)
- [ ] 2.4 Instrumented `DesktopTest`, beside the command tests: a quick reply is sent with no attachments, the draft "half a reply" and an attachment unchanged; a refused one leaves both and shows `quickRefused`; `/effort high` sent as a quick reply is confirmed by the reported effort

## 3. UI

- [ ] 3.1 The chip row in `Composer` (design D3): the parameters `quickReplies`, `roomForQuick`, `onQuick` passed by `ReplyArea` from the conversation screen and `WithReplyBar`; `SuggestionChip`s at most 220 dp wide above the field, one line, sideways scrolling; hidden for an empty list, in command mode, while `SlashPanel` shows, and below the height threshold; shown disabled with the prompt card; enabled by the D1 gate; a tap calls `sendQuick`; the `quickRefused` line (strings in both languages); the same in the reply bar through the shared composer
- [ ] 3.2 The editor section in `ui/StatusScreen.kt` (design D4): rows with labelled edit / up / down / remove; the edit dialog (single line, 200 units, Save disabled with the reason for an invalid or duplicate sentence); Add (not at 12); Restore defaults with a confirmation; strings in both languages
- [ ] 3.3 Compose UI tests (as `SlashSkillsTest`, `AppearanceSectionTest`): the row hidden in command mode, with `SlashPanel`, with an empty list, below the threshold; disabled with the prompt card and with an unknown state, the reason line saying spekterm cannot tell; a tap calls `onQuick` with the sentence; the `quickRefused` line shows, also with the inbox note; the editor adds, refuses a duplicate, reorders, removes, restores after confirming

## 4. Acceptance

- [ ] 4.1 `./gradlew test assembleDebug` green; `connectedDebugAndroidTest` on the emulator green
- [ ] 4.2 A recorded run against a spekterm desktop started from source (`npm run dev`): a chip sends its sentence and the reply is confirmed; with "half a reply" and an attachment in the field, both stay, also after leaving the screen and opening the reply bar from a change; chips disabled while the agent asks for a choice and while the state is unknown; the same chips in the reply bar; a refused quick reply leaves the field; the edited list survives an app restart and an unpair/pair
- [ ] 4.3 On the real phone: the row with the keyboard open in portrait and landscape (the field stays visible; adjust the threshold if needed and record it); TalkBack reads each chip's whole sentence; with an unedited list, switching the app's language to 中文（台灣） shows the Chinese defaults; screenshots light and dark in both languages
- [ ] 4.4 Record the runs and measurements in `docs/conversation-findings.md`

## 5. Docs

- [ ] 5.1 README: quick replies, that the list is kept on the phone (the desktop keeps its own), its defaults
- [ ] 5.2 Once this change's release is published: in the spekterm repository, a paragraph on the phone's quick replies in the website's remote access page (`site/src/content/docs/docs/using/remote-access.md` and its `zh-tw/` twin), `(cd site && npm ci && npm run build)` green
