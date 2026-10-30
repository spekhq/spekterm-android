// Tests for play-publish.mjs against a fake Play (`play-api-upload` design D7). Run: node --test tools/*.test.mjs
import assert from 'node:assert/strict'
import { createVerify, generateKeyPairSync } from 'node:crypto'
import { chmodSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { assertion, commitQuery, readNotes, readVersion, run, SCOPE, TOKEN_URL } from './play-publish.mjs'

const { privateKey, publicKey } = generateKeyPairSync('rsa', {
  modulusLength: 2048,
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  publicKeyEncoding: { type: 'spki', format: 'pem' },
})
const EMAIL = 'play-publisher@example.iam.gserviceaccount.com'
const TOKEN = 'fake-access-token-7f3a'
const PRIVATE_PART = privateKey.split('\n')[5]

const GRADLE = `android {
    defaultConfig {
        // Raised for every upload (versionName never reused).
        versionCode = 6
        versionName = "0.6.0"
    }
}
`
const NEW_NOTES = { 'en-US': '- New things\n', 'zh-TW': '- 新功能\n' }
const OLD_NOTES = [
  { language: 'en-US', text: '- Old things' },
  { language: 'zh-TW', text: '- 舊功能' },
]

/** A repository root with the Gradle file, the notes, a bundle and a key; options change any of them. */
function project({ gradle = GRADLE, notes = NEW_NOTES, keyMode = 0o600, key = true } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'play-publish-'))
  mkdirSync(join(root, 'app'), { recursive: true })
  writeFileSync(join(root, 'app/build.gradle.kts'), gradle)
  for (const [language, text] of Object.entries(notes)) {
    mkdirSync(join(root, 'play/release-notes', language), { recursive: true })
    writeFileSync(join(root, 'play/release-notes', language, 'default.txt'), text)
  }
  mkdirSync(join(root, 'app/build/outputs/bundle/release'), { recursive: true })
  writeFileSync(join(root, 'app/build/outputs/bundle/release/app-release.aab'), 'PK fake bundle')
  const keyPath = join(root, 'key.json')
  if (key) {
    writeFileSync(keyPath, JSON.stringify({ type: 'service_account', client_email: EMAIL, private_key: privateKey }))
    chmodSync(keyPath, keyMode)
  }
  return { root, keyPath }
}

const json = (body, status = 200) => new Response(JSON.stringify(body), { status })
const playError = (status, message, reason) =>
  json({ error: { code: status, message, status: 'FAILED_PRECONDITION', details: reason ? [{ reason }] : [] } }, status)

/** A fake Play recording every request; `on` replaces the answer for a route ("METHOD path-suffix"). */
function fakePlay(on = {}) {
  const calls = []
  const state = {
    internal: [{ name: '0.5.0 (5)', versionCodes: ['5'], status: 'completed', releaseNotes: OLD_NOTES }],
    production: [{ releaseName: '0.5.0 (5)', releaseLifecycleState: 'RELEASE_LIFECYCLE_STATE_PUBLISHED', activeArtifacts: [{ versionCode: 5 }] }],
  }
  const routes = [
    ['POST', /oauth2\.googleapis\.com\/token$/, 'token', () => json({ access_token: TOKEN, expires_in: 3599 })],
    ['GET', /\/tracks\/(internal|production)\/releases$/, 'releases', (m) => json({ releases: m[1] === 'production' ? state.production : [] })],
    ['POST', /\/edits$/, 'insert', () => json({ id: 'edit-1' })],
    ['GET', /\/edits\/edit-1\/tracks$/, 'tracks', () => json({ tracks: [{ track: 'internal', releases: state.internal }, { track: 'production', releases: [] }] })],
    ['GET', /\/edits\/edit-1\/tracks\/internal$/, 'getInternal', () => json({ track: 'internal', releases: state.internal })],
    ['POST', /\/upload\/.*\/edits\/edit-1\/bundles\?uploadType=media$/, 'upload', () => json({ versionCode: 6, sha256: 'x' })],
    ['PUT', /\/edits\/edit-1\/tracks\/(internal|production)$/, 'update', (m, init) => json(JSON.parse(init.body))],
    ['POST', /\/edits\/edit-1:validate$/, 'validate', () => json({ id: 'edit-1' })],
    ['POST', /\/edits\/edit-1:commit\?/, 'commit', () => json({ id: 'edit-1' })],
    ['DELETE', /\/edits\/edit-1$/, 'delete', () => new Response(null, { status: 204 })],
    // Play answered 400 for a deleted edit on 2026-10-30.
    ['GET', /\/edits\/edit-1$/, 'getEdit', () => playError(400, 'a deleted edit')],
  ]
  const fetch = async (url, init) => {
    const route = routes.find(([method, pattern]) => method === init.method && pattern.test(url))
    assert.ok(route, `unexpected request ${init.method} ${url}`)
    const [, pattern, name, answer] = route
    calls.push({ name, url, init })
    if (init.signal?.aborted) throw init.signal.reason
    return (on[name] ?? answer)(url.match(pattern), init, calls)
  }
  return { fetch, calls, state, names: () => calls.map((c) => c.name) }
}

