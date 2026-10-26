## ADDED Requirements

### Requirement: Reply from the screens opened from a conversation

The OpenSpec screens (a folder's changes and specs, a change, a main spec) and the handoff screen SHALL carry a reply
bar at their bottom for a session when they were opened from that session's conversation screen, directly or through
one another. Opened in any other way, they SHALL carry none. An agent session's bar SHALL hold the conversation's own
reply area — the answer note, the prompt card while the agent asks for a choice, the inbox note while an inbox item's
first prompt waits, and the composer with its attachments, its skills, its switch line and its blocked reasons —
behaving as it does on the conversation screen. The draft and the attachments SHALL be the session's own: what is
typed or attached in the bar SHALL be in the conversation's composer, and the other way round; so SHALL a hold on
replies the user lifted. The requirements on replying, attaching, skills and answering a choice apply to the bar as to
the conversation screen. The close offer, the usage line and the model-and-effort sheet SHALL NOT be in the bar.

Above the reply area, the bar SHALL show one line naming the session, its state while watched (working,
waiting for you, idle, asking), and the reply not yet in the conversation that the user waits on, with its state as
the conversation shows it; a reply that settled without appearing (not submitted, may not have been sent, not in the
conversation yet) SHALL be shown there only until the conversation moves on past it. Tapping that line SHALL return to the session's conversation. For a closed session the bar SHALL be that line saying it was closed; for a dormant session,
that line saying it is asleep, and the conversation it opens offers to wake it. A shell session SHALL get no bar.

#### Scenario: Replying while reading a change

- **WHEN** the user opens the OpenSpec from a conversation, opens a change's proposal, types a reply in the bar with
  a photo attached and sends it
- **THEN** the reply is sent to that session with the photo, the field empties, and the bar's line shows the reply's
  state until it appears in the conversation

#### Scenario: The draft follows the user

- **WHEN** the user types half a reply in the conversation, opens the OpenSpec, finishes it in the bar, and goes back
  before sending
- **THEN** the conversation's composer holds the whole text

#### Scenario: The agent asks while a spec is on screen

- **WHEN** a main spec opened from a conversation is on screen and the agent asks for a choice
- **THEN** the bar's line says the agent is asking, the prompt card shows above the field, and the field says it is
  blocked until the choice is answered

#### Scenario: OpenSpec opened from the session list

- **WHEN** the user opens a folder's OpenSpec from the session list
- **THEN** no reply bar is shown

#### Scenario: Back to the conversation

- **WHEN** the user taps the bar's line on a change opened through a folder from a conversation
- **THEN** that conversation is shown, and the folder and change screens above it are closed

#### Scenario: A shell session

- **WHEN** the user opens the OpenSpec from a shell session's screen while the connection is paused
- **THEN** no reply bar is shown

#### Scenario: A handoff screen already open below

- **WHEN** the user opened a session's handoff from the session list, went to a child's conversation and then to the
  session's own conversation, and opens its handoff from there
- **THEN** the handoff screen carries the session's reply bar, and going back from it returns to the session's
  conversation
