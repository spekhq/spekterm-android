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
adb shell run-as com.spekterm.android.debug cat files/log/connection.log > night.log   # debug build
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
node tools/drop-handoff.mjs --from <sessionId> --target spekterm-android --repeat "0 21 * * 3"           # repeating proposal
node tools/drop-handoff.mjs --from <sessionId> --target spekterm-android --repeat "0 9 * * *" --until 2026-11-30T00:00:00+08:00
```

- The desktop opens the new session in the target folder, or holds the handoff in its inbox when its routing does
  not accept it on its own; accept it there.
- `--repeat <cron>` makes it a repeating handoff (spekterm 0.2.16): five fields in the desktop's time zone, the
  minute a single number (at most once an hour). The desktop holds it as a proposal until it is approved in the
  inbox's Schedules tab. With `--repeat`, `--at` is when the recurrence starts and `--until` when it ends.
- `--title` and `--body` set the handoff's title and note; `--profile` points at another profile.

## `play-publish.mjs`

Publishes a release on Google Play through the Play Developer API (`play-api-upload`), from the terminal: the upload,
the internal testing release and, after the maintainer's OK, the production release. The run sheet that calls it is
`.claude/skills/play-release/SKILL.md`; the one-time key setup is docs/play-release.md, "Once: publishing through the API".

```sh
node tools/play-publish.mjs check                              # releases with review states; changes nothing
node tools/play-publish.mjs internal                           # app-release.aab → internal testing, all testers
node tools/play-publish.mjs production --version-code 6        # internal testing's release 6 → production, sent for review
node --test tools/*.test.mjs                                   # its tests, against a fake Play
```

- The key: `~/.android/spekterm-play-publisher.json`, or `--key <path>`, or `SPEKTERM_PLAY_KEY_FILE` (a path). Only the
  service account's e-mail is printed; never read or paste the file itself.
- `internal` takes the version from `app/build.gradle.kts`, names the release `<versionName> (<versionCode>)`, and takes
  the notes from `play/release-notes/<locale>/default.txt` (≤ 500 characters each). It refuses notes equal to those of the
  release internal testing holds — `--same-notes` keeps them on purpose. `--bundle <path>` uploads another bundle.
- `production` copies the internal testing release of that version code, its name and notes, and refuses while
  production already has a release in review.
- Every commit asks Play to fail rather than cancel a review in progress (the API's default would cancel it). Refusals:
  - *changes in review, and this commit would have cancelled them* — wait for the review, or cancel it in Play Console.
  - *Play will not send these changes for review by itself* — run again with `--send-in-console`, then Play Console →
    Publishing overview → Send changes for review.
  - *no answer came back … outcome is unknown* — run `check` before anything else; never run the same command again
    blindly (a release that went through makes it fail as "version code already used").
  - HTTP 403 — the service account lacks a permission, or a new one has not taken effect yet (hours).
- Any other failure deletes the edit: nothing is half-done. `--dry-run` validates and deletes instead of committing; for
  `internal` it still uploads, which may use up the version code, so the run sheet does not use it.
