# tools

## `drop-intake.mjs`

Desktop-side helper for the connection spike's measurements (`openspec/changes/connection-spike`, design D9).
It drops an inbox item into the intake drop point of spekterm run from source (`npm run dev` in
`~/git/spekterm`, profile `~/.config/spekterm-dev`) every N minutes. Each item raises an `intake` attention
event; the phone's connection log records when it arrived, against the item's drop time.

```sh
node tools/drop-intake.mjs --count 1          # one item now
node tools/drop-intake.mjs --every 30         # every 30 minutes until Ctrl-C (overnight run)
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
