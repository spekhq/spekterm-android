# spekterm for Android

The companion Android app for [spekterm](https://github.com/spekhq/spekterm), the agent-first desktop
workbench. It connects **directly** to a spekterm you have paired with — over your LAN, Tailscale, or a port
you forward — with no relay and no push service in between.

**Status: in development.** Nothing is published yet.

## How it talks to spekterm

The desktop side is built and documented in the spekterm repository:

- Protocol: [`docs/remote-protocol.md`](https://github.com/spekhq/spekterm/blob/master/docs/remote-protocol.md)
- Turning remote access on and pairing: spekterm's Settings → Remote access

In short: a TLS WebSocket on one port, a self-signed certificate the app pins by its SHA-256 fingerprint
(taken from the pairing QR code), and a per-device credential issued at pairing. Attention events (an agent
waiting for you, a finished handoff, a new inbox item) arrive only over the app's own connection; missed
ones are replayed on reconnect.

## License

MIT — see [`LICENSE`](LICENSE).
