## Why

The app works but looks like a scaffold. It has no look of its own: it uses Material's baseline purple, which
the spekterm desktop (near-black surfaces, amber accent) shares nothing with. Every control is text: "‹ Back",
"⋮", "▸/▾", "📎", "Attach", "Send". Screenshots on the emulator (2026-10-17) show what that costs in use:

- In light mode the status and navigation bars stay dark gray, so the clock and battery icons are dark on dark
  and almost invisible.
- In the session list every row says "Dormant" in the same weight as "Waiting for you", and every row's second
  line says "Agent". The one row that needs the user does not stand out.
- In a conversation, one agent turn reads as seven alternating "Bash …" / "Result ›" lines before the agent's
  answer. The user's own message is a saturated purple block, louder than the agent's reply, and "Newest" covers
  the text.
- The settings screen has no top bar, prints seven addresses in a row, and shows the connection state in a larger
  type than the screen's title. "Unpair" acts on the first tap, and the phone then has to be paired again from
  the desktop.
- Every notice uses the same tone whether it says "paused", "not connected" or "could not start a session".
- Icon-like controls carry no accessibility label (the "⋮" button reads as "⋮").

## What Changes

- **A look that matches the desktop.** The app gets its own theme built from the desktop's tokens: near-black
  surfaces, slate text, amber accent, the desktop's green/blue/red. It follows the system's light or dark
  setting by default, with a light variant in the same family; settings can also fix it to light or dark. The window background and the splash match the theme, so a
  cold start does not flash another color (on Android 12+ even when the choice differs from the phone's). Status and navigation bars draw over the app's own background, with
  icons that contrast in both modes.
- **Icons instead of glyphs.** Back, the overflow menu, collapsing, new session, attach, send, jump to newest,
  agent/shell, file and photo use vector icons. Each icon-only control has an accessibility label. No icon
  library is added: the few icons needed are drawn in the app.
- **One top bar** on every paired screen (sessions, conversation, settings), with a back icon where there is a
  way back.
- **Notices by severity**: information, warning (paused, not connected, out of date) and error (failed, refused),
  each with its own tone and icon.
- **Session list**: a status dot and label per row. Attention states (waiting for you, asks for a choice, asks to
  close) are prominent. Working is quieter, and dormant is the quietest. Agent or shell is shown by an icon in place
  of the "Agent" line. Folder headings are lighter, there are fewer dividers, starting a session moves to a
  floating "New session" button (dimmed while a session can't be started), and an empty desktop gets an empty state.
- **Conversation**:
  - A tool call and its result are shown as one row with a success or failure mark. A run of consecutive tool
    calls is summarized as one collapsed line ("7 tool calls", plus how many failed), expandable.
  - Replies the user sent stay where they were sent, never inside a summarized run.
  - The user's message, and a reply not yet in the conversation, get a quiet bubble.
  - Working is shown with a small animated indicator, still driven only by the wait state.
  - "Jump to newest" becomes a small round button that does not cover the text.
- **Composer**: a rounded field with an attach icon and a filled send icon. Attachments show as compact cards with their
  state and a remove button.
- **Prompt card**: set apart with the accent. The decision that goes ahead (Allow once, Send answer, Approve) is a
  filled button and the one that stops is outlined.
- **Settings**: a connection card (state dot, desktop name, address in use; more than three addresses collapse
  behind "Show all"), then sections for notifications and battery, recent events as a compact list, and pairing at the
  end. **Unpair asks to confirm** first.
- **Pair screen**: a centered layout with scanning as the main action and pasting a link as the alternative.
  Errors show as error notices.
- **Dialogs**: rename's buttons sit in one row (Clear name on the left, Cancel and Rename on the right). The
  "Closing…/Hibernating…" dialogs show a progress indicator.
- Text stays English; localization is a later change.

## Capabilities

### New Capabilities

- `app-appearance`: the app is light or dark as the phone is set or as chosen in its settings, with legible system
  bars, and every
  control that shows no text has an accessibility label. Attention states are told apart by more than color.

### Modified Capabilities

- `device-pairing`: unpairing from the phone asks the user to confirm first.
- `desktop-connection`: the status screen may show how many addresses there are, and the one in use, until the
  user asks to see all of them in the order they are tried.
- `conversation-view`: a tool call and its result are shown together. A run of consecutive tool calls is
  summarized in one collapsed line that says how many calls it holds and how many failed.

## Impact

- `ui`: every screen (`SessionsScreen`, `ConversationScreen`, `PromptCard`, `SessionMenu`, `StatusScreen`,
  `PairScreen`, `Common`). New `ui/theme` (colors, typography, shapes, status colors) and `ui/Icons.kt` (vector
  icons).
- `desktop`: a pure grouping of conversation entries into display items (tool call with its result, runs of
  tools), unit-tested.
- `store`: a stored theme choice, kept apart from the pairing.
- `res`: `values/themes.xml`, `values-night/themes.xml`, colors; the manifest's theme; the launcher icon's
  background color.
- No new dependency, no protocol change, no change to what is sent to the desktop.
- Tests: JVM tests for the entry grouping (with reply anchors) and for the color schemes; instrumented tests of
  the unpair confirmation, the theme store and the appearance choice. Screens are checked by emulator screenshots in light and dark mode
  and by use on the phone; outcomes go in `docs/conversation-findings.md`.
