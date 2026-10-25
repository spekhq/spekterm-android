## Context

See proposal.md for why. What exists today:

- The desktop sends a tool result as `{ kind: "result", id, text, error }`; `text` joins the result's text blocks and
  drops everything else (spekterm `transcript-follow.ts` `resultText`). An image Read's result arrives as `text: ""`.
- `ToolView.of` (`desktop/ToolView.kt`) gives a Read a line (the file name and lines) and, expanded, the path and the
  result text as one "Output" block. A blank result is hidden, so an image Read expands onto its path alone. A tool not
  in its table (every MCP tool) goes through `fallback`.
- `ConversationItems.group` builds runs of calls; any `Plain` entry closes the run being formed. The CLI's picture note
  is a meta user entry with no `origin`, so it shows as "Injected by the agent" **and splits the run in two** (kewang's
  screenshot: "3 tool calls", the note, "3 tool calls").
- Sent pictures (`tool-call-display` D11) have most of what a result picture needs: the chunked reader
  `SentFileRead.read(limit, sink, progress, request)` takes any request function; `Thumb` and `ToolContext.thumbs` cache
  thumbnails per screen in memory (with no bound today); `SentFileDialog` shows the full-screen viewer with pinch zoom,
  but picks its kind, its sink and its title from the sent file's **path**.
- spekterm's side is the change `remote-result-images` (drafted in `~/git/spekterm/openspec/changes/`, from this
  session's handoff of 2026-10-25). As drafted:
  - a result entry gains `images: [{ mediaType, size }]`, absent when the result holds none; only base64 PNG, JPEG,
    GIF and WebP blocks are counted, in order; `text` is unchanged;
  - `images.read { sessionId, toolUseId, index, offset?, length?, version? }` →
    `{ size, mediaType, version, offset, data, complete }`, no `name`; `length` 1..512 KiB; errors `NOT_FOUND`,
    `NO_CONVERSATION`, `NO_IMAGE` (new), `FILE_CHANGED` (another image now under that id), `TOO_LARGE` (over 8 MiB),
    `BAD_PARAMS`; offered in the operations list;
  - client obligation (its D8): decode only those four raster types with the platform decoder, show a failure as
    "cannot display", never hand the bytes to a WebView or another app.
  What its `docs/remote-protocol.md` states when it ships wins over this list (task 1.1).
- Measured in kewang's transcripts on this machine (by this change's review): 1,837 distinct image Reads; about 1,400
  MCP screenshots (claude-in-chrome), whose results mix text and an image; Read image results are a lone image block;
  only PNG and JPEG seen; no error result ever held an image; the largest decoded image 511,704 bytes; a main transcript
  with pictures holds 9 of them at the median and 557 at most. Of 6,164 Read results, every numbered one uses `\t`
  after the number; 77 begin with a `<system-reminder>` block (53 a memory file's age note followed by numbered
  lines, 24 the empty-file warning alone); 19 end with a blank line and `[This file is the saved source of artifact …]`.

## Goals / Non-Goals

**Goals:**

- A picture an agent's tool brought back is seen in the conversation without a tap, and in full on one.
- A text Read is readable when expanded.
- Nothing shown against an older desktop goes missing.

**Non-Goals:**

- PDFs, notebooks and other non-image blocks of a result: not shown (the desktop sends no bytes for them).
- Reading the file on disk that a Read named: the picture is the one in the transcript, never a path.
- Showing a text Read's content without expanding: a file's text is long and the run would no longer be a summary.
- Pictures the user attached to their own message (`[Image #1]`): another feature.

## Decisions

### D1. Protocol, as the phone reads it

- `Entry.Result.images: List<ResultImage(mediaType, size)>` — from `images` when it is an array. Each element must be an
  object with a string `mediaType` and a `size` that is a JSON integer ≥ 0 (read strictly: `1.5` and `"5"` are not
  sizes — not through `JsonObject.long()`, which truncates). One malformed element drops **the whole list**: the
  positions after it would shift, and a wrong picture is worse than none. Absent, or not an array: empty.
- `Operations.IMAGES_READ = "images.read"`; `Frames.imagesRead(sessionId, toolUseId, index, offset, length, version)`;
  its answer decoded by `FileChunk` (which already allows a missing `name`).
