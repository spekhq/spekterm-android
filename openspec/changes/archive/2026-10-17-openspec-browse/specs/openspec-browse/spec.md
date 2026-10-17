## Purpose

Lets the user read a desktop folder's OpenSpec from the phone, read-only: the folder's changes, a change's
artifacts, and the main specs with the changes that touched them, from the same content the desktop's side panel
shows. What is shown
follows the desktop's files while an agent writes them.

## ADDED Requirements

### Requirement: Browse a folder's changes and specs

The app SHALL show a folder's OpenSpec in two lists. The first holds the folder's active changes, then its archived
changes, each in the order the desktop sends. The second holds the folder's main specs, in the order the desktop
sends. A change SHALL be shown by its readable name, with:

- its task progress, when it has tasks;
- for an archived change, its archive date; for an active change, its start date when the desktop gives one;
- the branch of the working tree it is read from, when the desktop says that tree is not the main one.

The app SHALL NOT present the desktop's change timestamp as a creation or modification time. A spec SHALL be shown
by its topic, with the number of changes that touched it. A list SHALL be offered only while the desktop accepts
its operation (`openspec.changes`, `openspec.specs`).

#### Scenario: A folder with active and archived changes

- **WHEN** the user opens the OpenSpec of a folder with one active change and several archived ones
- **THEN** the active change is listed first with its task progress, and the archived ones follow in the desktop's
  order with their archive dates

#### Scenario: A change in another working tree

- **WHEN** a folder's repository has a second working tree on branch `feat` that holds an active change
- **THEN** that change's row shows `feat`, and a change in the main working tree shows no branch

#### Scenario: The specs list

- **WHEN** the user switches to the folder's specs
- **THEN** each main spec is listed by its topic with the number of changes that touched it

#### Scenario: A folder without changes

- **WHEN** the folder's OpenSpec has no change
- **THEN** the list says that there are no changes in this folder

### Requirement: Read a change's artifacts

Opening a change SHALL show its artifacts, in the order the desktop sends, one at a time, chosen by their titles.
How each is shown depends on its kind:

- A Markdown artifact SHALL be shown as formatted text. In Markdown, line breaks inside a paragraph SHALL be
  joined, as the desktop renders the same files.
- The tasks artifact SHALL be shown as its sections and tasks. Each task SHALL be marked done or not done, and the
  progress SHALL be shown. A task can be neither changed nor ticked from the phone.
- The specs artifact SHALL be shown as one part per capability the change touches, each named by its capability.
- A data file (YAML, JSON) SHALL be shown as plain monospace text.
- An artifact of a kind the app does not know SHALL be shown by its title, with a note that it cannot be shown on
  the phone.

A change SHALL be openable only while the desktop accepts `openspec.change`. When the desktop does not know the
change any more, the app SHALL say so. If the folder's archive holds a change of the same name, the app SHALL offer
to open that archived change.

#### Scenario: An active change

- **WHEN** the user opens an active change with a proposal, a design, delta specs and tasks
- **THEN** the artifacts are offered in the desktop's order, and the tasks show which are done and how many of all

#### Scenario: A multi-line task

- **WHEN** a task's text spans several lines in `tasks.md`
- **THEN** the phone shows the whole task as one item

#### Scenario: A data file

- **WHEN** a change holds a YAML file
- **THEN** it is shown as its plain text in a monospace face

#### Scenario: Hard-wrapped paragraphs

- **WHEN** a design's paragraph is wrapped over several lines in its file
- **THEN** the phone shows it as one paragraph, wrapped to the screen's width

#### Scenario: The change was archived while on screen

- **WHEN** the change on screen is archived on the desktop
- **THEN** the phone says the change was archived and offers to open the archived change

### Requirement: Read a main spec and its history

Opening a spec SHALL show its text as formatted Markdown, with line breaks inside a paragraph joined as for
artifacts, and the changes that touched it, in the order the
desktop sends, each with its readable name, its date when it has one, and whether it is active or archived. Each of those changes
SHALL open its change screen while the desktop accepts `openspec.change`. When the repository has more than one
working tree, the spec screen SHALL say that the spec is read from the main working tree. A spec SHALL be openable
only while the desktop accepts `openspec.spec`.

#### Scenario: A spec's history

