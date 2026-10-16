## ADDED Requirements

### Requirement: Offer a control only when the desktop accepts its operation

The app SHALL keep the list of operations the desktop's answer to `hello` or `pair` names, from the latest
authentication, and SHALL show renaming a session, attaching files and answering prompts only when the desktop
listed `sessions.rename`, `attachments.put` and `conversation.answer` respectively. An answer that names no
operations SHALL count as listing none of them, and the app SHALL then behave as it did before these controls
existed. The list SHALL be kept while the phone is disconnected — the controls stay, unusable until the phone
reconnects — and SHALL be forgotten when the user unpairs or pairs again.

#### Scenario: An older desktop

- **WHEN** the phone authenticates with a desktop whose answer names no operations, and the agent asks for a
  choice
- **THEN** the conversation offers no rename, no attach and no answers, and says to answer the choice on the
  desktop

#### Scenario: A desktop that offers them

- **WHEN** the phone authenticates with a desktop whose answer lists `sessions.rename`, `attachments.put` and
  `conversation.answer`
- **THEN** the conversation screen offers renaming, attaching and, for a prompt the desktop says can be answered,
  the answers

#### Scenario: Disconnected

- **WHEN** the connection to a desktop that offers attaching ends while a conversation is on screen
- **THEN** the attach control stays on screen but cannot be used until the phone reconnects
