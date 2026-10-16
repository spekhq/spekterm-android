# session-hibernate-close Specification

## Purpose
Lets the user hibernate or close a session of the paired desktop from the phone, asking first before anything
that cannot be undone or that stops work under way, without changing what the desktop shows or where its
keyboard focus is.

## Requirements

### Requirement: Hibernate a running session

While the desktop accepts `sessions.hibernate`, a session that the desktop's list shows running — an agent or a
shell — SHALL offer to hibernate it, in its menu in the session list (`session-list`) and on its screen; a
dormant session SHALL NOT offer it. Hibernating SHALL be possible only while the phone is connected, the
connection is not paused, the shown list is current and the desktop has an open window. The app SHALL first ask
the desktop to hibernate the session without forcing it, and SHALL NOT ask the user before that request. The app
SHALL NOT change the session's state itself: the session SHALL be shown dormant when the desktop says so. The
desktop hibernates without changing its selection or keyboard focus, and the session can be woken again. A
dormant session's menu SHALL offer to wake it while the desktop accepts `sessions.wake`, so that a shell put to
sleep from the phone can be woken from the phone.

#### Scenario: Hibernating an idle session from the list

- **WHEN** the user long-presses a running agent session that waits for the user and chooses to hibernate it
- **THEN** the session is shown dormant in the list and on the desktop, and the desktop's selection and keyboard
  focus do not change

#### Scenario: Hibernating from the session's screen

- **WHEN** the user hibernates a session from its conversation screen
- **THEN** the screen shows the session as dormant with a way to wake it

#### Scenario: A dormant session

- **WHEN** the user long-presses a dormant session
- **THEN** its menu does not offer to hibernate it, and offers to wake it

#### Scenario: Waking a shell from the menu

- **WHEN** the user hibernates an idle shell session from the phone, then long-presses it and chooses to wake it
- **THEN** the shell runs again on the desktop

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

### Requirement: Force only after the user agrees, knowing why the session is busy

When the desktop refuses to hibernate or close a session because it is not idle, the app SHALL tell the user why,
as the desktop states it — the agent is working; it waits for an answer to a choice; a message to it has not been
confirmed sent; a command is running; or the desktop cannot tell — and SHALL ask whether to hibernate or close it
anyway, saying that what it is doing is stopped. A reason the app does not know SHALL be shown as the desktop not
being able to tell. Only when the user agrees SHALL the app send the request again, forcing it. The app SHALL NOT
force a request that was not first refused as busy and then agreed to by the user.

#### Scenario: Hibernating a working agent

- **WHEN** the user hibernates an agent that is working
- **THEN** the phone says the agent is working and asks whether to hibernate it anyway; nothing has stopped yet

#### Scenario: Agreeing to force

- **WHEN** the user then agrees
- **THEN** the session is hibernated, and the agent's work is stopped

#### Scenario: Declining to force

- **WHEN** the user then cancels
- **THEN** nothing more is sent, and the agent keeps working

#### Scenario: Closing a shell running a command

- **WHEN** the user confirms closing a shell session in which a command is running
- **THEN** the phone says a command is running and asks again whether to close it anyway

### Requirement: Say why a session could not be hibernated or closed

When the desktop refuses or does not answer, the app SHALL say why: the session no longer exists; the desktop has
no open window; the desktop did not answer in time, so it may still happen; the desktop could not do it, with the
desktop's reason; the phone is not connected, so the session list shows whether it happened; or another error, by
its code.

#### Scenario: The desktop's window is closed

- **WHEN** the desktop's window closes after the user opened a session's menu, and the user then chooses to
  hibernate it
- **THEN** the phone says spekterm has no open window on the desktop, and nothing is hibernated
