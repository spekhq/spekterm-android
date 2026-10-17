## MODIFIED Requirements

### Requirement: Show the conversation of an agent session

The conversation screen SHALL show the session's entries in order, newest last: the user's messages, the agent's
text, the agent's thinking, its tool calls (the tool's name and identifying argument) and their results
(whether each succeeded or failed). A tool call and the result the desktop gives for it (the same tool call id)
SHALL be shown as one item, marked as succeeded or failed once the result is there; a result whose call is not
shown SHALL be shown on its own. Two or more tool calls in a row — with only their results and thinking between
them — SHALL be summarized in one collapsed item that says how many calls it holds, how many failed, and what the
latest call was; expanding it SHALL show each call. A reply of the user's shown between tool calls
(`conversation-reply`) SHALL end the run there, so that the reply stays where it was sent. Thinking and tool results SHALL be collapsed until the user
expands them. Messages the agent itself injected rather than the user typing them SHALL be marked as such. New
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
- **THEN** the call is shown as one item with its name, its argument and whether it succeeded, with its result
  collapsed

#### Scenario: A reply sent while the agent made tool calls

- **WHEN** the user sent a reply after the agent's third tool call in a row, the agent made two more calls, and the
  reply is then marked as not in the conversation
- **THEN** the screen shows the first three calls, then the reply, then the other two calls; no item holds calls
  from both sides of the reply

#### Scenario: A run that grows while expanded

- **WHEN** the user expanded a run of tool calls and the agent makes another call
- **THEN** the run stays expanded and shows the new call, and its count goes up by one
