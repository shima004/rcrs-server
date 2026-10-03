package rescuecore2.standard.kernel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.misc.geometry.Vector2D;
import rescuecore2.standard.entities.Building;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.kernel.LineOfSightPerception.LineInfo;
import rescuecore2.standard.kernel.LineOfSightPerception.Ray;

class LineOfSightRayIndexTest {
    static LineInfo line(double x, double y, double dx, double dy, int id, boolean blocking) {
        return new LineInfo(new Line2D(new Point2D(x, y), new Vector2D(dx, dy)),
                new Building(new EntityID(id)), blocking);
    }

    static List<LineInfo> scene(int count, long seed) {
        Random random = new Random(seed);
        List<LineInfo> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(line(random.nextDouble() * 70000 - 35000,
                    random.nextDouble() * 70000 - 35000,
                    random.nextDouble() * 6000 - 3000,
                    random.nextDouble() * 6000 - 3000, i, i % 3 == 0));
        }
        return lines;
    }

    static void assertSameRay(Line2D segment, List<LineInfo> lines, LineOfSightRayIndex index) {
        Ray reference = new Ray(segment, lines);
        Ray actual = new Ray(segment, index.candidates(segment));
        assertEquals(reference.getVisibleLength(), actual.getVisibleLength());
        assertEquals(reference.getVisibleLength() >= 1, index.isVisible(segment, null));
        assertEquals(reference.getLinesHit(), actual.getLinesHit());
        var visible = new java.util.HashSet<rescuecore2.standard.entities.StandardEntity>();
        index.addVisibleEntities(segment, null, visible);
        var expected = new java.util.HashSet<rescuecore2.standard.entities.StandardEntity>();
        for (LineInfo hit : reference.getLinesHit()) expected.add(hit.getEntity());
        assertEquals(expected, visible);
    }

    @Test
    void matchesAllHitsAndOcclusionOnRandomRays() {
        List<LineInfo> lines = scene(2000, 7845);
        LineOfSightRayIndex index = new LineOfSightRayIndex(lines);
        Random random = new Random(832);
        for (int i = 0; i < 3000; i++) {
            Point2D origin = new Point2D(random.nextDouble() * 20000 - 10000,
                    random.nextDouble() * 20000 - 10000);
            Vector2D direction = new Vector2D(random.nextDouble() * 70000 - 35000,
                    random.nextDouble() * 70000 - 35000);
            assertSameRay(new Line2D(origin, direction), lines, index);
        }
    }

    @Test
    void preservesTieOrderEndpointsParallelAndZeroLengthRays() {
        List<LineInfo> lines = scene(100, 85);
        // Duplicate distances must retain source order, including a blocker in the middle.
        lines.add(0, line(100, -100, 0, 200, 1001, false));
        lines.add(1, line(100, -100, 0, 200, 1002, true));
        lines.add(2, line(100, -100, 0, 200, 1003, false));
        lines.add(line(0, 0, 100, 0, 1004, true));
        lines.add(line(0, 0, 0, 0, 1005, true));
        LineOfSightRayIndex index = new LineOfSightRayIndex(lines);
        for (Vector2D direction : List.of(new Vector2D(100, 0), new Vector2D(200, 0),
                new Vector2D(0, 100), new Vector2D(0, 0), new Vector2D(-100, 0),
                new Vector2D(100, 100), new Vector2D(100, 1e-12))) {
            assertSameRay(new Line2D(new Point2D(0, 0), direction), lines, index);
        }
    }

    @Test
    void emptyAndRebuiltGeometry() {
        Line2D ray = new Line2D(new Point2D(0, 0), new Vector2D(100, 0));
        List<LineInfo> lines = new ArrayList<>();
        assertSameRay(ray, lines, new LineOfSightRayIndex(lines));
        lines.add(line(50, -100, 0, 200, 1, true));
        assertSameRay(ray, lines, new LineOfSightRayIndex(lines));
        lines.set(0, line(500, -100, 0, 200, 1, true));
        assertSameRay(ray, lines, new LineOfSightRayIndex(lines));
        lines.clear();
        assertSameRay(ray, lines, new LineOfSightRayIndex(lines));
    }

    @Test
    void filtersByNearbyEntitiesAndRetainsTheirTieOrder() {
        LineInfo excluded = line(10, -100, 0, 200, 2000, true);
        LineInfo first = line(100, -100, 0, 200, 2001, false);
        LineInfo second = line(100, -100, 0, 200, 2002, true);
        LineInfo third = line(100, -100, 0, 200, 2003, false);
        List<LineInfo> global = new ArrayList<>(scene(100, 17));
        global.addAll(List.of(third, second, excluded, first));
        var order = new java.util.HashMap<rescuecore2.standard.entities.StandardEntity, Integer>();
        order.put(first.getEntity(), 0);
        order.put(second.getEntity(), 1);
        order.put(third.getEntity(), 2);
        Line2D segment = new Line2D(new Point2D(0, 0), new Vector2D(1000, 0));
        Ray expected = new Ray(segment, List.of(first, second, third));
        Ray actual = new Ray(segment, new LineOfSightRayIndex(global).candidates(segment, order));
        assertEquals(expected.getLinesHit(), actual.getLinesHit());
        var visible = new java.util.HashSet<rescuecore2.standard.entities.StandardEntity>();
        new LineOfSightRayIndex(global).addVisibleEntities(segment, order, visible);
        assertEquals(java.util.Set.of(first.getEntity(), second.getEntity()), visible);
        assertEquals(expected.getVisibleLength(), actual.getVisibleLength());
        assertEquals(expected.getVisibleLength() >= 1, new LineOfSightRayIndex(global).isVisible(segment, order));
    }

    @Test
    void perceptionRebuildsAfterWorldChanges() {
        var world = new rescuecore2.standard.entities.StandardWorldModel();
        var observer = new rescuecore2.standard.entities.Civilian(new EntityID(3000));
        observer.setX(0);
        observer.setY(0);
        var target = new rescuecore2.standard.entities.Civilian(new EntityID(3001));
        target.setX(1000);
        target.setY(0);
        world.addEntity(observer);
        world.addEntity(target);
        Building wall = new Building(new EntityID(3002));
        wall.setX(550);
        wall.setY(0);
        wall.setEdges(List.of(new rescuecore2.standard.entities.Edge(500, -500, 600, -500),
                new rescuecore2.standard.entities.Edge(600, -500, 600, 500),
                new rescuecore2.standard.entities.Edge(600, 500, 500, 500),
                new rescuecore2.standard.entities.Edge(500, 500, 500, -500)));
        world.addEntity(wall);
        var connection = (rescuecore2.connection.Connection) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {rescuecore2.connection.Connection.class},
                (proxy, method, args) -> null);
        var agent = new kernel.AgentProxy("test", observer, connection);
        var perception = new LineOfSightPerception();
        perception.initialise(new rescuecore2.config.Config(), world);
        perception.setTime(1);
        assertFalse(perception.getVisibleEntities(agent).getChangedEntities().contains(target.getID()));
        world.removeEntity(wall.getID());
        perception.setTime(2);
        assertTrue(perception.getVisibleEntities(agent).getChangedEntities().contains(target.getID()));
        world.addEntity(wall);
        perception.setTime(3);
        assertFalse(perception.getVisibleEntities(agent).getChangedEntities().contains(target.getID()));
    }

    @Test
    void largeCoordinatesAndGrazingEndpoints() {
        double offset = 2_000_000_000;
        List<LineInfo> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            lines.add(line(offset + i * 100, offset - 100, 0, 200, i, i % 5 == 0));
        }
        LineOfSightRayIndex index = new LineOfSightRayIndex(lines);
        for (double y : new double[] {offset - 100, offset + 100, Math.nextUp(offset + 100)}) {
            assertSameRay(new Line2D(new Point2D(offset - 100, y), new Vector2D(35000, 0)), lines, index);
        }
    }
}