- The chunk length is the caller's: `SentFileRead.read`'s `request` lambda is given `(offset, length, version)`, and
  `read` takes `chunk` — 64 KiB for sent files as today, **512 KiB for result pictures** (the desktop parses the whole
  record, up to ≈ 3 MB of base64, per chunk, so one or two chunks per picture instead of eight).
- Picture reasons, each with its own string in `values` and `values-zh-rTW`, so no sent-file string is ever shown for a
  picture: `NO_IMAGE` → "This picture is no longer in the conversation"; `FILE_CHANGED` after its one restart → "The
  picture changed while it was being read"; `TOO_LARGE` → "Too large to show on the phone (size)"; not connected and
  session gone → picture wordings of today's; a decoding failure → "This picture can't be shown"; `NO_CONVERSATION`,
  `BAD_PARAMS`, a malformed answer and any other code → "The desktop could not send this picture (code)".
- `Desktop.readResultImage(...)` is `readSentFile` with the other operation and chunk: same timeout per chunk, same
  cancellation. Both log through a pure `FileLog.fields(source, outcome)`: kind `FILES`, `source` (`sent` / `result`),
  outcome, the error code and size; never a name, path, media type or byte.

### D2. A picture reference names its source and title; a result picture is always an image in memory

`SentFileRef` becomes `(toolUseId, index, path: String?, source: Source = SENT, title: String?)`. For a result
picture `path` is null and `title` is set by D3's caller:

- a Read: its file name — "sp.png" (the picture is the file);
- any other call: "<tool title> · picture N" — "claude-in-chrome · computer · picture 1";
- a `LoneResult` (its call is not shown, so there is no tool title): "Result · picture N".

The same title names the picture for a screen reader: "Picture: sp.png", "Picture: claude-in-chrome · computer ·
picture 1".

`SentFileDialog` stops calling `Desktop.readSentFile` itself: it takes the reader from `LocalToolContext.read`, so a test
can replace it, and `ToolContext.read` picks `readSentFile` or `readResultImage` by source. For `RESULT` it:

- always reads into a `MemorySink` (never `CacheSink`) and always uses the image view, never `SentFiles.view()` — a
  result picture has no path, so the path-based kind would make it `OTHER`, write to the cache sink, then cast it to a
  memory sink and crash; and `view()` would hand an `image/svg+xml` off to another app;
