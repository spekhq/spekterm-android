## Purpose

How the app looks across its screens: it is light or dark as the phone or the user chooses, keeps the system bars legible,
labels every control that shows no text, and never tells a session's state by color alone.

## ADDED Requirements

### Requirement: Light or dark, as the phone or the user chooses

The app SHALL let the user choose its appearance in its settings: follow the phone's setting (the default), always
light, or always dark. While it follows the phone, the app SHALL be dark while the phone is in dark mode and light
otherwise; otherwise it SHALL be as chosen whatever the phone's setting. The choice SHALL apply to every screen,
SHALL be kept across restarts of the app and the phone, and SHALL NOT be reset by unpairing, pairing again, or the desktop revoking the phone. On
Android 12 and later the window shown while the app starts SHALL follow the choice too; before Android 12 it MAY
follow the phone's setting. A change of the choice or of the phone's setting while the app is open SHALL apply
without the user restarting the app and without losing what is on screen (the open conversation, a typed draft,
the scroll position).

#### Scenario: Dark mode

- **WHEN** the app follows the phone, the phone is in dark mode, and the user opens the app
- **THEN** the starting window and every screen are dark

#### Scenario: Dark in the app, light on the phone

- **WHEN** the phone is in light mode and the user chooses dark in the app's settings
- **THEN** every screen turns dark at once, and after the app is closed and opened again it starts dark (on
  Android 12 and later, from its starting window)

#### Scenario: Back to the phone's setting

- **WHEN** the user who chose dark chooses to follow the phone again while the phone is in light mode
- **THEN** the app turns light

#### Scenario: Switching while a draft is open

- **WHEN** the user has scrolled up in a conversation, typed a reply, and switches the phone from dark to light mode
- **THEN** the screen turns light and still shows the same part of the conversation and the typed reply

### Requirement: Legible system bars

The status bar and the navigation bar SHALL show the app's own background behind them, and their icons SHALL
contrast with it whether the app is light or dark, whichever way that was decided. No content or control of the app
SHALL be hidden under them or under the on-screen keyboard.

#### Scenario: Light

- **WHEN** the app is light and the session list is on screen
- **THEN** the clock, battery and other status icons are dark on a light background

#### Scenario: Dark

- **WHEN** the app is dark and the session list is on screen
- **THEN** the status icons are light on a dark background

#### Scenario: Dark chosen on a light phone

- **WHEN** the phone is in light mode and the user chose dark in the app
- **THEN** the status icons are light on the app's dark background

### Requirement: Controls that show no text have a label

Every control that shows only an icon SHALL carry an accessibility label that says what it does (for example
"Back", "More options", "New session", "Send", "Attach", "Jump to newest", "Remove attachment"). An icon that only
repeats text shown next to it SHALL NOT be announced again.

#### Scenario: The overflow button

- **WHEN** a screen reader focuses the overflow button on the session list
- **THEN** it announces "More options", not a symbol

### Requirement: State is not told by color alone

Wherever the app shows a session's state (waiting for you, asks for a choice, asks to close, working, dormant), it
SHALL say it in words too; color and emphasis SHALL only add to the words. A notice's own text SHALL say what
happened; its color SHALL NOT be the only sign that something failed. States that need
the user (waiting for you, asks for a choice, asks to close) SHALL be emphasized over working, and working over
dormant.

#### Scenario: A list of mostly dormant sessions

- **WHEN** the list holds several dormant sessions and one that waits for the user
- **THEN** the waiting session's row says "Waiting for you" with more emphasis than the dormant rows' "Dormant"