async function publish(argv, { play = fakePlay(), setup = project(), signal } = {}) {
  const lines = []
  const out = (line) => lines.push(line)
  let error
  try {
    await run([...argv, '--key', setup.keyPath], { fetch: play.fetch, root: setup.root, out, err: out, signal })
  } catch (e) {
    error = e
  }
  const output = [...lines, error?.message ?? ''].join('\n')
  for (const secret of [PRIVATE_PART, TOKEN]) assert.ok(!output.includes(secret), 'a secret was printed')
  for (const call of play.calls) {
    if (call.name === 'token') assert.ok(!output.includes(new URLSearchParams(call.init.body).get('assertion')), 'the assertion was printed')
  }
  return { error, output, play, setup }
}

test('the version from app/build.gradle.kts', () => {
  assert.deepEqual(readVersion(GRADLE), { code: '6', name: '0.6.0' })
  assert.throws(() => readVersion('versionName = "0.6.0"'), /one `versionCode/)
  assert.throws(() => readVersion('versionCode = base + 1\nversionName = "0.6.0"'), /one `versionCode/)
  assert.throws(() => readVersion('versionCode = 6\nversionName = "0.6.0"\nversionCode = 7'), /found 2 and 1/)
})

test('the notes: present, not blank, at most 500 characters, CJK counted as one', () => {
  const { root } = project({ notes: { 'en-US': `${'a'.repeat(500)}\n\n`, 'zh-TW': '功'.repeat(500) } })
  assert.deepEqual(readNotes(root).map((n) => [n.language, [...n.text].length]), [['en-US', 500], ['zh-TW', 500]])
  const bad = project({ notes: { 'en-US': ' \n', 'zh-TW': '功'.repeat(501) } })
  assert.throws(() => readNotes(bad.root), /en-US\/default\.txt: empty\n.*zh-TW\/default\.txt: 501 characters, over 500/s)
  const missing = project({ notes: { 'en-US': 'x' } })
  assert.throws(() => readNotes(missing.root), /zh-TW\/default\.txt: missing/)
})

test('the assertion is signed with the key and claims the androidpublisher scope for an hour', () => {
  const jwt = assertion({ client_email: EMAIL, private_key: privateKey }, 1_000_000)
  const [header, claims, signature] = jwt.split('.')
  assert.ok(createVerify('RSA-SHA256').update(`${header}.${claims}`).verify(publicKey, signature, 'base64url'))
  assert.deepEqual(JSON.parse(Buffer.from(header, 'base64url')), { alg: 'RS256', typ: 'JWT' })
  assert.deepEqual(JSON.parse(Buffer.from(claims, 'base64url')), { iss: EMAIL, scope: SCOPE, aud: TOKEN_URL, iat: 1_000_000, exp: 1_003_600 })
})

