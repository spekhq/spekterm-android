## MODIFIED Requirements

### Requirement: List the files the agent sent

The files the agent sent with SendUserFile SHALL be shown in its message (`tool-call-display`), with the call's
caption above them. An image SHALL be shown as a picture in the message, read from the desktop by itself when it is
at most 2 MB, and otherwise on a tap that says its size; any other file SHALL be shown as a card with its name and
kind. Reading a picture by itself SHALL happen only while the message is on screen, or while the picture is shown full screen or next to the item shown full screen (`tool-call-display`), once per conversation shown. A file of a kind the phone can open —
images, Markdown and plain text, judged from its name — SHALL be offered for opening when the desktop accepts reading
sent files; a file of another kind SHALL be offered for opening with another app on the phone. With a desktop that does not accept reading
sent files, no file SHALL be offered for opening, and the call SHALL say the desktop cannot send files to the phone.

#### Scenario: A screenshot sent

- **WHEN** the agent sent `login.png` with the caption "The new login screen"
- **THEN** the message shows the caption and the picture, which opens larger when tapped

#### Scenario: A video sent

- **WHEN** the agent sent `reel.mp4`
- **THEN** the message shows a card for `reel.mp4`, offered for opening with another app

#### Scenario: A desktop that cannot send files

- **WHEN** the desktop lists the files a call sent but does not accept reading them
- **THEN** the message lists the files by name, none is offered for opening, and it says the desktop cannot send files
  to the phone

#### Scenario: A large photo

- **WHEN** the agent sent a 6 MB photo
- **THEN** the message shows a card with its name and size; the phone reads it only when the user taps it, or when
  swiping full screen stops on it

### Requirement: Open a sent file on the phone

Opening a sent file SHALL read it from the desktop, addressed by the call and the file's place in it, and show it on
the phone; full screen, a sent picture, Markdown or text file next to the item shown SHALL be read too, so swiping to it
shows it at once: an image as an image the user can zoom; Markdown rendered under the same rules as agent text — nothing
loaded over the network, HTML shown as text, links opened only when tapped; plain text as selectable plain text. The
file's content SHALL be held in memory only — never written to the phone's storage, and never its name, path or
content to the connection log or a notification — and dropped when the user leaves it. Leaving the file SHALL return
to the conversation as it was: the same items expanded, at the same position, or still following the newest entries
when it was. When the file cannot be shown, the
screen SHALL say why: it is not among the files sent in the current conversation, it is no longer on the desktop, it
changed while being read, it is too large (with its size or the
desktop's limit), it cannot be shown, the phone is not connected, or the session is gone; any other refusal SHALL say
the desktop could not send the file.

#### Scenario: Opening a Markdown file

- **WHEN** the user opens a sent `handoff-protocol-v2-draft.md`
- **THEN** the phone shows it rendered as Markdown, and makes no request for any image it links

#### Scenario: The file was deleted

- **WHEN** the user opens a sent file that has since been deleted on the desktop
- **THEN** the screen says the file is no longer on the desktop

#### Scenario: An image that does not decode

- **WHEN** the user opens a sent `.png` whose content is not a valid image
- **THEN** the screen says the file cannot be shown

#### Scenario: Swiping to a sent file

- **WHEN** a sent `notes.md` sits between two pictures in the conversation, and the first picture is open full screen
- **THEN** `notes.md` is read while it is next to the picture shown, and swiping left shows it rendered as Markdown

#### Scenario: Leaving the file

- **WHEN** the user opened a sent file from a run of tool calls they had expanded, nothing new arrived meanwhile, and
  the user goes back
- **THEN** the run is still expanded at the same place, the phone no longer holds the file's content, and nothing of
  it was written to storage

### Requirement: Hand a file the app cannot show to another app

Opening a sent file of a kind the app does not show SHALL read it from the desktop as above and offer it to the
phone's other apps, with the file's media type, letting the user choose the app. Reached by swiping full screen
(`tool-call-display`), such a file SHALL instead show its name and kind with a way to open it in another app, and SHALL
be read and offered only when that is chosen; the full-screen view stays where it was. This is the only case in which
conversation content is written to the phone's storage: a copy SHALL be written only to the app's private cache,
SHALL be readable by the chosen app only for that file, and SHALL be deleted when the user leaves the conversation,
when the app starts again, and when the desktop is unpaired. When no app on the phone opens that kind of file, the
screen SHALL say so and the copy SHALL be deleted at once.

#### Scenario: Watching a sent video

- **WHEN** the user opens a sent `reel.mp4` and chooses a video player
- **THEN** the video plays in that app, and after the user leaves the conversation no copy of it remains on the phone

#### Scenario: Swiping to a sent PDF

- **WHEN** the user swipes full screen to a sent `report.pdf`
- **THEN** it shows `report.pdf`, its kind and "Open in another app", nothing of it has been read, and tapping that
  reads it and offers it to the phone's other apps

#### Scenario: Tapping a sent PDF in its message

- **WHEN** the user taps a sent `report.pdf`'s card in its message, with pictures elsewhere in the conversation
- **THEN** it is read and offered to the phone's other apps at once, as before

#### Scenario: No app for the file

- **WHEN** the user opens a sent file of a kind no app on the phone accepts
- **THEN** the screen says no app on this phone opens this kind of file, and no copy remains
