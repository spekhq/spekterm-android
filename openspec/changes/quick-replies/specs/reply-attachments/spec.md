## MODIFIED Requirements

### Requirement: Send attachments with a reply

A reply SHALL carry the attachments of the composer that finished uploading, with or without text, and the
desktop then tells the agent where each file is. A quick reply (`quick-replies`) SHALL carry none: the composer's attachments, ready or
not, SHALL stay in the composer for the reply being written. Once the desktop has written the reply — sent, or typed but
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

#### Scenario: A quick reply leaves the attachments

- **WHEN** a photo finished uploading in the composer and the user taps a quick reply
- **THEN** the desktop receives the sentence with no attachment, and the photo is still in the composer, ready to send
