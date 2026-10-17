## ADDED Requirements

### Requirement: Open a folder's OpenSpec from the session list

While the desktop accepts `openspec.changes`, the session list SHALL offer a way to each folder's OpenSpec
(`openspec-browse`):

- The heading of a folder group whose folder the desktop marks as having OpenSpec SHALL carry a control that opens
  that folder's OpenSpec. It SHALL be labeled for accessibility with the folder's name. Tapping it SHALL NOT
  collapse or expand the group, which stays the heading's own tap.
- The list's menu SHALL offer "OpenSpec" while at least one folder of the desktop has OpenSpec. It SHALL list every
  such folder in the desktop's order, including folders with no session, and open the chosen folder's OpenSpec.

Neither SHALL be shown for the global group, for folders the desktop does not mark as having OpenSpec, or while the
desktop does not accept `openspec.changes`.

#### Scenario: From a folder's heading

- **WHEN** a folder with OpenSpec has sessions on the desktop and the user taps the OpenSpec control in its heading
- **THEN** that folder's OpenSpec opens, and the group's collapsed or expanded state does not change

#### Scenario: A folder without sessions

- **WHEN** a folder with OpenSpec has no session and the user chooses "OpenSpec" from the list's menu
- **THEN** the folder is listed there and opens its OpenSpec

#### Scenario: A desktop without OpenSpec reads

- **WHEN** the paired desktop does not accept `openspec.changes`
- **THEN** no heading shows the OpenSpec control and the menu has no "OpenSpec"