test('the commit never cancels a review and holds nothing back unless told', () => {
  assert.equal(commitQuery(false), 'changesInReviewBehavior=ERROR_IF_IN_REVIEW')
  assert.equal(commitQuery(true), 'changesInReviewBehavior=ERROR_IF_IN_REVIEW&changesNotSentForReview=true')
})

test('the key: missing stops before any request, readable by others warns, a refused sign-in names the reason', async () => {
  const none = await publish(['check'], { setup: project({ key: false }) })
  assert.match(none.error.message, /No service account key at .*key\.json\. Set it up as docs\/play-release\.md/)
  assert.deepEqual(none.play.calls, [])

  const open = await publish(['check'], { setup: project({ keyMode: 0o644 }) })
  assert.match(open.output, /readable by others than you; chmod 600/)
  assert.equal(open.error, undefined)

  const refused = await publish(['check'], {
    play: fakePlay({ token: () => json({ error: 'invalid_grant', error_description: 'Invalid JWT Signature.' }, 400) }),
  })
  assert.match(refused.error.message, /Signing in failed: HTTP 400: invalid_grant: Invalid JWT Signature\./)
})

test('check: releases with review states, the tracks in an edit, the edit deleted and gone', async () => {
  const play = fakePlay()
  play.state.production[0].releaseLifecycleState = 'RELEASE_LIFECYCLE_STATE_IN_REVIEW'
  const { error, output } = await publish(['check'], { play })
  assert.equal(error, undefined)
  assert.deepEqual(play.names(), ['token', 'releases', 'releases', 'insert', 'tracks', 'delete', 'getEdit'])
  assert.match(output, new RegExp(`Service account: ${EMAIL}`))
  assert.match(output, /0\.5\.0 \(5\) — in review — version codes 5/)
  assert.match(output, /internal in an edit:\n {2}0\.5\.0 \(5\) — completed — version codes 5\n {4}<en-US>\n {6}- Old things/)
  assert.match(output, /Edit deleted \(HTTP 204\); asked for again, it is gone \(HTTP 400\)\. Nothing was committed\./)
  assert.ok(!play.names().includes('commit'))
})

test('internal: upload, one completed release with both notes, validate, commit without holding back', async () => {
  const { error, output, play } = await publish(['internal'])
  assert.equal(error, undefined, output)
  assert.deepEqual(play.names(), ['token', 'releases', 'insert', 'getInternal', 'upload', 'update', 'validate', 'commit'])
  const upload = play.calls.find((c) => c.name === 'upload')
  assert.equal(upload.url, 'https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/com.spekterm.android/edits/edit-1/bundles?uploadType=media')
  assert.equal(upload.init.headers['Content-Type'], 'application/octet-stream')
  assert.equal(String(upload.init.body), 'PK fake bundle')
  assert.equal(upload.init.headers.Authorization, `Bearer ${TOKEN}`)
  const update = play.calls.find((c) => c.name === 'update')
  assert.match(update.url, /\/tracks\/internal$/)
  assert.deepEqual(JSON.parse(update.init.body), {
    track: 'internal',
    releases: [{
      name: '0.6.0 (6)',
      versionCodes: ['6'],
      status: 'completed',
      releaseNotes: [{ language: 'en-US', text: '- New things' }, { language: 'zh-TW', text: '- 新功能' }],
    }],
  })
  const commit = play.calls.find((c) => c.name === 'commit')
  assert.ok(commit.url.endsWith(':commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW'))
  assert.ok(!commit.url.includes('changesNotSentForReview'))
  assert.match(output, /Internal testing: 0\.6\.0 \(6\), version code 6, released to all testers\./)
})

