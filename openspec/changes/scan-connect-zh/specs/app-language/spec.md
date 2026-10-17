## Purpose

Shows the app's own texts in English or in Traditional Chinese (Taiwan), following the phone's or the app's
language, while what the desktop and its repositories send is shown as sent.

## ADDED Requirements

### Requirement: Show the app's texts in the user's language

The app SHALL show every text it writes itself — screen titles, labels, buttons, menus, dialogs, notices, empty
states, state labels, accessibility labels, notification titles and texts, notification channel names and
descriptions, and the share sheet's title — in Traditional Chinese (Taiwan) when the language Android resolves for
the app is Chinese in Traditional script (for example zh-TW, zh-Hant, zh-HK), and in English otherwise. Counts
SHALL use the language's plural forms, and dates and times SHALL use the language's format. A text of the app
that has no translation SHALL NOT exist: every English text SHALL have its zh-TW counterpart, with the same
placeholders. Where another capability quotes a text the app shows (for example "Waiting for you", "More options",
"The agent asked to close this session."), the quote is the English text; in Traditional Chinese the app SHALL show
its translation in the same place, and that SHALL satisfy the other capability.

#### Scenario: A phone set to Traditional Chinese

- **WHEN** the phone's language is 中文（台灣） and the user opens the session list, a conversation, the session
  menu, the OpenSpec screens, the handoff screen, settings and the pairing screen
- **THEN** every text the app writes on them is in Traditional Chinese, and none is left in English

#### Scenario: A notification in Traditional Chinese

- **WHEN** the phone's language is 中文（台灣） and an agent waits for the user
- **THEN** the notification's own text is in Traditional Chinese, and the session's name in it is as the desktop
  sent it

#### Scenario: Another language

- **WHEN** the phone's language is Japanese
- **THEN** the app's texts are in English

#### Scenario: A quoted text in Traditional Chinese

- **WHEN** the app's language is Traditional Chinese and an agent asks to close its session
- **THEN** the row and the conversation show the Traditional Chinese translation of "The agent asked to close this
  session.", with the Chinese labels for Close and Keep

### Requirement: Keep what the desktop sends as sent

The app SHALL NOT translate, reword or reorder text it receives: session and folder names, branch names, agent
messages, thinking, tool names and inputs, prompt texts and their options, handoff notes and reports, the
desktop's sentences (for example a sent attachment's), OpenSpec files and repository text, and error messages
from the desktop. Protocol error codes it shows in parentheses SHALL stay as they are. The connection log and its
export SHALL stay in English, whatever the app's language.

#### Scenario: A prompt in Traditional Chinese

- **WHEN** the app's language is Traditional Chinese and the agent asks a question whose options are in English
- **THEN** the card's own labels are in Traditional Chinese and the question and its options are as the desktop
  sent them

#### Scenario: The exported log

- **WHEN** the app's language is Traditional Chinese and the user exports the connection log
- **THEN** the exported entries are in English

### Requirement: Use the desktop's words

The Traditional Chinese texts SHALL use the terms of the desktop's own zh-TW translation for what both show —
among them session (kept as "session"), hibernate (休眠), wake (喚醒), handoff (交接), inbox (收件匣), waiting for
you (等你回應) and idle (閒置) — so a user sees the same word for the same thing on the phone and on the desktop.

#### Scenario: The same words as the desktop

- **WHEN** the app's language is Traditional Chinese and the user opens a dormant session's menu
- **THEN** it offers 喚醒 and the session is described as 休眠中, as the desktop's rail says

### Requirement: Choose the app's language

On Android 13 and later, the app SHALL appear in the system's per-app language settings, offering English and
Chinese (Taiwan), and SHALL follow a language chosen there instead of the phone's. A change of language SHALL take
effect on the app's screens without pairing again, without losing the connection, and without losing the screen
the user was on. Notifications posted after the change SHALL use the new language.

#### Scenario: Choosing Chinese for the app only

- **WHEN** the phone is in English and the user sets the app's language to 中文（台灣） in the system settings
- **THEN** the app's screens are in Traditional Chinese, the connection stays up, and the next notification is in
  Traditional Chinese
