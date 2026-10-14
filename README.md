# spekterm for Android

The companion Android app for [spekterm](https://github.com/spekhq/spekterm), the agent-first desktop
workbench. It connects **directly** to a spekterm you have paired with — over your LAN, Tailscale, or a port
you forward — with no relay and no push service in between.

**Status: in development.** A validation spike (pairing, a held connection, attention notifications) is being
tested on a real phone; nothing is published yet. Findings: [`docs/spike-findings.md`](docs/spike-findings.md).

## How it talks to spekterm

The desktop side is built and documented in the spekterm repository:

- Protocol: [`docs/remote-protocol.md`](https://github.com/spekhq/spekterm/blob/master/docs/remote-protocol.md)
- Turning remote access on and pairing: spekterm's Settings → Remote access

In short: a TLS WebSocket on one port, a self-signed certificate the app pins by its SHA-256 fingerprint
(taken from the pairing QR code), and a per-device credential issued at pairing. Attention events (an agent
waiting for you, a finished handoff, a new inbox item) arrive only over the app's own connection; missed
ones are replayed on reconnect.

## Building and running

Needs JDK 17 and the Android SDK with the android-37 platform (compile) — the app targets Android 16 (API 36)
and runs on Android 10 (API 29) and later.

```sh
export ANDROID_HOME=~/Android/Sdk        # or put sdk.dir=… in local.properties
./gradlew assembleDebug                  # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                   # onto a phone connected with USB debugging
./gradlew test                           # JVM unit tests
./gradlew connectedDebugAndroidTest      # instrumented tests, on the connected phone
```

The build never downloads SDK components on its own (`android.builder.sdkDownload=false`); install a missing
one deliberately, for example once with `./gradlew assembleDebug -Pandroid.builder.sdkDownload=true`.

### Testing against a desktop

The released spekterm does not have remote access yet. Run it from source — `npm run dev` in a spekterm
checkout, which uses its own profile `~/.config/spekterm-dev` — then Settings → Remote access → turn on → Pair
device, and scan the code with the app. The phone must reach the desktop's port (47615 by default) over the LAN,
Tailscale, or a forwarded port. `tools/drop-intake.mjs` drops inbox items into that profile to produce attention
events on a schedule (see [`tools/README.md`](tools/README.md)).

## License

MIT — see [`LICENSE`](LICENSE).
