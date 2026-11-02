## 1. Logic

- [x] 1.1 `desktop/SessionsView.kt` per D1, D2: `Bucket` (`NEEDS_YOU`, `WORKING`, `RUNNING`, `IDLE`, `DORMANT`),
  `bucket(row)`, `summary(groups)` (non-empty buckets in order, over every group's rows), `chips(summary, filter)` (the
  selected bucket kept with 0 in its place) and `filtered(groups, bucket)` (matching rows in `groups()`'s order with
  `depth = 0`, `nested = false`; groups with none left out). Verify: new cases in `SessionsViewTest` — the spec's
  "Counting the sessions" list gives needs you 2, working 1, running 1, idle 1, dormant 1 in that order; an offered
  working agent and an offered seen agent count as needs you, not working or idle; an agent asking for a choice and
  offered counts once; an unknown-wait agent counts as running; an all-working list gives only working; needs-you's
  count equals the sum of every group's `summary` (choice + offers + ready) on a mixed list; `chips` with `IDLE`
  selected and no idle row puts idle with 0 between running and dormant, and with nothing selected adds nothing;
  `filtered` on a tree with source A, a later root D and A's later child B, all ready, gives A, B, D (the tree's
  order, not the desktop's A, D, B) all at depth 0 and not nested, a waiting child under a working source comes alone,
  and a group with no match is left out; `./gradlew testDebugUnitTest` green

## 2. Interface

- [x] 2.1 `ui/SessionsScreen.kt` per D3, D4: the summary row (compact pills in a `FlowRow` that wraps, every pill with
  its dot, count and word; dormant in `outline`; content description from `sessions_bucket_count`; selected semantics)
  under the notices and the creation notice, outside the list, drawn from `chips(…)` only while there are groups; `filter` in `rememberSaveable`; choosing a
  pill scrolls the list to its top; while filtered the list draws `filtered(…)` with every heading expanded and not
  clickable (OpenSpec control kept), the menu without "Collapse all" / "Expand all", `collapsed` untouched, a "Show
  all" line while rows are shown, and the empty message with its own "Show all" when none; the filter cleared on
  choosing a folder in the new-session sheet and when the list has no session or no window. Strings
  `sessions_bucket_needs_you`, `sessions_bucket_count`, `sessions_filter_show_all` and the five
  `sessions_filter_empty_*` in `values` and `values-zh-rTW`. Verify: `./gradlew test assembleDebug` with `StringsTest`
  and `HardcodedTextTest` green; looked at in 3.1
- [x] 2.2 `README.md`: the Sessions item says the list counts the sessions by state at its top and a tap on a state shows
  only those sessions; the Handoffs item's "sits under it" says it does so except in that filtered list. Verify: it
  reads so

## 3. The screens

- [ ] 3.1 On the emulator (AVD `Pixel_9`) paired with the dev desktop over `10.0.2.2`, saving and restoring pairings as
  `driving-pixel-and-dev-desktop` says, with sessions in at least two folders (one collapsed) in all five states — one
  asking for a choice, one waiting not seen, one idle, one working, one shell, one hibernated, a handoff child waiting
  under a working source: the counts match the rows; tap needs you and see only those sessions, the collapsed folder's
  included, its heading counting them and not collapsing on a tap, the child alone with its "from" line, the list's
  menu without "Collapse all" / "Expand all", and a folder heading's OpenSpec control opening that folder's OpenSpec; open one,
  answer it, go back and see the filter still on and the session gone once it works; open one only to read it and see
  it gone as idle; answer the last one and see needs you at 0 with the empty message and one "Show all", then "Show
  all" and the collapsed folder still collapsed; filter by dormant and wake one from its menu; with a filter on, change
  the app's language in the system's per-app settings and see the filter kept; with a filter on, start a new session
  and see the filter cleared on the way back; close every session on the desktop with a filter on, open a new one and
  see the whole list; disconnect the desktop and see the summary still counting the last list. Screenshots in English
  and 中文（台灣）, light and dark: the full list with all five states, the needs-you filter, the empty filter, and in
  English all five on screen, wrapped onto a second line. Verify: the screenshots, looked at, listed in `docs/conversation-findings.md`'s new
  section "Sessions by state (`session-state-summary`)"
- [x] 3.2 `./gradlew test assembleDebug` passes, and the instrumented suites pass on the emulator with
  `ANDROID_SERIAL=<emulator> ./gradlew connectedDebugAndroidTest` — never on the Pixel: first `adb disconnect` the
  Pixel and check that `adb devices` lists only the emulator; reconnect it afterwards. Verify: the commands' output,
  recorded in the findings section

## 4. On the phone

- [ ] 4.1 On the Pixel, with kewang's OK before driving it, against the installed desktop: `adb install -r` the debug
  build; kewang uses the needs-you filter to answer the agents waiting on them; I take screenshots only with the app in
  front. Verify: kewang's word and the screenshots recorded in the findings section
