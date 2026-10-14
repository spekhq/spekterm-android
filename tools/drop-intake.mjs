#!/usr/bin/env node
// Drops an inbox item into a spekterm desktop's intake drop point every N minutes, so the phone has attention
// events to deliver during a measurement (design D9). Desktop-side only; it never talks to the phone.
//
//   node tools/drop-intake.mjs [--every <minutes>] [--count <n>] [--dir <intake-inbox>]
//
// Defaults: every 30 minutes, until stopped, into the dev profile that `npm run dev` in ~/git/spekterm uses.
// Delivery format: spekterm's `src/main/intake-schema.ts` `parseIntake` (id, origin.kind, origin.id, body).
import { mkdirSync, renameSync, writeFileSync } from 'node:fs'
import { homedir, hostname } from 'node:os'
import { join } from 'node:path'

const args = process.argv.slice(2)
const option = (name, fallback) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] !== undefined ? args[i + 1] : fallback
}
const everyMinutes = Number(option('every', '30'))
const count = Number(option('count', 'Infinity'))
const dir = option('dir', join(homedir(), '.config', 'spekterm-dev', 'Spekterm', 'intake-inbox'))
const run = new Date().toISOString().replace(/[:.]/g, '-')

mkdirSync(dir, { recursive: true })

let n = 0
function drop() {
  n += 1
  const now = new Date()
  // A unique id per item: the desktop silently drops a repeated one, which would raise no event.
  const id = `spike-${run}-${n}`
  const item = {
    id,
    origin: { kind: 'spike-probe', id: hostname(), label: 'connection spike' },
    title: `Spike probe ${n}`,
    body: `Dropped at ${now.toISOString()} (epoch ms ${now.getTime()}) by tools/drop-intake.mjs, item ${n}.`,
    occurredAt: now.toISOString(),
  }
  // A name starting with a dot is skipped by the desktop until renamed: never read half-written.
  const temp = join(dir, `.${id}.json`)
  writeFileSync(temp, JSON.stringify(item))
  renameSync(temp, join(dir, `${id}.json`))
  console.log(`${now.toISOString()} dropped ${id}`)
  if (n >= count) process.exit(0)
}

drop()
setInterval(drop, everyMinutes * 60_000)
