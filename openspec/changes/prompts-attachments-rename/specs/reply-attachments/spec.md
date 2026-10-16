## Purpose

Lets the user attach photos and PDF or text files to a reply to an agent, uploaded to the desktop over the
app's own connection, so the agent can read them.

## ADDED Requirements

### Requirement: Attach photos and files to a reply

While the desktop accepts `attachments.put` and the conversation on screen belongs to an agent session, the
composer SHALL offer to attach photos, through the system's photo picker, and files, through the system's
document picker, several at a time, without asking for a storage permission. A reply SHALL hold at most 10
attachments; picking more SHALL be refused with a message. Each attachment SHALL be shown in the composer with
its name, its size and how far its upload has come, and SHALL be removable until the reply is sent. Attachments
SHALL be kept, like the text being written, when the user leaves the conversation and comes back to it, and
SHALL be dropped when another session's conversation is opened in between.

#### Scenario: Attaching a photo

- **WHEN** the user picks a photo from the photo picker in an agent's conversation
- **THEN** the composer shows the photo's name and size and its upload progress

#### Scenario: Too many

- **WHEN** the reply already holds 10 attachments and the user picks another
- **THEN** it is not added, and the screen says a reply can carry at most 10

### Requirement: Prepare attachments the desktop accepts

The app SHALL send only content the desktop accepts — PNG, JPEG, GIF or WebP images, PDF, and UTF-8 text with
no NUL character — of at most 10 MiB. A photo in another format the phone can decode, such as HEIC, SHALL be
converted to JPEG on the phone. Every photo SHALL reach the desktop upright as the gallery shows it: a JPEG that
relies on its orientation tag to be shown upright SHALL be re-encoded upright. An accepted photo larger than 10 MiB SHALL be
re-encoded smaller until it fits. Any other file the desktop would not accept, and any file other than a photo
larger than 10 MiB, SHALL be refused on the phone before uploading, saying why. A picked file SHALL be copied
by the app, so that the upload does not depend on the picker's permission lasting.

#### Scenario: A HEIC photo

- **WHEN** the user picks a HEIC photo
- **THEN** it is attached as a JPEG, and the agent can read it

#### Scenario: A file of another kind

- **WHEN** the user picks a ZIP archive
- **THEN** it is not attached, and the screen says only photos, PDF and text files can be sent

#### Scenario: A large PDF

- **WHEN** the user picks a 25 MB PDF
- **THEN** it is not attached, and the screen says it is larger than the desktop's 10 MB limit

### Requirement: Upload in pieces, and continue after a dropped connection

The app SHALL upload each attachment as soon as it is prepared, in pieces the desktop accepts, one attachment at
a time. Leaving the conversation screen SHALL NOT stop an upload. When the connection ends during an upload, the
upload SHALL continue after the phone reconnects, from where the desktop says it got to. When the desktop no
longer holds an unfinished upload, the app SHALL start it again from the beginning once. Pieces SHALL be small
enough that a slow mobile connection carries each within the time the app waits for an answer; when three
attempts in a row at the same piece get no answer, the attachment SHALL be marked as failed because the
connection is too slow or unstable, instead of being tried again and again. When the desktop refuses
an attachment — too large, too many attachments not yet sent for this session, a kind it does not accept, or a
session that is not an agent's — the attachment SHALL be marked as failed with the reason, including the limit
the desktop names, and the user SHALL be able to retry or remove it. The app SHALL NOT start an upload again on
its own except as stated here. Removing an attachment whose upload had started SHALL stop it; the screen SHALL
make clear in the "too many" reason that removed uploads still count on the desktop for 24 hours.

#### Scenario: The connection drops mid-upload

- **WHEN** the connection ends while a 6 MB photo is half uploaded, and the phone reconnects
- **THEN** the upload continues from where the desktop got to, and completes

#### Scenario: The desktop restarted

- **WHEN** the desktop quit during an upload and is started again
- **THEN** after the phone reconnects the upload starts again from the beginning and completes

#### Scenario: A connection too slow for uploading

- **WHEN** three attempts in a row to upload the same piece of a photo get no answer
- **THEN** the photo is marked as failed because the connection is too slow or unstable, and the user can retry
  it

#### Scenario: Too many unsent attachments

- **WHEN** the desktop refuses an attachment because the session already holds 10 not yet sent
- **THEN** the attachment is marked as failed, saying so and that removed uploads count for 24 hours

### Requirement: Send attachments with a reply

A reply SHALL carry the attachments of the composer that finished uploading, with or without text, and the
desktop then tells the agent where each file is. Once the desktop has written the reply — sent, or typed but
withheld — its attachments SHALL leave the composer and SHALL be listed by name on the reply. When the desktop
refuses the reply before writing anything, its attachments SHALL return to the composer with its text, ready to
send again; when it refuses because the session or an attachment was not found, the screen SHALL say so, and
when an attachment is refused that way a second time, it SHALL be marked as failed — only the attachments the
app has reason to doubt are still on the desktop, or all of them when it doubts none — so the user can upload
them again or remove them. When it is unknown whether the reply was written, its attachments SHALL NOT
return.

#### Scenario: A reply with two attachments

- **WHEN** the user sends "compare these" with two photos that finished uploading
- **THEN** the agent receives the text followed by the desktop's sentence naming both stored photos, and the
  composer is empty

#### Scenario: Refused because a choice appeared

- **WHEN** the agent raised a prompt just as the user sent a reply with an attachment, and the desktop refuses it
- **THEN** the text and the attachment are back in the composer, ready to send once the agent can take a reply
