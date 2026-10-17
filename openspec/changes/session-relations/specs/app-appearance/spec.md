## MODIFIED Requirements

### Requirement: State is not told by color alone

Wherever the app shows a session's state (waiting for you, asks for a choice, asks to close, working, idle,
dormant), it SHALL say it in words too; color and emphasis SHALL only add to the words. A notice's own text SHALL say
what happened; its color SHALL NOT be the only sign that something failed. States that need the user (waiting for
you, asks for a choice, asks to close) SHALL be emphasized over working and idle, and working and idle over dormant.
Idle SHALL NOT use the color of the states that need the user.

#### Scenario: A list of mostly dormant sessions

- **WHEN** the list holds several dormant sessions and one that waits for the user
- **THEN** the waiting session's row says "Waiting for you" with more emphasis than the dormant rows' "Dormant"

#### Scenario: An idle agent next to a waiting one

- **WHEN** the list holds one agent waiting for the user and one idle agent the user has already looked at
- **THEN** the first says "Waiting for you" in the attention color and the second says "Idle", calm, without it
