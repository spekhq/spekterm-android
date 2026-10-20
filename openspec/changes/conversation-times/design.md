## Context

`Entry` (`net/Sessions.kt`) carries `at`: epoch milliseconds from the transcript record's own timestamp, `0` when the
desktop could not read one (spekterm `src/main/transcript-follow.ts` `project()`: `Date.parse(timestamp) || 0`, the
same for user, queued, text, thinking, tool and result entries; present since before the remote bridge). The
protocol says `at` is not an ordering key, and in practice times do go backwards: a message typed while the agent
worked is recorded with its typing time where the agent took it in, often before the preceding tool result (a scan of
476 local transcripts found 405 such steps, up to 400 s). `ConversationItems.group` turns entries into shown items
(`Call`, `Run`, `LoneResult`, `Plain`); `ConversationScreen.items` (private) interleaves the user's own replies
(`Item.Mine`, not yet or never confirmed in the conversation) and the `LazyColumn` renders `list` by key, following
the newest with `count = list.size + working`. `minSdk` is 29, so `java.time` is available. Other screens format
times with `java.text.DateFormat` in the system's default locale (the app's language when one is set per app,
`app-language`); a phone in a language the app does not speak gets that language's date format with English text,
as on those screens.

## Goals / Non-Goals

**Goals:**

- See at a glance when the user last wrote and when the agent last answered.
- See where one day ends and the next begins in a long conversation.
- Day words that are right whenever the screen is looked at — after a night asleep, a time zone change, a clock change.

**Non-Goals:**

- Times on tool calls, thinking, results or injected messages (they would bury the messages' times).
- Relative times ("5 min ago") that need a ticking clock per row.
- A time on a reply that is not in the conversation: it has no recorded time, and the phone's send time would read as
  if it were the agent's record.
- Any change on the desktop.

## Decisions

### D1. Which items carry a time

A `Plain` item whose entry is the user's own message (`Entry.User`, `meta == false`) or the agent's text
(`Entry.Text`), with `at > 0`. Shown as one line under the message — under the bubble, aligned to its end, for the
user; under the text, aligned to the start, for the agent — in `labelSmall`, `onSurfaceVariant`, as plain `Text`.
Formatted with `android.text.format.DateFormat.getTimeFormat(context)` (the default locale, as other screens; it also
follows the phone's 12/24-hour setting) with its time zone set to the zone of D3. Other screens' `SHORT` times do not
follow the 12/24-hour setting; that difference is accepted here, since these times are read most. *Alternative:* a
time on the last text of a turn only — rejected: which text ends a turn is not known while the turn runs, and each
text is its own message on screen.

### D2. Day headings, as pure functions

`ConversationTimes` (in `desktop`), pure and JVM-tested:

- `timeOf(shown): Long?` — D1's rule.
- `startOf(shown): Long` — the item's first entry's `at` (a run's first part, which is always a call).
- `place(items: List<Line>, zone): List<Line>` over the screen's lines (`Line` = a shown item or a reply, the same order
  `ConversationScreen.items` builds today, which moves into `desktop` as `ConversationTimes.rows` so it can be
  tested), returning them with `Day(date, key = "day:" + <headed item key>)` lines inserted. Thinking and tool
  calls count for the days through `startOf` though they show no time. Walking in order: a shown
  item with `startOf > 0` whose local date differs from the last date seen, or the first such item, is headed.
  Items with `at <= 0` and replies are never headed and leave the last date unchanged.

So **a day is headed again when times go backwards across midnight** (Mon, Tue, Mon gives three headings): that is
what the order shows, and the spec says so. The headings are part of `list` itself, so `count` covers them and
following the newest is unchanged; keys stay unique because they name the headed item. Two visible results are
accepted: a settled reply whose anchor is gone sits at the top, above the first heading; and a reply still pending
sits under the last heading (possibly "Yesterday"), with a "Today" heading appearing above it once it is confirmed
as today's first message.

### D3. The heading's words, and keeping "today" right

`ConversationTimes.dayWord(date, today): DayWord` — `Today`, `Yesterday`, or `Date(date)`; a date is written with
`DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)` in the default locale. A heading is a small `internal`
composable `DayHeading(word)`, announced as a heading (`semantics { heading() }`).

The screen holds one state `Clock(today: LocalDate, zone: ZoneId)`, read from the system, and re-read:

1. when the screen's lifecycle reaches `ON_RESUME` (back from the background or from a night with the screen off —
   coroutine delays use uptime, which stops in deep sleep, so a delay alone would be hours late);
2. on `ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED` and `ACTION_DATE_CHANGED`, from a receiver registered in a
   `DisposableEffect` while the screen is composed;
3. as a backstop while the screen stays on, at the next local midnight, by a delay of `ConversationTimes.untilMidnight(now, zone)` —
   computed as `today.plusDays(1).atStartOfDay(zone)` minus now, never +24 h, so a DST change at midnight is right.

Correctness rests on 1 and 2 (`ACTION_DATE_CHANGED` is sent at midnight while the phone is awake); the delay of 3
uses uptime and can run late after sleep, so it is restarted on every re-read (keyed on a counter bumped by each
re-read, not on the `Clock` value, which a same-day re-read leaves unchanged). Headings (D2) and message times (D1)
both use `Clock.zone`, so they never disagree.

### D4. Older desktops and missing times

Every desktop that sends the conversation fills `at`; one with no readable record time sends `0`. Such an item shows
no time and heads no day, so a conversation with no times looks exactly as before.

## Risks / Trade-offs

- [A run of tool calls that crosses midnight] → the run is one item, headed by its first call's day; expanding it shows
  no day boundary inside. Its calls carry no times anyway.
- [Times going backwards repeat a date heading] → kept rather than reordering what the agent recorded (D2).
- [The phone's zone differs from the desktop's] → times are shown in the phone's zone, where the user is reading.
- [12/24-hour differs from other screens] → accepted (D1).
