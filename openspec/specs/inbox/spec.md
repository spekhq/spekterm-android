# inbox Specification

## Purpose
Shows the desktop's inbox on the phone — handoffs scheduled for later, items waiting for the user, and accepted items
whose first prompt has not been sent — and lets the user act on them there: run now or cancel, choose the folder and
accept or dismiss, and send an accepted item's first prompt after reading it.

## Requirements

### Requirement: Hold the desktop's inbox

While the desktop lists `inbox.list` among its operations, the app SHALL read it after each successful authentication and SHALL
replace what it holds with every later `inbox.changed`, applying lists in the order the connection delivered them and dropping one
older than the list already applied. An answer or event that is not an object with an `items` array SHALL NOT replace the held
list. Within a list, an item that is not an object with a non-empty string `id` and `adapter` SHALL be skipped and the others kept;
an unknown field, an unknown `state` and an unknown `unresolved` value SHALL be ignored, not treated as errors. The held list SHALL
stay across a lost connection and while paused, and SHALL be forgotten when the user unpairs. Item text SHALL NOT be written to
storage or to the connection log.

#### Scenario: An item arrives while connected

- **WHEN** a Slack mention reaches the desktop's inbox while the phone is connected
- **THEN** the phone's inbox lists it without the user refreshing

#### Scenario: The connection is lost

- **WHEN** the phone holds three inbox items and the connection drops
- **THEN** the inbox still shows the three items, says it is not current and since when, and its actions are disabled

#### Scenario: One malformed item

- **WHEN** the desktop's list holds an item with no `id` among two valid ones
- **THEN** the inbox shows the two valid items

### Requirement: Show what is scheduled, what waits and what was opened

The Inbox screen SHALL show, in this order:

- **Waiting for approval**: every pending item whose `recurrence.role` is `"proposal"`, whether or not it carries
  `scheduledAt`, in the desktop's order, as the requirement "Show a proposal and decline it" says.
