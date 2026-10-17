## Context

Built on `close-offer` (archived). The interface is Jetpack Compose with Material 3 (BOM 2026.09.00) and no icon
library. `MainActivity` wraps everything in `MaterialTheme(lightColorScheme()/darkColorScheme())` with no
arguments, which is Material's baseline purple, inside a `Surface(Modifier.safeDrawingPadding())`. The manifest's
window theme is `@android:style/Theme.Material.NoActionBar`, which is always dark. `enableEdgeToEdge()` is called
once, in `onCreate`. The activity handles `uiMode` itself (`configChanges`), so switching between light and dark
recomposes the screen (keeping `remember`ed state such as `LazyListState`) but does not recreate the activity.

The screens are `PairScreen` (outside navigation), `SessionsScreen`, `ConversationScreen` (with `PromptCard` and
the composer) and `StatusScreen` (titled "Settings"). Shared pieces live in `Common.kt` (`TopBar`, `Notice`) and
`SessionMenu.kt` (the menu, the close offer and the dialogs).

Emulator screenshots (AVD `Pixel_9`, 2026-10-17, paired with the installed spekterm) show the problems:

- In light mode the status and navigation bars are dark gray behind dark icons. The window background comes from
  the dark platform theme, and `safeDrawingPadding` keeps the app's surface out of the bar areas.
- The list gives every row a "Dormant" or state label of the same weight, plus a second line saying "Agent".
- In a conversation, tool calls and their results alternate line by line, and "Newest" covers the text.
- Settings has no top bar and prints every address.

A reply the user sent is placed in the conversation by its anchor: the key of the entry that was last when it was
sent (`Replies.kt:191`). Settled replies are inserted after that entry (`ConversationScreen.items`). When the agent
is working, the anchor is usually a tool result, thinking, or a tool call.

The desktop's look (spekterm `src/renderer/src/index.css`, `@theme`) is dark only:

| Token | Value | Use |
|---|---|---|
| shell | `#07090c` | outermost frame |
| rail | `#0a0c0f` | side rail |
| stage | `#0f1216` | main area |
| panel | `#14181d` | panels, cards |
| hover | `#1b2027` | hovered or selected row |
| hairline | `#1e242c` | borders |
| ink | `#e2e8f0` | text |
| ink-dim | `#94a3b8` | secondary text |
| ink-faint | `#5b6675` | tertiary text, dormant |
| accent | `#f59e0b` | accent |
| accent-soft | `rgba(245,158,11,.12)` | accent backgrounds |
| blue / green / danger | `#60a5fa` / `#34d399` / `#f87171` | |

The desktop's session dot is emerald (`#34d399`) for running, ink-faint for dormant and danger for a failed exit
(`session-badge.tsx` `StatusDot`). The desktop's base text size is 17 px, and it uses a monospace face for ids and
commands.

## Goals / Non-Goals

**Goals:**

- The app looks like spekterm's phone companion, in dark and in light mode.
- A row or item that needs the user stands out at a glance, and everything else recedes.
- A conversation with many tool calls reads as the agent's messages, with the tool work summarized between them.
  Every reply the user sent stays where it was sent.
- Every control is a recognizable icon or a clearly labeled button, and has an accessibility label.
- Nothing changes in what the app sends, keeps or decides, except one addition: a confirmation before unpairing.
  Everything else changes only how things are shown.

**Non-Goals:**

- Localization. Text stays English; a zh-TW change follows separately.
- Material You dynamic color. The theme is fixed, so that the app matches the desktop.
- Tablet or landscape layouts, beyond a maximum content width.
- New behavior: the terminal view, inbox, swipe actions, pull to refresh.
- Rewording texts the specs quote (state labels, notices, dialog texts), except the labels of controls that become
  icons.

## Decisions

### D1. A theme from the desktop's tokens, with a light variant

A new package `ui/theme` holds:

- `Color.kt`: both palettes and `SpekColors`.
- `Theme.kt`: `SpektermTheme(dark, content)` provides `MaterialTheme` (color scheme, typography, shapes) and
  `LocalSpekColors`.

