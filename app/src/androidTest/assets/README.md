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

# Test photos for the attachment preparer (`prompts-attachments-rename`)

- `rotated.heic` — 400 × 200 stored, left half red and right half blue, turned upright (200 × 400, red on top) by its
  orientation (pillow-heif writes it as the HEIF `irot` transform).
- `rotated.jpg` — the same picture as a JPEG with EXIF orientation 6 (rotate 90° clockwise to view).
- `upright.jpg` — a plain 64 × 32 green JPEG with no orientation tag.

Made 2026-10-16 with Pillow 12.3.0 and pillow-heif 1.8.1 in a scratch virtualenv:

```python
from PIL import Image
import pillow_heif
pillow_heif.register_heif_opener()
img = Image.new("RGB", (400, 200), (0, 0, 255))
img.paste((255, 0, 0), (0, 0, 200, 200))
exif = Image.Exif()
exif[0x0112] = 6
img.save("rotated.heic", format="HEIF", exif=exif.tobytes(), quality=90)
img.save("rotated.jpg", format="JPEG", exif=exif.tobytes(), quality=90)
Image.new("RGB", (64, 32), (0, 128, 0)).save("upright.jpg", format="JPEG", quality=90)
```
