## 1. Protocol

- [x] 1.1 `net/OpenSpec.kt`: `OsChange`, `OsChangeDetail`, `OsSpecSummary`, `OsSpec`, `Piece` and the sealed `OsArtifact`, decoded per design D1; `Operations.OPENSPEC_*`; `Frames.openspecChanges/Change/Specs/Spec/Watch` (`openspecWatch(null)` sends `{"folderId":null}`). Verify: `OpenSpecTest` (JVM) covers:
  - a change list with one malformed element keeps the others;
  - a worktree with a null branch;
  - `taskStats` null;
  - each artifact kind: markdown, data, tasks with a multi-line task text, specs with two deltas;
  - an unknown `kind`, a markdown artifact whose `content` is a number, and an artifact without `id`;
  - `omitted` on an artifact, a tasks artifact, a delta and a spec;
  - an older desktop's answers (`relPath`, `schema`, `artifactCount`, a tasks artifact with both `content` and `tasks`, a spec with `relatedChanges`, a specs list with `relPath`) decode the same values;
  - the request payloads
- [x] 1.2 `EventRoute`: `openspec.changed` → `DESKTOP`. Verify: `EventRouteTest` lists it under the desktop kinds

## 2. State and requests

- [x] 2.1 `desktop/OpenSpecTracker.kt` per D3. Verify: `OpenSpecTrackerTest` (JVM) covers:
  - focus on a live connection → `Watch(A)`, and no read until its answer; the answer → `Read`;
  - focus when `A` is confirmed → `Read` at once, no watch;
  - focus on a connection that is not live → `Read`, never `Watch`;
  - switching tabs in `A` while `Watch(A)` is in flight → no second watch, and the new target is read after the answer;
  - focus moving to `B` while `Watch(A)` is in flight → nothing until that answer, then `Watch(B)`, then `Read` of B after B's answer;
  - unfocus and idle while `Watch(A)` is in flight → `Watch(null)` after A's answer;
  - `confirmed = A`, focus B, `Watch(B)` answered `NOT_FOUND` → `Read` of B and `Watch(null)` in the same step; `confirmed` stays A until the stop's answer; B is not asked again;
  - an event for `confirmed` with a focus there → `Read`; for another folder or with no focus → nothing;
  - three events during a read → exactly one more read after it;
  - the focus moving during a read → the new target is read after it;
  - unfocus by another owner → ignored; unfocus, then focus, then idle → nothing sent;
  - attached with a focus → `Watch` again, then `Read`; attached without one → nothing;
  - detached → nothing in flight; while detached, focus, refresh and idle send nothing; then attach → `Watch`, and `Read` after its answer;
  - `confirmed = A`, focus B (`Watch(B)` in flight), focus A → no read yet; B's answer → `Watch(A)`; A's answer → `Read` of A;
  - focus back on a refused folder → `Read` at once in the same step, no `Watch` of it, and `Watch(null)` when `confirmed` is set; `notLive` is true; refresh → false once the folder's watch succeeds; a new connection → false
  - a watch answered `INTERNAL` → refused; refresh → `Watch` again before the read;
  - an `openspec.changed` handled before its watch's answer → ignored; the answer → `Read`;
  - a watch that fails after `wanted` moved on → the next folder is watched, and the failed one is refused;
  - an answer to `Watch(null)` of any kind → `confirmed = null`;
  - B refused, detach, attach → `Watch(B)` again, and the read after its answer;
  - refresh with `confirmed` the focus's folder → `Read` at once, no `Watch`;
  - unfocus while the focus's watch is in flight → its answer sends no read; only the stop after idle;
  - unfocus while a read is in flight, then an event → no further read;
  - `stale` cleared when a read is sent, so an event during it sets it again;
  - pending from `stale` until the read's answer, including the wait for the watch; not pending while detached
