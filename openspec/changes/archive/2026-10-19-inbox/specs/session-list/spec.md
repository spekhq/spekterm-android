## ADDED Requirements

### Requirement: Open the inbox from the session list

While the desktop lists `inbox.list` among its operations, or the app holds inbox items, the session list's top bar SHALL carry an
Inbox control that opens the Inbox screen (`inbox`). It SHALL show the number of items waiting for the user — pending items
without `scheduledAt` — when that number is not zero, and its accessibility label SHALL include that number. Scheduled and opened
items SHALL NOT be counted. The control SHALL stay while the connection is down or paused.

#### Scenario: Two mentions and a scheduled handoff

- **WHEN** the desktop's inbox holds two pending mentions and one scheduled handoff
- **THEN** the Inbox control shows 2

#### Scenario: Nothing waiting

- **WHEN** the desktop's inbox holds only a scheduled handoff
- **THEN** the Inbox control shows no number, and opening it shows the scheduled handoff

#### Scenario: A desktop without an inbox

- **WHEN** the paired desktop does not list `inbox.list` and no item is held
- **THEN** the top bar has no Inbox control
