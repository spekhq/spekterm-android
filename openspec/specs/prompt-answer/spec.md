# prompt-answer Specification

## Purpose
Lets the user see the agent's open prompt — a permission request, the agent's questions, or a plan to approve —
on the phone exactly as the desktop states it, and answer it from the phone through the desktop, which writes the
keys itself and refuses when the prompt is no longer the one shown.

## Requirements

### Requirement: Show the open prompt as the desktop states it

While the conversation on screen is watched and the desktop states an open prompt for it, the screen SHALL show
that prompt above the composer: for a permission request, the tool and its argument; for the agent's questions,
each question's header and text and its options with their descriptions, marked as one choice or several; for
a plan approval, the plan text when there is one. All of it SHALL be shown as plain text — never rendered as
Markdown, never as links, and loading nothing. When the desktop says more than one request is open, the screen
SHALL say how many. A newer statement of the prompt SHALL replace the one shown, and the prompt SHALL disappear
when the desktop states that none is open, or when the conversation is no longer watched. A long plan or many
questions SHALL NOT push the composer off the screen.

#### Scenario: A command to approve

- **WHEN** the agent asks permission to run `npm test` while its conversation is on screen
- **THEN** the screen shows the tool and `npm test` as plain text above the composer

#### Scenario: A question with options

- **WHEN** the agent asks two questions, the second allowing several choices
- **THEN** the screen shows both questions with their options in order, the second marked as allowing several

#### Scenario: Two open requests

- **WHEN** the agent and a subagent each raise a permission request
- **THEN** the screen shows the first request and says that two are open

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

### Requirement: Say how an answer went

While an answer is in progress the prompt's answers SHALL NOT be usable and the screen SHALL say it is being
answered. Then the app SHALL show:

- when every key was written, or the agent recorded the chosen answer: nothing beyond the prompt going away when
  the desktop says so;
- when only part of the answer was written, or the agent recorded a different answer than the one chosen: a
  warning that sends the user to the desktop, kept until dismissed;
- when the desktop refused the answer because the prompt could no longer be answered from a device — it was
  replaced, or typed into on the desktop: that nothing was sent;
- when the desktop did not take the answer from the phone: that nothing was sent and to answer on the desktop;
- when the session is not running: that nothing was sent;
- when the connection ended before the desktop answered, or it answered with any other error: that it is
  unknown whether the answer was written, pointing to the desktop, kept until dismissed.

#### Scenario: A replaced prompt

- **WHEN** the agent's prompt is replaced by another one just before the user's answer reaches the desktop
- **THEN** the screen says the choice could no longer be answered from the phone and nothing was sent, and shows
  the new prompt

#### Scenario: Leaving right after answering

- **WHEN** the user taps an answer and immediately leaves the conversation, and returns once the agent raises
  another prompt
- **THEN** the new prompt can be answered

#### Scenario: Partly written

- **WHEN** the desktop answers that only part of a multi-question answer was written
- **THEN** the screen warns that the answer is incomplete and to finish it on the desktop

#### Scenario: The connection ends while answering

- **WHEN** the connection ends after the user answered and before the desktop replied
- **THEN** the screen says it is unknown whether the answer was written and to check the desktop