- never hands the bytes to another app (spekterm's D8);
- decodes with `BitmapFactory` only when the media type is `image/png`, `image/jpeg`, `image/gif` or `image/webp`;
  anything else, or a decoding failure, is "This picture can't be shown";
- is titled by `ref.title`.

### D3. Where pictures show

- `ToolView.of(tool, result, list, imagesReadable)` — the new parameter is whether `images.read` is offered, carried by
  a new `ToolContext.imagesReadable` (set where `ToolContext` is built, `ConversationScreen.kt`, beside
  `canReadFiles`), and part of the `remember` keys of its callers. `ToolView` gains `pictures: List<ResultImage>`: the
  result's `images` when `imagesReadable` and the result is not an error, else empty; set in `of` **and in `fallback`**,
  so MCP tools have them. A `LoneResult` shows its pictures under its line too.
- **A lone call**: the pictures under its line, collapsed or expanded, before the parts; at most 240 dp wide and 360 dp
  tall, by aspect (found during apply: uncapped, a portrait screenshot stood ~530 dp tall on the
  emulator; capped, it narrows to about 160 dp), and never wider than its own pixels (found in 2.3: a 300 px picture
  was blown up to 240 dp), at most 2 per row; until decoded, a 4:3 placeholder.
- **A run, collapsed**: under the latest call's line, one row of fixed 72 × 72 dp tiles, the picture cropped to fill
  (`ContentScale.Crop`), latest call first, 6 dp apart. How many is known before any picture is read: with `W` the row's
  width (`BoxWithConstraints`) and `P` the run's pictures, show `n = min(3, P, floor((W + 6) / 78))` tiles when all `P`
  fit, else `n = min(3, floor((W + 6 − 54) / 78))` tiles and a 48 × 72 dp "+N" tile with `N = P − n`. On a 412 dp phone the
  row is about 366 dp (12 dp padding each side and the 22 dp chevron off), so 3 tiles and "+N". The "+N" tile expands
  the run. A picture over the size limit or failed is the same tile holding an icon (and the size for a large one);
  tapping it does what the full-size card does. Only the `n` shown are read.
- **A run, expanded**: each call's pictures under its line, as for a lone call. The parts of an expanded run are drawn
  in one list item, so **expanding a run reads all of its pictures**; that is the user's explicit ask, and the collapsed
  strip (what the conversation shows unasked) reads at most 3.
- **Reading**: decided from the entry's `size` before any request — up to `THUMB_LIMIT` (2 MiB) read by itself; larger,
  a card with the size, read on a tap. Strip tiles decode to at most 256 px on the longer side, the others to
  `THUMB_PIXELS` (600; power-of-two sampling may leave up to 1,199). The two are cached apart (D2's key carries the size:
  `r:<id>:<index>:s` and `:m`), so a tile seen collapsed is not stretched once the run is expanded.
- **Memory**: result pictures go in their own cache, not the sent files' map (sent pictures keep `sent-files`' "read
  once per conversation shown"). It is an LRU holding at most 24 decoded pictures **not currently composed**: each
  picture on screen pins its entry (a `DisposableEffect` counts it in and out), and only unpinned entries are evicted,
  least recently unpinned first; an evicted picture is read again when it is composed again. So an expanded run of 30
  screenshots holds all 30 while shown, and at most 24 once it is collapsed or scrolled away. Measured on 2,961 of
  kewang's pictures decoded to 600 px: median 1.38 MB, p90 2.40 MB, largest 5.32 MB; 24 unpinned pictures take about
  35–60 MB, about 100 MB at worst.
- **Following**: a picture that finishes decoding changes its item's height (a portrait screenshot grows from the
  placeholder's ~180 dp to 360 dp). When the screen follows the newest (`conversation-view`), it scrolls to the newest
  again after a picture of the last item is drawn.
- **Accessibility**: each picture is one node with the image role, described by its title (D2); a strip's "+N" tile is
  a button "N more pictures, expand".
- **Gating**: with `images.read` not offered, `pictures` is empty: no picture, no card, no strip.

Alternative: pictures only when expanded. Rejected: kewang's complaint is precisely that they are not there; the
Claude Code app shows them inline. Alternative: every picture of a collapsed run at full size. Rejected: a run of ten
screenshots would turn the conversation into a gallery; the strip says "there are pictures" and opens them.
Alternative: pictures in an expanded run only on a tap. Rejected: an expanded run is where the user went to look.
Alternative: strip tiles sized by aspect. Rejected: the desktop sends no dimensions, so how many fit is unknown until
they are read, and reading only those shown becomes impossible.

### D4. A text Read's content

Read's expanded parts become: the path, then the result. The view keeps the result as an "Output" block marked
`numbered`; parsing happens only when the part is drawn (expanded), in a pure `ReadLines.parse(text)` remembered by the
text, so a collapsed call does no work and a 2,000-line read is parsed once per expansion. `parse`:

1. a leading `<system-reminder>…</system-reminder>` block (one or more, with the newline after each) is taken off and
   kept as a note shown muted above the content — it is the CLI's note to the model (a memory file's age, the
   empty-file warning), not the file;
2. a trailing blank line followed by one bracketed line (`[This file is the saved source of artifact …]`) is taken off
   and kept as a muted note under the content;
3. the rest parses as numbered when every line matches `^\s*(\d+)\t(.*)$` (also `→` for older CLIs) and the numbers go
   up by one. Anything else is shown as it came, under "Output". With nothing left after 1 (the empty-file warning
   alone) the note is shown as the content, unchanged.

Numbered lines are shown under "Content" as a `Row` of two columns inside the part's vertical scroll: the gutter, one
`Text` of the numbers joined by newlines, right-aligned and muted; and the text, one `Text` of the lines joined by
newlines in a `SelectionContainer`, not wrapped and scrolling sideways, so each number stays beside its line and copying
gives the lines with newlines and no numbers. **Only within Compose's text bounds**: a `Text` laid out wider than 8,191
px gets a lower height cap (65,534 px; 32,766 above 32,767 px wide; 8,190 above 65,535 px wide) and silently loses
lines. So the two-column view is used only when the longest line is at most 400 characters and there are at most 1,500
lines (12 sp monospace is about 7.2 dp a character, 19 px at the Pixel's 2.625 density: 400 characters stay under
8,191 px wide, and 1,500 lines of 16 sp under 65,534 px tall); above either, the numbered lines are shown
wrapped under "Content" as `n  line` rows built from the parse, in pieces of at most 2,000 characters as
`SentFileViewer.PIECE` does — numbers then copy with the text. (Of 3,710 numbered Reads measured, 266 have a line wider
than 8,191 px.)

