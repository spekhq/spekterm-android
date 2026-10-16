## MODIFIED Requirements

### Requirement: Close a session only after the user confirms

While the desktop accepts `sessions.close`, every session the desktop lists — an agent or a shell, running or
dormant — SHALL offer to close it, in its menu in the session list and on its screen, under the same conditions
as hibernating. Before anything is sent, the app SHALL ask the user to confirm, naming the session and saying
that it leaves the desktop for good and that its attachments not yet sent are removed. Only after the user
confirms SHALL the app ask the desktop to close it, without forcing it. Choosing "Close" on an agent's offer to
close its own session (`close-offer`) SHALL count as that confirmation, and SHALL NOT be asked again; the menu's
close SHALL still ask. The session SHALL leave the list when the desktop's list no longer holds it. Closing a
session from its own screen SHALL return to the session list once the desktop has closed it.

#### Scenario: Closing an idle session

- **WHEN** the user chooses to close an idle session and confirms
- **THEN** the session leaves the list on the phone and on the desktop

#### Scenario: Changing one's mind

- **WHEN** the user chooses to close a session and then cancels
- **THEN** nothing is sent to the desktop, and the session stays

#### Scenario: Closing from the session's screen

- **WHEN** the user closes a session from its conversation screen and confirms
- **THEN** the phone returns to the session list, which no longer holds the session

#### Scenario: The menu's close of an offered session

- **WHEN** the user chooses "Close session" in the menu of a session whose agent offered to close it
- **THEN** the phone asks to confirm first, as for any session
