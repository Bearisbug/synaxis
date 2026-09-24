# `.synx` test vectors

Frozen vectors of `docs/DESIGN-M3.md` §24 for the region file format (`API-M3-001`) and the
release manifest (`API-M3-002`). The reader and the writer are both held to these files.

## How they are made

```
./gradlew :synx:generateVectors --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

runs `SynxVectorGenerator` (test sources) over the inputs in `SynxVectors`. It rewrites every
`*.synx` file, `manifest.json` and `manifest.txt` in this directory. Regenerate only together with
a `format_version` bump or a deliberate change of the inputs, and commit the result in the same
change; `SynxVectorTest` fails on any vector file that no longer matches `manifest.txt`.

`manifest.txt` holds `format_version`, a `zlib` fingerprint (SHA-256 of what `Deflater` at level
-1 makes of a fixed 64 KiB input, see `SynxVectors#zlibFingerprint`) and the SHA-256 of each file.
The written bytes are only compared on a JDK whose fingerprint matches; the reader, the
uncompressed content and both region hashes are compared on every JDK. CI sets
`-Dsynaxis.synx.require-frozen-zlib=true`, which turns a fingerprint mismatch into a test failure
instead of a skip.

## Regular vectors

World `min_y` 0, height 256, threshold 256, level -1, `has_columns` set, light engine and baker
version 1, registry digest of `Registries.vanilla()`. All four share one `content_hash`: together
they are the regions of the build `manifest.json` describes.

| File | Region | Chunks | What it covers |
| --- | --- | --- | --- |
| `empty.synx` | -1,-1 | none | header and index only, `payload_sha256` of zero bytes |
| `one-chunk-flat.synx` | 0,0 | 5,7 | unlit packet, no segment ③ |
| `two-by-two-lit.synx` | -1,0 | -2..-1 × 0..1 | sky and block light, index entries 30, 31, 62, 63 |
| `block-entities.synx` | 0,-1 | 3,-27 | chest with a handler, sign and spawner (types `ChunkData` drops on read), two static entities |

Every section palette of the inputs is filled back to front and section 2 of each chunk arrives as
a direct palette, so the vectors only reproduce if the writer rebuilds every palette canonically.

## Edge vectors

Each is a regular vector with a few bytes changed (`SynxVectorGenerator#edges`).

| File | Made from | Change | Expected |
| --- | --- | --- | --- |
| `edge/truncated-index.synx` | one-chunk-flat | cut to 4,096 bytes | open fails, `synx-truncated` |
| `edge/truncated-records.synx` | two-by-two-lit | cut 10 bytes into the record of -2,1 | -2,1 and -1,1 out of range, the other two read; `synx-payload-hash` |
| `edge/offset-out-of-range.synx` | two-by-two-lit | offset of -1,1 set past the end | that chunk out of range, the rest read, payload hash still holds |
| `edge/crc-mismatch.synx` | two-by-two-lit | one byte of segment ② of -2,1 flipped | that chunk fails its crc32c, the rest read |
| `edge/payload-hash.synx` | one-chunk-flat | one byte of `payload_sha256` flipped | records read, `synx-payload-hash` |
| `edge/protocol-775.synx` | one-chunk-flat | `protocol_version` 775 | `synx-protocol-mismatch` |
| `edge/registry-mismatch.synx` | one-chunk-flat | one byte of `registry_digest` flipped | `synx-registry-mismatch` |
| `edge/unknown-flag.synx` | one-chunk-flat | `flags` bit 2 set | open fails, `synx-magic` |
