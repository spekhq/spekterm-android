## ADDED Requirements

### Requirement: Show when messages were recorded, and which day

The conversation screen SHALL show, under each of the user's own messages and each piece of the agent's text, the
time the desktop says it was recorded, in the phone's time zone and time format. Thinking, tool calls, results and
messages the agent injected SHALL NOT show a time of their own. Going through the items in the order they are shown,
the screen SHALL show a heading naming the day — "Today", "Yesterday", or the date, by the phone's time zone —
before the first item with a recorded time, and before each item with a recorded time whose day differs from that of
the last item with a recorded time before it — tool calls and thinking included, though they show no time; a day is therefore headed again when recorded times go back across midnight. The headings and
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
