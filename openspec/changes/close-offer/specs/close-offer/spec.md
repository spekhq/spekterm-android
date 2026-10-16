## Purpose

Shows on the phone that an agent on the paired desktop asked to close its own session, and lets the user answer
it from the phone — close the session or keep it — as the desktop's own offer does.

## ADDED Requirements

### Requirement: Show an agent's offer to close its session

When the desktop's session list marks a running agent session as offered for closing (`closeOffered: true`), the
app SHALL show "The agent asked to close this session." on that session's row in the session list and on its
conversation screen, wherever that screen still shows the conversation — also while the connection is paused or
not connected, from the last list held. On the row, the offer SHALL take the place of
"waiting for input"; a session that also asks for a choice or is working SHALL keep showing that state, with the
offer. The app SHALL show the offer exactly while the latest list it holds marks it:
it SHALL NOT keep an offer of its own, so an offer that the desktop withdraws — the user keeps the session on the
desktop or from another device, types into it, or its process ends — SHALL disappear from the phone when the
desktop's list no longer marks it. The mark's absence, any value other than `true`, a dormant session and a shell
session SHALL NOT be shown as offered, and an unexpected value SHALL NOT drop the session from the list.

#### Scenario: An agent asks to close itself

- **WHEN** an agent on the desktop asks to close its own session and its turn ends
- **THEN** the phone's list shows "The agent asked to close this session." on that session's row with "Close" and
  "Keep", and its conversation screen shows the same

#### Scenario: Kept on the desktop

- **WHEN** the user chooses Keep on the desktop's card while the phone shows the offer
- **THEN** the offer disappears from the phone's row and conversation screen, and the session stays running

#### Scenario: Typed into on the desktop

- **WHEN** the user types into the offered session on the desktop
- **THEN** the offer disappears from the phone

#### Scenario: A reply from the phone

- **WHEN** the user sends a reply from the phone to an offered session
- **THEN** the offer disappears once the desktop's list no longer marks it

#### Scenario: A malformed mark

- **WHEN** the desktop's list holds a session with `closeOffered` set to something other than `true`
- **THEN** the session is listed as usual and is not shown as offered

#### Scenario: An offered session that also asks for a choice

- **WHEN** an offered session's agent waits on a choice
- **THEN** its row still says it asks for a choice, and shows the offer with it

### Requirement: Close or keep from the offer

Wherever the app shows an offer, it SHALL offer "Close" while the desktop accepts `sessions.close`, and "Keep" while
the desktop accepts `sessions.keep`; neither SHALL be shown otherwise. Both SHALL be usable only while the phone is
connected, the connection is not paused, the shown list is current and the desktop has an open window. Choosing
Close SHALL ask the desktop to close the session without forcing it and SHALL NOT ask the user to confirm again:
choosing Close on the offer is the confirmation. When the desktop refuses because the session is not idle, the app
SHALL tell the user why and close it only if the user agrees, as for any close (`session-hibernate-close`). Closing
from the session's own screen SHALL return to the session list once the desktop has closed it. Choosing Keep SHALL
ask the desktop to keep the session, and the phone SHALL stop showing the offer when the desktop's list no longer
marks it. When the desktop refuses or does not answer Keep, the app SHALL
say why: the session no longer exists; the desktop has no open window; the desktop did not answer in time, so its own card may still show there; the desktop could not do it, with its reason; the phone is not connected; or another error, by
its code.

#### Scenario: Keeping from the phone

- **WHEN** the user chooses "Keep" on an offered session's row
- **THEN** the offer disappears from the phone and from the desktop, and the session keeps running

#### Scenario: Keeping from the conversation screen

- **WHEN** the user chooses "Keep" on the offer in the session's conversation screen
- **THEN** the phone stays on that screen, and the offer goes from it

#### Scenario: Closing from the list

- **WHEN** the user chooses "Close" on an offered session's row
- **THEN** the session is closed without a further confirmation, and leaves the list on the phone and the desktop

#### Scenario: Closing from the conversation screen

- **WHEN** the user chooses "Close" on the offer in the session's conversation screen
- **THEN** the session is closed and the phone returns to the session list

#### Scenario: The session is busy after all

- **WHEN** the user chooses "Close" on an offer and the desktop answers that the session is not idle
- **THEN** the phone says why and asks whether to close it anyway; nothing is closed unless the user agrees

#### Scenario: An older desktop

- **WHEN** the desktop does not accept `sessions.keep` and marks no session as offered
- **THEN** the phone shows no offer and no "Keep" anywhere

#### Scenario: A mark without Keep

- **WHEN** the desktop marks a session as offered but does not accept `sessions.keep`
- **THEN** the phone shows the offer with "Close" and without "Keep"

#### Scenario: Not connected

- **WHEN** the phone is not connected, or its connection is paused, and its last list shows an offer
- **THEN** the offer is shown on the row and on the conversation screen with "Close" and "Keep" not usable, and
  they become usable once the list is current again and still marks the offer
