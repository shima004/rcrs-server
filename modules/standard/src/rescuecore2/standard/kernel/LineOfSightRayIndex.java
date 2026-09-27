package rescuecore2.standard.kernel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.standard.kernel.LineOfSightPerception.LineInfo;

/** A bounding-volume tree shared by observers within one perception timestep. */
final class LineOfSightRayIndex {
    private static final int LEAF_SIZE = 8;
    private final Entry[] entries;
    private final Node root;

    LineOfSightRayIndex(Collection<LineInfo> lines) {
        entries = new Entry[lines.size()];
        int order = 0;
        for (LineInfo line : lines) {
            entries[order] = new Entry(line, order);
            order++;
        }
        root = entries.length == 0 ? null : new Node(0, entries.length);
    }

    List<LineInfo> candidates(Line2D ray) {
        return candidates(ray, null);
    }

    List<LineInfo> candidates(Line2D ray, Map<StandardEntity, Integer> entityOrder) {
        List<Entry> found = new ArrayList<>();
        if (root != null) {
            root.collect(ray, entityOrder, found);
        }
        // Ray intersection sorting is stable: retain source order for equal-distance
        // hits, including coincident blocking and non-blocking edges.
        Comparator<Entry> order = Comparator.comparingInt(e -> e.order);
        if (entityOrder != null) {
            order = Comparator.<Entry>comparingInt(e -> entityOrder.get(e.line.getEntity())).thenComparing(order);
        }
        found.sort(order);
        List<LineInfo> result = new ArrayList<>(found.size());
        for (Entry entry : found) {
            result.add(entry.line);
        }
        return result;
    }

    /** Human visibility only needs to know whether a blocker lies before the endpoint. */
    boolean isVisible(Line2D ray, Map<StandardEntity, Integer> entityOrder) {
        return root == null || !root.blocks(ray, entityOrder);
    }

    private static final class Entry {
        final LineInfo line;
        final int order;
        final double minX, minY, maxX, maxY;

        Entry(LineInfo line, int order) {
            this.line = line;
            this.order = order;
            Line2D segment = line.getLine();
            minX = Math.min(segment.getOrigin().getX(), segment.getEndPoint().getX());
            maxX = Math.max(segment.getOrigin().getX(), segment.getEndPoint().getX());
            minY = Math.min(segment.getOrigin().getY(), segment.getEndPoint().getY());
            maxY = Math.max(segment.getOrigin().getY(), segment.getEndPoint().getY());
        }
    }

    private final class Node {
        final int from, to;
        final double minX, minY, maxX, maxY;
        final Node left, right;
        final boolean hasBlocking;

        Node(int from, int to) {
            this.from = from;
            this.to = to;
            double x0 = Double.POSITIVE_INFINITY, y0 = Double.POSITIVE_INFINITY;
            double x1 = Double.NEGATIVE_INFINITY, y1 = Double.NEGATIVE_INFINITY;
            boolean blocking = false;
            for (int i = from; i < to; i++) {
                Entry e = entries[i];
                blocking |= e.line.isBlocking();
                x0 = Math.min(x0, e.minX);
                y0 = Math.min(y0, e.minY);
                x1 = Math.max(x1, e.maxX);
                y1 = Math.max(y1, e.maxY);
            }
            hasBlocking = blocking;
            minX = x0;
            minY = y0;
            maxX = x1;
            maxY = y1;
            if (to - from <= LEAF_SIZE) {
                left = right = null;
            } else {
                boolean splitX = maxX - minX >= maxY - minY;
                Arrays.sort(entries, from, to, Comparator.comparingDouble(e ->
                        splitX ? e.minX + e.maxX : e.minY + e.maxY));
                int mid = (from + to) >>> 1;
                left = new Node(from, mid);
                right = new Node(mid, to);
            }
        }

        boolean blocks(Line2D ray, Map<StandardEntity, Integer> entityOrder) {
            if (!hasBlocking || !intersects(ray, minX, minY, maxX, maxY)) {
                return false;
            }
            if (left != null) {
                return left.blocks(ray, entityOrder) || right.blocks(ray, entityOrder);
            }
            for (int i = from; i < to; i++) {
                Entry e = entries[i];
                if (!e.line.isBlocking() || (entityOrder != null && !entityOrder.containsKey(e.line.getEntity()))) {
                    continue;
                }
                double d1 = ray.getIntersection(e.line.getLine());
                double d2 = e.line.getLine().getIntersection(ray);
                // A wall at the endpoint still gives visibleLength == 1.
                if (d2 >= 0 && d2 <= 1 && d1 > 0 && d1 < 1) {
                    return true;
                }
            }
            return false;
        }

        void collect(Line2D ray, Map<StandardEntity, Integer> entityOrder, List<Entry> result) {
            if (!intersects(ray, minX, minY, maxX, maxY)) {
                return;
            }
            if (left != null) {
                left.collect(ray, entityOrder, result);
                right.collect(ray, entityOrder, result);
            } else {
                for (int i = from; i < to; i++) {
                    Entry e = entries[i];
                    if ((entityOrder == null || entityOrder.containsKey(e.line.getEntity()))
                            && intersects(ray, e.minX, e.minY, e.maxX, e.maxY)) {
                        result.add(e);
                    }
                }
            }
        }
    }

    private static boolean intersects(Line2D ray, double minX, double minY, double maxX, double maxY) {
        double x = ray.getOrigin().getX(), y = ray.getOrigin().getY();
        double dx = ray.getDirection().getX(), dy = ray.getDirection().getY();
        // Broad-phase only. Pad bounds to avoid rejecting endpoint hits through
        // roundoff; the original intersection calculation makes the final decision.
        double scale = Math.max(Math.max(Math.abs(x) + Math.abs(dx), Math.abs(y) + Math.abs(dy)),
                Math.max(Math.max(Math.abs(minX), Math.abs(maxX)), Math.max(Math.abs(minY), Math.abs(maxY))));
        if (!Double.isFinite(scale)) {
            return true;
        }
        double padding = Math.max(1e-7, 32 * Math.ulp(scale));
        double lo = 0, hi = 1;
        if (dx == 0) {
            if (x < minX - padding || x > maxX + padding) return false;
        } else {
            double a = (minX - padding - x) / dx, b = (maxX + padding - x) / dx;
            lo = Math.max(lo, Math.min(a, b));
            hi = Math.min(hi, Math.max(a, b));
        }
        if (dy == 0) {
            if (y < minY - padding || y > maxY + padding) return false;
        } else {
            double a = (minY - padding - y) / dy, b = (maxY + padding - y) / dy;
            lo = Math.max(lo, Math.min(a, b));
            hi = Math.min(hi, Math.max(a, b));
        }
        return lo <= hi;
    }
}
