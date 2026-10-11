"""SyntheticTerrain#surface and its tree rule (baker/src/main/java/io/github/bearisbug/synaxis/baker/SyntheticTerrain.java)
in Python, for generator:synthetic:1, the map the P10 jobs bake: prints the top block of the spawn column and of the
column p10.py seed saves its position on, with the 24 columns around it and any tree within 3 blocks. Read only;
RUN-075 group P, docs/TEST.md TC-PROD-754 step 5."""
import math

M = (1 << 64) - 1
SEED = 1


def hash3(x, y, z):  # Java long arithmetic, then (int) of the last xor-shift
    v = (SEED ^ 0x5EED202609) & M
    v ^= (x * 0x9E3779B97F4A7C15) & M
    v ^= (y * 0xC2B2AE3D27D4EB4F) & M
    v ^= (z * 0x165667B19E3779F9) & M
    v = ((v ^ (v >> 30)) * 0xBF58476D1CE4E5B9) & M
    v = ((v ^ (v >> 27)) * 0x94D049BB133111EB) & M
    return (v ^ (v >> 31)) & 0xFFFFFFFF


def lattice(x, y, z):
    return (hash3(x, y, z) >> 8) / float(1 << 23) - 1.0


def smooth(t):
    return t * t * (3 - 2 * t)


def lerp(a, b, t):
    return a + (b - a) * t


def noise2(x, z):
    x0, z0 = math.floor(x), math.floor(z)
    u, v = smooth(x - x0), smooth(z - z0)
    return lerp(lerp(lattice(x0, 0, z0), lattice(x0 + 1, 0, z0), u),
                lerp(lattice(x0, 0, z0 + 1), lattice(x0 + 1, 0, z0 + 1), u), v)


def fbm(x, z, octaves):
    total, amplitude, norm = 0, 1, 0
    for i in range(octaves):
        total += amplitude * noise2(x * (1 << i), z * (1 << i))
        norm += amplitude
        amplitude *= 0.5
    return total / norm


def surface(x, z):
    hills = fbm(x / 256.0, z / 256.0, 5)
    mountains = math.pow(max(0, fbm(x / 640.0 + 17.3, z / 640.0 - 9.1, 4)), 1.5)
    return min(math.floor(64 + 22 * hills + 70 * mountains), 256 - 16)


def tree(x, z):
    return surface(x, z) > 63 and 2 <= (x & 15) < 14 and 2 <= (z & 15) < 14 and (hash3(x, 1, z) & 255) == 0


print(f"spawn column 120, 120: top block y {surface(120, 120)}, a player stands at y {surface(120, 120) + 1}")
x, z = 100, 120
around = sorted({surface(x + dx, z + dz) for dx in range(-2, 3) for dz in range(-2, 3)})
trees = [(x + dx, z + dz) for dx in range(-3, 4) for dz in range(-3, 4) if tree(x + dx, z + dz)]
print(f"seed column {x}, {z}: top block y {surface(x, z)}, a player stands at y {surface(x, z) + 1}; "
      f"top blocks of the 5 x 5 columns around it {around}; trees within 3 blocks {trees}; chunk {x // 16}, {z // 16}")