For a screen reader the part is one node, as every part is: "Content, " followed by the lines without numbers (the
notes, when present, are their own muted nodes). An error result stays "Error". A call with pictures shows no "Output"
part when its text is blank; text beside pictures (an MCP tool's) is shown as "Output" as today.

Alternative: strip the numbers and show plain text. Rejected: with `offset` the numbers tell where in the file the
lines are, which the line summary only gives when `offset` was passed.

### D5. The CLI's picture note

In `ConversationItems.group`, a `User` entry with `meta` true, no `origin`, and whole text matching
`^\[Image: original \d+x\d+, displayed at \d+x\d+\. Multiply coordinates by [\d.]+ to map to original image\.\]$` is
skipped: no item, and the run being formed stays open. **The anchor check still runs for it**: a reply's anchor is the
key of the last entry when it was sent (`Replies.kt`), and after an image Read that is often this note; when the key is
in `anchors`, the run is closed and the anchor goes on the last item shown, as for any entry. Only that exact shape:
any other meta text is shown as today. Not tied to `images` (an older desktop sends the note too, and it is noise there
as well). `conversation-view`'s run rule names the note among what may sit between calls.

### D6. An older desktop

The test is the operations list: `images.read` not offered (`imagesReadable` false, D3). Then a Read whose `file_path`
is an image by extension (`SentFiles.kind`), whose result is not an error and has blank text, shows, expanded, the note
"Seeing pictures on the phone needs spekterm X or later" (X from spekterm's release, filled in task 3.1). A new desktop
that sends no `images` for a result simply has no picture there. Nothing else changes against an older desktop.

### D7. Test seams and the shared file

- `ThumbKey.of(ref, size)`, `FileLog.fields`, `ReadLines.parse`, the strip count `Strip.count(width, pictures)` and
  `ResultImage` decoding are pure Kotlin (`ThumbKey` and `Strip` in `ui/`, beside `SentFileRef`; the rest in
  `desktop/` and `net/`), for JVM tests.
- The result-picture LRU with pinning is a plain class (`PictureCache`) tested on the JVM.
- `ShownRow` (the composable that draws one shown item: a call, a run, a lone result) becomes `internal`, so an
  instrumented test can draw a lone call and a run with a fake `ToolContext.read`.
- `ConversationScreen.kt` is shared with the `model-effort-switch` session. This change touches there only `ShownRow`,
  `CallLine`, `RunItem`, `ResultLine`, `viewing` and the dialog it opens (~lines 183, 316, D8), the cache created
  beside `thumbs` (~line 179), the `ToolContext` built at
  ~line 263 (`imagesReadable`, the picture cache) and the follow-after-decode hook beside `toNewest` (~line 406); that
  session is told so.

### D8. Swiping between pictures in the full-screen view

Added after kewang's first try on the Pixel (2026-10-25): with a picture open full screen, swiping left or right shows
the conversation's other tool-result pictures.

- **Which pictures**: the same refs and titles the conversation uses for tool-result pictures (D2, D3), in conversation
  order — each lone call's, each run's calls' (all of a collapsed run's, not only the strip's three), each lone
  result's. Opened from a collapsed run's strip, whose tiles are latest first, swiping left goes on to the next call
  in the conversation, the opposite of the strip's order; kept, since the order is the conversation's.
  - A pure `ConversationItems.pictureSources(items, imagesReadable)` walks the shown items in order and gives a
    `Source.Call(tool, result)` or `Source.Lone(result)` for each whose result has pictures by
    `ToolView.pictures(result, imagesReadable)` (so a failed call, a call without a result, a `Message` and an older
    desktop give none). A picture already given by `(toolUseId, index)` is not given again (a second result with the
    same id becomes a `LoneResult` with the same refs).
  - Pictures the agent sent you (`SendUserFile`) are not in it: they are files, with their own viewer and hand-off;
    opening one stays as it is.
- **Where the list is built**: in `ConversationScreen`, when `viewing` becomes a `RESULT` ref, the sources are taken
  once — `remember(viewing) { pictureSources(group(c.entries, ConversationReducer.keys(c.entries), emptySet()), imagesReadable) }`
  (anchors do not change the order of calls and results) — and mapped in composition with D3's `picturesOf`
  functions to `ShownPicture`s (the ref with its title and the `ResultImage` with its size). The opened picture is
  found by `(toolUseId, index)`, not by `==`
  (the ref's title takes part in equality); not found, the list is that one picture. Pictures that arrive while the
  view is open join on the next open.
- **The view**: `SentFileDialog(sessionId, ref, pictures: List<ShownPicture> = emptyList(), onClose)`. For a `RESULT`
  ref with more than one picture in `pictures` it is a `HorizontalPager` over them, opened at the tapped one, keyed by `(toolUseId, index)`. Each
  page reads and decodes its own picture as one does today (memory sink, screen-sized decode, the same failure words).
  `beyondViewportPageCount = 1`, so the neighbours are ready.
  - Reading follows D3's rule, by distance, not by being composed (the pager may compose a prefetched page further
    away): a page whose picture is up to `THUMB_LIMIT` (2 MB, by its `ResultImage.size`) is read once
    `abs(page − currentPage) <= 1` (`LaunchedEffect` keyed on that), so the one shown and its neighbours; a larger one
    shows its size (as in the conversation) and is read only once `page == settledPage` — reaching it full screen is
    the tap. A composed page further away shows a spinner and reads nothing. The spec's sentence says so.
  - Held: at rest the current page and one either side, so at most 3 decoded pictures; during a swipe the pager may
    also compose the pages in view and one prefetched ahead, so up to 5. Each is decoded to the screen's longer side,
    ≈ 10 MB for a 1080 × 2400 picture. A page further away drops its picture and reads it again if shown again.
- **The title**: the current page's (`pagerState.currentPage`) title, and its place "3 / 8" (`picture_position`),
  which a screen reader hears as "Picture 3 of 8" (`picture_position_description`).
- **Gestures**: one `(scale, offset)` held by the dialog applies to `currentPage`; when `settledPage` changes it goes
  back to 1×. The picture's handler is its own `awaitEachGesture` loop, not `detectTransformGestures` (which consumes
  every move past the slop): it handles and consumes a change only while two or more pointers are pressed or
  `scale > 1`, so at 1× a one-finger drag reaches the pager and changes the picture. Zoomed in, the pager's
  `userScrollEnabled` is false, so a drag pans.
- **One picture, or a sent file**: no pager and no position; a sideways swipe changes nothing.

## Risks / Trade-offs

- [spekterm's shipped document differs from its draft] → task 1.1 reads `docs/remote-protocol.md` first and adjusts D1.
- [Expanding a run of many screenshots reads them all, on a mobile connection] → the CLI's scaling keeps each at
  ≤ ≈ 512 KB; the collapsed strip reads at most 3; nothing is read again while it stays in the cache.
- [A 512 KiB chunk (≈ 700 KB of base64 on the wire) within the 30 s per-chunk timeout on a slow mobile link] → about
  190 kbit/s is enough; below that the read fails as "not connected" and a tap retries. Not lowered to 64 KiB, which
  makes the desktop parse the record eight times.
- [The CLI changes its numbered format or its notes] → the parser falls back to the raw text; tests pin the shapes
  measured.
- [The CLI rewords its picture note] → it shows as an injected message again, as today; a test pins the current text.
- [An expanded run of very many pictures holds them all while shown] → pinned on purpose (evicting what is on screen
  would leave spinners or thrash); released when the run is collapsed or scrolled away.
- [Swiping through many pictures on a mobile link] → only the pictures in view, one either side and one prefetched
  are read, each ≤ ≈ 512 KB after the CLI's scaling; a picture over 2 MB only once it is the current one (D8).
- [Several screen-sized pictures in memory while swiping] → at most 5 composed pages, ≈ 10 MB each, dropped as the
  pager moves on (D8).
- [Two sessions edit the same checkout] → `model-effort-switch` was told which parts of the shared files this change
  owns; commits stage explicit paths.

## Open Questions

- The spekterm release that ships `remote-result-images`, for D6's note and the README: known when spekterm releases
  it.
