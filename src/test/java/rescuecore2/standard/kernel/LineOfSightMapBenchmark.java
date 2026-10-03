package rescuecore2.standard.kernel;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kernel.AgentProxy;
import kernel.Perception;
import gis2.GMLWorldModelCreator;
import rescuecore2.config.Config;
import rescuecore2.connection.Connection;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.worldmodel.ChangeSet;

/** Compare full perception outputs and timings with a separately compiled baseline. */
public final class LineOfSightMapBenchmark {
    private static volatile int sink;

    public static void main(String[] args) throws Exception {
        String map = args.length > 0 ? args[0] : "maps/vc";
        String baselineClass = args.length > 1 ? args[1]
                : "rescuecore2.standard.kernel.BaselineLineOfSightPerception";
        Config config = new Config(new File(map, "config/perception.cfg"));
        config.setValue("gis.map.dir", new File(map, "map").getPath());
        config.setValue("fire.tank.maximum", "15000");
        config.setValue("senario.human.random-id", "false");
        StandardWorldModel world = (StandardWorldModel) new GMLWorldModelCreator().buildWorldModel(config);
        Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, arguments) -> null);
        List<AgentProxy> agents = new ArrayList<>();
        for (StandardEntity entity : world) {
            if (entity instanceof Human) {
                agents.add(new AgentProxy("benchmark", entity, connection));
            }
        }
        Perception original = (Perception) Class.forName(baselineClass).getConstructor().newInstance();
        Perception indexed = new LineOfSightPerception();
        original.initialise(config, world);
        indexed.initialise(config, world);
        original.setTime(1);
        indexed.setTime(1);
        for (AgentProxy agent : agents) {
            ChangeSet expected = original.getVisibleEntities(agent);
            ChangeSet actual = indexed.getVisibleEntities(agent);
            if (!canonical(expected).equals(canonical(actual))) {
                throw new AssertionError("Different perception for " + agent.getControlledEntity().getID()
                        + "\nExpected " + expected + "\nActual " + actual);
            }
        }
        System.out.printf("%s: identical ChangeSets for all %d humans%n", map, agents.size());
        for (int warmup = 0; warmup < 2; warmup++) {
            run(original, agents);
            run(indexed, agents);
        }
        double[] oldTimes = new double[5], newTimes = new double[5];
        for (int trial = 0; trial < 5; trial++) {
            if (trial % 2 == 0) {
                oldTimes[trial] = run(original, agents);
                newTimes[trial] = run(indexed, agents);
            } else {
                newTimes[trial] = run(indexed, agents);
                oldTimes[trial] = run(original, agents);
            }
        }
        Arrays.sort(oldTimes);
        Arrays.sort(newTimes);
        System.out.printf("Full getVisibleEntities, median of 5: baseline=%.1f ms, indexed=%.1f ms, %.2fx%n",
                oldTimes[2], newTimes[2], oldTimes[2] / newTimes[2]);
    }

    // HashSet insertion order can differ while the delivered properties are identical.
    private static java.util.Map<Integer, String> canonical(ChangeSet changes) {
        var result = new java.util.TreeMap<Integer, String>();
        for (var id : changes.getChangedEntities()) {
            var properties = new java.util.ArrayList<String>();
            for (var property : changes.getChangedProperties(id)) properties.add(property.toString());
            java.util.Collections.sort(properties);
            result.put(id.getValue(), changes.getEntityURN(id) + ":" + properties);
        }
        for (var id : changes.getDeletedEntities()) result.put(id.getValue(), "deleted");
        return result;
    }

    private static double run(Perception perception, List<AgentProxy> agents) {
        long start = System.nanoTime();
        perception.setTime(1); // Include a fresh tree in every measured timestep.
        int count = 0;
        for (AgentProxy agent : agents) {
            count += perception.getVisibleEntities(agent).getChangedEntities().size();
        }
        sink = count;
        return (System.nanoTime() - start) / 1e6;
    }
}
