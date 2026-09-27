package traffic3.simulator;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import rescuecore2.config.Config;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.Road;
import rescuecore2.worldmodel.EntityID;
import traffic3.manager.TrafficManager;
import traffic3.objects.TrafficAgent;
import traffic3.objects.TrafficArea;

class TrafficSpatialIndexTest {
    static TrafficArea area(TrafficManager manager, int id, int left, int right) {
        Road road = new Road(new EntityID(id));
        road.setEdges(List.of(new Edge(left, -200000, right, -200000),
                new Edge(right, -200000, right, 200000),
                new Edge(right, 200000, left, 200000),
                new Edge(left, 200000, left, -200000)));
        TrafficArea area = new TrafficArea(road);
        manager.register(area);
        return area;
    }

    static TrafficAgent agent(TrafficManager manager, int id, double x, double y) {
        TrafficAgent agent = new TrafficAgent(new Civilian(new EntityID(id)), manager, 200, 0.2);
        manager.register(agent);
        agent.setPositionHistoryEnabled(false);
        agent.setLocation(x, y);
        return agent;
    }

    private static Set<TrafficAgent> reference(TrafficManager manager, TrafficAgent agent, double cutoff) {
        Set<TrafficAgent> expected = new HashSet<>(manager.getNearbyAgents(agent));
        expected.removeIf(other -> !other.isMobile()
                || Math.abs(other.getX() - agent.getX()) > cutoff
                || Math.abs(other.getY() - agent.getY()) > cutoff);
        return expected;
    }

    @Test
    void matchesFullScanDuringMovementAndMobilityChanges() {
        TrafficManager manager = new TrafficManager();
        TrafficArea left = area(manager, 1, -200000, 0);
        TrafficArea right = area(manager, 2, 0, 200000);
        manager.getNeighbours(left).add(right);
        manager.getNeighbours(right).add(left);
        Random random = new Random(712);
        List<TrafficAgent> agents = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            agents.add(agent(manager, 100 + i, random.nextDouble() * 399998 - 199999,
                    random.nextDouble() * 399998 - 199999));
        }
        double[] cutoffs = {0, 100, 10000, 17000, 1000000, Double.POSITIVE_INFINITY};
        for (int step = 0; step < 1000; step++) {
            TrafficAgent moved = agents.get(random.nextInt(agents.size()));
            moved.setLocation(random.nextDouble() * 399998 - 199999,
                    random.nextDouble() * 399998 - 199999);
            moved.setMobile(random.nextBoolean());
            TrafficAgent query = agents.get(random.nextInt(agents.size()));
            double cutoff = cutoffs[step % cutoffs.length];
            assertEquals(reference(manager, query, cutoff), new HashSet<>(manager.getNearbyAgents(query, cutoff)));
        }
    }

    @Test
    void includesSquareBoundaryAndExcludesNonAdjacentAreas() {
        TrafficManager manager = new TrafficManager();
        TrafficArea left = area(manager, 1, -200000, 0);
        TrafficArea right = area(manager, 2, 0, 200000);
        TrafficAgent query = agent(manager, 100, -5000, -10000);
        TrafficAgent corner = agent(manager, 101, 5000, 0);
        TrafficAgent colocated = agent(manager, 102, -5000, -10000);
        TrafficAgent outside = agent(manager, 103, -15000.01, -10000);
        for (int i = 0; i < 100; i++) {
            agent(manager, 1000 + i, -100000, i * 1000);
        }
        assertEquals(Set.of(colocated), new HashSet<>(manager.getNearbyAgents(query, 10000)));
        manager.getNeighbours(left).add(right);
        assertEquals(Set.of(colocated, corner), new HashSet<>(manager.getNearbyAgents(query, 10000)));
        outside.setLocation(-15000, -10000);
        assertEquals(reference(manager, query, 10000), new HashSet<>(manager.getNearbyAgents(query, 10000)));
        left.removeAgent(outside);
        assertFalse(manager.getNearbyAgents(query, 10000).contains(outside));
        left.addAgent(outside);
        assertTrue(manager.getNearbyAgents(query, 10000).contains(outside));
        corner.setLocation(-5000, -10000);
        assertTrue(right.getAgents().isEmpty());
        assertEquals(Set.of(corner, colocated), new HashSet<>(manager.getNearbyAgents(query, 0)));
        manager.clear();
        assertTrue(manager.getAgents().isEmpty());
        assertTrue(manager.getAreas().isEmpty());
    }

    @Test
    void preservesRoundedDistanceAtCellBoundaries() {
        TrafficManager manager = new TrafficManager();
        area(manager, 1, -200000, 200000);
        TrafficAgent query = agent(manager, 100, 10000, 10000);
        TrafficAgent roundedBoundary = agent(manager, 101, -1e-13, -1e-13);
        for (int i = 0; i < 100; i++) {
            agent(manager, 1000 + i, -100000, i * 1000);
        }
        assertTrue(reference(manager, query, 10000).contains(roundedBoundary));
        assertEquals(reference(manager, query, 10000), new HashSet<>(manager.getNearbyAgents(query, 10000)));
    }

    @Test
    void forceAndMovementRemainEquivalentOver600Microsteps() {
        TrafficConstants.init(new Config());
        TrafficManager indexed = new TrafficManager();
        TrafficManager original = new TrafficManager() {
            @Override
            public Collection<TrafficAgent> getNearbyAgents(TrafficAgent agent, double cutoff) {
                return super.getNearbyAgents(agent);
            }
        };
        area(indexed, 1, -200000, 200000);
        area(original, 1, -200000, 200000);
        List<TrafficAgent> fast = new ArrayList<>();
        List<TrafficAgent> slow = new ArrayList<>();
        Random random = new Random(45);
        for (int i = 0; i < 150; i++) {
            double x = random.nextDouble() * 200000 - 100000;
            double y = random.nextDouble() * 200000 - 100000;
            fast.add(agent(indexed, i + 100, x, y));
            slow.add(agent(original, i + 100, x, y));
            Point2D goal = new Point2D(x + 15000, y + 15000);
            fast.get(i).setPath(List.of(new PathElement(new EntityID(1), null, goal)));
            slow.get(i).setPath(List.of(new PathElement(new EntityID(1), null, goal)));
        }
        fast.forEach(TrafficAgent::beginTimestep);
        slow.forEach(TrafficAgent::beginTimestep);
        for (int step = 0; step < 600; step++) {
            for (int i = 0; i < fast.size(); i++) {
                fast.get(i).step(100);
                slow.get(i).step(100);
                assertEquals(slow.get(i).getX(), fast.get(i).getX(), 1e-6);
                assertEquals(slow.get(i).getY(), fast.get(i).getY(), 1e-6);
            }
        }
    }
}
