## MODIFIED Requirements

### Requirement: Show the conversation of an agent session

The conversation screen SHALL show the session's entries in order, newest last: the user's messages, the agent's
text, the agent's thinking, its tool calls, each shown by its kind as `tool-call-display` says, and their results
(whether each succeeded or failed). A tool call and the result the desktop gives for it (the same tool call id)
SHALL be shown as one item, marked as succeeded or failed once the result is there; a result whose call is not
shown SHALL be shown on its own. Two or more tool calls in a row — with only their results, thinking and background-task notices
between them — SHALL be summarized in one collapsed item that says how many calls it holds, how many failed, and what the
latest call was; expanding it SHALL show each call. A reply of the user's shown between tool calls
(`conversation-reply`) SHALL end the run there, so that the reply stays where it was sent; a message from another
session or a subagent's report SHALL end the run too, so that it is never hidden inside one. Thinking and tool results SHALL be collapsed until the user
expands them. Messages the agent itself injected rather than the user typing them SHALL be marked as such, saying who sent
them when the desktop says so (`tool-call-display`). New
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
- **THEN** the screen shows a run of the first two calls, the notice, the message, then a run of the other two calls

#### Scenario: A notice between two calls

- **WHEN** a background task's notice arrived between two of the agent's tool calls
- **THEN** the screen shows one run of the two calls, holding the notice

#### Scenario: A run that grows while expanded

- **WHEN** the user expanded a run of tool calls and the agent makes another call
- **THEN** the run stays expanded and shows the new call, and its count goes up by one

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
