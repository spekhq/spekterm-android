# session-create Specification

## Purpose
Lets the user start a new agent session on the paired desktop from the phone, in a folder of their choice,
without going to the desktop or taking over the desktop's selection or keyboard focus, and then work in that session from the phone.

## Requirements

### Requirement: Start an agent session in a chosen folder

The session list SHALL offer to start a new session. Choosing it SHALL let the user pick where: the global item
and every folder the desktop's current list names, in the desktop's order, each folder shown by its name and
its branch when the desktop reports one — folders that have no session at the time included. Picking one SHALL
ask the desktop to create an agent session there. Sessions SHALL NOT be created as shells from the phone. The
desktop creates the session without changing its selection or keyboard focus and without replacing a session
it shows. Starting a session SHALL be possible only while the phone is connected, the connection is not
paused, the shown list is current, the desktop's current list says it has an open window, and no other start
is in progress; otherwise the offer SHALL NOT be usable, and the session list SHALL say why as it already does
for those states. When starting stops being possible while the user is picking a folder, the choice SHALL
close.

#### Scenario: Starting a session in a folder without sessions

- **WHEN** the desktop has a folder with no session while another folder is selected on the desktop, and the
  user chooses to start a new session and picks that folder
- **THEN** the desktop creates an agent session in that folder, and the desktop's selection and keyboard focus
  do not change

#### Scenario: Starting a global session

- **WHEN** the user chooses to start a new session and picks the global item
- **THEN** the desktop creates an agent session that belongs to no folder

#### Scenario: Not connected

- **WHEN** the phone is not connected to the desktop, or the connection is paused
- **THEN** starting a new session is not possible, and the session list says the phone is not connected or
  that the connection is paused

#### Scenario: The connection drops while picking

- **WHEN** the connection ends while the user is choosing a folder
- **THEN** the choice closes and nothing is sent

### Requirement: Open the new session once the desktop lists it

While the desktop creates the session, the phone SHALL show that the session is being started and in which
folder, and SHALL NOT start another one until this one has an outcome. Leaving the session list SHALL NOT
abandon the request. Once the desktop has answered with the new session and a list from the same connection
holds it as running, the app SHALL open its conversation if the session list is on screen at that moment;
otherwise it SHALL NOT take the user away from what they are doing, and the session list SHALL offer to open
the new session when the user returns to it, without opening it by itself. When the desktop answered but no
such list arrived within 10 seconds of the answer, or the connection ended in that time, the app SHALL say that
the session was started and will appear in the list, and SHALL NOT open it. The app SHALL NOT repeat a request
to create a session on its own. Unpairing or pairing again SHALL drop any start in progress and its outcome.

#### Scenario: The new session opens

- **WHEN** the user starts a new session while the session list is on screen, and the desktop creates it
- **THEN** its conversation opens on the phone, and the session appears in the desktop's rail in the chosen
  folder

#### Scenario: The answer comes before the list

- **WHEN** the desktop answers the request before its next session list holds the new session running
- **THEN** the phone keeps showing that the session is being started until the list holds it, and does not say
  the session was closed or dormant

#### Scenario: The user went elsewhere meanwhile

- **WHEN** the user starts a new session and opens another session's conversation before the desktop lists the
  new one
- **THEN** the phone stays on the conversation the user opened, and back on the session list it says the
  session was started and offers to open it

#### Scenario: Not listed in time

- **WHEN** the desktop answers with the new session but no list holding it running arrives within 10 seconds
- **THEN** the phone says the session was started and will appear in the list, and does not open it

### Requirement: Say why a session could not be started

When the desktop does not create the session, the app SHALL say why, and SHALL keep the session list on
screen: that spekterm has no open window on the desktop; that the desktop could not create the session, with
the reason the desktop gave (a folder that no longer exists is one such reason). When the desktop did not
answer in time, or the connection ended before it answered, the app SHALL say that it cannot tell whether the
session was started and that the session list will show it if it was.

#### Scenario: The desktop cannot create it

- **WHEN** the desktop answers that it could not create the session, giving a reason
- **THEN** the phone says the session could not be started and shows the desktop's reason

#### Scenario: No window

- **WHEN** the desktop answers that it has no open window
- **THEN** the phone says spekterm has no open window on the desktop

#### Scenario: The connection drops during the request

- **WHEN** the connection ends after the request was sent and before the desktop answered
- **THEN** the phone says it cannot tell whether the session was started and that the list will show it if it
  was, and does not send the request again after reconnecting
