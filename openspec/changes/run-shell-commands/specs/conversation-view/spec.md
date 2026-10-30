## ADDED Requirements

### Requirement: Show a shell-mode command as the command the user ran, and its output

A user entry not marked as injected, from no other session and no background task, whose whole text, without white
space at either end, begins with `<bash-input>` and ends with `</bash-input>` SHALL be shown as a command the user ran
in the agent's terminal: `$` and the text between the markers — taken as it is, since the agent CLI does not escape
it — in a monospace face, as plain text, under a label saying it is a command the user ran, not as a message the user
typed. Whoever ran it — from the phone or on the desktop — it SHALL be shown so.

A user entry not marked as injected, from no other session and no background task, whose whole text, without white
space at either end, is one of these SHALL be shown as that command's output:

- `<bash-stdout>`, the standard output, `</bash-stdout><bash-stderr>`, the error output, `</bash-stderr>` — the
  standard output ending at the last `</bash-stdout>`;
- `<bash-stderr>`, the error output, `</bash-stderr>` alone.

The output SHALL be shown as plain text, without terminal escape sequences, under a label saying it is the command's
output, with only its first three lines shown until the user expands it: the standard output, then the error output
when there is any, set apart as errors. Error output means the command failed, and the label SHALL say so. Text the
agent CLI escaped SHALL be shown with `&lt;`, `&gt;`, `&quot;`, `&#39;` and `&amp;` turned back into the characters
they stand for. A standard output that is `<persisted-output>` … `</persisted-output>` — the agent CLI's preview of
output too large to keep — SHALL be shown as what is between those markers, not unescaped, followed by a line saying
the full output was saved on the desktop. When there is no output at all, it SHALL say that the command printed
nothing.

Both SHALL end a run of tool calls as the user's message they replace would, keep the place of a reply sent after
them, and show no time of their own while counting for their day as a tool call does. Any other entry holding these
markers SHALL be shown as it is today.

#### Scenario: A command and its output

- **WHEN** the conversation holds the user entry "<bash-input>git status</bash-input>" and then
  "<bash-stdout>On branch master\nnothing to commit</bash-stdout><bash-stderr></bash-stderr>"
- **THEN** the screen shows `$ git status` as a command the user ran, followed by "On branch master" and "nothing to
  commit" as its output, with no markers

#### Scenario: Escaped output

- **WHEN** the output entry reads "<bash-stdout>&lt;a&gt;&amp;b</bash-stdout><bash-stderr></bash-stderr>"
- **THEN** the output shown is `<a>&b`

#### Scenario: A command holding a marker

- **WHEN** the user entry reads "<bash-input>echo '</bash-input>'</bash-input>"
- **THEN** the screen shows `$ echo '</bash-input>'` as a command the user ran

#### Scenario: A failed command

- **WHEN** the output entry reads "<bash-stdout></bash-stdout><bash-stderr>ls: cannot access 'x'</bash-stderr>"
- **THEN** the screen shows "ls: cannot access 'x'" as error output, labeled as a failed command

#### Scenario: Error output alone

- **WHEN** the output entry reads "<bash-stderr>Command failed: x</bash-stderr>"
- **THEN** the screen shows "Command failed: x" as error output, labeled as a failed command

#### Scenario: Output too large

- **WHEN** the output entry's standard output is "<persisted-output>\nOutput too large (38KB). Full output saved to:
  /tmp/x.txt\n\nPreview (first 2KB):\n1\n2\n</persisted-output>"
- **THEN** the screen shows the preview text as it is, and a line saying the full output was saved on the desktop

#### Scenario: No output

- **WHEN** the output entry reads "<bash-stdout></bash-stdout><bash-stderr></bash-stderr>"
- **THEN** the screen says the command printed nothing

#### Scenario: Markers among other text

- **WHEN** a user entry reads "I ran <bash-input>ls</bash-input> before"
- **THEN** it is shown as the user's message, as recorded
