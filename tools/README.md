# tools

## `drop-intake.mjs`

Desktop-side helper for the connection spike's measurements (`openspec/changes/connection-spike`, design D9).
It drops an inbox item into the intake drop point of spekterm run from source (`npm run dev` in
`~/git/spekterm`, profile `~/.config/spekterm-dev`) every N minutes. Each item raises an `intake` attention
event; the phone's connection log records when it arrived, against the item's drop time.

```sh
node tools/drop-intake.mjs --count 1          # one item now
node tools/drop-intake.mjs --every 30         # every 30 minutes until Ctrl-C (overnight run)
node tools/drop-intake.mjs --count 1 --title "Deploy is red" --body-file body.txt   # one item with that title and body
node tools/drop-intake.mjs --count 1 --origin "#weather-team" --dir <profile>/Spekterm/intake-inbox   # its origin, another profile
```

- Each item has a unique id; the desktop silently drops a repeated id.
- The desktop holds **at most 200 pending inbox items** and refuses deliveries beyond that, so clear the inbox
  (ignore the probe items on the desktop) between nights.
- Compare the desktop's and the phone's clocks before reading delays: `timedatectl` / `chronyc tracking` here,
  automatic time on the phone.

## `analyze-log.mjs`

Summarizes an exported connection log for the overnight measurement: time connected, each disconnection and
its cause, reconnection times, attempts, every attention event with its delivery delay, battery, idle.

```sh
adb shell run-as com.spekterm.android cat files/log/connection.log > night.log   # debug build
node tools/analyze-log.mjs night.log --from 2026-10-14T15:00:00Z --offset-ms 1230
```

`--offset-ms` is the phone's clock minus the desktop's (`adb shell date +%s%3N` against `date +%s%3N`), which
the logged delays include.

## `drop-handoff.mjs`

Desktop-side helper for the relations checks (`openspec/changes/session-relations`, task 4.1). It writes a handoff,
or a completion report, into a session's handoff outbox of spekterm run from source (`npm run dev` in
`~/git/spekterm`, profile `~/.config/spekterm-dev`), as an agent in that session would.

```sh
node tools/drop-handoff.mjs                                              # list the sessions, their folders and sources
node tools/drop-handoff.mjs --from <sessionId> --target spekterm-android # hand off to a folder (name or absolute path)
node tools/drop-handoff.mjs --from <childId> --report "Done: …"          # a handed-off session reports done
node tools/drop-handoff.mjs --from <sessionId> --target spekterm-android --at 2026-10-20T09:00:00+08:00   # scheduled
```

- The desktop opens the new session in the target folder, or holds the handoff in its inbox when its routing does
  not accept it on its own; accept it there.
- `--title` and `--body` set the handoff's title and note; `--profile` points at another profile.