Each `ColorScheme` is built with **every slot passed explicitly**. A slot left out would fall back to the
baseline purple, for example in a FAB's `onPrimaryContainer` or in `FilledTonalButton`'s `secondaryContainer`.
`ThemeTest` (JVM) asserts that no slot of either scheme equals the baseline scheme's value for that slot,
unless the value is a neutral (R = G = B).

| Slot | Dark | Light |
|---|---|---|
| primary / onPrimary | accent `#f59e0b` / `#1c1300` | amber-700 `#b45309` / `#ffffff` |
| primaryContainer / onPrimaryContainer | accent-soft over stage `#2b2315` / `#fcd34d` | `#fef3c7` / `#78350f` |
| inversePrimary | `#b45309` | `#fbbf24` |
| secondary / onSecondary | ink-dim `#94a3b8` / stage | `#475569` / `#ffffff` |
| secondaryContainer / onSecondaryContainer | hover `#1b2027` / ink | `#e2e8f0` / `#0f172a` |
| tertiary / onTertiary | blue `#60a5fa` / stage | `#2563eb` / `#ffffff` |
| tertiaryContainer / onTertiaryContainer | `#15253a` / `#bfdbfe` | `#dbeafe` / `#1e3a8a` |
| error / onError | danger `#f87171` / stage | `#dc2626` / `#ffffff` |
| errorContainer / onErrorContainer | `#3a1d1f` / `#fecaca` | `#fee2e2` / `#7f1d1d` |
| background, surface / onBackground, onSurface | stage `#0f1216` / ink `#e2e8f0` | `#f8fafc` / `#0f172a` |
| surfaceVariant / onSurfaceVariant | hover `#1b2027` / ink-dim `#94a3b8` | `#e2e8f0` / `#475569` |
| surfaceContainerLowest … Highest | shell `#07090c`, rail `#0a0c0f`, panel `#14181d`, hover `#1b2027`, `#232a33` | `#ffffff`, `#f8fafc`, `#f1f5f9`, `#e2e8f0`, `#cbd5e1` |
| surfaceBright / surfaceDim | `#232a33` / shell | `#ffffff` / `#e2e8f0` |
| surfaceTint | = surface (no tint) | = surface |
| inverseSurface / inverseOnSurface | ink / stage | `#1e293b` / `#f1f5f9` |
| outline / outlineVariant | ink-faint `#5b6675` / hairline `#1e242c` | `#64748b` / `#e2e8f0` |
| scrim | `#000000` | `#000000` |
| primaryFixed / primaryFixedDim / onPrimaryFixed / onPrimaryFixedVariant | `#fef3c7` / `#fde68a` / `#78350f` / `#92400e` | same |
| secondaryFixed / secondaryFixedDim / onSecondaryFixed / onSecondaryFixedVariant | `#e2e8f0` / `#cbd5e1` / `#0f172a` / `#334155` | same |
| tertiaryFixed / tertiaryFixedDim / onTertiaryFixed / onTertiaryFixedVariant | `#dbeafe` / `#bfdbfe` / `#1e3a8a` / `#1e40af` | same |

Material 3 1.4.0 (BOM 2026.09.00) has 48 color slots, all listed above. `ThemeTest` enumerates them by reflection
(every `ColorScheme` getter returning a color) rather than from a hand-written list. It asserts that there are 48,
so a version that adds a slot fails the test until the table covers it.

Contrast, measured as WCAG ratios:

- Light `primary` `#b45309` on white: 5.0. `#f59e0b` on white is 2.15, so it cannot be used as text.
- Light `outline` `#64748b` on `#f8fafc`: 4.55. Dark `outline` on stage: 3.2, which is enough for borders and dots
  but not for small text.

If a token reads poorly on the phone (for example hairline dividers that vanish on an OLED panel), the adjusted
value is noted in `Color.kt` and in this table.

**Status colors** (`SpekColors`), used everywhere a state is shown (rows, the collapsed summary, the conversation's
top bar, settings):

| Status | Color |
|---|---|
| attention: waiting for you, asks for a choice | `primary` |
| working, running | green: `#34d399` dark, `#059669` light (dots only) |
| offer: asks to close | `tertiary` |
| danger: failed | `error` |
| dormant | `outline` (dots only) |

Asking for a choice uses the attention color, not danger: on the desktop red means something failed, and a choice
is not a failure. The collapsed summary's "asks for a choice" (`error` today) and "asks to close" (`primary` today)
move to these colors.

