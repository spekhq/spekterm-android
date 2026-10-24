## Why

kewang, from the phone (2026-10-25): "read 這個 tool 在 app 上根本無法使用" — the Claude Code Android app shows what a
Read brought back; ours does not. A real session's screenshot shows why:

- **A picture the agent read is not there at all.** When the agent reads an image, the CLI records the tool result as
  one image block and no text (CLI 2.1.310). The desktop sends a result's text only, so the phone gets an empty result
  and the call line `Read sp.png` opens onto nothing. kewang's sessions read pictures often: the transcripts on this
  machine hold 1,837 distinct image Reads, and about 1,400 browser screenshots from an MCP tool.
- **The CLI's note to the model about the picture is shown instead.** After an image result it scaled down, the CLI adds a hidden user
  record (341 of them here), `[Image: original 1080x2400, displayed at 900x2000. Multiply coordinates by 1.20 to map to original image.]`.
  The phone shows it as "injected by the agent", so a screenshot read shows up as a line of coordinates.
- **A text file's content is reachable only by expanding the call, as one block with the CLI's line-number prefixes**
  (`1\t## Why`), which reads as noise on a small screen.

Other tools' results carry pictures the same way (MCP screenshot tools, browser screenshots), and are lost the same way.

The phone cannot show a picture alone: it never receives the bytes. spekterm gets a matching change,
`remote-result-images` (handed off 2026-10-25): a result lists the pictures it held, and a new read-only operation,
`images.read`, returns one by the call's id and its position, in chunks, from the transcript. This change is the phone's
side.

## What Changes

- **Pictures in a tool result are shown without expanding**: under a lone call's line; in a collapsed run of calls as a
  short strip of thumbnails under the run's line; in an expanded run under the call that brought them back. Tapping one
  opens the full-screen viewer the sent files use. Read the same way as sent pictures: small ones by themselves, larger
  ones on a tap, held in memory only.
- **A text Read, expanded, shows the file's content** under a "Content" label with the line numbers in a muted gutter
  instead of glued to each line, and the CLI's notes to the model (a memory file's age, an artifact's source) set apart;
  anything that does not have that shape is shown as it came.
- **The CLI's `[Image: original …]` note is not shown** in the conversation, and no longer splits a run of calls.
- **An older desktop** (no `images.read`): a Read of a picture whose result is empty says that seeing the picture needs a newer spekterm,
  instead of nothing.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `tool-call-display`: a tool result's pictures are shown and opened; a Read's expanded view shows the content with line
  numbers apart; the CLI's picture note is not shown as an injected message.
- `conversation-view`: the CLI's picture note may sit inside a run of calls and is not marked as injected.

## Impact

- Code: `desktop/ToolView.kt` (pictures, Read's numbered result), new pure `desktop/ReadLines.kt`, `ThumbKey.kt`,
  `FileLog.kt`, `desktop/ConversationItems.kt` (the CLI's note dropped), `desktop/SentFiles.kt` / `SentFileRead` (the chunked read over either operation), `desktop/Desktop.kt` and
  `net/Frames.kt` (the new operation), `net/Sessions.kt` (`images` on a result), `ui/ToolViews.kt`,
  `ui/MessageViews.kt` (thumbnails shared), `ui/ConversationScreen.kt` (run strip), `ui/SentFileViewer.kt` (opened
  from a result), strings in `values` and `values-zh-rTW`, `README.md`.
- Protocol: needs spekterm with `remote-result-images`; the final names and fields are its `docs/remote-protocol.md`'s. Against an older desktop everything shown today stays.
- Another session (`model-effort-switch`, handed off 2026-10-25) edits `ConversationScreen.kt`, `Desktop.kt` and the
  strings in the same checkout: commits stage explicit paths only.
