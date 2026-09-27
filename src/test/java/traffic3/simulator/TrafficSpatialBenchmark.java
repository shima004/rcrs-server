package traffic3.simulator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import rescuecore2.config.Config;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.worldmodel.EntityID;
import traffic3.manager.TrafficManager;
import traffic3.objects.TrafficAgent;

/** Standalone synthetic benchmark; deliberately excluded from timed test assertions. */
public final class TrafficSpatialBenchmark {
    private static volatile long sink;

    public static void main(String[] args) {
        TrafficConstants.init(new Config());
        // Warm both code paths before measuring. Run with a fixed heap to limit GC variance.
        for (int i = 0; i < 3; i++) {
            run(false, 300, 30, false);
            run(true, 300, 30, false);
        }
        for (int count : new int[] {100, 1000, 3000}) {
            double[] oldTimes = new double[3];
            double[] newTimes = new double[3];
            for (int trial = 0; trial < 3; trial++) {
                // Alternate order to reduce systematic JIT/GC bias.
                if (trial % 2 == 0) {
                    oldTimes[trial] = run(false, count, 100, false);
                    newTimes[trial] = run(true, count, 100, false);
                } else {
                    newTimes[trial] = run(true, count, 100, false);
                    oldTimes[trial] = run(false, count, 100, false);
                }
            }
            java.util.Arrays.sort(oldTimes);
            java.util.Arrays.sort(newTimes);
            System.out.printf("agents=%d, 100 microsteps: full scan=%.1f ms, grid=%.1f ms, speedup=%.2fx%n",
                    count, oldTimes[1], newTimes[1], oldTimes[1] / newTimes[1]);
        }
        // Same crowded footprint: the cutoff includes everyone, so quadratic forces remain.
        double crowdedOld = run(false, 1000, 100, true);
        double crowdedNew = run(true, 1000, 100, true);
        System.out.printf("crowded 1000 agents: full scan=%.1f ms, grid=%.1f ms, speedup=%.2fx%n",
                crowdedOld, crowdedNew, crowdedOld / crowdedNew);
    }

    private static double run(boolean indexed, int count, int steps, boolean crowded) {
        TrafficManager manager = indexed ? new TrafficManager() : new TrafficManager() {
            @Override
            public Collection<TrafficAgent> getNearbyAgents(TrafficAgent agent, double cutoff) {
                return super.getNearbyAgents(agent);
            }
        };
        TrafficSpatialIndexTest.area(manager, 1, -200000, 200000);
        List<TrafficAgent> agents = new ArrayList<>();
        Random random = new Random(173);
        double span = crowded ? 5000 : 300000;
        for (int i = 0; i < count; i++) {
            double x = random.nextDouble() * span - span / 2;
            double y = random.nextDouble() * span - span / 2;
            TrafficAgent agent = TrafficSpatialIndexTest.agent(manager, i + 100, x, y);
            agent.setPath(List.of(new PathElement(new EntityID(1), null, new Point2D(x + 15000, y + 15000))));
            agent.beginTimestep();
            agents.add(agent);
        }
        long start = System.nanoTime();
        for (int step = 0; step < steps; step++) {
            for (TrafficAgent agent : agents) {
                agent.step(100);
            }
        }
        double elapsed = (System.nanoTime() - start) / 1e6;
        sink = Double.doubleToLongBits(agents.get(0).getX());
        return elapsed;
    }
}
