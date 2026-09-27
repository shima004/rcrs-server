package rescuecore2.log;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.tukaani.xz.ArrayCache;
import rescuecore2.messages.protobuf.RCRSLogProto.LogProto;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.EntityID;

class ZipLogWriterTest {
    @TempDir Path directory;

    static List<LogRecord> records(int count) {
        List<LogRecord> records = new ArrayList<>();
        records.add(new StartLogRecord());
        for (int i = 0; i < count; i++) {
            ChangeSet changes = new ChangeSet();
            for (int j = 0; j < i % 100; j++) {
                Civilian human = new Civilian(new EntityID(j + 1000));
                human.setHP(10000 - i - j);
                human.setX(i * 100 + j);
                human.setY(j * 10 - i);
                changes.addChange(human, human.getHPProperty());
                changes.addChange(human, human.getXProperty());
                changes.addChange(human, human.getYProperty());
            }
            records.add(new PerceptionRecord(i / 50 + 1, new EntityID(i), changes, List.of()));
        }
        records.add(new EndLogRecord());
        return records;
    }

    static String name(LogRecord record) {
        if (record instanceof PerceptionRecord perception) {
            return perception.getTime() + "/PERCEPTION/" + perception.getEntityID();
        }
        return record.getRecordType().name();
    }

    static void writeBaseline(Path path, List<LogRecord> records) throws Exception {
        writeBaseline(path, records, false);
    }

    static void writeBaseline(Path path, List<LogRecord> records, boolean cacheArrays) throws Exception {
        try (SevenZOutputFile out = new SevenZOutputFile(path.toFile())) {
            if (cacheArrays) {
                var options = new org.tukaani.xz.LZMA2Options() {
                    private final org.tukaani.xz.BasicArrayCache cache = new org.tukaani.xz.BasicArrayCache();
                    @Override
                    public org.tukaani.xz.FinishableOutputStream getOutputStream(org.tukaani.xz.FinishableOutputStream stream) {
                        return super.getOutputStream(stream, cache);
                    }
                };
                out.setContentMethods(List.of(new org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration(
                        org.apache.commons.compress.archivers.sevenz.SevenZMethod.LZMA2, options)));
            }
            for (LogRecord record : records) {
                SevenZArchiveEntry entry = new SevenZArchiveEntry();
                entry.setDirectory(false);
                entry.setName(name(record));
                out.putArchiveEntry(entry);
                out.write(record.toLogProto().toByteArray());
                out.closeArchiveEntry();
            }
        }
    }

    static void writeOptimized(Path path, List<LogRecord> records) throws Exception {
        ZipLogWriter writer = new ZipLogWriter(path.toFile());
        try {
            for (LogRecord record : records) writer.writeRecord(record);
        } finally {
            writer.close();
        }
    }

    static void assertSameContents(Path baseline, Path actual) throws Exception {
        try (SevenZFile expected = SevenZFile.builder().setFile(baseline.toFile()).get();
                SevenZFile result = SevenZFile.builder().setFile(actual.toFile()).get()) {
            SevenZArchiveEntry entry;
            while ((entry = expected.getNextEntry()) != null) {
                SevenZArchiveEntry other = result.getNextEntry();
                if (other == null || !entry.getName().equals(other.getName())) {
                    throw new AssertionError("Entry order/name differs");
                }
                try (var a = expected.getInputStream(entry); var b = result.getInputStream(other)) {
                    if (!java.util.Arrays.equals(a.readAllBytes(), b.readAllBytes())) {
                        throw new AssertionError("Record bytes differ: " + entry.getName());
                    }
                }
            }
            if (result.getNextEntry() != null) throw new AssertionError("Extra record");
        }
    }

    @Test
    void preservesAllRecordsAcrossDifferentDictionarySizes() throws Exception {
        List<LogRecord> records = records(105);
        var config = new rescuecore2.config.Config();
        config.setValue("large-entry", "0123456789abcdef".repeat(10000));
        records.add(1, new ConfigRecord(config));
        Path baseline = directory.resolve("baseline.7z");
        Path actual = directory.resolve("actual.7z");
        ArrayCache defaultCache = ArrayCache.getDefaultCache();
        writeBaseline(baseline, records);
        writeOptimized(actual, records);
        assertSame(defaultCache, ArrayCache.getDefaultCache());
        assertSameContents(baseline, actual);
        try (SevenZFile reader = SevenZFile.builder().setFile(actual.toFile()).get()) {
            for (LogRecord record : records) {
                SevenZArchiveEntry entry = reader.getNextEntry();
                assertNotNull(entry);
                assertEquals(name(record), entry.getName());
                try (var in = reader.getInputStream(entry)) {
                    assertEquals(record.toLogProto(), LogProto.parseFrom(in.readAllBytes()));
                }
            }
            assertNull(reader.getNextEntry());
        }
    }
}
