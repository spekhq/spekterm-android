#!/usr/bin/env node
// Publishes spekterm for Android on Google Play through the Play Developer API (`play-api-upload`): no browser, so no
// upload size limit from one. Desktop-side only; the maintainer's run sheet is .claude/skills/play-release/SKILL.md.
//
//   node tools/play-publish.mjs check                                   # releases and review states; changes nothing
//   node tools/play-publish.mjs internal [--bundle <aab>] [--same-notes] [--dry-run] [--send-in-console]
//   node tools/play-publish.mjs production --version-code <n> [--dry-run] [--send-in-console]
//
// The service account's JSON key: --key <path>, SPEKTERM_PLAY_KEY_FILE (a path), or ~/.android/spekterm-play-publisher.json
// (docs/play-release.md, "Once: publishing through the API"). Only its client_email is ever printed.
// Release notes: play/release-notes/<locale>/default.txt, at most 500 characters each (design D4).
// Every commit asks Play to fail rather than cancel a review in progress (design D5); every run that does not commit
// deletes its edit, unless the commit was sent and its answer never came (then: run check).
import { createSign } from 'node:crypto'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const PACKAGE = 'com.spekterm.android'
export const LOCALES = ['en-US', 'zh-TW']
export const NOTES_LIMIT = 500
export const TOKEN_URL = 'https://oauth2.googleapis.com/token'
export const SCOPE = 'https://www.googleapis.com/auth/androidpublisher'
const API = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}`
const UPLOAD_API = `https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/${PACKAGE}`
const REQUEST_TIMEOUT_MS = 60_000
// Google recommends at least 2 minutes for an upload; 5 is also the ceiling of Node's built-in fetch.
const UPLOAD_TIMEOUT_MS = 300_000
const IN_REVIEW = 'RELEASE_LIFECYCLE_STATE_IN_REVIEW'
const SETUP_DOC = 'docs/play-release.md, "Once: publishing through the API"'

/** A failure the script explains itself; its message is printed as it is. */
export class PublishError extends Error {}

/** Play (or the token endpoint) answered with an error. */
export class PlayError extends PublishError {
  constructor(what, status, body) {
    const error = body?.error
    let detail
    if (typeof error === 'string') detail = [error, body.error_description].filter(Boolean).join(': ')
    else if (error) detail = [error.message, error.status, reasonOf(body)].filter(Boolean).join(' — ')
    else detail = 'no error body'
    super(`${what} failed: HTTP ${status}: ${detail}`)
    this.status = status
    this.reason = reasonOf(body)
    this.text = typeof error === 'string' ? `${error} ${body.error_description ?? ''}` : error?.message ?? ''
  }
}

function reasonOf(body) {
  const details = body?.error?.details
  return Array.isArray(details) ? details.find((d) => d?.reason)?.reason : undefined
}

/** `versionCode` and `versionName` from the text of app/build.gradle.kts: each on one line of its own, once. */
export function readVersion(gradle) {
  const codes = [...gradle.matchAll(/^\s*versionCode\s*=\s*(\d+)\s*$/gm)]
  const names = [...gradle.matchAll(/^\s*versionName\s*=\s*"([^"\\]+)"\s*$/gm)]
  if (codes.length !== 1 || names.length !== 1) {
    throw new PublishError(
      'app/build.gradle.kts must hold one `versionCode = <number>` line and one `versionName = "<name>"` line ' +
        `(found ${codes.length} and ${names.length})`,
    )
  }
  return { code: codes[0][1], name: names[0][1] }
}

export const releaseName = (version) => `${version.name} (${version.code})`

const noteText = (text) => text.replace(/\s+$/, '')

/** The release notes, one per locale, checked against Play's limits; every problem named at once. */
export function readNotes(root) {
  const notes = []
  const problems = []
  for (const language of LOCALES) {
    const relative = `play/release-notes/${language}/default.txt`
    const path = join(root, relative)
    if (!existsSync(path)) {
      problems.push(`${relative}: missing`)
      continue
    }
    const text = readFileSync(path, 'utf8').replace(/\n+$/, '')
    const length = [...text].length
    if (!text.trim()) problems.push(`${relative}: empty`)
    else if (length > NOTES_LIMIT) problems.push(`${relative}: ${length} characters, over ${NOTES_LIMIT}`)
    notes.push({ language, text })
  }
  if (problems.length) throw new PublishError(`Release notes:\n  ${problems.join('\n  ')}`)
  return notes
}

/** Whether every locale's notes equal those of `release` (trailing white space ignored). */
export function sameNotes(notes, release) {
  const theirs = new Map((release?.releaseNotes ?? []).map((n) => [n.language, noteText(n.text ?? '')]))
  return notes.every((n) => theirs.get(n.language) === noteText(n.text))
}

export function commitQuery(sendInConsole) {
  return sendInConsole
    ? 'changesInReviewBehavior=ERROR_IF_IN_REVIEW&changesNotSentForReview=true'
    : 'changesInReviewBehavior=ERROR_IF_IN_REVIEW'
}

/** The signed assertion a service account trades for an access token (RS256, valid one hour). */
export function assertion(key, nowSeconds) {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url')
  const input = `${encode({ alg: 'RS256', typ: 'JWT' })}.${encode({
    iss: key.client_email,
    scope: SCOPE,
    aud: TOKEN_URL,
    iat: nowSeconds,
    exp: nowSeconds + 3600,
  })}`
  return `${input}.${createSign('RSA-SHA256').update(input).sign(key.private_key, 'base64url')}`
}

/** Reads the key file's two fields; its contents never reach the output. */
export function loadKey(path, warn) {
  if (!existsSync(path)) {
    throw new PublishError(`No service account key at ${path}. Set it up as ${SETUP_DOC} says.`)
  }
  if (statSync(path).mode & 0o077) warn(`Warning: ${path} is readable by others than you; chmod 600 it.`)
  let key
  try {
    key = JSON.parse(readFileSync(path, 'utf8'))
  } catch {
    throw new PublishError(`${path} is not a JSON key file.`)
  }
  if (typeof key.client_email !== 'string' || typeof key.private_key !== 'string') {
    throw new PublishError(`${path} has no client_email or private_key: not a service account key.`)
  }
  return { client_email: key.client_email, private_key: key.private_key }
}

const stateName = (state) => (state ?? 'unknown').replace(/^RELEASE_LIFECYCLE_STATE_/, '').toLowerCase().replaceAll('_', ' ')

/** Talks to Play: the token, then JSON requests. Errors carry Play's message, never a header. */
export class Play {
  constructor({ fetch, key, signal, now = () => Date.now() }) {
    this.fetch = fetch
    this.key = key
    this.signal = signal
    this.now = now
  }

  async token() {
    if (this.accessToken) return this.accessToken
    const body = new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: assertion(this.key, Math.floor(this.now() / 1000)),
    })
    const answer = await this.send('Signing in', TOKEN_URL, { method: 'POST', body, auth: false })
    if (!answer.access_token) throw new PublishError('Signing in failed: the answer held no access token')
    this.accessToken = answer.access_token
    return this.accessToken
  }

  /** One request; the parsed JSON answer (`{}` when empty), with `httpStatus` set. */
  async send(what, url, { method = 'GET', body, json, headers = {}, timeout = REQUEST_TIMEOUT_MS, auth = true, own = false } = {}) {
    const timeoutSignal = AbortSignal.timeout(timeout)
    const init = {
      method,
      headers: { ...headers },
      signal: own || !this.signal ? timeoutSignal : AbortSignal.any([this.signal, timeoutSignal]),
    }
    if (auth) init.headers.Authorization = `Bearer ${await this.token()}`
    if (json !== undefined) {
      init.headers['Content-Type'] = 'application/json'
      init.body = JSON.stringify(json)
    } else if (body !== undefined) init.body = body
    const response = await this.fetch(url, init)
    const text = await response.text()
    let parsed = {}
    try {
      parsed = text ? JSON.parse(text) : {}
    } catch {
      parsed = {}
    }
    if (!response.ok) throw new PlayError(what, response.status, parsed)
    return Object.assign(parsed, { httpStatus: response.status })
  }

  releases(track) {
    return this.send(`Reading ${track} releases`, `${API}/tracks/${track}/releases`).then((a) => a.releases ?? [])
  }

  async openEdit() {
    return (await this.send('Opening an edit', `${API}/edits`, { method: 'POST', json: {} })).id
  }

  deleteEdit(id) {
    return this.send('Deleting the edit', `${API}/edits/${id}`, { method: 'DELETE', own: true })
  }
}

const inReview = (releases) => releases.filter((r) => r.releaseLifecycleState === IN_REVIEW)
const codesOf = (release) => (release.activeArtifacts ?? []).map((a) => String(a.versionCode)).join(', ') || 'none'

/**
 * Runs `work` in an edit: on any failure, or an interrupt, before the commit is sent the edit is deleted; when the
 * commit request gets no answer, the outcome is unknown and the edit is left alone.
 */
async function inEdit(play, out, work) {
  const id = await play.openEdit()
  const stage = { committing: false }
  try {
    return await work(id, stage)
  } catch (error) {
    if (stage.committing && !(error instanceof PlayError)) {
      throw new PublishError(
        `The commit was sent but no answer came back (${error.message}): its outcome is unknown. ` +
          'Run `node tools/play-publish.mjs check` before anything else.',
      )
    }
    try {
      const deleted = await play.deleteEdit(id)
      out(`Edit deleted (HTTP ${deleted.httpStatus}); nothing was committed.`)
    } catch (deleteError) {
      out(`The edit could not be deleted (${deleteError.message}); the next edit replaces it.`)
    }
    if (error instanceof PlayError && error.reason === 'CHANGES_ALREADY_IN_REVIEW') {
      throw new PublishError(
        'Play has changes in review, and this commit would have cancelled them: nothing was committed. ' +
          'Wait for the review, or cancel it in Play Console (Publishing overview), then run this again.',
      )
    }
    if (error instanceof PlayError && error.text.includes('changesNotSentForReview')) {
      throw new PublishError(
        `${error.message}\nPlay will not send these changes for review by itself: run this again with ` +
          '--send-in-console, then send them from Play Console → Publishing overview.',
      )
    }
    throw error
  }
}

async function commit(play, id, stage, { dryRun, sendInConsole }, out) {
  await play.send('Validating the edit', `${API}/edits/${id}:validate`, { method: 'POST' })
  if (dryRun) {
    const deleted = await play.deleteEdit(id)
    out(`Dry run: the edit is valid and was deleted (HTTP ${deleted.httpStatus}); nothing was committed.`)
    return false
  }
  stage.committing = true
  await play.send('Committing the edit', `${API}/edits/${id}:commit?${commitQuery(sendInConsole)}`, { method: 'POST' })
  stage.committing = false
  return true
}

export async function check(play, out) {
  out(`Service account: ${play.key.client_email}`)
  for (const track of ['internal', 'production']) {
    const releases = await play.releases(track)
    out(`${track}: ${releases.length ? '' : 'no releases'}`)
    for (const r of releases) out(`  ${r.releaseName ?? '(no name)'} — ${stateName(r.releaseLifecycleState)} — version codes ${codesOf(r)}`)
  }
  const id = await play.openEdit()
  try {
    const tracks = (await play.send('Reading the tracks', `${API}/edits/${id}/tracks`)).tracks ?? []
    for (const { track, releases = [] } of tracks.filter((t) => ['internal', 'production'].includes(t.track))) {
      out(`${track} in an edit:`)
      for (const r of releases) {
        out(`  ${r.name ?? '(no name)'} — ${r.status} — version codes ${(r.versionCodes ?? []).join(', ') || 'none'}`)
        for (const n of r.releaseNotes ?? []) out(`    <${n.language}>\n${n.text.replace(/^/gm, '      ')}`)
      }
    }
  } finally {
    const deleted = await play.deleteEdit(id)
    let gone = 'still there'
    try {
      await play.send('Reading the deleted edit', `${API}/edits/${id}`, { own: true })
    } catch (error) {
      if (error instanceof PlayError) gone = `gone (HTTP ${error.status})`
    }
    out(`Edit deleted (HTTP ${deleted.httpStatus}); asked for again, it is ${gone}. Nothing was committed.`)
  }
}

export async function internal(play, out, { root, bundle, sameNotes: keepNotes, dryRun, sendInConsole }) {
  const version = readVersion(readFileSync(join(root, 'app/build.gradle.kts'), 'utf8'))
  const notes = readNotes(root)
  if (!existsSync(bundle)) throw new PublishError(`No bundle at ${bundle}: run ./gradlew bundleRelease first.`)
  out(`Service account: ${play.key.client_email}`)
  const reviewing = inReview(await play.releases('production'))
  if (reviewing.length) {
    out(`Warning: production has ${reviewing.map((r) => r.releaseName).join(', ')} in review; Play may refuse this commit.`)
  }
  const release = { name: releaseName(version), versionCodes: [version.code], status: 'completed', releaseNotes: notes }
  return inEdit(play, out, async (id, stage) => {
    const current = (await play.send('Reading internal testing', `${API}/edits/${id}/tracks/internal`)).releases ?? []
    if (!keepNotes && current.length && sameNotes(notes, current[0])) {
      throw new PublishError(
        `The release notes are the same as internal testing's ${current[0].name}. Write the new release's notes into ` +
          'play/release-notes, or pass --same-notes to keep them.',
      )
    }
    out(`Uploading ${bundle} …`)
    const uploaded = await play.send('Uploading the bundle', `${UPLOAD_API}/edits/${id}/bundles?uploadType=media`, {
      method: 'POST',
      body: readFileSync(bundle),
      headers: { 'Content-Type': 'application/octet-stream' },
      timeout: UPLOAD_TIMEOUT_MS,
    })
    if (String(uploaded.versionCode) !== version.code) {
      throw new PublishError(
        `The bundle has version code ${uploaded.versionCode}, app/build.gradle.kts says ${version.code}: rebuild it.`,
      )
    }
    await play.send('Setting internal testing', `${API}/edits/${id}/tracks/internal`, {
      method: 'PUT',
      json: { track: 'internal', releases: [release] },
    })
    if (await commit(play, id, stage, { dryRun, sendInConsole }, out)) {
      out(`Internal testing: ${release.name}, version code ${version.code}, released to all testers.`)
      if (sendInConsole) out('Its changes wait in Play Console → Publishing overview: send them for review there.')
    }
    return release
  })
}

export async function production(play, out, { versionCode, dryRun, sendInConsole }) {
  if (!/^\d+$/.test(String(versionCode ?? ''))) throw new PublishError('production needs --version-code <number>.')
  const code = String(versionCode)
  out(`Service account: ${play.key.client_email}`)
  const reviewing = inReview(await play.releases('production'))
  if (reviewing.length) {
    throw new PublishError(
      `Production has ${reviewing.map((r) => r.releaseName).join(', ')} in review: wait for it, or cancel it in ` +
        'Play Console, before releasing another.',
    )
  }
  return inEdit(play, out, async (id, stage) => {
    const tested = (await play.send('Reading internal testing', `${API}/edits/${id}/tracks/internal`)).releases ?? []
    const source = tested.find((r) => (r.versionCodes ?? []).map(String).includes(code))
    if (!source) {
      const held = tested.flatMap((r) => r.versionCodes ?? []).join(', ') || 'none'
      throw new PublishError(`Internal testing holds no release of version code ${code} (it holds: ${held}).`)
    }
    const release = { name: source.name, versionCodes: [code], status: 'completed', releaseNotes: source.releaseNotes ?? [] }
    await play.send('Setting production', `${API}/edits/${id}/tracks/production`, {
      method: 'PUT',
      json: { track: 'production', releases: [release] },
    })
    if (await commit(play, id, stage, { dryRun, sendInConsole }, out)) {
      out(
        sendInConsole
          ? `Production: ${release.name} committed; send it for review from Play Console → Publishing overview.`
          : `Production: ${release.name} committed and sent for review.`,
      )
    }
    return release
  })
}

export function parseArgs(argv) {
  const [command, ...rest] = argv
  const options = { command }
  for (let i = 0; i < rest.length; i++) {
    const arg = rest[i]
    if (arg === '--dry-run') options.dryRun = true
    else if (arg === '--send-in-console') options.sendInConsole = true
    else if (arg === '--same-notes') options.sameNotes = true
    else if (['--bundle', '--key', '--version-code'].includes(arg) && rest[i + 1] !== undefined) {
      options[arg.slice(2).replace(/-(\w)/g, (_, c) => c.toUpperCase())] = rest[++i]
    } else throw new PublishError(`Unknown or incomplete option: ${arg}`)
  }
  return options
}

/** The command line, with everything it touches injectable for the tests. */
export async function run(argv, { fetch = globalThis.fetch, env = process.env, root, out, err, signal, now } = {}) {
  const options = parseArgs(argv)
  const commands = { check, internal, production }
  if (!commands[options.command]) {
    throw new PublishError('Usage: node tools/play-publish.mjs <check|internal|production> [options] (see the file\'s header)')
  }
  const key = loadKey(options.key ?? env.SPEKTERM_PLAY_KEY_FILE ?? join(homedir(), '.android', 'spekterm-play-publisher.json'), err)
  const play = new Play({ fetch, key, signal, now })
  return commands[options.command](play, out, {
    root,
    bundle: resolve(root, options.bundle ?? 'app/build/outputs/bundle/release/app-release.aab'),
    sameNotes: options.sameNotes,
    dryRun: options.dryRun,
    sendInConsole: options.sendInConsole,
    versionCode: options.versionCode,
  })
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const interrupt = new AbortController()
  process.once('SIGINT', () => interrupt.abort(new Error('interrupted')))
  run(process.argv.slice(2), {
    root: resolve(dirname(fileURLToPath(import.meta.url)), '..'),
    out: (line) => console.log(line),
    err: (line) => console.error(line),
    signal: interrupt.signal,
  }).catch((error) => {
    console.error(error instanceof PublishError ? error.message : `Failed: ${error.message}`)
    process.exitCode = 1
  })
}
