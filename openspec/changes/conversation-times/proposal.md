## Why

The conversation screen shows what was said but not when. Away from the desk, the user cannot tell whether the
agent's last answer came a minute ago or last night, or how long ago they themselves wrote — the first thing they
need to judge whether a session is stale or still moving. The desktop already sends each entry's time (`Entry.at`);
the app decodes it and throws it away.

## What Changes

- The user's own messages and the agent's text show the time they were recorded, small and muted under the message,
  in the phone's time format (12- or 24-hour as the phone is set).
- The conversation is divided by day: a heading ("Today", "Yesterday", or the date) before the first item of each
  day, in the phone's time zone, kept right across midnight, a night with the screen off, and date, time or time
  zone changes. When recorded times go back across midnight, a day is headed again rather than reordered.
- An entry the desktop sends without a time (`at` 0) shows none and starts no day; the screen looks as before for
  it. Thinking, tool calls, results and injected messages show no time of their own, but count for the days.
- Replies still waiting to appear in the conversation show no time: they are not in it yet.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `conversation-view`: a new requirement — the conversation shows when messages were recorded, and which day.

## Impact

- `desktop`: pure functions for which items carry a time, where day headings go (with the reply interleaving moved
  out of the screen so it can be tested), the day's word, and the time until midnight.
- `ui/ConversationScreen.kt`: the time line under the user's bubble and the agent's text; day heading items in the
  list; a clock re-read on resume, on the system's time broadcasts and at midnight. Strings for "Today" / "Yesterday" in `values` and `values-zh-rTW`.
- No protocol change: `Entry.at` (epoch milliseconds, 0 when unknown) is already decoded. Works with every desktop
  that sends the conversation.
- `README.md`'s conversation description.