- [x] 2.2 `desktop/OpenSpecText.kt`: `archivedAs(slug, archived)` per D5, `readableSlug(slug)` per D6 (core's `parseSlug` rule), and the D4 error texts. Verify: `OpenSpecTextTest` (JVM):
  - `x` finds `2026-10-17-x` and not `2026-10-17-xy` or `x-2`;
  - of two dated copies, the first in list order wins;
  - a slug that already has a date prefix finds nothing;
  - `readableSlug("2026-10-16-close_offer")` is `close offer`, and `readableSlug("add--thing")` is `add thing`;
  - each error code gives its text, and `TOO_LARGE` names `detail.limit` in MB
- [x] 2.3 `desktop/OpenSpecGates.kt` per D2: the heading's and the menu's way in, the Specs tab, and which rows open, from the operations and the folders. Verify: `OpenSpecGatesTest` (JVM):
  - no `openspec.changes` → no heading control and no menu item;
  - folders with and without `hasOpenSpec` → only the marked ones;
  - no marked folder → no menu item;
  - the global group → never;
  - without `openspec.specs` → no Specs tab;
  - without `openspec.change` / `openspec.spec` → rows do not open;
  - without `openspec.watch` → not live
- [x] 2.4 `Desktop`: `openspecFocus(owner, target)`, `openspecUnfocus(owner)` (idle after `openspecLingerMs`, 5 s, settable by tests as `createWaitMs` is), `openspecRefresh()`, `openspec: StateFlow<Map<Target, Loaded>>` with the 8-target bound. Also:
  - `openspec.changed` is handled in `onEvent`;
  - the tracker is reset in `attach`, and `pending` ends in `detach`;
  - every unfocus restarts the linger, and a focus cancels it;
  - `clear()` and `resetForTest()` empty the cache and reset the tracker and the linger timer;
  - OpenSpec calls use `OPENSPEC_TIMEOUT_MS` (60 s);
  - a change's `NOT_FOUND` causes one `openspec.changes` read for D5;
  - log kind `OPENSPEC` per D9.
  Verify: `DesktopTest` (instrumented, scripted desktop), run in 5.2:
  - with `openspec.watch` listed, focus sends `openspec.watch {folderId}` and `openspec.change` only after its answer;
  - an `openspec.changed` for that folder causes exactly one more read;
  - a reconnect sends the watch again, then the read;
  - without `openspec.watch`, no watch is sent and refresh reads;
  - unfocus sends `openspec.watch {folderId:null}` after the linger and not before;
  - unfocus, then a focus within the linger → no stop sent;
  - a watch answered `INTERNAL` → `notLive` true for that target;
  - a change answered `NOT_FOUND` reads the list and sets `archivedAs`;
  - a detach ends `pending`;
  - `clear()` empties the cache
- [x] 2.5 `SessionsView.Group.hasOpenSpec` from the folder (`false` for the global and unknown groups). Verify: `SessionsViewTest` — a folder marked and one not, and the existing assertions unchanged

## 3. Interface

- [x] 3.1 `AgentMarkdown.kt`: shared safety settings and `RepositoryMarkdown(text, lazy)` per D7 (`immediate = true`; lazy through `LazyMarkdownSuccess`). Verify: `AgentMarkdownTest` (instrumented), run in 5.2:
  - a two-line paragraph renders as one line of text;
  - `[design](design.md)` has no link annotation and `[site](https://example.com)` keeps one;
  - `<b>x</b>` shows its tags as text;
  - an `![i](https://example.com/i.png)` loads nothing;
  - agent text still keeps its line breaks
- [x] 3.2 `ui/Icons.kt`: the icons D6 needs that `ui-polish` did not add (refresh, check box, check box outline), from Material Symbols as `ui-polish` D3 does. `ui/Format.kt`: the size formatter moved from `ConversationScreen`, which then uses it. Verify: `./gradlew assembleDebug`; each icon checked on an emulator screenshot in 5.3
- [x] 3.3 `ui/OpenSpecScreens.kt`: folders, folder (tabs, active and archived sections, rows, empty states), change (artifact tabs, every kind, omitted pieces, D5's notice), spec (text and history), per D6; the status line, refresh and progress bar; gating through `OpenSpecGates`. Verify: `./gradlew assembleDebug`; screenshots in 5.3
- [x] 3.4 `MainActivity`: the four routes per D6, with each screen's focus on `ON_START` / `ON_STOP` keyed by its back-stack entry; "Open archived change" replaces the change route. Verify: `./gradlew assembleDebug`; back from each screen returns to the one under it on the emulator (5.3)
- [x] 3.5 `SessionsScreen`: the heading's OpenSpec icon button and the menu's "OpenSpec" per D8, through `OpenSpecGates`. Verify: `./gradlew assembleDebug`; on the emulator (5.3), tapping the button leaves the group's collapse state unchanged, and `uiautomator dump` shows `content-desc` "OpenSpec for <folder>"

## 4. Docs

- [x] 4.1 `README.md`:
  - "What it does" says the phone can browse each folder's OpenSpec, read-only, updated live on a desktop that offers it;
  - "Not yet" no longer lists OpenSpec browsing;
  - the version line says browsing needs spekterm 29a5d0a (0.2.6) or later and live refresh 0fcdc1f (0.2.8);
  - the line's "spekterm with `remote-close-offer`" becomes its commit, 3feaac0.
  Verify: it reads so

## 5. Build and checks

- [x] 5.1 `./gradlew test assembleDebug` passes. Verify: the output, recorded in `docs/conversation-findings.md`'s new section "OpenSpec"
- [x] 5.2 The instrumented suites pass on the emulator: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`, never on the Pixel. Before it:
  - tell `spekterm-android-06b5`, if that session is still running: it uses the same emulator and its pairing, and the run unpairs it;
  - `adb disconnect` the Pixel, and check that `adb devices` lists only the emulator.
  Reconnect the Pixel afterwards. Verify: the output, recorded in "OpenSpec"
- [x] 5.3 Emulator screenshots (AVD `Pixel_9`, paired with a dev spekterm desktop, after telling `spekterm-android-06b5` as in 5.2) in dark and light mode. Each is reviewed against D6–D8, and `content-desc` is checked on the icon-only controls. The screens:
  - the folders list;
  - folder changes with a worktree chip and the archived section expanded;
  - a folder with no change (an empty `openspec/` in a test repository);
  - the specs tab;
  - a change on each artifact kind;
  - an omitted piece: a test change in the dev desktop's workspace holding a data file over 1.2 MB;
  - a spec with its history;
  - the not-connected notice with refresh disabled while paused;
  - the older-desktop notice (from 5.5).
  Verify: screenshot names and outcomes in "OpenSpec"
- [ ] 5.4 On the Pixel: `adb install -r`, keeping the pairing if it is with the dev desktop, otherwise pairing anew. The desktop is a dev spekterm (`npm run dev` in ~/git/spekterm at or after `0fcdc1f`, profile `~/.config/spekterm-dev`). Record each outcome in "OpenSpec":
  - this repository's and spekterm's folders open; spekterm's largest change (by artifact size) opens, with the time to first content noted;
  - a task ticked in a `tasks.md` on the desktop shows as done on the phone within a few seconds, with no tap, and the log shows `OPENSPEC op=change cause=event`;
  - a new change in a second working tree of a test repository appears in the list with its branch chip;
  - a change archived while on screen shows "This change was archived." and opens its archived copy;
  - airplane mode, a task ticked meanwhile, then back: the tick shows without a refresh;
  - leaving to the session list, and separately putting the app in the background: each time the log shows the watch stopped (`stop=true`) about 5 s later;
  - a relative link and an `https` link in an artifact: the first does nothing, the second opens the browser;
  - the exported log has no slug, topic or text.
  Verify: every item has an outcome or a named carrier
- [x] 5.5 Against spekterm 0.2.7 (the reads, no watch): run from source at `99ca1ce` in a separate git worktree of ~/git/spekterm, with its own profile, so that it shares nothing with 5.4's: `XDG_CONFIG_HOME=$HOME/.config/spekterm-027 npx electron-vite dev` (its `npm run dev` would use `~/.config/spekterm-dev`), and a remote port of its own set in that profile. Use whichever device can be paired with it without disturbing another session's run, record which, and pair back afterwards. Check:
  - the same screens read;
  - "This desktop doesn't update OpenSpec by itself" is shown;
  - refresh shows a ticked task;
  - no `openspec.watch` is sent (log).
  Verify: outcomes in "OpenSpec"
