#!/usr/bin/env node
// Writes a handoff or a completion report into a session's handoff outbox on a spekterm desktop, as an agent in
// that session would, so the phone has handoff relations to show (`session-relations`, task 4.1). Desktop-side
// only; it never talks to the phone.
//
//   node tools/drop-handoff.mjs                                          # list the profile's sessions
//   node tools/drop-handoff.mjs --from <sessionId> --target <repo or path> [--title <t>] [--body <text>]
//   node tools/drop-handoff.mjs --from <sessionId> --report <summary>    # from a handed-off session: done
//
// Defaults to the dev profile that `npm run dev` in ~/git/spekterm uses (`--profile` to change it).
// Delivery format: spekterm's `src/main/handoff-delivery.ts` `parseHandoffPayload` ({target, title, body}) and
// `outboxKindOf` ({"kind": "report", "summary"}).
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { basename, join } from 'node:path'

const args = process.argv.slice(2)
const option = (name, fallback) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] !== undefined ? args[i + 1] : fallback
}
const profile = option('profile', join(homedir(), '.config', 'spekterm-dev', 'Spekterm'))
const outbox = join(profile, 'handoff', 'outbox')
const from = option('from')

function readJson(path, fallback) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'))
  } catch {
    return fallback
  }
}

if (!from) {
  const folders = new Map((readJson(join(profile, 'workspace.json'), {}).folders ?? []).map((f) => [f.id, basename(f.path)]))
  const sessions = readJson(join(profile, 'sessions.json'), {}).sessions ?? []
  if (sessions.length === 0) console.log(`No sessions in ${profile}/sessions.json.`)
  for (const s of sessions) {
    const where = s.folderId === null ? 'global' : (folders.get(s.folderId) ?? `? ${s.folderId}`)
    const parent = s.lineage?.parentId ? `  ← ${s.lineage.parentId}` : ''
    console.log(`${s.id}  ${s.spawnTarget}  ${where}  ${s.title ?? ''}${parent}`)
  }
  console.log('\nRepos for --target are folder names above, or absolute paths.')
  process.exit(0)
}

const report = option('report')
const now = new Date()
let payload
if (report !== undefined) {
  payload = { kind: 'report', summary: report }
} else {
  const target = option('target')
  if (!target) {
    console.error('--target <repo name or absolute path> is required for a handoff (or --report <summary>).')
    process.exit(2)
  }
  payload = {
    target,
    title: option('title', `Phone test handoff ${now.toISOString()}`),
    body: option('body', `A handoff written by tools/drop-handoff.mjs at ${now.toISOString()} for the phone's relations test.`),
  }
}

const dir = join(outbox, from)
if (!existsSync(dir)) console.warn(`warning: ${dir} does not exist yet; the desktop creates it when the session starts.`)
mkdirSync(dir, { recursive: true })
const name = `${report !== undefined ? 'report' : 'handoff'}-${now.toISOString().replace(/[:.]/g, '-')}`
// A name not ending in .json is not read: never a half-written file.
const temp = join(dir, `${name}.json.partial`)
writeFileSync(temp, JSON.stringify(payload))
renameSync(temp, join(dir, `${name}.json`))
console.log(`wrote ${join(dir, `${name}.json`)}`)
