## 1. Theme and window

- [x] 1.1 `ui/theme`: `Color.kt` (both full color schemes per D1's table, every slot passed, and `SpekColors`), `Theme.kt` (`SpektermTheme(dark)`, `LocalSpekColors`, typography, shapes). `MainActivity` uses it in place of the bare `MaterialTheme`. Verify: `ThemeTest` (JVM) — no slot of either scheme equals the baseline scheme's value for that slot unless it is a neutral; `./gradlew assembleDebug`
- [x] 1.2 `res`: `values/colors.xml`, `values-night/colors.xml`, `values/themes.xml`, `values-night/themes.xml` (`Theme.Spekterm` per D2: window background, transparent bars, light/dark bar icons; no `values-v31`); the manifest's `android:theme`; the launcher icon background `#0f1216`. Verify: emulator cold start in light and in dark mode — the starting window and the splash show the mode's background, the status bar icons contrast with it from the first frame (screenshots taken during launch); the splash icon is legible in light mode, else D2's icon background color is added
- [x] 1.3 `MainActivity`: the root `Surface` without `safeDrawingPadding`; `enableEdgeToEdge(SystemBarStyle.auto(…) { dark })` re-applied from `LaunchedEffect(dark)` per D2. Verify: emulator screenshots of the session list in both modes (status icons dark on light, light on dark); scrolled up in a conversation with a typed draft, switching the mode keeps the scroll position and the draft (spec `app-appearance`)

## 2. Shared pieces

- [x] 2.1 `ui/Icons.kt`: the icons listed in D3, with the Apache-2.0 note. Verify: `./gradlew assembleDebug`; each icon is checked where it is used in the screenshots of 7.2
- [x] 2.2 `Common.kt`: `TopBar` over `TopAppBar` per D4 (back icon labeled "Back", pinned scroll behavior); `Notice(text, tone, …)` with `Tone` and no default per D5; `StatusDot` and `StateLabel` per D6, using `SpekColors`. Verify: `./gradlew assembleDebug`
- [x] 2.3 Every `Notice` call site and outcome gets the tone of D5's table. Verify: the build fails on a call without a tone (no default); each row of D5's table checked against the code

## 3. Session list

- [x] 3.1 `SessionsScreen`: a `Scaffold` per D2/D4 (overflow icon labeled "More options"); the "New session" extended FAB per D6 — its icon labeled "New session" while collapsed, always shown, dimmed, ignoring taps and marked disabled while `!canCreate`, collapsing on scroll; bottom padding for the list. Verify: `./gradlew assembleDebug`; screenshots in 7.2, one with the connection paused (dimmed FAB, the notice says why); `uiautomator dump` after scrolling shows the collapsed FAB's label
- [x] 3.2 `SessionsScreen`: the row per D6 (kind icon labeled Agent/Shell, label, handoff line, trailing state by `StateLabel`, close offer below with Close then Keep), no row dividers; the group heading per D6 (chevron icon, monospace branch, count pill, one hairline, summary in `SpekColors`); the empty and loading states per D6 (progress only while connecting or catching up). Verify: `./gradlew test` (`SessionsViewTest` unchanged and passing); screenshots in 7.2
- [x] 3.3 The new-session sheet per D6 (folder icon per choice, monospace branch). Verify: screenshot in 7.2

## 4. Conversation

- [x] 4.1 `desktop/ConversationItems.kt`: `group(entries, keys, anchors)` per D7. Verify: `ConversationItemsTest` (JVM):
  - a call followed by its result → one `Call` with that result;
  - two parallel calls whose results follow both → each paired with its own;
  - a result with no call, one with an empty id, and one before its call → `LoneResult`; two calls with the same id → results paired greedily in call order;
  - seven calls with thinking between them → one `Run` of seven, `failed` counting error results, `latest` the seventh;
  - a single call between two texts → `Call`, no run;
  - user, injected and text entries end a run, and are `Entry` items in order;
  - a run's key stays the same when a call is appended;
  - anchors, each case of D7's worked-cases table as its own test, with the exact `Shown` list and the item carrying the anchor; plus an anchor on a plain text entry (carried by that `Entry`) and on a consumed result with nothing being formed (carried by the last emitted item);
  - a `LoneResult` ends a run; thinking after a run's last call becomes an `Entry` after the run
- [x] 4.2 `ConversationScreen`: build the list from `group` (settled replies after the item carrying their anchor, unanchored first, pending last); render per D7 (call line with mark, `LoneResult`, run summary with parts inside the run's item and expansion remembered by key, thinking, user bubble with "You" semantics and no visible label, `ReplyRow` in the bubble family with an attach icon instead of "📎", agent Markdown with theme colors and code-block background); "Older entries are not shown." kept as a pinned Info notice; the working item; the jump-to-newest small FAB labeled "Jump to newest"; following keyed on the last entry key, last reply id and working flag, the count taken from the built list, scrolling with `scrollToItem(count - 1, Int.MAX_VALUE)`; whole-line expanding with a state description; `imePadding()` removed in favor of the `Scaffold` insets (D2). Verify: `./gradlew test` (`RepliesTest` unchanged and passing); screenshots in 7.2 of a conversation with a run collapsed and expanded, with the keyboard open, of a reply that was sent while the agent worked, and of an expanded run taller than the screen at the end while a permission prompt is open (the newest call visible)
- [x] 4.3 `ConversationScreen`: the top bar's subtitle as a state dot plus the wait text; the Dormant screen (bedtime icon) and the Closed/Shell messages (info icon) centered; the close-offer card per D8. Verify: screenshots in 7.2
- [x] 4.4 Composer and attachments per D8. Verify: `./gradlew test` (`UploadsTest` unchanged and passing); screenshots in 7.2 with an attachment added
- [x] 4.5 `PromptCard` per D9 (border, header icon, decisions ranked filled / tonal / outlined, the plan dialog's top bar). Verify: `./gradlew test` (`AnswersTest` unchanged and passing); screenshot in 7.2 of a permission prompt and, if one can be produced, a question

## 5. Settings, pairing and dialogs

- [x] 5.1 `StatusScreen` per D10 (top bar, connection card with status dot and the address list collapsing above three behind "Show all", notices inside the card, notification and battery rows, recent events rows, pairing section). Verify: screenshots in 7.2, collapsed and after "Show all" (every address in the order tried)
- [x] 5.2 `UnpairDialog` per D10 and spec `device-pairing`; "Unpair" in settings opens it. Verify: `UnpairDialogTest` (instrumented, Compose test rule, on the emulator in 7.1) — Cancel does not call `onUnpair` and closes the dialog, Unpair calls it once; on the emulator, Cancel keeps "Connected via …"
- [x] 5.3 `PairScreen` per D11 (a `Scaffold` always, top bar only while re-pairing, centered layout, steps, scan as main action, "or" divider, link field with the paste icon labeled "Paste link", progress bar, notices, "Back to settings"). Verify: screenshots in 7.2 of the first-pairing screen and of a refused link
- [x] 5.4 `SessionMenu.kt` dialogs per D12. Verify: screenshots in 7.2 of the rename dialog and of a hibernate's working dialog

## 6. Docs

- [x] 6.1 `README.md`: "What it does" says unpairing asks first, and that a conversation summarizes runs of tool calls; the session menu is described without the `"⋮"` glyph ("the session's menu"). Verify: it reads so

## 7. Build and checks

- [x] 7.1 `./gradlew test assembleDebug` passes; the instrumented suites (with `UnpairDialogTest`) pass on the emulator with `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`, never on the Pixel: first `adb disconnect` the Pixel and check that `adb devices` lists only the emulator, reconnect it afterwards; tell the session `spekterm-android-ad92` first if it still shares the emulator. The run unpairs the emulator; it is paired again for 7.2. Verify: the commands' output, recorded in `docs/conversation-findings.md`'s new section "Appearance"
- [x] 7.2 Emulator screenshots (AVD `Pixel_9`, paired with a spekterm desktop) in light and dark mode of every screen and state in design "Verification", reviewed against D1–D12 and spec `app-appearance`: bar icon contrast, and via `uiautomator dump` a `content-desc` on every icon-only control. Findings are fixed or recorded. Verify: the screenshot names and outcomes in `docs/conversation-findings.md` "Appearance"
- [x] 7.3 On the Pixel: `adb install -r` (keeping the pairing); the maintainer uses it for real; log and screenshot evidence is gathered where it exists, including dark mode on the phone's panel. Verify: outcomes recorded in `docs/conversation-findings.md` "Appearance"

## 8. Choosing light or dark in the app

- [x] 8.1 `store`: `ThemeChoice` and `ThemeStore` per D13; `App` creates it, exposes the choice as a `StateFlow`, and on API 31+ applies it with `UiModeManager.setApplicationNightMode` in every `onCreate` and on change. Verify: `ThemeStoreTest` (instrumented, emulator only as in 7.1) — the default is `SYSTEM`; each choice saved reads back; an unreadable file and an unknown value read `SYSTEM`; by reading, none of unpairing, pairing again and the desktop's revocation (`MainActivity.unpair`, `onPaired`, `ConnectionService`) touches `theme.json`
- [x] 8.2 `MainActivity`: `dark` from the choice per D13, driving `SpektermTheme` and the bar icons. `StatusScreen`: the "Appearance" section with "System", "Light", "Dark" (an `AppearanceSection` composable). Verify: `AppearanceSectionTest` (instrumented, emulator only) — the current choice is shown selected, and tapping another reports it
- [x] 8.3 Emulator (API 36), with screenshots, the choice written to the app's `theme.json` with `run-as` and the app force-stopped and relaunched: dark on a light phone (the splash and the pairing screen dark, status icons light); light on a dark phone; with dark chosen, the phone's mode toggled both ways, the app staying dark; with dark chosen, `adb reboot`, then a cold start still dark; `SYSTEM` again following the phone. `./gradlew test` and the instrumented suites pass on the emulator only. Verify: outcomes in `docs/conversation-findings.md` "Appearance", noting API 29–30 as checked by reading only
- [x] 8.4 `README.md`: the look line says it follows the phone or the choice in settings. Verify: it reads so
- [ ] 8.5 On the Pixel: `adb install -r` (pairing kept). The maintainer's use of the live switch in Settings (no recreation: "Show all" stays open across a switch) is recorded when done. Verify: the install, and the outcome recorded in "Appearance"