- **WHEN** the user opens a main spec that three changes touched and taps one of them
- **THEN** the spec's text and the three changes are shown, and the tapped change's artifacts open

### Requirement: Follow the desktop's files

While a folder's OpenSpec is on screen, if the desktop accepts `openspec.watch`, the app SHALL ask the desktop to
watch that folder before reading it. Each time the desktop says the folder changed, the app SHALL read again what
is on screen. After every reconnect, it SHALL watch and read again. It SHALL ask the desktop to watch at most one folder at
a time, and SHALL stop watching soon after no OpenSpec screen is visible. Every OpenSpec screen that reads from the desktop SHALL
offer a manual refresh, also on a desktop without the watch. On a desktop without the watch, or when the desktop refused to
watch the folder, the screen SHALL say that it does not update by itself and when it was read; after a refusal,
a manual refresh SHALL try to watch again. While the phone is not connected or the connection is paused, the
screen SHALL show what was last read and when, say that it is not connected, and keep refresh disabled. Reading again
SHALL keep the shown artifact and, where the content allows, the scroll position.

#### Scenario: An agent ticks a task

- **WHEN** a change's tasks are on screen and an agent on the desktop marks a task done in `tasks.md`, on a
  desktop that offers the watch
- **THEN** the task shows as done within a few seconds, without the user doing anything

#### Scenario: A change written in another working tree

- **WHEN** the folder's list of changes is on screen and an agent creates a change in another working tree of the
  folder's repository
- **THEN** the new change appears in the list

#### Scenario: An older desktop

- **WHEN** the paired desktop offers the OpenSpec reads but not the watch
- **THEN** the screens read the same content, say that they do not update by themselves, and refreshing shows the
  desktop's current files

#### Scenario: Back after a disconnect

- **WHEN** the connection drops while a change is on screen, a task is ticked meanwhile, and the connection comes
  back
- **THEN** the phone shows the ticked task without a refresh

#### Scenario: Leaving the screens

- **WHEN** the user goes from an OpenSpec screen back to the session list, or puts the app in the background
- **THEN** the desktop stops sending the phone that folder's changes within a few seconds

### Requirement: Show repository text safely

OpenSpec text is the repository's, and the app SHALL treat it as untrusted. This covers artifact, delta and spec
content, task text and section titles.

- Raw HTML in Markdown SHALL be shown as text, never interpreted.
- A link or image reference whose destination is not an `http`/`https` URL SHALL lead nowhere. An inline link of
  that kind SHALL be shown as plain text.
- An `http`/`https` link SHALL open only when the user taps it, in the browser, outside the app.
- No image SHALL be loaded.

#### Scenario: HTML in a design

- **WHEN** a design contains `<script>` and `<img src=…>` markup
- **THEN** the phone shows that markup as text, runs nothing and loads nothing

#### Scenario: A relative link

- **WHEN** a proposal links to `design.md`
- **THEN** the link's text is shown as plain text, and tapping it does nothing

#### Scenario: A web link

- **WHEN** the user taps an `https` link in an artifact
- **THEN** it opens in the browser

### Requirement: Content too large for the phone

When the desktop leaves a piece of content out because its answer would be too large, the app SHALL show that
piece as left out by the desktop for being too large to send, with its size, and SHALL show everything else of the answer. A piece can be an
artifact, a delta, a task list or a spec's text. When the desktop refuses a list as too large, the app SHALL say so
instead of showing an error it cannot explain.

#### Scenario: A huge data file

- **WHEN** a change holds a data file that the desktop left out of its answer
- **THEN** that artifact says the desktop left it out as too large to send, and gives its size, and the change's other artifacts are
  shown

### Requirement: OpenSpec content stays on screen only

The app SHALL NOT change anything in OpenSpec. It SHALL keep OpenSpec content only in memory and only for the
screens it has open or recently opened, and SHALL drop all of it when the user unpairs or pairs again. The
connection log SHALL record each read and watch with its operation, its outcome code and counts, and SHALL NOT
record any slug, topic, title or content.

#### Scenario: Unpairing

- **WHEN** the user unpairs after browsing a folder's OpenSpec
- **THEN** nothing of that content remains on the phone

#### Scenario: The exported log

- **WHEN** the user exports the connection log after browsing
- **THEN** it shows the reads and watches with their codes and no change names, topics or text
