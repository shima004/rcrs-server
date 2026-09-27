package rescuecore2.standard.kernel;

import java.util.Arrays;
import java.util.List;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.misc.geometry.Vector2D;
import rescuecore2.standard.kernel.LineOfSightPerception.LineInfo;
import rescuecore2.standard.kernel.LineOfSightPerception.Ray;

/** Standalone ray-loop benchmark, including shared index construction. */
public final class LineOfSightRayBenchmark {
    private static volatile long sink;

    public static void main(String[] args) {
        for (int count : new int[] {100, 500, 2000}) {
            List<LineInfo> lines = LineOfSightRayIndexTest.scene(count, 17);
            for (int warmup = 0; warmup < 5; warmup++) {
                run(lines, false, 20);
                run(lines, true, 20);
            }
            double[] original = new double[5], indexed = new double[5];
            for (int trial = 0; trial < 5; trial++) {
                if (trial % 2 == 0) {
                    original[trial] = run(lines, false, 100);
                    indexed[trial] = run(lines, true, 100);
                } else {
                    indexed[trial] = run(lines, true, 100);
                    original[trial] = run(lines, false, 100);
                }
            }
            Arrays.sort(original);
            Arrays.sort(indexed);
            System.out.printf("edges=%d, 100 observers x 72 rays: scan=%.2f ms, index=%.2f ms, %.2fx%n",
                    count, original[2], indexed[2], original[2] / indexed[2]);
        }
    }

    private static double run(List<LineInfo> lines, boolean indexed, int observers) {
        long checksum = 0;
        long start = System.nanoTime();
        LineOfSightRayIndex index = indexed ? new LineOfSightRayIndex(lines) : null;
        for (int observer = 0; observer < observers; observer++) {
            Point2D origin = new Point2D(observer * 10, observer * 20);
            for (int i = 0; i < 72; i++) {
                double angle = i * Math.PI * 2 / 72;
                Line2D segment = new Line2D(origin, new Vector2D(Math.sin(angle), Math.cos(angle)).scale(35000));
                Ray ray = new Ray(segment, indexed ? index.candidates(segment) : lines);
                checksum += ray.getLinesHit().size();
            }
        }
        sink = checksum;
        return (System.nanoTime() - start) / 1e6;
    }
}
