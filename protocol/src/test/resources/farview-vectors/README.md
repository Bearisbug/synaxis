# Far view test vectors

Frozen vectors of `docs/DESIGN-M3.md` §24 for the height field pack (`API-M3-014`, `FarViewPack`)
and the far view announcement (`API-M3-015`, `FarViewAnnouncement`). `FarViewVectorTest` in this
module and `FrozenVectorTest` in `client-fabric` hold their codecs to the same files; the client finds
the directory through the system property `synaxis.farview-vectors.dir`. They are kept apart from
`vectors/`, whose digest belongs to the M2 `schema_version`: each format here has its own
`format_version`.

## How they are made

```
./gradlew :protocol:generateFarViewVectors --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

runs `FarViewVectorGenerator` (test sources) over the inputs in `FarViewVectors`. It rewrites every
file here but this one. Regenerate only together with a `format_version` bump of the format that
changed, or a deliberate change of the inputs, and commit the result in the same change.

`manifest.txt` holds `pack_format_version`, `announcement_format_version`, a `zlib` fingerprint
(SHA-256 of what `Deflater` at level 9 makes of a fixed 64 KiB input, `FarViewVectors#zlibFingerprint`)
and the SHA-256 of each file. What the writer makes is read back and compared with the frozen packs
sample by sample on every JDK; its bytes are compared too on a JDK whose fingerprint matches. With
`-Dsynaxis.synx.require-frozen-zlib=true` (CI, the switch of the `.synx` vectors) another fingerprint
fails the byte comparison instead of skipping it.

## Regular vectors

| File | What it is |
| --- | --- |
| `announce-one-tile.bin` | one tile, (0, 0) whole; base level 2, `min_y` 0 |
| `announce-four-tiles.bin` | a region not aligned to the tiles: (-1..0, -1..0) all whole; base level 1, `min_y` -64 |
| `announce-nine-tiles.bin` | an aligned region with regions all round: (0, 0) whole, the eight around it `layer` 1; base level 2, `min_y` -64 |
| `pack-small.hfp` | tile (-1, 0), base level 2, `min_y` -64, height 256, of a map of chunks x -2..-1, z 0..1 |
| `pack-small.coarse.hfp` | the first `fine_offset` bytes of `pack-small.hfp`, a pack of the coarse layer alone |
| `pack-small.samples.txt` | the header, the biome table, the two present blocks and every sample of the map in both layers |

`pack-small.hfp` has fine block (15, 0) and coarse block (3, 0); every other block is absent, and
every sample of the two blocks outside the map is an empty column of biome 0. Its 8 × 8 fine samples
hold the five kinds of column (ground alone, ground under fluid, fluid hanging in the air, fluid down
to the bottom of the world, an empty column), a tie for the coarse sample of chunk (-1, 0) that goes
to the first by z and then x, and a hidden area of blocks x -12..-5, z 20..27: its fine samples are
stone of plains at the top of the pond on their rim, and its chunk's coarse sample is stone at 0, as
that rim reaches past the map. The tower of red concrete under it shows in neither layer. Block states
are the 26.2 global palette ids (stone 1, grass block 9, dirt 10, water 86, sand 118, red concrete
15044) of 32,366.

## Edge vectors

Each pack is `pack-small.hfp` with a few bytes changed (`FarViewVectors#edgePacks`); each
announcement is written field by field (`FarViewVectors#edgeAnnouncements`). A pack is refused by
`FarViewPack.Reader` (on `read` or on `verify`) with `FarViewPack.Invalid`, an announcement by
`FarViewAnnouncement.read` with `MalformedMessageException`.

| File | Change |
| --- | --- |
| `edge/pack-format-version-2.hfp` | `format_version` 2 |
| `edge/pack-flags.hfp` | `flags` bit 0 set |
| `edge/pack-base-level-3.hfp` | `base_level` 3 |
| `edge/pack-coarse-level-3.hfp` | `coarse_level` 3 |
| `edge/pack-fine-offset-past-end.hfp` | `fine_offset` one past the end of the file |
| `edge/pack-coarse-block-past-fine-offset.hfp` | coarse block (3, 0) points at the bytes of the fine block, after `fine_offset` |
| `edge/pack-index-past-end.hfp` | fine block (15, 0) one byte longer than the file |
| `edge/pack-overlapping-blocks.hfp` | fine block (14, 0) points at the bytes of (15, 0) |
| `edge/pack-block-not-32768.hfp` | fine block (15, 0) inflates to 32,767 bytes |
| `edge/pack-invariant.hfp` | the pond of chunk (-2, 0) has its fluid a block above the ground |
| `edge/announce-count-65.bin` | 65 tiles |
| `edge/announce-url-too-long.bin` | a `url` of 1,025 bytes |
| `edge/announce-duplicate-tile.bin` | tile (0, 0) twice, `layer` 0 and 1 |
| `edge/announce-layer-2.bin` | a tile of `layer` 2 |
| `edge/announce-no-full-tile.bin` | two tiles, both `layer` 1 |
| `edge/announce-object-over-40mib.bin` | a tile of 41,943,041 `bytes`, one over `FarViewAnnouncement.MAX_OBJECT_BYTES` |
