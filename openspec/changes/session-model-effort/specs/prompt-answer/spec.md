## MODIFIED Requirements

### Requirement: Answer only what the desktop says can be answered

The app SHALL offer answers only while the desktop accepts `conversation.answer`, the phone is connected and not
paused, the conversation is watched, the desktop says the prompt can be answered from a device, and no answer
to it is in progress, and no model switch holds (`model-effort-switch`). Otherwise the prompt SHALL be shown
read-only with the reason and a pointer to answer it on the desktop; while a model switch holds, the reason SHALL be
the hold's, with its offer to continue anyway. The app SHALL offer exactly these answers:

- a permission request whose tool the desktop allows once: "allow once" and "deny"; any other permission request:
  only "deny", saying that allowing it is done on the desktop;
- the agent's questions: choosing one option for each single-choice question and one or more for each
  several-choice question, sending those choices only once every question has a valid choice, and declining;
- a plan approval: approving with edits asked for, approving in auto mode only when the desktop offers it, and
  keeping the agent planning.

Every answer SHALL name the prompt it answers, so the desktop refuses it when that prompt is no longer the open
one. A prompt's answers SHALL become usable only a moment after that prompt appears, so a tap meant for a prompt
that was just replaced does not answer the new one. The screen SHALL say that denying, declining or keeping the
agent planning stops the agent and that a reply can follow. The app SHALL NOT answer by typing into the agent's
terminal, and SHALL NOT send an answer again on its own.

#### Scenario: Allowing a command once

- **WHEN** the agent asks to run a command, the desktop says the prompt can be answered, and the user taps
  "allow once"
- **THEN** the desktop allows the command once, and the agent runs it

#### Scenario: A tool that cannot be allowed from the phone

- **WHEN** the agent asks permission for a tool the desktop does not allow once
- **THEN** the phone offers only "deny" and says to allow it on the desktop

#### Scenario: Answering questions

- **WHEN** the agent asks one single-choice and one several-choice question, and the user picks one option of the
  first and two of the second and sends them
- **THEN** the agent records those choices

#### Scenario: Not all questions answered

- **WHEN** the user has chosen nothing for a single-choice question
- **THEN** the choices cannot be sent yet

#### Scenario: Approving a plan

- **WHEN** the agent asks to approve its plan and the user approves with edits asked for
- **THEN** the agent leaves plan mode and asks before editing

#### Scenario: Typed into on the desktop

- **WHEN** someone types into the agent's terminal on the desktop while its prompt is open
- **THEN** the desktop says the prompt can no longer be answered from a device, and the phone shows it read-only
  with a pointer to the desktop

#### Scenario: A desktop without answering

- **WHEN** the desktop states the prompt but does not accept `conversation.answer`
- **THEN** the prompt is shown read-only and the screen says to answer it on the desktop

#### Scenario: A model switch holds

- **WHEN** a model switch has not shown for 10 seconds and the agent asks to run a command
- **THEN** the prompt is shown without its answers, saying the desktop may be asking to confirm the switch, with the
  offer to continue anyway
