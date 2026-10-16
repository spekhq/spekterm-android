## 1. Protocol

- [x] 1.1 `net`: `Session.closeOffered: Boolean = false` with the lenient serializer of D1 (only the boolean `true` reads `true`); `Operations.KEEP = "sessions.keep"`; `Frames.sessionsKeep(sessionId)`. Verify: `FramesTest` — a list whose sessions carry `closeOffered` `true`, absent, `false`, `null`, `"yes"`, the string `"true"`, `1`, `[]` and `{}` decodes every session, `true` only for the boolean `true`; `sessionsKeep` encodes `{"sessionId":"s1"}`

## 2. State and requests

- [x] 2.1 `desktop`: `SessionActions.offered(session)` and `SessionActions.offerActions(session, operations)` per D1–D2. Verify: `SessionActionsTest` — offered only for a running agent marked; a dormant agent and a shell marked are not; `offerActions` gives `[CLOSE, KEEP]` with both operations, `[CLOSE]` without `sessions.keep`, `[KEEP]` without `sessions.close`, nothing when not offered
- [x] 2.2 `desktop`: `SessionAction.Kind.KEEP`, `SessionAction.fromOffer(kind, …)` → `Working` unforced (no `Confirm`); `answered` makes `Busy` only for `HIBERNATE` / `CLOSE`; `keepText` per D3. Verify: `SessionActionsTest` — an offer's close starts `Working(force = false)`; its `SESSION_BUSY` → `Busy` with the reason, then `agreed` → forced; Keep's success → `Done(KEEP)`; Keep's `SESSION_BUSY` → `Failed`; each of Keep's error texts; the menu's close still starts at `Confirm`
- [x] 2.3 `desktop`: `Desktop.keep(sessionId)` per D4; log kind `KEEP` (`error`). Verify: `DesktopTest` (instrumented, scripted desktop) — `keep` sends `sessions.keep` with only `sessionId` and returns `null` on success, the error on a refusal, `NOT_CONNECTED` when detached; a `sessions.changed` with the mark and then without it is reflected in `Desktop.sessions`; run in 4.2
- [x] 2.4 `desktop`: `SessionsView.Row.offered`, `Summary.offers` (default `0`) counted per D5 (a choice first, then an offer, then ready; each row once). Verify: `SessionsViewTest` — a group with one offered ready agent, one ready agent, one choice, one offered agent awaiting a choice and one offered busy agent → `Summary(choice = 2, ready = 1, offers = 2)`; an offered dormant session counts as dormant, not as an offer; the existing `Summary(choice = 1, ready = 2)` assertion still compiles and holds

## 3. Interface

- [x] 3.1 `ui`: `SessionDialogs.answerOffer(action, session)`; the `Working` text "Keeping “name”…"; `Failed` with Keep's texts. Verify: `./gradlew assembleDebug`; behavior in 5.1
- [x] 3.2 `ui`: the offered row per D5 (the sentence and "Close" then "Keep" under the label; "Waiting for you" not shown, "Working" and "Asks for a choice" kept; tap and long press unchanged) and the collapsed heading's "asks to close" count. Verify: `./gradlew assembleDebug`; behavior in 5.1
- [x] 3.3 `ui`: the conversation screen's offer card above the composer in the branch that shows the conversation (`Content`, `Paused`, `NotConnected`; `NoWindow` too, where the empty list shows none), buttons enabled by `actionsEnabled`; closing returns to the list, keeping stays. Verify: `./gradlew assembleDebug`; behavior in 5.1

## 4. Docs and the build

- [x] 4.1 `README.md`: "Sessions" mentions answering an agent's offer to close its session with Close or Keep, and "Closing asks first" names the exception (Close on the agent's offer); the version line names the desktop that offers it (spekterm's commit of `remote-close-offer`, filled in once it is committed; until then "spekterm with remote-close-offer"). Verify: it reads so
- [x] 4.2 `./gradlew test assembleDebug` passes, and the instrumented suites pass on the emulator (AVD `Pixel_9`) with `ANDROID_SERIAL=<emulator> ./gradlew connectedDebugAndroidTest` — never on the Pixel: first `adb disconnect` the Pixel and check that `adb devices` lists only the emulator; reconnect the Pixel afterwards. Verify: the commands' output, recorded in `docs/conversation-findings.md`'s new section

## 5. On the phone

- [ ] 5.1 Install on the Pixel with `adb install -r` (keeping the pairing) and run against a dev spekterm desktop (`npm run dev` in ~/git/spekterm, with `remote-close-offer` in its working tree, paired anew under `~/.config/spekterm-dev`), recording each outcome in `docs/conversation-findings.md` under a new section "Close offers":
  - an agent asked to close itself: when its turn ends, the row shows the sentence with "Close" and "Keep", and its conversation screen shows the card; a collapsed folder holding it says "1 asks to close";
  - "Keep" on the phone's row: the offer goes from the phone and the desktop's card goes; the session keeps running;
  - another offer, "Keep" on the conversation screen: the screen stays, the card goes from it and from the desktop;
  - another offer, "Close" from the conversation screen: no confirmation, the session leaves the desktop, the phone returns to the list;
  - another offer, "Close" from the list: no confirmation, the session leaves both;
  - another offer, "Keep" on the desktop's card: the offer goes from the phone;
  - another offer, typing into the session on the desktop: the offer goes from the phone;
  - another offer, a reply sent from the phone: the offer goes;
  - the menu's "Close session" on an offered session still asks to confirm;
  - an offer while the phone's connection is paused: shown on the row and on the conversation screen, buttons not usable; resuming makes them usable;
  - an offered session that also awaits a choice (a background subagent's prompt left open) if it can be produced: the row keeps "Asks for a choice" with the offer; else carried by 2.4;
  - `SESSION_BUSY` after an offer's Close if it can be produced (e.g. typing into the agent so it works, racing the tap); else carried by 2.2;
  - the exported connection log shows `KEEP` and `CLOSE` lines with codes and no labels.

  An older desktop (no `sessions.keep`, no mark) is carried by 1.1 and 2.1 unless the installed spekterm (without the feature) is paired too (record which). Verify: every item has an outcome or a named carrier
