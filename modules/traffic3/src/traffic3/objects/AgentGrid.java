package traffic3.objects;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Spatial buckets for the agents in one area, updated after each movement. */
final class AgentGrid {
    private static final double CELL_SIZE = 10000;
    private final Map<Cell, Set<TrafficAgent>> cells = new HashMap<>();

    private record Cell(int x, int y) {
        static Cell at(double x, double y) {
            return new Cell((int) Math.floor(x / CELL_SIZE), (int) Math.floor(y / CELL_SIZE));
        }
    }

    void add(TrafficAgent agent) {
        add(agent, Cell.at(agent.getX(), agent.getY()));
    }

    private void add(TrafficAgent agent, Cell cell) {
        cells.computeIfAbsent(cell, ignored -> new HashSet<>()).add(agent);
    }

    void remove(TrafficAgent agent) {
        remove(agent, Cell.at(agent.getX(), agent.getY()));
    }

    private void remove(TrafficAgent agent, Cell cell) {
        Set<TrafficAgent> bucket = cells.get(cell);
        if (bucket != null) {
            bucket.remove(agent);
            if (bucket.isEmpty()) {
                cells.remove(cell);
            }
        }
    }

    void move(TrafficAgent agent, double x, double y) {
        Cell oldCell = Cell.at(agent.getX(), agent.getY());
        Cell newCell = Cell.at(x, y);
        if (!oldCell.equals(newCell)) {
            remove(agent, oldCell);
            add(agent, newCell);
        }
    }

    void collect(double x, double y, double cutoff, Collection<TrafficAgent> all,
            Collection<TrafficAgent> result) {
        // Expand the bucket bounds conservatively: subtraction in the final
        // distance comparison may round a point just outside x +/- cutoff
        // onto the inclusive cutoff (especially near a zero cell boundary).
        double extent = Math.nextUp(cutoff);
        Cell min = Cell.at(Math.nextDown(x - extent), Math.nextDown(y - extent));
        Cell max = Cell.at(Math.nextUp(x + extent), Math.nextUp(y + extent));
        double cellCount = ((double) max.x - min.x + 1) * ((double) max.y - min.y + 1);
        // Scanning is cheaper for small areas or unusually large cutoffs. It also
        // preserves the old comparison semantics for non-finite inputs.
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(cutoff)
                || cutoff < 0 || cellCount >= all.size()) {
            collectMatching(all, x, y, cutoff, result);
            return;
        }
        for (long cx = min.x; cx <= max.x; cx++) {
            for (long cy = min.y; cy <= max.y; cy++) {
                Set<TrafficAgent> bucket = cells.get(new Cell((int) cx, (int) cy));
                if (bucket != null) {
                    collectMatching(bucket, x, y, cutoff, result);
                }
            }
        }
    }

    private static void collectMatching(Collection<TrafficAgent> agents, double x, double y,
            double cutoff, Collection<TrafficAgent> result) {
        for (TrafficAgent agent : agents) {
            if (agent.isMobile() && !(Math.abs(agent.getX() - x) > cutoff)
                    && !(Math.abs(agent.getY() - y) > cutoff)) {
                result.add(agent);
            }
        }
    }
}
