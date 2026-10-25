#!/usr/bin/env node
// Summarizes an exported connection log (the app's "Export log", or `adb shell run-as com.spekterm.android.debug cat
// files/log/connection.log`) for the overnight measurement (design D9, task 6.4).
//
//   node tools/analyze-log.mjs <log file> [--from <ISO time>] [--to <ISO time>] [--offset-ms <phone minus desktop>]
//
// --offset-ms corrects the attention delays for the clocks' difference: the app logs phone receive time minus
// the desktop's `at`, so a phone clock ahead by 1230 ms is `--offset-ms 1230`.
import { readFileSync } from 'node:fs'

const args = process.argv.slice(2)
const option = (name, fallback) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 ? args[i + 1] : fallback
}
const file = args.find((a, i) => !a.startsWith('--') && !(i > 0 && args[i - 1].startsWith('--')))
if (!file) {
  console.error('usage: analyze-log.mjs <log file> [--from ISO] [--to ISO] [--offset-ms N]')
  process.exit(2)
}
const from = option('from') ? Date.parse(option('from')) : -Infinity
const to = option('to') ? Date.parse(option('to')) : Infinity
const offsetMs = Number(option('offset-ms', '0'))

const entries = readFileSync(file, 'utf8')
  .split('\n')
  .filter(Boolean)
  .map((line) => {
    const [time, kind, ...rest] = line.split(' ')
    const fields = Object.fromEntries(rest.map((pair) => pair.split('=')))
    return { t: Date.parse(time), time, kind, fields }
  })
  .filter((e) => Number.isFinite(e.t) && e.t >= from && e.t <= to)

if (entries.length === 0) {
  console.log('no entries in range')
  process.exit(0)
}

const start = entries[0].t
const end = entries.at(-1).t
const span = end - start
const fmt = (ms) => {
  const s = Math.round(ms / 1000)
  return s >= 3600 ? `${Math.floor(s / 3600)}h${String(Math.floor((s % 3600) / 60)).padStart(2, '0')}m` : s >= 60 ? `${Math.floor(s / 60)}m${s % 60}s` : `${(ms / 1000).toFixed(1)}s`
}

// Connected intervals: from `watching` (authenticated and answered) to the next `disconnected`/`service_stop`.
let connectedSince = entries[0].kind === 'watching' ? start : null
let connectedMs = 0
const disconnections = []
const reconnects = []
let lostAt = null
for (const e of entries) {
  if (e.kind === 'watching') {
    if (connectedSince === null) connectedSince = e.t
    if (lostAt !== null) {
      reconnects.push(e.t - lostAt)
      lostAt = null
    }
  } else if (e.kind === 'disconnected' || e.kind === 'service_stop') {
    if (connectedSince !== null) {
      connectedMs += e.t - connectedSince
      connectedSince = null
    }
    if (e.kind === 'disconnected') {
      disconnections.push({ time: e.time, cause: e.fields.code ? `close ${e.fields.code}` : (e.fields.failure ?? e.fields.error ?? '?'), idle: e.fields.idle })
      lostAt = e.t
    }
  }
}
if (connectedSince !== null) connectedMs += end - connectedSince

const attention = entries.filter((e) => e.kind === 'attention')
const delays = attention.map((e) => Number(e.fields.delayMs) - offsetMs).filter(Number.isFinite)
const sorted = [...delays].sort((a, b) => a - b)
const pct = (p) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))] : NaN)
const outcomes = attention.reduce((acc, e) => ({ ...acc, [e.fields.outcome]: (acc[e.fields.outcome] ?? 0) + 1 }), {})
const bySource = attention.reduce((acc, e) => ({ ...acc, [e.fields.source]: (acc[e.fields.source] ?? 0) + 1 }), {})

const battery = entries.map((e) => Number(e.fields.battery)).filter(Number.isFinite)
const idleOn = entries.filter((e) => e.kind === 'idle_mode' && e.fields.idle === 'true').length
const attempts = entries.filter((e) => e.kind === 'attempt').length
const failed = entries.filter((e) => e.kind === 'unreachable' || e.kind === 'pin_mismatch').length
const buckets = [...new Set(entries.map((e) => e.fields.bucket).filter(Boolean))]

console.log(`range            ${entries[0].time} → ${entries.at(-1).time} (${fmt(span)})`)
console.log(`connected        ${fmt(connectedMs)} (${((100 * connectedMs) / span).toFixed(1)}% of the range)`)
console.log(`disconnections   ${disconnections.length}`)
for (const d of disconnections) console.log(`  ${d.time}  ${d.cause}${d.idle === 'true' ? '  (in idle)' : ''}`)
console.log(`reconnect time   ${reconnects.length ? reconnects.map(fmt).join(', ') : '—'}`)
console.log(`attempts         ${attempts} (${failed} failed)`)
console.log(`attention        ${attention.length} — ${JSON.stringify(outcomes)} by source ${JSON.stringify(bySource)}`)
if (delays.length) {
  console.log(`delay (offset ${offsetMs} ms)  min ${fmt(sorted[0])}  median ${fmt(pct(50))}  p90 ${fmt(pct(90))}  max ${fmt(sorted.at(-1))}`)
  for (const e of attention) console.log(`  ${e.time}  seq ${e.fields.seq}  ${e.fields.kind}  ${fmt(Number(e.fields.delayMs) - offsetMs)}  ${e.fields.source}  ${e.fields.outcome}${e.fields.idle === 'true' ? '  (in idle)' : ''}`)
}
console.log(`battery          ${battery.length ? `${battery[0]}% → ${battery.at(-1)}%` : '—'}`)
console.log(`idle entries     ${idleOn} (entered deep idle)    standby buckets seen: ${buckets.join(', ') || '—'}`)
