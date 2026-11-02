## MODIFIED Requirements

### Requirement: Nest handed-off sessions under their source in the list

Within each group of the session list, except while the list is filtered by state (`session-list`, `Summary of the
sessions by state`), a session handed off from an existing session of the same group SHALL be
shown under that session, indented one level deeper than it, together with everything nested under it. Sessions
under the same source, and the group's top-level sessions, SHALL keep the desktop's order. Indentation SHALL stop
growing after three levels; deeper sessions SHALL be drawn at the third level. A session whose source does not
exist, is in another group, is itself, or lies on a loop of sources SHALL be shown at the top level of its group.
The group of sessions whose folder the list does not name SHALL show all its sessions at the top level. No
session SHALL be left out of the list because of its relations, and the groups, their order, their headings and
collapsing SHALL be unaffected.

#### Scenario: A handoff within one folder

- **WHEN** an agent in a folder hands off to a new session in the same folder
- **THEN** the new session is shown directly under the agent's session, indented

#### Scenario: A handoff to another folder

- **WHEN** an agent in one folder hands off to a session in another folder
- **THEN** the new session is shown at the top level of the other folder's group

#### Scenario: Two handoffs from one session

- **WHEN** one session handed off two sessions in its own folder
- **THEN** both are shown under it, in the desktop's order, at the same indentation

#### Scenario: A source closed while its handoffs remain

- **WHEN** the session that handed off others is closed on the desktop
- **THEN** the sessions it handed off move to the top level of their group, and none disappears

#### Scenario: A loop of sources

- **WHEN** the desktop's list names two sessions of one folder as each other's source
- **THEN** both are shown at the top level of their group

#### Scenario: A deep chain

- **WHEN** a folder holds a chain of five sessions, each handed off from the previous one
- **THEN** the second, third and fourth are indented one level more each, and the fifth is drawn at the same
  indentation as the fourth

#### Scenario: A filtered list

- **WHEN** a session and the session it handed off in the same folder both wait for the user, and the list is filtered
  by needs you
- **THEN** both are shown at the top level of their group, the handed-off one saying where it came from
