# Test fixture: a certificate in spekterm's exact shape

**Test-only material. Nothing outside the instrumented tests trusts or uses this key.**

- `spekterm-cert.pem` — a self-signed certificate made by spekterm's own generator: ECDSA P-256, `CN=spekterm`,
  X.509 v3 with **no extensions at all** (no SAN, no basicConstraints), ten years.
- `spekterm-key.pem` — its PKCS#8 private key, so the instrumented tests can run a TLS server presenting it.
- `spekterm-fingerprint.txt` — SHA-256 of the certificate's DER, the form a pairing link's `fp` carries.

The key is committed on purpose: it is a throwaway the tests use to play the desktop on the phone's loopback
interface, and no desktop or app trusts it. GitHub's secret scanning may still flag the `BEGIN PRIVATE KEY`
block; that alert can be closed as "used in tests".

Made 2026-10-14 with spekterm's `generateIdentity()` (`src/main/remote-cert.ts`), run directly on Node 22:

```sh
node --input-type=module -e "
import { generateIdentity } from '$HOME/git/spekterm/src/main/remote-cert.ts'
import { writeFileSync } from 'node:fs'
const i = generateIdentity()
writeFileSync('spekterm-cert.pem', i.certPem)
writeFileSync('spekterm-key.pem', i.keyPem)
writeFileSync('spekterm-fingerprint.txt', i.fingerprint.replace(/:/g, '') + '\n')
"
openssl x509 -in spekterm-cert.pem -noout -text   # Version 3, ecdsa-with-SHA256, no X509v3 lines
```
