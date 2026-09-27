package rescuecore2.log;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.tukaani.xz.BasicArrayCache;
import org.tukaani.xz.FinishableOutputStream;
import org.tukaani.xz.LZMA2Options;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;

public class ZipLogWriter implements LogWriter {

	private SevenZOutputFile sevenZOutput;
	private final BasicArrayCache cache = new BasicArrayCache();
	private final Map<Integer, LZMA2Options> optionsBySize = new HashMap<>();

	public ZipLogWriter(File file) throws IOException {
		sevenZOutput = new SevenZOutputFile(file);
	}

	/** Reuse encoder work arrays across entries without changing global XZ settings. */
	private static final class CachedLZMA2Options extends LZMA2Options {
		private final BasicArrayCache cache;

		CachedLZMA2Options(int dictionarySize, BasicArrayCache cache) throws IOException {
			setDictSize(dictionarySize);
			this.cache = cache;
		}

		@Override
		public FinishableOutputStream getOutputStream(FinishableOutputStream out) {
			return super.getOutputStream(out, cache);
		}
	}

	@Override
	public void writeRecord(LogRecord record) throws LogException {
		try {
			byte[] data = record.toLogProto().toByteArray();
			// Each entry has an independent compression history. A dictionary larger
			// than this entry cannot improve matches, but costs memory and reset time.
			int size = LZMA2Options.DICT_SIZE_MIN;
			while (size < data.length && size < LZMA2Options.DICT_SIZE_DEFAULT) {
				size <<= 1;
			}
			LZMA2Options options = optionsBySize.get(size);
			if (options == null) {
				options = new CachedLZMA2Options(size, cache);
				optionsBySize.put(size, options);
			}
			sevenZOutput.setContentMethods(List.of(new SevenZMethodConfiguration(SevenZMethod.LZMA2, options)));
			sevenZOutput.putArchiveEntry(getRecordArchive(record));
			sevenZOutput.write(data);
			sevenZOutput.closeArchiveEntry();
		} catch (IOException e) {
			throw new LogException(e);
		}
	}

	@Override
	public void close() {
		try {
			sevenZOutput.close();
		} catch (IOException e) {
			Logger.error("Error closing log stream", e);
		}
	}

	private SevenZArchiveEntry createArchiveEntry(String path) {
		final SevenZArchiveEntry entry = new SevenZArchiveEntry();
		entry.setDirectory(false);
		entry.setName(path);
		return entry;
	}

	private SevenZArchiveEntry getRecordArchive(LogRecord record) {
		String recordType = record.getRecordType().name();
		switch (record.getRecordType()) {
		case COMMANDS:
			CommandsRecord crecord = (CommandsRecord) record;
			return createArchiveEntry(crecord.getTime() + "/" + recordType);
		case PERCEPTION:
			PerceptionRecord precord = (PerceptionRecord) record;
			return createArchiveEntry(precord.getTime() + "/" + recordType + "/"
					+ precord.getEntityID());
		case UPDATES:
			UpdatesRecord urecord = (UpdatesRecord) record;
			return createArchiveEntry(urecord.getTime() + "/" + recordType);
		case CONFIG:
		case INITIAL_CONDITIONS:
		case START_OF_LOG:
		case END_OF_LOG:
			return createArchiveEntry(recordType);

		default:
			throw new IllegalArgumentException(
					"Unexpected value: " + record.getRecordType());
		}

	}

}
