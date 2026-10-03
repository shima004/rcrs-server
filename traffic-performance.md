# Traffic simulation spatial lookup

Agent forces previously built a set of every agent in the same area and its
neighbours on every microstep, then discarded agents outside the force cutoff.
There are 600 microsteps per simulation timestep. For large populations sharing
an area, this repeatedly scanned and allocated sets proportional to that population.

Each traffic area now maintains 10,000 mm spatial buckets. Force queries visit
only buckets intersecting the existing square cutoff and collect mobile agents.
Small populations and large cutoffs fall back to a direct scan. The index updates
on every successful movement, including movements within an area, so later agents
in the same microstep see the updated position. Area adjacency, the inclusive
square cutoff, and the force equations remain unchanged. The original unfiltered
`TrafficManager.getNearbyAgents(agent)` API is retained.

The reduced candidate set can change floating-point summation order. Bit-for-bit
identical long-run trajectories are not guaranteed. Regression tests compare
candidate sets against the original full scan across 1,000 randomized movements
and mobility changes, exercise boundaries and area transitions, and compare
trajectories for 600 microsteps with a tolerance of 0.000001 mm.

Unused quadratic debug-geometry construction in blockade detour routing was also
removed.

## Reproduce validation and measurement

```sh
./gradlew test launcherJar traffic3Jar exportLibs
java -Xms512m -Xmx512m -cp 'build/classes/java/main:build/classes/java/test:lib/*' traffic3.simulator.TrafficSpatialBenchmark
```

The standalone benchmark runs the actual agent movement/force loop for 100
microsteps with seeded positions and destinations in one rectangular area. It
compares the indexed implementation with a manager overriding the force query to
use the original full scan. Both runs maintain the spatial index; this isolates
the query change rather than comparing two complete repository revisions. Timings
exclude scenario setup. The spread scenarios
use a 300,000 mm square, three warmup runs per implementation and the median of
three measured runs with alternating execution order. A separate crowded case
uses a 5,000 mm square and one measured run per implementation. Position-history
recording is disabled in both implementations. Timings are informational rather
than test assertions.

These are synthetic movement-loop measurements, not full-server or real-map
speedups. If everyone is inside the force cutoff, pairwise force computation
remains quadratic. Maps with few agents per area or costs dominated by routing,
geometry, rendering, or other simulators may see smaller gains.

## Example results

Measured on 2026-09-27 with OpenJDK 21.0.12, using the commands above:

| Population/layout | Full scan (ms) | Spatial query (ms) | Ratio |
| --- | ---: | ---: | ---: |
| 100, spread | 19.0 | 3.0 | 6.39x |
| 1,000, spread | 2,080.7 | 79.1 | 26.30x |
| 3,000, spread | 23,985.4 | 574.0 | 41.78x |
| 1,000, crowded | 3,130.7 | 3,090.5 | 1.01x |

Ratios use unrounded timings. Absolute timings vary with JVM warmup and machine
load; the crowded example demonstrates that spatial filtering cannot avoid
computing interactions when all agents are genuinely nearby.
