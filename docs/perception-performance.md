# Perception timing and line-of-sight acceleration

The kernel's existing `Perception took` timer includes communication processing,
visibility calculation, hearing/registration, writing perception records, and
sending updates to agents. It is not a visibility-only timer. A new DEBUG line
reports these phases separately:

```text
Perception breakdown (ms): setup=..., visibility=..., hearing/register=..., log=..., send=...
```

`setup` includes `perception.setTime` and communication processing. `visibility`
includes range lookup, ray casting and constructing property updates. `log` and
`send` measure time spent in those calls; asynchronous work may complete later.

Line-of-sight perception now builds a bounding-volume tree for world edges once
per timestep and shares it across observers. Ray queries filter by the same nearby
entity collection as before, preserve the collection's edge ordering for tied
intersections, and use the original intersection and occlusion calculations.
Conservative padded bounds serve only to reject spatially irrelevant edges.
Direction vectors are precomputed without changing ray counts or view distances.
The tree is discarded on every `setTime` call, so geometry additions, removals and
updates are reflected in the next perception phase. Undefined geometry is skipped
when constructing the world-wide index.

## Validation

```sh
./gradlew test launcherJar kernelJar standardJar exportLibs
```

Six LOS regression tests cover 3,000 seeded random rays, equal-distance blocking
edges, nearby-entity filtering and ordering, parallel/zero-length rays, endpoints,
large coordinates, and rebuilding after world changes. The four traffic tests
also pass.

A standalone comparison against the pre-change implementation checks full
`ChangeSet` output for all humans in the VC map's initial scenario, then measures
five timesteps after two warmup timesteps, alternating execution order. Each timed
run includes rebuilding the index. On OpenJDK 21.0.12 with a fixed 1 GB heap,
2026-09-27:

- All 539 human observers produced identical `ChangeSet` output.
- Median original visibility generation: 245.3 ms.
- Median indexed visibility generation: 175.4 ms (1.40x faster).

This benchmark includes `getVisibleEntities`, not kernel communication, perception
record writing, network transmission, GUI rendering or debug-log output. It uses
the initial map, not a running disaster scenario. The user's reported 3,492 ms
cannot be directly compared with these timings; use the new breakdown on the
actual run to identify the remaining cost.

## Reproduce the real-map comparison

Compile the old implementation under a separate name outside the repository:

```sh
mkdir -p /tmp/rcrs-perception-baseline
git show 0a924b985aade606c61d3162bc0bab99ec641876:modules/standard/src/rescuecore2/standard/kernel/LineOfSightPerception.java | sed 's/LineOfSightPerception/BaselineLineOfSightPerception/g' > /tmp/rcrs-perception-baseline/BaselineLineOfSightPerception.java
javac -proc:none -cp 'build/classes/java/main:lib/*' -d /tmp/rcrs-perception-baseline /tmp/rcrs-perception-baseline/BaselineLineOfSightPerception.java
java -Xms1g -Xmx1g -cp '/tmp/rcrs-perception-baseline:build/classes/java/main:build/classes/java/test:lib/*' rescuecore2.standard.kernel.LineOfSightMapBenchmark maps/vc
```

An additional synthetic ray-loop benchmark is available as
`rescuecore2.standard.kernel.LineOfSightRayBenchmark`. It includes shared tree
construction but excludes nearby lookup/property generation; its timings are
not full perception or full-server speedups.

## 7z perception-record compression

A follow-up runtime measurement showed `visibility=351 ms` but `log=3065 ms`.
The 7z writer creates a separate LZMA2 encoder for every record. With the XZ
library's default dummy array cache, each encoder allocates large working arrays
again. The first log optimization supplied a writer-local `BasicArrayCache` through its
LZMA2 options, allowing successive entries to reuse those arrays. That first optimization left the default compression parameters, archive entry
names, protobuf payloads and synchronous write ordering unchanged. The subsequent
dictionary-size optimization is described below. No global XZ cache settings are modified. Cached
arrays use the library's bounded, soft-reference cache and may be reclaimed under
memory pressure.

Validation of that first optimization compared whole archive bytes against the
old writer, then decoded and compared every protobuf record. All 11 tests pass. The executable benchmark
includes serialization, compression and closing each archive, with one warmup
and a median of three measured runs per implementation (OpenJDK 21.0.12, 512 MB
fixed heap, 2026-09-27):

| Input | Original | Cached | Ratio | Archive size |
| --- | ---: | ---: | ---: | ---: |
| 539 synthetic perceptions | 2487.8 ms | 223.9 ms | 11.11x | 209175 bytes |
| VC initial scenario, 539 perceptions | 2540.9 ms | 129.7 ms | 19.59x | 134251 bytes |

Both original benchmark runs verified byte-for-byte equality of the resulting archives.
The current benchmark compares the cached fixed-dictionary version against the
adaptive version, verifying decompressed record bytes instead. VC input
contains each human's actual initial visible state, with an empty hearing list;
map loading and perception generation are outside the measured write phase.
These figures measure log writing alone, not the entire simulation timestep.

```sh
./gradlew test launcherJar rescuecore2Jar exportLibs
java -Xms512m -Xmx512m -cp 'build/classes/java/main:build/classes/java/test:lib/*' rescuecore2.log.ZipLogWriterBenchmark
java -Xms512m -Xmx512m -cp 'build/classes/java/main:build/classes/java/test:lib/*' rescuecore2.log.ZipLogWriterBenchmark maps/vc
```

Restart the server to load the rebuilt `rescuecore2.jar`, then compare the `log`
field in `Perception breakdown (ms)` on the actual run. This optimization applies
to `.7z` output, which the launcher selects by default.

## Further optimization: entry-sized dictionaries and human occlusion

The 7z writer now chooses the smallest power-of-two dictionary that can hold an
entry's serialized payload, bounded by the LZMA2 minimum (4 KB) and the original
8 MB default. Each archive entry starts a new compression history, so allocating
a dictionary larger than the entire payload offers no additional match history.
Options are retained per dictionary size and share the writer-local array cache;
options already attached to an entry are never mutated. Large entries still use
the original 8 MB dictionary. Compression remains synchronous and lossless.

The resulting archive can differ at the byte level because its dictionary
metadata and compressed representation may change. Entry names/order and every
decompressed protobuf payload are preserved. Tests include a larger configuration
record followed by small perception records, and verify both entry decoding and
that the process-wide XZ cache remains unchanged.

When the LOS visualization has not been created, human visibility now queries
only blocking geometry and stops at the first obstruction. It does not allocate
or sort the complete hit list. Origin hits remain excluded; a wall exactly at
the human endpoint remains visible, as in the original ray calculation. The GUI
continues to use the full ray path for display. The randomized ray regression
also compares this predicate with the original visible-length result.

Measured separately on the VC initial scenario, 539 humans, against the versions
from the previous optimization (2026-09-27, same JVM/heap settings as above):

| Operation | Previous optimized version | Further optimized | Ratio |
| --- | ---: | ---: | ---: |
| Write perception records (median of 3) | 150.1 ms | 68.0 ms | 2.21x |
| Generate perception (median of 5) | 161.7 ms | 140.9 ms | 1.15x |

The log archives both occupied 134251 bytes in this fixture and all decompressed
records matched. Full perception `ChangeSet`s matched for all 539 observers.
These are isolated measurements, not a guarantee of the same whole-timestep
speedup on a running scenario. All 11 regression tests pass.

Run the current `ZipLogWriterBenchmark maps/vc` command above to compare fixed
8 MB cached dictionaries against entry-sized dictionaries. Restart the server
after rebuilding `rescuecore2Jar` and `standardJar` to use these changes.
