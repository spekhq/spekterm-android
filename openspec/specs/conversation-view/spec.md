# conversation-view Specification

## Purpose
Shows one agent session's conversation on the phone — what the user and the agent said, the agent's tool calls
and their results — kept current while it is on screen, and honest about whatever it cannot show.

## Requirements

### Requirement: Show the conversation of an agent session

The conversation screen SHALL show the session's entries in order, newest last: the user's messages, the agent's
text, the agent's thinking, its tool calls, each shown by its kind as `tool-call-display` says, and their results
(whether each succeeded or failed). A tool call and the result the desktop gives for it (the same tool call id)
SHALL be shown as one item, marked as succeeded or failed once the result is there; a result whose call is not
shown SHALL be shown on its own. Two or more tool calls in a row — with only their results, thinking, background-task notices and the agent CLI's
note about a picture it read (`tool-call-display`) between them — SHALL be summarized in one collapsed item that says how many calls it holds, how many failed, and what the
latest call was; expanding it SHALL show each call. A reply of the user's shown between tool calls
(`conversation-reply`) SHALL end the run there, so that the reply stays where it was sent; a message from another
session, a subagent's report, and a message or files the agent sent (`tool-call-display`) SHALL end the run too, so
that none is ever hidden inside one. Thinking and tool results SHALL be collapsed until the user
expands them. Messages the agent itself injected rather than the user typing them SHALL be marked as such, except the agent CLI's
note about a picture, which is not shown (`tool-call-display`), and its fixed caveat before a built-in command,
which SHALL NOT be shown (requirement "Show a built-in command's output as the command's output"); those exchanged with
other sessions, subagents and the user SHALL be shown as messages from their sender (`tool-call-display`). New
entries SHALL appear as the desktop delivers them, without the user refreshing; a new tool call that extends a
summarized run SHALL update the summary without collapsing it if the user expanded it. When the user has scrolled
up, new entries SHALL NOT move the view, and the screen SHALL offer to jump to the newest.

#### Scenario: Reading a conversation

- **WHEN** the user opens a running agent session that has a conversation
- **THEN** the screen shows its entries, newest last, with thinking and tool results collapsed

#### Scenario: The agent answers while the screen is open

- **WHEN** the agent writes a new message while the conversation is on screen and scrolled to the newest entry
- **THEN** the message appears without the user doing anything, and the view follows it

#### Scenario: A run of tool calls

- **WHEN** the agent made seven tool calls in a row, one of which failed, and then wrote its answer
- **THEN** the screen shows one item saying "7 tool calls", that one failed, and the latest call, followed by the
  answer; expanding the item shows the seven calls, each marked succeeded or failed

#### Scenario: A single tool call

- **WHEN** the agent made one tool call between two messages
- **THEN** the call is shown as one item with its name, its one-line summary (`tool-call-display`) and whether it
  succeeded, with its result collapsed

#### Scenario: A reply sent while the agent made tool calls

- **WHEN** the user sent a reply after the agent's third tool call in a row, the agent made two more calls, and the
  reply is then marked as not in the conversation
- **THEN** the screen shows the first three calls, then the reply, then the other two calls; no item holds calls
  from both sides of the reply

#### Scenario: A message arrives during a run

- **WHEN** the agent made two tool calls, a background task's notice and then another session's message arrived, and
  the agent made two more calls
- **THEN** the screen shows a run of the first two calls, the notice's line, the message, then a run of the other two
  calls

#### Scenario: A notice between two calls

- **WHEN** a background task's notice arrived between two of the agent's tool calls
- **THEN** the screen shows one run of the two calls, holding the notice

#### Scenario: A run that grows while expanded

- **WHEN** the user expanded a run of tool calls and the agent makes another call
- **THEN** the run stays expanded and shows the new call, and its count goes up by one

#### Scenario: A picture read inside a run

