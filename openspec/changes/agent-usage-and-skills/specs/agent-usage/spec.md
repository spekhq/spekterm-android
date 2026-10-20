## ADDED Requirements

### Requirement: Take the model and usage from the watched session's reports

The app SHALL take the model and usage of a session only from the desktop's usage events of the conversation it
watches, each replacing what was held, and SHALL keep none of its own. Each value — the model, the effort, the context
used, the context window's size, the session's cost, and the five-hour and seven-day rate limits with their reset
times — SHALL be taken independently: a value that is missing or malformed SHALL be treated as not reported, without
affecting the others. A percent outside 0–100 SHALL be shown as the nearest bound. The values SHALL be dropped when the
watch is requested again, left, ended or its connection lost, and an event arriving before the desktop answers the
watch SHALL be kept. A desktop that never sends the event — older than spekterm 0.2.15 — SHALL leave the screen as it
was before the feature.

#### Scenario: A value missing

- **WHEN** the desktop reports a model and a cost, and no context
- **THEN** the screen shows the model and the cost, and nothing about context

#### Scenario: A malformed value

- **WHEN** the desktop reports a context percent that is a string, and a valid cost
- **THEN** the cost is shown and the context is not

#### Scenario: Before the watch's answer

- **WHEN** the usage event of a new watch arrives before the desktop answers the watch
- **THEN** its values are shown once the screen shows the conversation

#### Scenario: Reconnecting

- **WHEN** the connection is lost while the usage line shows a cost
- **THEN** the line goes, and comes back with the values of the next watch's first report

#### Scenario: An older desktop

- **WHEN** the desktop sends no usage event
- **THEN** the conversation screen has no usage line

### Requirement: Show the usage on one line, and in full on request

While the app watches an agent session and holds any reported value, the conversation screen SHALL show one line
under its top bar with, in this order and each only when reported: the model with its effort, the context used as a
whole percent with the window's size in thousands or millions of tokens, the session's cost in US dollars in the
phone's locale with two decimals, and each rate-limit window whose use, rounded to a whole percent, is at least 80%
and whose reset time — when one is reported — has not passed, marked as a warning. A reset time passing while the
screen is open SHALL take its window off the line without a new report.
With no value reported there SHALL be no line. The line SHALL be one line, cut short with an ellipsis when it does not
fit. Tapping it SHALL show every reported value: the model, the effort, the context used and the window's size in
tokens, the cost, and each rate-limit window's use with its reset time — the time when today, the day and time
otherwise. A window whose reset time has passed SHALL say it was reset and has not been reported since, instead of a
percent that no longer holds. The detail SHALL say that the values are the agent's last report to the desktop, and that
the rate limits are the account's, not the session's. The model and the effort SHALL be shown as plain text.

#### Scenario: A typical session

- **WHEN** the desktop reports model "Opus 5.5", effort "high", context 42.5% of 200,000 tokens and a cost of 3.214
  dollars, and a five-hour limit at 31%
- **THEN** the line reads "Opus 5.5 (high) · Context 43% of 200K · $3.21", without the rate limit

#### Scenario: A rate limit near full

- **WHEN** the five-hour limit is reported at 85% with a reset time later today
- **THEN** the line ends with the five-hour limit at 85%, marked as a warning, and the detail shows 85% and the reset
  time

#### Scenario: A reset that has passed

- **WHEN** the seven-day limit was reported at 90% with a reset time that has passed
- **THEN** the line does not show it, and the detail says it was reset at that time and not reported since

#### Scenario: A reset passing on an idle session

- **WHEN** the line shows the five-hour limit at 85% and its reset time passes with no new report from the desktop
- **THEN** the line no longer shows it, and the detail says it was reset at that time and not reported since

#### Scenario: Nothing reported yet

- **WHEN** the desktop's first usage event of a watch carries only the session id
- **THEN** there is no usage line

#### Scenario: The phone's language

- **WHEN** the app is in 中文（台灣）
- **THEN** the line's words and the detail are in Traditional Chinese, and the cost in that locale's form of US
  dollars