- **Scheduled**: every other pending item that carries `scheduledAt`, earliest first, each with the time it opens (date and time in the
  app's language), its origin, the folder it will open in when the desktop names one of its folders, its title unless the body
  already contains it, its full body and its length. An item whose time has passed SHALL say it is due and opening.
- **Waiting**: every other pending item without `scheduledAt`, in the desktop's order, each with its author, origin, the time it happened
  (none when the desktop sends none), its title unless the body already contains it, its full body, its length and the folder it
  will open in.
- **Already opened**: every accepted item whose session the current session list holds in one of its folders (or, with no current
  list or no window, as last known), each with the folder it opened in, its origin, its title, its full body and its length, and
  the state of its first prompt (next requirement but one); tapping its header SHALL open that session's conversation.

The body SHALL be shown whole, as plain text, without markup rendering, links or truncation, before any control that accepts, runs
or sends the item, and SHALL be selectable. A scheduled item SHALL NOT be counted as waiting and SHALL raise no notification while
it waits. The Inbox entry's number SHALL follow `session-list`'s rule (pending without `scheduledAt`), which includes a proposal
without `scheduledAt`, as on the desktop's badge. An item whose contents expired SHALL say its contents are no longer available and SHALL offer only Dismiss. With
no item in any section the screen SHALL say nothing is waiting.

#### Scenario: A scheduled handoff

- **WHEN** an agent hands work off to tomorrow at 09:00 and the user opens the Inbox
- **THEN** it is listed under Scheduled with tomorrow's date and 09:00, its origin, its folder and its full body, and the Inbox
  entry's count does not include it

#### Scenario: A proposal is counted

- **WHEN** the desktop's inbox holds one Slack mention waiting and one repeating handoff waiting for approval
- **THEN** the Inbox entry's count is 2, the mention is under Waiting and the proposal under Waiting for approval

#### Scenario: A Slack mention whose title repeats its body

- **WHEN** a pending Slack mention's title is the first line of its body
- **THEN** the card shows the body once, without a separate title line

#### Scenario: A long body

- **WHEN** a scheduled handoff's body is 15 000 characters long
- **THEN** its card shows all of it, scrolling with the list, and its length

#### Scenario: A body with a link

- **WHEN** a pending item's body holds a URL and markdown image syntax
- **THEN** both are shown as literal text, and tapping them opens nothing

### Requirement: Choose where an item opens

Each item waiting for the user SHALL show the folder it will open in and SHALL let the user choose another of the desktop's
folders. The folder first selected SHALL be: the user's choice on this screen, if they made one — and, if that folder is no longer
one of the desktop's folders, none; otherwise the folder of the session an earlier accept of the item left, if it is still one of
the desktop's folders; otherwise the desktop's suggestion (`folderId`), if it is one of the desktop's folders; otherwise none. With
none, the card SHALL say why: the folder the user chose is no longer in the workspace; the folder the item's rule names is no
longer in the workspace; or the desktop cannot tell which folder the item belongs to. The app SHALL NOT select a folder of its own.

#### Scenario: The desktop suggests a folder

- **WHEN** a pending item carries a `folderId` of one of the desktop's folders
- **THEN** that folder is selected

#### Scenario: No rule matches

- **WHEN** a pending item has no `folderId` and `unresolved` is `NO_MATCH`
- **THEN** no folder is selected, Accept is disabled, and the card says the desktop cannot tell which folder it belongs to

#### Scenario: The chosen folder goes away

- **WHEN** the user chose folder B for an item, the item also has a suggested folder A, and B is removed from the desktop's
  workspace
- **THEN** no folder is selected, Accept is disabled, and the card says the folder chosen is no longer in the workspace

### Requirement: Accept, dismiss, run now and cancel

The app SHALL offer for an item waiting for the user **Accept**, enabled only with a folder selected, sending `inbox.accept` with
that folder, and **Dismiss**, sending `inbox.ignore` after the user confirms; and for a scheduled item **Run now**, sending
`inbox.accept` with the folder the desktop names, and **Cancel**, sending `inbox.ignore` after the user confirms. Accept and Run now
SHALL be enabled only while the desktop lists `inbox.accept`, the phone is connected and not paused, and the current session list
has a window; Run now additionally only when the desktop names one of its folders for the item, the card otherwise saying why.
Dismiss and Cancel SHALL be enabled only while the desktop lists `inbox.ignore` and the phone is connected and not paused. While an
action on an item is in progress, that item's actions SHALL be disabled.

When Accept or Run now succeeds, the card SHALL say it is opening, and the conversation of the session the desktop answers SHALL
open, with the Inbox behind it, once the current session list holds that session (running or dormant) and only if the Inbox is
still on screen; if the Inbox was left, the card SHALL link to the session instead; if the session is not listed within 10 seconds,
the card SHALL say it was opened on the desktop and is not listed yet, link to it, and open nothing by itself; if the connection ends
first, the card SHALL say so and link to it.

When an action fails, the item SHALL say why in the user's words — choose a folder first; could not open it, with the desktop's
message (also for an accept's `NOT_FOUND` while the item is still held); the item is no longer in the inbox; the desktop has no window; the
desktop did not answer in time; not connected; approve it on the desktop (`APPROVE_ON_DESKTOP`) — and nothing SHALL be retried by
itself. The reason SHALL stay with the item, also when the item moves to another section. Each action SHALL be logged with its
operation and outcome, never with item text.

#### Scenario: Accepting a Slack mention

- **WHEN** the user reads a pending mention, keeps the suggested folder and taps Accept
- **THEN** the card says it is opening, and once the desktop lists the new session the phone shows its conversation

#### Scenario: The list is slow

- **WHEN** the desktop answers an accept but its session list does not show the session within 10 seconds
- **THEN** the card says the item was opened on the desktop and is not listed yet, with a link, and no conversation opens by itself

#### Scenario: Running a scheduled handoff now

- **WHEN** the user taps Run now on a scheduled handoff
- **THEN** the desktop opens its session at once, the phone shows its conversation once listed, and the item leaves Scheduled

#### Scenario: A scheduled handoff whose folder is gone

- **WHEN** a scheduled handoff's target folder was removed from the desktop's workspace
- **THEN** its Run now is disabled and the card says the folder is no longer in the workspace; Cancel stays available

#### Scenario: Cancelling a scheduled handoff

- **WHEN** the user taps Cancel on a scheduled handoff and confirms
- **THEN** the item leaves the inbox and never opens

#### Scenario: Dismiss not confirmed

- **WHEN** the user taps Dismiss on a pending item and then backs out of the confirmation
- **THEN** nothing is sent and the item stays

#### Scenario: The desktop is opening the handoff already

- **WHEN** the user taps Run now on a handoff whose time has just come and the desktop answers `CREATE_FAILED`
- **THEN** the item's card says it could not be opened, with the desktop's message, and no second session is opened

#### Scenario: Accepting what the desktop holds as a proposal

- **WHEN** an item's `recurrence` was malformed, so the phone shows it under Waiting, and the desktop answers its Accept with
  `APPROVE_ON_DESKTOP`
- **THEN** the card says to approve it on the desktop, and the item stays

#### Scenario: Paused

- **WHEN** the connection is paused
- **THEN** the items stay readable and Accept, Dismiss, Run now and Cancel are disabled

### Requirement: Send an opened item's first prompt

For an item under Already opened whose session carries a `prefill` naming that item, the card SHALL show, as plain text, the prompt
the desktop filled in, and:

- when `prefill.edited` is false, the desktop lists `sessions.submitPrefill`, the phone is connected and not paused, and the
  current session list has a window: **Send the first prompt**, which sends `sessions.submitPrefill` with the session and the
  prefill's id, only on the user's tap — never as part of accepting, never automatically, never from a notification;
- when `prefill.edited` is true: no send control, and a line saying something else was typed there too and to finish it at the
  desktop.

For an opened item whose session carries no `prefill` and is running, the card SHALL say the desktop is filling in the first prompt
when the desktop lists `sessions.submitPrefill`, and otherwise that the first prompt waits on the desktop and is sent there; for one
whose session is not running, it SHALL say nothing about a prompt. After a send, the card
SHALL say: the agent started (`confirmed`); or sent but the desktop has not seen the agent start yet, keeping the control (`sent`);
or the prompt is no longer waiting (`PREFILL_GONE`); or to finish at the desktop (`PREFILL_EDITED`); or the agent cannot take it now
(`WAIT_STATE`); or the session is not running (`NOT_FOUND`); other failures as for Accept.

#### Scenario: Sending a Slack mention's prompt from the phone

- **WHEN** the user accepted a mention on the phone, its card under Already opened shows the body and the filled-in prompt, and the
  user taps Send the first prompt
- **THEN** the agent starts working on it, the card says so, and the item leaves the inbox

#### Scenario: Typed into at the desktop

- **WHEN** the session's `prefill` says it was edited
- **THEN** the card shows the prompt, no send control, and says to finish it at the desktop

#### Scenario: An older desktop

- **WHEN** the paired desktop does not list `sessions.submitPrefill` and an item was accepted from the phone
- **THEN** its card under Already opened shows no send control and says the first prompt waits on the desktop

### Requirement: Read a repeating handoff

The app SHALL read an item's `recurrence` when it is an object whose `role` is a string, keeping that role (`"proposal"`,
`"occurrence"`, or any other value as unknown), its `repeat` when it is a string, its `until` when it is a number, and the numbers
of its `next` array. A `recurrence` that is not such an object SHALL be ignored as an unknown field, and the item kept. What an item is SHALL be
decided by `recurrence.role`, never by the presence of `scheduledAt`; an item with an unknown role or no `recurrence` SHALL be
shown as an item without one.

#### Scenario: A failed time of a recurrence

- **WHEN** a pending item carries `recurrence.role` `"occurrence"` and no `scheduledAt`
- **THEN** it is listed under Waiting, not under Waiting for approval

#### Scenario: A malformed recurrence

- **WHEN** a pending item carries `recurrence: "weekly"`
- **THEN** the item is listed as if it had no `recurrence`

#### Scenario: A proposal without its expression

- **WHEN** a pending item carries `recurrence: { "role": "proposal" }`
- **THEN** it is under Waiting for approval, without a schedule line, and offers no Accept

### Requirement: Show a proposal and decline it

Each item under Waiting for approval SHALL show, before any control: that it is a repeating handoff; its `repeat` as sent, marked
as the desktop's time; its `next` times later than now, as dates and times in the app's language; its `until` as a date and time,
or "until stopped" without one; its origin, and the folder it would open in when the desktop names one of its folders; its title
unless the body contains it, its full body and its length; and one of: when its `until` is not later than now, or it has an
`until` and the desktop sent no `next` time, that its end time has passed and it can no longer be approved, with no times; when no
`next` time is later than now otherwise, that the next times
are shown on the desktop and it is approved there; else that it is approved on the desktop. It SHALL NOT offer Accept, or any
control that sends `inbox.accept`. It SHALL offer **Decline**, sending `inbox.ignore` after the user confirms that it will not run,
enabled as Dismiss is, its failures shown as for Dismiss.

#### Scenario: An agent proposes a weekly handoff

- **WHEN** an agent proposes `0 21 * * 3` with no end and the user opens the Inbox
- **THEN** it is under Waiting for approval with `0 21 * * 3`, the next three Wednesdays at 21:00, "until stopped", its full body,
  a line saying to approve it on the desktop, and Decline but no Accept

#### Scenario: Declining a proposal

- **WHEN** the user taps Decline on a proposal and confirms
- **THEN** `inbox.ignore` is sent and the proposal leaves the inbox with the next list

#### Scenario: Times that have passed

- **WHEN** a proposal's `next` holds one time an hour ago and two next week
- **THEN** the card shows only the two times next week

#### Scenario: A proposal past its end

- **WHEN** a proposal's `until` was yesterday and its `next` is empty
- **THEN** the card says its end time has passed and it can no longer be approved, shows no times, and offers Decline

### Requirement: Show a recurrence's times and skip one

A scheduled item whose `recurrence.role` is `"occurrence"` SHALL show its `repeat` as sent, marked as the desktop's time, and its
`until` when present, beside its time; its Cancel SHALL read **Skip this time**, and its confirmation SHALL say that only this
time is skipped and skipping does not stop the repeating handoff — never that a next time is scheduled, which the desktop does not
promise (none follows the last time before `until`, a recurrence stopped on the desktop, or a full inbox). A pending occurrence
without `scheduledAt` (its opening failed) SHALL be shown under Waiting as an ordinary item, marked as part of a repeating handoff
with its `repeat`, and SHALL NOT say whether a next time follows; its Dismiss SHALL read **Skip this time**, with the same
confirmation. With an empty `repeat`, a card SHALL leave out the schedule rather than show an empty one. Run now and Accept on an occurrence SHALL be as for any scheduled or waiting item.

#### Scenario: Skipping one Wednesday

- **WHEN** an approved `0 21 * * 3` recurrence's next time waits under Scheduled and the user taps Skip this time and confirms
- **THEN** `inbox.ignore` is sent, the item leaves, and the following Wednesday's time takes its place with the next list

#### Scenario: A time that failed to open

- **WHEN** an occurrence's session could not be opened and it waits for the user without `scheduledAt`
- **THEN** it is under Waiting with its folder picker and Accept, says it is part of a repeating handoff with its schedule, and
  offers Skip this time instead of Dismiss
