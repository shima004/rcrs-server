package rescuecore2.log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Includes serialization, entry compression and archive close for both implementations. */
public final class ZipLogWriterBenchmark {
    public static void main(String[] args) throws Exception {
        List<LogRecord> records = args.length == 0 ? ZipLogWriterTest.records(539) : mapRecords(args[0]);
        Path directory = Files.createTempDirectory("rcrs-log-benchmark-");
        Path baseline = directory.resolve("baseline.7z"), actual = directory.resolve("cached.7z");
        try {
            measure(baseline, records, false);
            measure(actual, records, true);
            double[] oldTimes = new double[3], newTimes = new double[3];
            for (int i = 0; i < 3; i++) {
                oldTimes[i] = measure(baseline, records, false);
                newTimes[i] = measure(actual, records, true);
            }
            Arrays.sort(oldTimes);
            Arrays.sort(newTimes);
            ZipLogWriterTest.assertSameContents(baseline, actual);
            System.out.printf("%d perceptions: baseline=%.1f ms, adaptive=%.1f ms, %.2fx, identical records, archive %d -> %d bytes%n",
                    records.size() - 2, oldTimes[1], newTimes[1], oldTimes[1] / newTimes[1], Files.size(baseline), Files.size(actual));
        } finally {
            Files.deleteIfExists(baseline);
            Files.deleteIfExists(actual);
            Files.delete(directory);
        }
    }

    private static List<LogRecord> mapRecords(String map) throws Exception {
        var config = new rescuecore2.config.Config(new java.io.File(map, "config/perception.cfg"));
        config.setValue("gis.map.dir", new java.io.File(map, "map").getPath());
        config.setValue("fire.tank.maximum", "15000");
        config.setValue("senario.human.random-id", "false");
        var world = (rescuecore2.standard.entities.StandardWorldModel)
                new gis2.GMLWorldModelCreator().buildWorldModel(config);
        var perception = new rescuecore2.standard.kernel.LineOfSightPerception();
        perception.initialise(config, world);
        perception.setTime(1);
        var connection = (rescuecore2.connection.Connection) java.lang.reflect.Proxy.newProxyInstance(
                ZipLogWriterBenchmark.class.getClassLoader(),
                new Class<?>[] {rescuecore2.connection.Connection.class},
                (proxy, method, arguments) -> null);
        List<LogRecord> records = new java.util.ArrayList<>();
        records.add(new StartLogRecord());
        for (var entity : world) {
            if (entity instanceof rescuecore2.standard.entities.Human) {
                var agent = new kernel.AgentProxy("benchmark", entity, connection);
                records.add(new PerceptionRecord(1, entity.getID(), perception.getVisibleEntities(agent), List.of()));
            }
        }
        records.add(new EndLogRecord());
        return records;
    }

    private static double measure(Path path, List<LogRecord> records, boolean cached) throws Exception {
        long start = System.nanoTime();
        if (cached) ZipLogWriterTest.writeOptimized(path, records);
        else ZipLogWriterTest.writeBaseline(path, records, true);
        return (System.nanoTime() - start) / 1e6;
    }
}
