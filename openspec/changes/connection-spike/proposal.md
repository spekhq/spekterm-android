## Why

The app's whole design rests on three things that could not be verified from the desktop side, and each one
could change that design: that Android's TLS stack accepts spekterm's self-signed certificate (no extensions at
all) when the app pins it by fingerprint; that a foreground service can hold a WebSocket open through screen-off,
Doze, standby buckets and vendor battery killers at an acceptable battery cost; and that Google Play accepts
such a foreground service. Building the session, conversation and terminal screens before these are proven on
the user's real phone risks building on ground that does not hold.

## What Changes

- A new Android project (single app module, Kotlin, application id `com.spekterm.android`) with a Gradle wrapper,
  build and test setup. Nothing is published to Google Play in this change.
- **Pairing**: scan the desktop's pairing QR code with the camera, or paste the `spekterm://pair?…` link; pair
  over a pinned TLS WebSocket; store the issued credential encrypted with a key held in Android Keystore.
- **Pinned connection**: trust the desktop only by the SHA-256 fingerprint of its certificate, in both the trust
  manager and the host name verifier, and check the pin again before the first frame is sent. Never trust any
  other certificate.
- **Held connection**: a foreground service keeps one authenticated connection open, tries the pairing's
  addresses in turn, and reconnects with backoff, honoring the protocol's close codes (above all: forget the
  credential on 4004).
- **Attention notifications**: watch the attention stream, raise one system notification per live attention
  event that is not suppressed, resume from the last position after a reconnect, and summarize an incomplete
  replay instead of notifying for each event.
- **A minimal status screen** (paired desktop, connection state, last error, recent events) and an on-device
  connection log, for the measurements. No session list, conversation, terminal, inbox or OpenSpec screens.
- **A findings document** (`docs/spike-findings.md`) recording, for each of the three questions, what was
  measured on which phone, the result, the sources for the Google Play conclusions, and what remains unverified.

## Capabilities

### New Capabilities

- `device-pairing`: reading a pairing link (QR or pasted), pairing with the desktop, and keeping the credential in
  protected storage until the user or the desktop ends the pairing.
- `desktop-connection`: the pinned TLS WebSocket to the paired desktop, held by a foreground service across
  idle and network changes, with address fallback, backoff and close-code handling.
- `attention-notifications`: turning the desktop's attention stream into system notifications, with resume,
  suppression and the summary of an incomplete replay.

### Modified Capabilities

None — this repository has no specs yet.

## Impact

- New code: the whole Android project (`settings.gradle.kts`, `app/`, Gradle wrapper), `docs/spike-findings.md`,
  and an update to `README.md` (how to build and run).
- New dependencies: OkHttp (WebSocket and TLS), a JSON library, CameraX with a QR decoder, Jetpack Compose for
  the two screens, and test libraries.
- Desktop: no change to spekterm. Testing uses spekterm run from source (`npm run dev`, its own
  `~/.config/spekterm-dev` profile), because the installed AppImage has no remote access yet.
- Google Play: the foreground service type and its Play Console declaration are decided in this change, but
  the app is not uploaded; the conclusion is written down with sources for the first upload.