**Typography**: Material 3 defaults (Roboto, with the system's CJK fallback), `bodyLarge` 16 sp for message text,
and `FontFamily.Monospace` for commands, tool arguments, results and branches.

**Shapes**: 8 dp small, 12 dp medium, 16 dp large. The composer field and pills are fully rounded.

Rejected alternatives: dynamic color (the maintainer chose the desktop look, 2026-10-17) and dark only (the
system setting is honored).

### D2. Window theme, splash and insets

`res/values/themes.xml` defines `Theme.Spekterm`, parented on `android:Theme.Material.Light.NoActionBar`.
`values-night/themes.xml` parents it on `android:Theme.Material.NoActionBar`. Each sets:

- `android:windowBackground` to the palette's background (`res/values{,-night}/colors.xml`);
- `android:statusBarColor` and `android:navigationBarColor` transparent;
- `android:windowLightStatusBar` and `android:windowLightNavigationBar`: `true` in light, `false` in night.

The starting window and the splash therefore already match the mode, before `enableEdgeToEdge` runs. On API 31+,
the splash background defaults to `windowBackground`, so there is no `values-v31`: a night qualifier outranks the
API level, and a `values-v31` would be skipped in dark mode. The splash icon stays the launcher icon. The manifest
uses `@style/Theme.Spekterm`. The launcher icon's background changes from `#1E1E2E` to stage `#0f1216`. Whether
the foreground (`#CDD6F4`) reads on the light splash is checked on the emulator. If it does not, `values` (light)
sets `android:windowSplashScreenIconBackgroundColor` to stage; that attribute is ignored before API 31.

Edge to edge:

- The root `Surface` fills the window, without `safeDrawingPadding`.
- Every screen, `PairScreen` included, is a `Scaffold` with `contentWindowInsets = WindowInsets.safeDrawing`, which
  includes the IME. Its content applies `innerPadding` and `consumeWindowInsets(innerPadding)`. The background
  draws behind the bars, the controls stay clear of them and of the keyboard, and the keyboard is not counted
  twice.
- `ConversationScreen`'s own `imePadding()` is removed.
- Bar icons follow the theme through `enableEdgeToEdge(SystemBarStyle.auto(light, dark) { dark })`, re-applied from
  a `LaunchedEffect(dark)` in `setContent`, with `dark` taken from Compose. The activity is not recreated on a
  `uiMode` change, so the call in `onCreate` alone would keep the first mode's icons.

### D3. Icons drawn in the app

`ui/Icons.kt` defines the needed icons as `ImageVector`s built with `ImageVector.Builder`. The path data is copied
from Google's Material Symbols (Apache-2.0, noted in the file header):

- Navigation and actions: arrow back, more vert, add, send, attach file, close, chevron right, expand more,
  arrow downward (jump to newest), content paste.
- Marks: check circle (succeeded), error, warning, info, help (question prompt), list (plan prompt).
- Things: terminal (shell), smart toy (agent), folder (new-session sheet), bedtime (dormant screen), image,
  description (file), qr code.

`material-icons-core` lacks half of these, `material-icons-extended` is large, and the release build is not
minified (`isMinifyEnabled = false`). Copying about 25 paths costs one file. Every icon-only control passes a
`contentDescription` (spec `app-appearance`); an icon beside its own text passes `null`.

### D4. Screen scaffolding

Every screen is a `Scaffold` (D2):

| Screen | Top bar title | Navigation | Actions |
|---|---|---|---|
| Sessions | desktop name | — | ⋮ "More options" (Collapse/Expand all, Settings) |
| Conversation | session label; subtitle is a state dot and the wait text | Back | session menu ⋮ "More options" |
| Settings | "Settings" | Back | — |
| Pair, while re-pairing | "Pair again" | Back | — |
| Pair, first pairing | no top bar | — | — |

`TopBar` in `Common.kt` becomes a wrapper over `TopAppBar(title, subtitle, onBack, actions, scrollBehavior)`, so
call sites stay one line. The bar is `surface`, and turns `surfaceContainer` once content scrolls under it
(`pinnedScrollBehavior`). The back icon is labeled "Back".

### D5. Notices with a tone

`Notice(text, tone, action…)` takes `Tone.Info | Warning | Error`, with **no default**, so every call site states
its tone and a `grep` can check it.

| Tone | Container | On-color | Icon |
|---|---|---|---|
| Info | `surfaceContainerHigh` | `onSurface` | info |
| Warning | `primaryContainer` | `onPrimaryContainer` | warning |
| Error | `errorContainer` | `onErrorContainer` | error |

Every call site and outcome:

| Where | Notice | Tone |
|---|---|---|
| Sessions | paused (Resume) | Warning |
| Sessions | not connected / list out of date (Details) | Warning |
| Sessions | no window | Warning |
| Creation | Starting | Info |
| Creation | Opened (Open, Dismiss) | Info |
| Creation | Unlisted | Info |
| Creation | Failed `NO_WINDOW` | Error |
| Creation | Failed `CREATE_FAILED` | Error |
| Creation | Failed `UNKNOWN` ("Can't tell whether…") | Warning |
| Conversation | paused | Warning |
| Conversation | not connected | Warning |
| Conversation | no window | Warning |
| Content notices | catching up | Info |
| Content notices | unavailable | Warning |
| Content notices | refused ("Could not open…") | Error |
| Content notices | truncated | Info |
| `answerNote` | `Partial`, `Mismatch`, `Unknown` | Warning |
| `answerNote` | `Gone`, `Refused` | Error |
| `attachNote` | `TOO_MANY_PICKED` | Warning |
| `attachNote` | `KIND`, `SIZE`, `EMPTY`, `UNREADABLE`, `SEND_NOT_FOUND` | Error |
| Settings | identity changed | Error |
| Settings | connection stopped by Android | Warning |
| Settings | protocol versions differ | Error |
| Pair | "no longer accepts this phone, pair again" | Warning |
| Pair | a refused link | Error |
| Pair | a failed pairing | Error |
| Pair | camera denied | Warning |

"Older entries are not shown." stays a pinned Info notice. `conversation-view` requires the screen to say it, and
the top of the list is out of view when the screen opens at the newest entry.

### D6. Session list

**Row** (min 56 dp, no dividers between rows):

- **Leading**: an agent or terminal icon in `onSurfaceVariant`, labeled "Agent" / "Shell". It replaces the "Agent"
  line and still satisfies `session-list`'s "whether it is an agent or a shell".
- **Middle**: the label (`bodyLarge`), and a second line only for a handoff state.
- **Trailing**, by state. The text never uses `outline`, which is too faint for text in light mode.

  | State | Trailing |
  |---|---|
  | Waiting for you | an 8 dp attention dot, and the text in attention color, semibold |
  | Asks for a choice | a pill (`primaryContainer`, `onPrimaryContainer`, semibold); the strongest |
  | Working | a green dot, and the text in `onSurfaceVariant` |
  | Dormant | a hollow `outline` dot, and the text in `onSurfaceVariant`, `labelSmall` |
  | Running, no reported wait state | a green dot only, labeled "Running" |
  | Offered and ready | nothing (the offer below says it) |

- **Close offer**: under the label, "The agent asked to close this session." in the offer color, then "Close"
  (outlined) and "Keep" (text button), in today's order and wording.

**Group heading**:

- A chevron icon, which rotates when collapsed.
- The folder name (`titleSmall`, semibold), the branch (`labelMedium`, monospace, `onSurfaceVariant`), and the count
  as a small pill at the end.
- A `surface` background, because the heading is sticky and rows must not show through it. 8 dp of space above,
  and one hairline under the heading instead of per-row dividers.
- The collapsed summary keeps its texts and takes the `SpekColors` of D1.
- The heading's own tap toggles collapsing. The end of the heading row, left of the count, is left free for a later
  per-folder action.

**New session**: an `ExtendedFloatingActionButton` ("New session", add icon), which collapses to the icon once the
list is scrolled. Collapsed, the icon carries `contentDescription` "New session"; expanded, the text says it and the
icon passes `null`. `session-create` requires the offer to stay but be unusable when a session cannot be started,
so the FAB is always shown. While `!canCreate` it uses `surfaceContainerHigh` / `outline` colors, ignores taps, and
carries `semantics { disabled() }`. The notice says why, as today. The list gets bottom padding so that the last
row clears the FAB.

**New-session sheet**: each choice with a folder icon (the Global item too), and the
branch in monospace.

**Empty states**:

- No list received yet: a centered progress indicator only while connecting or catching up (status `CONNECTING`,
  or connected and the list not current). Otherwise nothing; the notice says why.
- An empty list: the terminal icon, "No sessions on the desktop.", and "Start one with New session." when
  `canCreate`.

### D7. Conversation: tool calls grouped by a pure function

A new pure `desktop/ConversationItems.kt` provides
`group(entries: List<Entry>, keys: List<String>, anchors: Set<String>): List<Shown>`, unit-tested.

**Pairing results with calls**: a `Result` is paired with the earliest earlier `Tool` that has the same non-empty
`id` and no result yet (greedy in call order). A result that cannot be paired — its call is not among the entries
(truncated), it has an empty id, or it comes before its call — is a `LoneResult`. A paired result is not shown on
its own. It appears with its call, even if other entries came between them (parallel calls), so it can show
earlier on screen than it arrived. That is intended: a result belongs to its call.

**Items**:

- `Call(key, tool, result?)`: a tool call, with its result once paired.
- `LoneResult(key, result)`.
- `Run(key, parts: List<Call | Thinking>)`: two or more calls in a row, where only paired results and thinking
  come between them. A `LoneResult` ends a run, as an `Entry` does. A run's parts never end with thinking: thinking
  after the last call is emitted as an `Entry` after the run.
  - Its key is the key of its first call, so it stays stable as the run grows.
  - `calls`, `failed` (calls whose result is an error) and `latest` (the last call) are derived from `parts`.
- `Entry(key, entry)`: everything else, i.e. user messages, injected messages, text, and thinking outside a run.
  Any of these ends a run.

**The pass.** The function first pairs results with calls, then walks the entries once, in order. It keeps:

- the item being formed: a `Call` or a `Run`, or none;
- the thinking seen since that item's last call ("held thinking").

Each entry is handled as follows:

- **A call**:
  - If an item is being formed, the call joins it: a `Call` becomes a `Run`, and the held thinking becomes parts
    before the new call.
  - Otherwise the call starts a `Call`.
- **Thinking**: held if an item is being formed. Otherwise it is emitted as an `Entry`.
- **A paired result**: nothing to show (it is already with its call).
- **Anything else** (a user, injected or text entry, a `LoneResult`): it ends the item being formed. That item is
  emitted, the held thinking is emitted after it as `Entry` items, and then the entry itself is emitted.
- **The end of the entries** ends the item being formed in the same way.

**Replies stay where they were sent** (`conversation-reply`). `anchors` holds the anchor keys of the settled
replies. When the entry just handled has an anchor key:

1. the item being formed is emitted;
2. the held thinking is emitted after it, as `Entry` items;
3. the anchor is attached (`anchorsAfter`) to the last item emitted.

So a reply never lands inside a run or a call, and never before an entry that came before it. The screen inserts
each anchor's replies after the item that carries it. Replies with no anchor, or whose anchor is no longer among
the entries, come first; pending replies come last, as today.

Worked cases (the ⟵ marks the item carrying the anchor):

| Entries | Shown |
|---|---|
| `call1, result1, thinking=anchor, call2` | `Call(1)`, `Entry(thinking)`⟵, `Call(2)` |
| `call1, thinking, result1=anchor` | `Call(1)`, `Entry(thinking)`⟵ |
| `call1, result1, call2, result2, thinking, result-of-an-earlier-call=anchor, call3` | `Run(1,2)`, `Entry(thinking)`⟵, `Call(3)` |
| `call1=anchor, result1, call2` | `Call(1)`⟵, `Call(2)` |
| `call1, call2=anchor, call3` | `Run(1,2)`⟵, `Call(3)` |
| `callA, text, callB, callC, resultA=anchor` | `Call(A)`, `Entry(text)`, `Run(B,C)`⟵ |

The anchor splits a run, so `conversation-view` (modified) says that a reply shown between tool calls ends the run
there.

**Rendering**:

- **Call**: one line with a mark, the name (`labelLarge`, semibold) and the argument (monospace, one line,
  ellipsized).
  - The mark: a green check when the call succeeded, a red error icon when it failed, a hollow `outline` dot while
    there is no result.
  - Tapping the call expands the full argument and the result in a monospace box (`surfaceContainer`, at most
    280 dp, scrolling).
- **LoneResult**: the same line with "Result" or "Failed" in place of the name and no argument, expandable the same
  way.
- **Run**: one line, "7 tool calls · 1 failed" (the failed part in danger color, and only when non-zero). Under it,
  the latest call's name and argument, one line, in `onSurfaceVariant`.
  - A chevron expands the run to its parts, indented, rendered as above.
  - The parts are drawn **inside the run's lazy item**, so their keys never collide with the run's.
  - The run's expansion is remembered by its key. A single call the user expanded that then becomes the start of a
    run opens as an expanded run.
  - A part's expansion is remembered by `part:<key>`.
- **Thinking**: a muted "Thinking" line with a chevron. Expanded, the text is shown in `onSurfaceVariant`.
- **Injected**: unchanged wording, with the same chevron pattern.
- **User message**: a bubble aligned to the end, at most 85% wide, `surfaceContainerHigh`, 16 dp corners. It has no
  visible "You" label; the bubble carries `semantics { contentDescription = "You" }` so a screen reader still says
  whose it is.
- **A reply of the user's not (yet) in the conversation** (`ReplyRow`): the same bubble with a 1 dp `outline`
  border, its state text above the message (`labelSmall`; `error` when settled), and attachments as an attach icon
  followed by their names (no "📎").
- **Agent text**: full width, as today. The Markdown renderer takes the theme's colors and typography, and its code
  blocks get a `surfaceContainer` background and a monospace face.
- **Working**: an item at the end with a 14 dp indeterminate circular indicator and "Working". It is driven only by
  the wait state, so `conversation-view`'s "nothing that claims progress within a message" holds.
- **Jump to newest**: a `SmallFloatingActionButton` with an arrow-down icon at the bottom end, labeled "Jump to
  newest".
- **Following the newest**: the code follows the last entry key, the last reply id and the working flag, not the
  last shown item's key, which stays the same while a run grows. The item count comes from the list being built
  (shown items, replies, and the working item), not from `layoutInfo`, which is a frame behind. The code scrolls with
  `scrollToItem(count - 1, scrollOffset = Int.MAX_VALUE)`. The list clamps that to its end, so an expanded run taller
  than the screen still shows its newest call.
- **Expanding**: a call, a run, thinking and an injected message expand by tapping their whole line. The line
  carries the text, plus `stateDescription` "Expanded" / "Collapsed".

### D8. Composer and attachments

The composer is a bar on `surfaceContainer` with a hairline on top:

- An attach `IconButton` labeled "Attach", shown when attaching is offered. Its menu has "Photo" and "File", with
  the image and description icons.
- A `TextField` with a fully rounded shape, `surfaceContainerHigh` fill and no indicator line. The placeholder and
  the 1–6 lines are unchanged.
- A send `FilledIconButton` labeled "Send", enabled under today's condition.

The "why you can't send" line stays above the bar (`bodySmall`, `onSurfaceVariant`).

Attachments sit above the field, in a horizontally scrolling row of compact cards (`surfaceContainerHigh`, at most
220 dp wide). Each card has:

- an image or file icon;
- the name on one line;
- the size and state on a second line, in `error` when failed;
- "Retry" when failed;
- a close icon labeled "Remove attachment".

The prompt card and the close-offer card stay between the list and the composer. The close-offer card uses the
prompt card's container (D9), with a `tertiary` border.

### D9. Prompt card

The card is `surfaceContainerHigh` with a 1 dp `primary` border and 16 dp corners. Its header has an icon (warning
for a permission, help for a question, checklist for a plan) and today's sentence.

Decisions are ranked by type:

| Decisions | Button |
|---|---|
| go ahead: `ALLOW`, `ANSWER`, `APPROVE` | filled `Button` |
| `APPROVE_AUTO` | `FilledTonalButton` |
| stop: deny, decline, keep planning | `OutlinedButton` |

The order follows `offer.decisions`, as today. The texts, the tap guard and the half-screen cap are unchanged. The
plan's "Show all" dialog gets a top bar with Back.

### D10. Settings

A `Scaffold` with the top bar. The content is a scrolling column, max width 600 dp, in this order.

**1. Connection card** (`surfaceContainer`, 16 dp corners):

- A status dot and the short state ("Connected", "Paused", …, `titleMedium`), the desktop name (`bodyMedium`), and the
  full state ("Connected via 192.168.0.199 since 11:54 AM") as a small line. The dot is green when
  connected, attention while connecting or waiting, `outline` when paused or stopped, and danger for an identity or
  protocol problem.
- Small lines: the last disconnect, and the connections this session.
- **Addresses**: with three or fewer, each on its own line, in monospace, in the order they are tried. With more,
  "7 addresses · port 47615" and "Show all"; the address in use is in the full state line above. Show all lists every address in order, as
  `desktop-connection` (modified) requires.
- Buttons: Pause/Resume (tonal) and Export log (text).
- The identity, stopped and protocol banners become notices inside the card, with the tones of D5 and their
  Dismiss.

**Appearance** (D13) comes between 1 and 2.

**2. Notifications**: one row, "Attention notifications" with "On" / "Off", and "Notification settings" when off.

**3. Battery**: one row with today's text and "Battery settings".

**4. Recent events**: compact two-line rows. The first line has the time and the kind, the second the title (one
line, ellipsized) and the outcome. "None yet." when there are none.

**5. Pairing**: "Pair with a new code" (outlined), then "Unpair" in error color. Unpair opens `UnpairDialog`, a
separate composable so it can be tested on its own:

- Title: "Unpair from <desktop>?"
- Text: "The phone stops connecting and forgets this desktop. To use it again, pair from the desktop's Remote
  access settings."
- Buttons: "Unpair" (error color) and "Cancel".

Only "Unpair" calls today's `unpair()` (spec `device-pairing`).

### D11. Pair screen

A `Scaffold` (D2, D4), with a centered, scrolling column, max width 480 dp:

- The QR-scanner icon in a 72 dp `primaryContainer` circle.
- "Pair with spekterm" (`headlineSmall`).
- Two numbered steps: "On the desktop, open Settings → Remote access → Pair device." and "Scan the code, or paste
  its link."
- "Scan QR code", a full-width filled button. While scanning, the camera preview takes its place, with "Cancel"
  under it.
- An "or" divider.
- The link field, with a trailing paste icon button labeled "Paste link".
- "Pair", a full-width tonal button.

While pairing, a `LinearProgressIndicator` shows with the text. Errors and "pair again" are notices with the tones
of D5. When re-pairing, the bottom text button's "Back to status" becomes "Back to settings", because the screen it
returns to is titled Settings; the system back returns there too.

### D12. Dialogs

- **Rename**: a `BasicAlertDialog` with its own button row: "Clear name" at the start; "Cancel" and "Rename" at
  the end.
- **Working** ("Closing …", "Hibernating …", "Waking …", "Keeping …"): a 20 dp `CircularProgressIndicator` beside
  the text.
- Other dialogs keep `AlertDialog`. Their destructive confirms ("Close session", "Close anyway", "Hibernate
  anyway", "Unpair") use the error color.

### D13. Choosing light or dark in the app

Added after the phone run, at the maintainer's request (2026-10-17).

**The choice**: `ThemeChoice { SYSTEM, LIGHT, DARK }`, default `SYSTEM`. It is kept by a `ThemeStore` in
`store/Stores.kt`: a `JsonFile` `theme.json` in `noBackupFilesDir`, like the other stores. Unpairing and pairing
again do not touch it, because it is the app's setting, not the pairing's. An unreadable or unknown value reads as
`SYSTEM`. `App` holds the store and a `MutableStateFlow<ThemeChoice>` that the screens collect.

**Applying it**:

- **Compose**: `MainActivity` computes `dark = when (choice) { SYSTEM -> isSystemInDarkTheme(); LIGHT -> false;
  DARK -> true }`. That drives `SpektermTheme(dark)` and the bar icons (D2), so a change recomposes in place
  (`configChanges` keeps the activity and its state).
- **The starting window**, on API 31+: `UiModeManager.setApplicationNightMode(MODE_NIGHT_AUTO | NO | YES)`. The
  system keeps it for this app and resolves `values-night` resources and the splash with it, so a cold start matches
  the choice. The system service maps `MODE_NIGHT_AUTO` to "no override", i.e. the phone's setting. The javadoc's
  "automatic by location" reading does not apply to the per-app call, so `AUTO` is the right value for `SYSTEM`.
- **Keeping the two copies in step**: it is called when the user chooses, and again in every `App.onCreate`, which
  repairs any divergence. On API 31–32, clearing the app's data does not clear the system's copy (Android 13 does);
  our store then reads `SYSTEM` while the system still holds the old mode, until the next process start resets it.
- **Live changes**: the call changes this app's configuration in place. The manifest's `configChanges` includes
  `uiMode`, so the activity is not recreated; `isSystemInDarkTheme()` then reads the overridden mode, which keeps
  the `SYSTEM` mapping above right.
- **API 29–30**: the starting window follows the phone's setting (spec: MAY), and the first Compose frame switches.
  No API 29–30 device or image is available here, so that path is checked by reading only.

**In settings**: a section "Appearance" between Connection and Notifications, with a single-choice segmented row:
"System", "Light", "Dark". The current choice is selected, and tapping another applies it at once.

Rejected alternative: AppCompat's `setDefaultNightMode`. It needs the AppCompat library and an AppCompat
activity, and it does not tell the system, so on Android 12+ the starting window would not follow the choice.

Settings is reachable only once paired, so a phone that is not paired yet follows the phone's setting until then.

## Risks / Trade-offs

- **When the two copies of the choice differ at a process start** (on Android 12–12L after the app's data is
  cleared), that first run keeps the previously chosen mode, splash and screens, until the app is started again:
  `App.onCreate` removes the system's override, but the starting activity already has it, and a removed override
  is not pushed to a running process (seen on the emulator by writing the choice file directly; a choice made in
  Settings switches live both ways, D13).

- **Hand-copied icon paths can be wrong.** Each icon is checked on an emulator screenshot.
- **`TopAppBar` and `BasicAlertDialog` are `@ExperimentalMaterial3Api`.** The bottom sheet already uses that
  opt-in, and the BOM is pinned.
- **A result shown with its call can sit well above where it arrived.** This is accepted (D7).
- **The theme can look different on the Pixel's OLED panel than on the emulator.** The phone run checks dark mode,
  and token adjustments are noted per D1.
- **The FAB looks disabled while a session is starting.** The notice says why.
- **Much UI code is touched.** The behavior-bearing code stays as it is (`SessionsView`, `SessionActions`,
  `Answers`, `Replies`, `SessionAction`). Their unit tests must pass unchanged, which guards that only
  presentation moved.
- **One change carries both the restyle and the tool grouping** (D7). They are kept together because the grouping
  is the conversation screen's restyle and both touch `ConversationScreen.kt`. D7's logic is held apart in a pure,
  unit-tested function.

## Verification

- **JVM**:
  - `ConversationItemsTest` for D7, including reply anchors.
  - `ThemeTest` for D1's "no slot falls back to the baseline".
  - The existing suites pass unchanged.
- **Instrumented** (emulator only): `UnpairDialogTest`. Cancel does not call unpair, and Unpair does.
- **Emulator** (`Pixel_9`, paired with a spekterm desktop): screenshots in light and dark mode, reviewed against
  D1–D12, with the screenshot names recorded in `docs/conversation-findings.md`. They cover every screen and state
  that can be produced:
  - a cold start;
  - the list with mixed states, a collapsed folder, an empty list;
  - a conversation with a tool run, collapsed and expanded, and with the keyboard open;
  - the prompt card;
  - the composer with an attachment;
  - settings and the unpair dialog;
  - the first-pairing screen and a refused link;
  - the rename dialog and the working dialog.
- **Theme choice** (D13):
  - `ThemeStoreTest` and `AppearanceSectionTest` (instrumented, emulator only).
  - On the emulator, with the choice written to its file and the app relaunched (Settings needs a pairing the
    unpaired emulator lacks): dark on a light phone, from the splash; light on a dark phone; the phone's mode toggled
    while dark is chosen; a reboot; back to the system setting.
  - The live switch from Settings, on the phone.
- **Phone**: installed with `adb install -r` (keeping the pairing) and used for real by the maintainer. Log and
  screenshot evidence is gathered where it exists.