test('internal --send-in-console holds the changes back for Play Console', async () => {
  const { error, output, play } = await publish(['internal', '--send-in-console'])
  assert.equal(error, undefined)
  assert.ok(play.calls.find((c) => c.name === 'commit').url.endsWith('changesInReviewBehavior=ERROR_IF_IN_REVIEW&changesNotSentForReview=true'))
  assert.match(output, /Publishing overview/)
})

test('internal: notes over the limit stop it before any request', async () => {
  const { error, play } = await publish(['internal'], { setup: project({ notes: { 'en-US': 'x', 'zh-TW': '功'.repeat(501) } }) })
  assert.match(error.message, /zh-TW\/default\.txt: 501 characters, over 500/)
  assert.deepEqual(play.calls, [])
})

test('internal: notes equal to internal testing\'s stop it before the upload, unless --same-notes', async () => {
  const same = { 'en-US': '- Old things\n', 'zh-TW': '- 舊功能  \n' }
  const stopped = await publish(['internal'], { setup: project({ notes: same }) })
  assert.match(stopped.error.message, /same as internal testing's 0\.5\.0 \(5\).*--same-notes/s)
  assert.deepEqual(stopped.play.names(), ['token', 'releases', 'insert', 'getInternal', 'delete'])

  const kept = await publish(['internal', '--same-notes'], { setup: project({ notes: same }) })
  assert.equal(kept.error, undefined)
  assert.ok(kept.play.names().includes('commit'))
})

test('internal: a bundle of another version code is not released', async () => {
  const { error, play } = await publish(['internal'], { play: fakePlay({ upload: () => json({ versionCode: 5 }) }) })
  assert.match(error.message, /version code 5, app\/build\.gradle\.kts says 6/)
  assert.deepEqual(play.names().slice(-2), ['upload', 'delete'])
  assert.ok(!play.names().includes('commit'))
})

test('internal: a production review in progress is a warning, not a stop', async () => {
  const play = fakePlay()
  play.state.production[0].releaseLifecycleState = 'RELEASE_LIFECYCLE_STATE_IN_REVIEW'
  const { error, output } = await publish(['internal'], { play })
  assert.equal(error, undefined)
  assert.match(output, /Warning: production has 0\.5\.0 \(5\) in review/)
})

test('a failing request deletes the edit and prints Play\'s error', async () => {
  const { error, output, play } = await publish(['internal'], {
    play: fakePlay({ update: () => playError(400, 'Release notes are too long.', 'badRequest') }),
  })
  assert.match(error.message, /Setting internal testing failed: HTTP 400: Release notes are too long\. — FAILED_PRECONDITION — badRequest/)
  assert.match(output, /Edit deleted \(HTTP 204\); nothing was committed\./)
  assert.equal(play.names().filter((n) => n === 'delete').length, 1)
  assert.ok(!play.names().includes('commit'))
})

test('a review in progress at commit: one commit, the edit deleted, nothing cancelled', async () => {
  const { error, play } = await publish(['internal'], {
    play: fakePlay({ commit: () => playError(400, 'You already have changes in review.', 'CHANGES_ALREADY_IN_REVIEW') }),
  })
  assert.match(error.message, /changes in review, and this commit would have cancelled them/)
  assert.equal(play.names().filter((n) => n === 'commit').length, 1)
  assert.deepEqual(play.names().slice(-2), ['commit', 'delete'])
})

test('Play refusing to send for review points at --send-in-console', async () => {
  const { error } = await publish(['production', '--version-code', '5'], {
    play: fakePlay({
      commit: () => playError(400, 'Changes cannot be sent for review automatically. Please set the query parameter changesNotSentForReview to true.'),
    }),
  })
  assert.match(error.message, /run this again with --send-in-console/)
})

test('a commit without an answer is unknown: no delete, check first', async () => {
  const { error, play } = await publish(['internal'], {
    play: fakePlay({ commit: () => { throw new TypeError('fetch failed') } }),
  })
  assert.match(error.message, /no answer came back \(fetch failed\): its outcome is unknown\. Run `node tools\/play-publish\.mjs check`/)
  assert.ok(!play.names().includes('delete'))
})

test('an interrupt: before the commit the edit is deleted; after it was sent the outcome is unknown', async () => {
  const before = new AbortController()
  const early = await publish(['internal'], {
    signal: before.signal,
    play: fakePlay({ upload: (m, init) => { before.abort(new Error('interrupted')); throw init.signal.reason } }),
  })
  assert.match(early.error.message, /interrupted/)
  assert.deepEqual(early.play.names().slice(-2), ['upload', 'delete'])

  const after = new AbortController()
  const late = await publish(['internal'], {
    signal: after.signal,
    play: fakePlay({ commit: (m, init) => { after.abort(new Error('interrupted')); throw init.signal.reason } }),
  })
  assert.match(late.error.message, /no answer came back \(interrupted\): its outcome is unknown/)
  assert.ok(!late.play.names().includes('delete'))
})

test('production: the internal release\'s name and notes, sent for review', async () => {
  const play = fakePlay()
  const { error, output } = await publish(['production', '--version-code', '5'], { play })
  assert.equal(error, undefined, output)
  assert.deepEqual(play.names(), ['token', 'releases', 'insert', 'getInternal', 'update', 'validate', 'commit'])
  const update = play.calls.find((c) => c.name === 'update')
  assert.match(update.url, /\/tracks\/production$/)
  assert.deepEqual(JSON.parse(update.init.body), {
    track: 'production',
    releases: [{ name: '0.5.0 (5)', versionCodes: ['5'], status: 'completed', releaseNotes: OLD_NOTES }],
  })
  const commit = play.calls.find((c) => c.name === 'commit')
  assert.ok(commit.url.endsWith(':commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW'))
  assert.match(output, /Production: 0\.5\.0 \(5\) committed and sent for review\./)
})

test('production: a version code not in internal testing', async () => {
  const { error, play } = await publish(['production', '--version-code', '7'])
  assert.match(error.message, /no release of version code 7 \(it holds: 5\)/)
  assert.deepEqual(play.names().slice(-1), ['delete'])
  assert.ok(!play.names().includes('commit'))
})

test('production: a release already in review stops it before any edit', async () => {
  const play = fakePlay()
  play.state.production[0].releaseLifecycleState = 'RELEASE_LIFECYCLE_STATE_IN_REVIEW'
  const { error } = await publish(['production', '--version-code', '5'], { play })
  assert.match(error.message, /Production has 0\.5\.0 \(5\) in review/)
  assert.deepEqual(play.names(), ['token', 'releases'])
})

test('production --send-in-console holds the changes back for Play Console', async () => {
  const { error, output, play } = await publish(['production', '--version-code', '5', '--send-in-console'])
  assert.equal(error, undefined)
  assert.ok(play.calls.find((c) => c.name === 'commit').url.endsWith('&changesNotSentForReview=true'))
  assert.match(output, /send it for review from Play Console/)
})

test('--dry-run validates and deletes, never commits', async () => {
  for (const argv of [['internal', '--dry-run'], ['production', '--version-code', '5', '--dry-run']]) {
    const { error, output, play } = await publish(argv)
    assert.equal(error, undefined)
    assert.deepEqual(play.names().slice(-2), ['validate', 'delete'])
    assert.ok(!play.names().includes('commit'))
    assert.match(output, /Dry run: the edit is valid and was deleted \(HTTP 204\)/)
  }
})

test('version codes from the release list are numbers, from edits strings', async () => {
  const play = fakePlay()
  play.state.internal = [{ name: '0.5.0 (5)', versionCodes: [5], status: 'completed', releaseNotes: OLD_NOTES }]
  const { error } = await publish(['production', '--version-code', '5'], { play })
  assert.equal(error, undefined)
})