- **WHEN** the agent ran a command, read a screenshot (followed by the CLI's note about its sizes), and ran another
  command
- **THEN** the screen shows one item saying "3 tool calls", and no injected message

#### Scenario: A command's caveat

- **WHEN** the user ran "/effort low" and the conversation holds the CLI's caveat entry before it
- **THEN** the screen shows "/effort low" and its output, and nothing for the caveat

### Requirement: Messages appear whole, and working is shown from the wait state

Each entry SHALL appear whole when it arrives. The app SHALL NOT simulate content being produced — no
character-by-character reveal, no delay or transition whose length depends on the entry's length, nothing that
claims progress within a message. Whether the agent is working SHALL be shown from the wait state the desktop
reports, and SHALL disappear when the agent stops working.

#### Scenario: A long and a short message

- **WHEN** a long message and a short message arrive
- **THEN** each appears completely at once, in the same way

#### Scenario: Working

- **WHEN** the desktop reports the agent as working
- **THEN** the screen shows that the agent is working, and stops showing it once the desktop reports otherwise

### Requirement: Agent text loads no remote content

Agent and user text MAY be rendered as Markdown, but rendering SHALL NOT fetch anything over the network:
images SHALL NOT be loaded, and nothing in the text SHALL cause a request without the user acting. HTML in the
text SHALL NOT be rendered as HTML, and SHALL NOT be dropped either: tags and HTML blocks SHALL be shown as the
text they are. A link SHALL open only when the user taps it, only for `http` and `https`
addresses, and only in a separate browser; links of any other scheme SHALL do nothing. Thinking, tool arguments and tool
results SHALL be shown as plain text, except the body of a message received from another session or a
subagent's report (`tool-call-display`), which follows the rules above for agent text.

#### Scenario: An image in agent text

- **WHEN** an agent message contains a Markdown image pointing to a web address
- **THEN** the phone makes no request to that address

#### Scenario: Angle brackets in agent text

- **WHEN** an agent message mentions `Vec<T>`, or a user entry holds a slash command recorded as
  `<command-name>/opsx:continue</command-name>`
- **THEN** the text shows those characters as written, and nothing of it is missing

#### Scenario: A link of another scheme

- **WHEN** the user taps a link in agent text whose scheme is not `http` or `https`
- **THEN** nothing is opened

#### Scenario: An image in a subagent's report

- **WHEN** a subagent's report, shown expanded, contains a Markdown image pointing to a web address
- **THEN** the phone makes no request to that address

### Requirement: Say when the content is incomplete

The conversation screen SHALL say, rather than present the content as complete:

- that it is catching up, while the desktop has not yet delivered the conversation;
- that older entries are not shown, when the desktop did not load them or the phone no longer holds them;
- that the conversation cannot be read, when the desktop reports it unavailable, pointing to the desktop;
- that the phone is not connected, that the connection is paused (with a way to resume), or that the desktop's
  window went away — saying in each case that what is shown may be out of date.

When the desktop has delivered the conversation and it holds no entries and the user has sent no reply, the
screen SHALL say that there are no messages yet, rather than showing an empty screen.

#### Scenario: Opening a conversation

- **WHEN** the user opens a conversation and the desktop has not yet delivered it
- **THEN** the screen says it is catching up, not that the conversation is empty

#### Scenario: A long conversation

- **WHEN** the desktop delivers a conversation whose older entries were not loaded
- **THEN** the screen says older entries are not shown

#### Scenario: Disconnected while reading

- **WHEN** the phone loses its connection while a conversation is on screen
- **THEN** the screen keeps the entries it has and says the phone is not connected and they may be out of date

#### Scenario: A new session

- **WHEN** the user opens the conversation of an agent session in which nothing has been said yet
- **THEN** the screen says there are no messages yet

### Requirement: Watch only while on screen, and resume after a reconnect

The app SHALL watch a session's conversation only while its conversation screen is visible, and SHALL stop
watching it when the screen is no longer visible — the user leaves it, switches app, or the screen turns off —
because a watched session counts as in use on the desktop and is not put to sleep. When the screen becomes
visible again, or the connection returns while it is visible, the app SHALL watch again from the last entry it
holds, so that it receives only the entries it missed, none repeated and none lost; when the desktop can no
longer resume from that entry, the app SHALL replace what it holds with what the desktop sends. When the
desktop replaces the conversation (for example, the user cleared it in the agent), the app SHALL replace what
it holds. Entries delivered again after a resume SHALL NOT be shown twice. Whether older entries are not shown
SHALL be judged from the desktop's replacing deliveries and from what the phone itself dropped, not from
appended ones. The app SHALL NOT write conversation content — entries, replies or drafts — to the phone's
storage, the connection log, or a notification; it holds them in memory only.

#### Scenario: Wi-Fi drops while reading

- **WHEN** the phone loses its connection while a conversation is on screen, the agent writes two messages,
  and the connection returns
- **THEN** both messages appear once, in order, and no earlier entry is repeated

#### Scenario: Leaving and returning quickly

- **WHEN** the user turns the screen off and on again while a conversation is open, and the agent writes a
  message meanwhile
- **THEN** every entry is shown once, and the new message appears

#### Scenario: A long conversation grows

- **WHEN** the desktop delivered a conversation whose older entries were not loaded, and the agent then writes
  a message
- **THEN** the screen still says older entries are not shown

#### Scenario: Leaving the screen stops watching

- **WHEN** the user goes back from the conversation to the session list
- **THEN** the phone no longer watches that conversation

#### Scenario: The desktop's window reloads

- **WHEN** the desktop's window reloads while a conversation is on screen, which stops the session
- **THEN** the screen says the desktop's window went away, then shows the session as dormant with a way to wake
  it, and after waking the conversation resumes from where it was

### Requirement: A dormant session shows as dormant and can be woken

A session that is not running SHALL be shown as dormant, with a way to wake it, and SHALL NOT be shown as an
empty conversation. Waking SHALL start the session on the desktop without taking over the desktop's display;
once the desktop reports it running, its conversation SHALL be shown. When waking fails, the screen SHALL say
why: the desktop has no open window, the desktop could not start the session (with the desktop's reason), the
desktop did not answer in time, or the session no longer exists. A watched session that is put to sleep SHALL be
shown as dormant, and its conversation SHALL be watched again once it runs. A session that is closed, or whose
process ended on its own (the desktop then removes it), while its conversation is open SHALL be shown as no
longer on the desktop. When the desktop ends the watch saying why, the screen SHALL follow that reason at once:
the desktop's window went away; the session was hibernated, so it is dormant; or the session was closed or its
process ended, so it is no longer on the desktop. A reason the app does not know SHALL be taken as the session
no longer running.
Otherwise a closed session SHALL be judged only from the desktop's current list; while the desktop has no open
window it lists no sessions, and that SHALL NOT be taken for the session being closed.
Once the desktop has a window again, the screen SHALL show the session as its list does.
When several of these hold, the screen SHALL say first that the phone is not connected, then that the desktop
has no open window, then that the session was closed, then that it is dormant.

#### Scenario: Waking a dormant session

- **WHEN** the user opens a dormant session and chooses to wake it
- **THEN** the screen shows it is waking, and then shows the session's conversation once it is running

#### Scenario: A dormant session is not an empty conversation

- **WHEN** the user opens a dormant session
- **THEN** the screen says the session is dormant, and does not show an empty conversation

#### Scenario: No window

- **WHEN** the user wakes a session while the desktop has no open window
- **THEN** the screen says spekterm has no open window on the desktop

#### Scenario: The session is hibernated while being read

- **WHEN** the user hibernates a session on the desktop while its conversation is on the phone's screen, and
  then wakes it
- **THEN** the phone shows the session as dormant, and then shows its conversation again, new entries included

#### Scenario: Closed on the desktop

- **WHEN** the user closes a session on the desktop while its conversation is on the phone's screen
- **THEN** the phone says the session is no longer on the desktop, without first saying that the desktop has no
  open window

#### Scenario: The desktop's window closes

- **WHEN** the desktop's window closes while a conversation is on the phone's screen
- **THEN** the phone says spekterm has no open window on the desktop, not that the session was closed

#### Scenario: Hibernated without a "no window" moment

- **WHEN** a session is hibernated, from the phone or on the desktop, while its conversation is on the phone's
  screen
- **THEN** the screen shows the session as dormant with a way to wake it, and never says that the desktop has no
  open window

### Requirement: Show when messages were recorded, and which day

The conversation screen SHALL show, under each of the user's own messages and each piece of the agent's text, the
time the desktop says it was recorded, in the phone's time zone and time format. Thinking, tool calls, results and
messages the agent injected SHALL NOT show a time of their own. Going through the items in the order they are shown,
the screen SHALL show a heading naming the day — "Today", "Yesterday", or the date, by the phone's time zone —
before the first item with a recorded time, and before each item with a recorded time whose day differs from that of
the last item with a recorded time before it — tool calls and thinking included, though they show no time;
while the user scrolls, the heading of the day the topmost shown item belongs to SHALL stay at the top of the
conversation, standing out from the messages, until the next day's heading reaches it; a day is therefore headed again when recorded times go back across midnight. The headings and
the times SHALL follow the phone's current date, time zone and clock without the user doing anything: after
midnight, after the phone slept, and after its date, time or time zone changed. An entry the desktop sends without a
time SHALL show no time and SHALL start no day. A reply of the user's that is not yet, or not, in the conversation
SHALL show no time and SHALL start no day.

#### Scenario: A morning's exchange

- **WHEN** the phone is set to 24-hour time, and the user opens a session where they wrote at 09:12 today and the
  agent answered at 09:14 after three tool calls
- **THEN** the screen shows a "Today" heading before the first item of today, "09:12" under the user's message and
  "09:14" under the agent's answer, and no time on the tool calls

#### Scenario: A conversation over two days

- **WHEN** a session's conversation started yesterday evening and went on this morning
- **THEN** a "Yesterday" heading comes before yesterday's first item and a "Today" heading before this morning's
  first item

#### Scenario: Scrolling through a long day

- **WHEN** the user scrolls up through yesterday's long answer, whose heading has scrolled out of view
- **THEN** "Yesterday" stays shown at the top of the conversation over the answer, and gives way to "Today" when
  today's heading reaches the top

#### Scenario: Midnight while the screen is open

- **WHEN** the conversation screen is open at 23:59 showing a "Today" heading, and the phone's clock passes midnight
- **THEN** that heading reads "Yesterday", without the user doing anything

#### Scenario: Back the next morning

- **WHEN** the user leaves the conversation screen showing "Today", turns the screen off, and turns it on again the
  next morning on the same screen
- **THEN** that heading reads "Yesterday" as soon as the screen is shown

#### Scenario: Times that go back across midnight

- **WHEN** the shown items' times are, in order, Monday, Tuesday, and Monday again
- **THEN** a heading comes before each of the three

#### Scenario: An entry without a time

- **WHEN** the desktop sends a conversation whose entries carry no time
- **THEN** the screen shows no times and no day headings

#### Scenario: A reply not in the conversation yet

- **WHEN** the user sends a reply and it has not appeared in the conversation
- **THEN** its bubble shows its sending state and no time, and no heading comes before it

### Requirement: Show a built-in command's output as the command's output

A user entry not marked as injected whose whole text, without white space at either end, is one
`<local-command-stdout>…</local-command-stdout>` or `<local-command-stderr>…</local-command-stderr>` with no other
marker inside SHALL be shown as the output of the agent CLI's command: the text between the markers without terminal
escape sequences, as plain text, under a label saying it is command output, not as a message the user typed, with
only its first three lines shown until the user expands it. It SHALL end a run of tool calls as the user's message it
replaces would, keep the place of a reply sent after it, and show no time of its own while counting for its day as
a tool call does. An entry marked as injected
whose whole text is one `<local-command-caveat>…</local-command-caveat>` SHALL NOT be shown; it SHALL still end a run
of tool calls as an injected message does, and a reply sent after it SHALL stay where it was sent. Any other entry
holding these markers SHALL be shown as it is today.

#### Scenario: An effort change

- **WHEN** the conversation holds, in order, an injected entry "<local-command-caveat>The command below was run
  directly in Claude Code…</local-command-caveat>", the user entry "/effort low", and the entry
  "<local-command-stdout>Set effort level to low (saved as your default for new sessions): Quick…
  </local-command-stdout>"
- **THEN** the screen shows "/effort low" as the user's message, followed by "Set effort level to low (saved as your
  default for new sessions): Quick…" as command output, and no caveat

#### Scenario: Output with terminal styling

- **WHEN** the output entry of "/compact" reads "<local-command-stdout>\u001b[2mCompacted (ctrl+o to see full
  summary)\u001b[22m</local-command-stdout>"
- **THEN** the screen shows "Compacted (ctrl+o to see full summary)" as command output, with no escape codes

#### Scenario: A caveat between two tool calls

- **WHEN** the CLI's caveat entry arrived between two of the agent's tool calls
- **THEN** the screen shows the two calls as it did before this change: in two items, not one run

#### Scenario: Markers among other text

- **WHEN** a user entry reads "see <local-command-stdout>x</local-command-stdout> above"
- **THEN** it is shown as the user's message, as recorded
