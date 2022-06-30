/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cutlass.text;

import io.questdb.MessageBus;
import io.questdb.cairo.*;
import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryMARW;
import io.questdb.cutlass.text.types.*;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.RingQueue;
import io.questdb.mp.Sequence;
import io.questdb.std.*;
import io.questdb.std.datetime.DateFormat;
import io.questdb.std.str.DirectCharSink;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;
import org.jetbrains.annotations.TestOnly;

import java.io.Closeable;
import java.util.function.Consumer;


/**
 * Class is responsible for importing of large unordered import files into partitioned tables.
 * It does the following (in parallel) :
 * - splits the file into N-chunks, scans in parallel and finds correct line start for each chunk
 * - scans each chunk and extracts timestamps and line offsets to per-partition index files
 * (index files are stored as $inputWorkDir/$inputFileName/$partitionName/$workerId_$chunkNumber)
 * then it sorts each file by timestamp value
 * - merges all partition index chunks into one index file per partition (index.m)
 * - loads partitions into separate tables using merged indexes (one table per worker)
 * - scans all symbol columns to build per-column global symbol table
 * - remaps all symbol values
 * - moves and attaches partitions from temp tables to target table
 * - removes temp tables and index files
 * <p>
 */
public class ParallelCsvFileImporter implements Closeable, Mutable {
    private static final Log LOG = LogFactory.getLog(ParallelCsvFileImporter.class);

    private static final String LOCK_REASON = "parallel import";
    private static final int NO_INDEX = -1;

    private static final int DEFAULT_MIN_CHUNK_SIZE = 300 * 1024 * 1024;
    private int minChunkSize = DEFAULT_MIN_CHUNK_SIZE;

    //holds result of first phase - boundary scanning
    //count of quotes, even new lines, odd new lines, offset to first even newline, offset to first odd newline
    private final LongList chunkStats = new LongList();

    //holds input for second phase - indexing: offset and start line number for each chunk
    private final LongList indexChunkStats = new LongList();

    //stores timestamp values of indexed partitions 
    private final LongList partitionKeys = new LongList();
    private final StringSink partitionNameSink = new StringSink();
    //stores directory names of imported partitions
    private final ObjList<CharSequence> partitionNames = new ObjList<>();
    //stores 3 values per task : index, lo, hi (lo, hi are indexes in partitionNames)
    private final IntList taskDistribution = new IntList();

    private final FilesFacade ff;

    private final Path inputFilePath = new Path();
    private final Path tmpPath = new Path();

    private final RingQueue<TextImportTask> queue;
    private final Sequence pubSeq;
    private final Sequence subSeq;
    private final Sequence collectSeq;
    private final int workerCount;

    private final CharSequence inputRoot;
    private final CharSequence inputWorkRoot;
    //path to import directory under, usually $inputWorkRoot/$tableName
    private CharSequence importRoot;

    //input params start
    private CharSequence tableName;
    //name of file to process in inputRoot dir
    private CharSequence inputFileName;
    //name of timestamp column
    private CharSequence timestampColumn;
    private int partitionBy;
    private byte columnDelimiter;
    private TimestampAdapter timestampAdapter;
    private final ObjectPool<OtherToTimestampAdapter> otherToTimestampAdapterPool = new ObjectPool<>(OtherToTimestampAdapter::new, 4);
    private boolean forceHeader;
    private int atomicity;
    //input params end

    //index of timestamp column in input file
    private int timestampIndex;
    private final CairoSecurityContext securityContext;

    private final DirectCharSink utf8Sink;
    private final TypeManager typeManager;
    private final TextDelimiterScanner textDelimiterScanner;
    private final TextMetadataDetector textMetadataDetector;

    private final CairoEngine cairoEngine;
    private final CairoConfiguration configuration;

    private boolean targetTableCreated;
    private int targetTableStatus;
    private final TableStructureAdapter targetTableStructure;
    private int bufferLength;

    //import status variables
    private byte status = TextImportTask.STATUS_OK;
    private byte phase = TextImportTask.PHASE_BOUNDARY_CHECK;
    private CharSequence errorMessage;
    private final CancellationToken cancellationToken;
    private long startMs;//start time of current phase (in millis)

    public ParallelCsvFileImporter(CairoEngine cairoEngine, int workerCount, CancellationToken token) {
        this.cairoEngine = cairoEngine;
        this.workerCount = workerCount;
        this.cancellationToken = token;

        this.securityContext = AllowAllCairoSecurityContext.INSTANCE;
        this.configuration = this.cairoEngine.getConfiguration();

        MessageBus bus = this.cairoEngine.getMessageBus();
        this.queue = bus.getTextImportQueue();
        this.pubSeq = bus.getTextImportPubSeq();
        this.subSeq = bus.getTextImportSubSeq();
        this.collectSeq = bus.getTextImportColSeq();

        CairoConfiguration cfg = this.cairoEngine.getConfiguration();

        this.ff = cfg.getFilesFacade();

        this.inputRoot = cfg.getInputRoot();
        this.inputWorkRoot = cfg.getInputWorkRoot();

        TextConfiguration textConfiguration = configuration.getTextConfiguration();
        this.utf8Sink = new DirectCharSink(textConfiguration.getUtf8SinkSize());
        this.typeManager = new TypeManager(textConfiguration, utf8Sink);
        this.textDelimiterScanner = new TextDelimiterScanner(textConfiguration);
        this.textMetadataDetector = new TextMetadataDetector(typeManager, textConfiguration);

        this.targetTableStructure = new TableStructureAdapter(configuration);
        this.bufferLength = configuration.getSqlCopyBufferSize();
        this.targetTableStatus = -1;
        this.targetTableCreated = false;

        this.atomicity = Atomicity.SKIP_COL;
    }

    private static void checkTableName(CharSequence tableName, CairoConfiguration configuration) {
        if (!TableUtils.isValidTableName(tableName, configuration.getMaxFileNameLength())) {
            throw CairoException.instance(0)
                    .put("invalid table name [table=").putAsPrintable(tableName)
                    .put(']');
        }
    }

    public static void createTable(final FilesFacade ff, int mkDirMode, final CharSequence root, final CharSequence tableName, TableStructure structure, int tableId, CairoConfiguration configuration) {
        checkTableName(tableName, configuration);
        try (Path path = new Path()) {
            switch (TableUtils.exists(ff, path, root, tableName, 0, tableName.length())) {
                case TableUtils.TABLE_EXISTS:
                    int errno;
                    if ((errno = ff.rmdir(path)) != 0) {
                        LOG.error().$("remove failed [tableName='").utf8(tableName).$("', error=").$(errno).$(']').$();
                        throw CairoException.instance(errno).put("Table remove failed");
                    }
                case TableUtils.TABLE_DOES_NOT_EXIST:
                    try (MemoryMARW memory = Vm.getMARWInstance()) {
                        TableUtils.createTable(
                                ff,
                                root,
                                mkDirMode,
                                memory,
                                path,
                                tableName,
                                structure,
                                ColumnType.VERSION,
                                tableId
                        );
                    }
                    break;
                default:
                    throw CairoException.instance(0).put("name is reserved [tableName=").put(tableName).put(']');
            }
        }
    }

    @Override
    public void close() {
        clear();
        this.inputFilePath.close();
        this.tmpPath.close();
        this.utf8Sink.close();
        this.textMetadataDetector.close();
        this.textDelimiterScanner.close();
    }

    public void importPartitions() throws TextException {
        checkCancelled(TextImportTask.PHASE_PARTITION_IMPORT);
        if (partitionNames.size() == 0) {
            throw TextException.$("No partitions to merge and load found");
        }

        logStartOf("index merge and partition load");

        final int partitionCount = partitionNames.size();
        final int chunkSize = (partitionCount + workerCount - 1) / workerCount;
        final int taskCount = (partitionCount + chunkSize - 1) / chunkSize;

        int queuedCount = 0;
        int collectedCount = 0;
        taskDistribution.clear();
        for (int i = 0; i < taskCount; ++i) {
            final int lo = i * chunkSize;
            final int hi = Integer.min(lo + chunkSize, partitionCount);

            while (true) {
                final long seq = pubSeq.next();
                if (seq > -1) {
                    final TextImportTask task = queue.get(seq);
                    task.setTaskId(i);
                    task.setCancellationToken(cancellationToken);
                    task.ofImportPartitionDataStage(cairoEngine, targetTableStructure, textMetadataDetector.getColumnTypes(), atomicity, columnDelimiter, importRoot, inputFileName, i, lo, hi, partitionNames, maxLineLength);
                    pubSeq.done(seq);
                    queuedCount++;
                    break;
                } else {
                    collectedCount += collect(queuedCount - collectedCount, this::collectDataImportStats);
                }
            }

            taskDistribution.add(i);
            taskDistribution.add(lo);
            taskDistribution.add(hi);
        }

        collectedCount += collect(queuedCount - collectedCount, this::collectDataImportStats);
        assert collectedCount == queuedCount;

        logEndOf("index merge and partition load");
    }

    private void logStartOf(String phase) {
        LOG.info().$("Started ").$(phase).$(" of file='").$(inputFilePath).$("'").$();
        startMs = getCurrentTimeMs();
    }

    private void logEndOf(String phase) {
        long endMs = getCurrentTimeMs();
        LOG.info().$("Finished ").$(phase).$(" of file='").$(inputFilePath).$("' time=").$((endMs - startMs) / 1000).$("s").$();
    }

    private long getCurrentTimeMs() {
        return configuration.getMillisecondClock().getTicks();
    }

    private int getTmpTablesCount() {
        return taskDistribution.size() / 3;
    }

    public void mergeSymbolTables(final TableWriter writer) throws TextException {
        checkCancelled(TextImportTask.PHASE_SYMBOL_TABLE_MERGE);
        logStartOf("symbol table merge");
        final int tmpTableCount = getTmpTablesCount();

        int queuedCount = 0;
        int collectedCount = 0;
        TableWriterMetadata metadata = writer.getMetadata();

        for (int columnIndex = 0, size = metadata.getColumnCount(); columnIndex < size; columnIndex++) {
            if (ColumnType.isSymbol(metadata.getColumnType(columnIndex))) {
                final CharSequence symbolColumnName = metadata.getColumnName(columnIndex);
                int tmpTableSymbolColumnIndex = targetTableStructure.getSymbolColumnIndex(symbolColumnName);

                while (true) {
                    final long seq = pubSeq.next();
                    if (seq > -1) {
                        final TextImportTask task = queue.get(seq);
                        task.setTaskId(columnIndex);
                        task.ofMergeSymbolTablesStage(configuration, importRoot, writer, tableName, symbolColumnName, columnIndex, tmpTableSymbolColumnIndex, tmpTableCount, partitionBy);
                        pubSeq.done(seq);
                        queuedCount++;
                        break;
                    } else {
                        collectedCount += collect(queuedCount - collectedCount, this::collectStub);
                    }
                }
            }
        }

        collectedCount += collect(queuedCount - collectedCount, this::collectStub);
        assert collectedCount == queuedCount;

        logEndOf("symbol table merge");
    }

    public void updateSymbolKeys(final TableWriter writer) throws TextException {
        checkCancelled(TextImportTask.PHASE_UPDATE_SYMBOL_KEYS);
        logStartOf("symbol keys update");

        final int tmpTableCount = getTmpTablesCount();
        int queuedCount = 0;
        int collectedCount = 0;
        for (int t = 0; t < tmpTableCount; ++t) {
            tmpPath.of(importRoot).concat(tableName).put("_").put(t);

            try (TxReader txFile = new TxReader(ff).ofRO(tmpPath, partitionBy)) {
                txFile.unsafeLoadAll();
                final int partitionCount = txFile.getPartitionCount();

                for (int p = 0; p < partitionCount; p++) {
                    final long partitionSize = txFile.getPartitionSize(p);
                    final long partitionTimestamp = txFile.getPartitionTimestamp(p);
                    TableWriterMetadata metadata = writer.getMetadata();
                    int symbolColumnIndex = 0;

                    if (partitionSize == 0) {
                        continue;
                    }

                    for (int c = 0, size = metadata.getColumnCount(); c < size; c++) {
                        if (ColumnType.isSymbol(metadata.getColumnType(c))) {
                            final CharSequence symbolColumnName = metadata.getColumnName(c);
                            final int symbolCount = txFile.getSymbolValueCount(symbolColumnIndex++);

                            while (true) {
                                final long seq = pubSeq.next();
                                if (seq > -1) {
                                    final TextImportTask task = queue.get(seq);
                                    task.setTaskId(t);
                                    task.setCancellationToken(cancellationToken);
                                    task.ofUpdateSymbolColumnKeysStage(cairoEngine, targetTableStructure, t, partitionSize, partitionTimestamp, importRoot, symbolColumnName, symbolCount);
                                    pubSeq.done(seq);
                                    queuedCount++;
                                    break;
                                } else {
                                    collectedCount += collect(queuedCount - collectedCount, this::collectStub);
                                }
                            }
                        }
                    }
                }
            }

        }

        collectedCount += collect(queuedCount - collectedCount, this::collectStub);
        assert collectedCount == queuedCount;

        logEndOf("symbol keys update");
    }

    public void of(CharSequence tableName, CharSequence inputFileName, int partitionBy, byte columnDelimiter, CharSequence timestampColumn, CharSequence tsFormat, boolean forceHeader) {
        of(tableName, inputFileName, partitionBy, columnDelimiter, timestampColumn, tsFormat, forceHeader, Atomicity.SKIP_COL);
    }

    public void of(CharSequence tableName, CharSequence inputFileName, int partitionBy, byte columnDelimiter, CharSequence timestampColumn, CharSequence tsFormat, boolean forceHeader, int atomicity) {
        clear();

        this.tableName = tableName;
        this.importRoot = tmpPath.of(inputWorkRoot).concat(tableName).toString();
        this.inputFileName = inputFileName;
        this.timestampColumn = timestampColumn;
        this.partitionBy = partitionBy;
        this.columnDelimiter = columnDelimiter;
        if (tsFormat != null) {
            DateFormat dateFormat = typeManager.getInputFormatConfiguration().getTimestampFormatFactory().get(tsFormat);
            this.timestampAdapter = (TimestampAdapter) typeManager.nextTimestampAdapter(false, dateFormat,
                    configuration.getTextConfiguration().getDefaultDateLocale());
        }
        this.forceHeader = forceHeader;
        this.timestampIndex = -1;
        this.status = TextImportTask.STATUS_OK;
        this.phase = 0;
        this.targetTableStatus = -1;
        this.targetTableCreated = false;
        this.atomicity = Atomicity.isValid(atomicity) ? atomicity : Atomicity.SKIP_COL;

        inputFilePath.of(inputRoot).concat(inputFileName).$();
    }

    @Override
    public void clear() {
        chunkStats.clear();
        indexChunkStats.clear();
        partitionKeys.clear();
        partitionNames.clear();
        partitionNameSink.clear();
        taskDistribution.clear();
        utf8Sink.clear();
        typeManager.clear();
        textMetadataDetector.clear();
        otherToTimestampAdapterPool.clear();

        inputFileName = null;
        tableName = null;
        timestampColumn = null;
        timestampIndex = -1;
        partitionBy = -1;
        columnDelimiter = -1;
        timestampAdapter = null;
        forceHeader = false;

        status = TextImportTask.STATUS_OK;
        phase = TextImportTask.PHASE_BOUNDARY_CHECK;
        errorMessage = null;
        targetTableStatus = -1;
        targetTableCreated = false;
        atomicity = Atomicity.SKIP_COL;
    }

    private void removeWorkDir() {
        Path workDirPath = tmpPath.of(importRoot).slash$();

        if (ff.exists(workDirPath)) {
            LOG.info().$("removing import directory path='").$(workDirPath).$("'").$();

            int errno = ff.rmdir(workDirPath);
            if (errno != 0) {
                throw CairoException.instance(errno).put("Can't remove import directory path='").put(workDirPath).put("' errno=").put(errno);
            }
        }
    }

    private void createWorkDir() {
        removeWorkDir();

        Path workDirPath = tmpPath.of(importRoot).slash$();
        int errno = ff.mkdir(workDirPath, configuration.getMkDirMode());
        if (errno != 0) {
            throw CairoException.instance(errno).put("Can't create import work dir ").put(workDirPath).put(" errno=").put(errno);
        }

        LOG.info().$("created import dir ").$(workDirPath).$();
    }

    TableWriter parseStructure(long fd) throws TextException {
        final CairoConfiguration configuration = cairoEngine.getConfiguration();

        final int textAnalysisMaxLines = configuration.getTextConfiguration().getTextAnalysisMaxLines();
        int len = configuration.getSqlCopyBufferSize();
        long buf = Unsafe.malloc(len, MemoryTag.NATIVE_DEFAULT);

        try (TextLexer lexer = new TextLexer(configuration.getTextConfiguration())) {
            long n = ff.read(fd, buf, len, 0);
            if (n > 0) {
                if (columnDelimiter < 0) {
                    columnDelimiter = textDelimiterScanner.scan(buf, buf + n);
                }

                lexer.of(columnDelimiter);
                lexer.setSkipLinesWithExtraValues(false);

                final ObjList<CharSequence> names = new ObjList<>();
                final ObjList<TypeAdapter> types = new ObjList<>();
                if (timestampColumn != null && timestampAdapter != null) {
                    names.add(timestampColumn);
                    types.add(timestampAdapter);
                }

                textMetadataDetector.of(names, types, forceHeader);
                lexer.parse(buf, buf + n, textAnalysisMaxLines, textMetadataDetector);
                textMetadataDetector.evaluateResults(lexer.getLineCount(), lexer.getErrorCount());
                forceHeader = textMetadataDetector.isHeader();

                return prepareTable(securityContext, textMetadataDetector.getColumnNames(), textMetadataDetector.getColumnTypes(), inputFilePath, typeManager);
            } else {
                throw TextException.$("Can't read from file path='").put(inputFilePath).put("' to analyze structure");
            }
        } finally {
            Unsafe.free(buf, len, MemoryTag.NATIVE_DEFAULT);
        }
    }

    private int collect(int queuedCount, Consumer<TextImportTask> consumer) {
        int collectedCount = 0;
        while (collectedCount < queuedCount) {
            final long seq = collectSeq.next();
            if (seq > -1) {
                TextImportTask task = queue.get(seq);
                consumer.accept(task);
                task.clear();
                collectSeq.done(seq);
                collectedCount += 1;
            } else {
                stealWork(queue, subSeq);
            }
        }
        return collectedCount;
    }

    private void movePartitions() {
        logStartOf("moving partitions");
        checkCancelled(TextImportTask.PHASE_MOVE_PARTITONS);
        final int taskCount = getTmpTablesCount();

        for (int i = 0; i < taskCount; i++) {
            int index = taskDistribution.getQuick(i * 3);
            int lo = taskDistribution.getQuick(i * 3 + 1);
            int hi = taskDistribution.getQuick(i * 3 + 2);
            final Path srcPath = Path.getThreadLocal(importRoot).concat(tableName).put("_").put(index);
            final Path dstPath = Path.getThreadLocal2(configuration.getRoot()).concat(tableName);
            final int srcPlen = srcPath.length();
            final int dstPlen = dstPath.length();

            for (int j = lo; j < hi; j++) {
                final CharSequence partitionName = partitionNames.get(j);
                if (partitionName == null) {
                    continue;
                }

                srcPath.trimTo(srcPlen).concat(partitionName).slash$();
                dstPath.trimTo(dstPlen).concat(partitionName).slash$();

                if (!ff.rename(srcPath, dstPath)) {
                    if (Os.translateSysErrno(ff.errno()) == Os.Errno.EXDEV) {
                        LOG.info().$(srcPath).$(" and ").$(dstPath).$(" are not on the same mounted filesystem. Partitions will be copied.").$();

                        if (ff.mkdirs(dstPath, configuration.getMkDirMode()) != 0) {
                            throw CairoException.instance(ff.errno()).put("Cannot create partition directory [path=").put(dstPath).put(']');
                        }

                        ff.iterateDir(srcPath, (long name, int type) -> {
                            if (type == Files.DT_FILE) {
                                srcPath.trimTo(srcPlen).concat(partitionName).concat(name).$();
                                dstPath.trimTo(dstPlen).concat(partitionName).concat(name).$();
                                if (ff.copy(srcPath, dstPath) < 0) {
                                    throw CairoException.instance(ff.errno()).put("Cannot copy partition file [to=").put(dstPath).put(']');
                                }
                            }
                        });
                    } else {
                        throw CairoException.instance(ff.errno()).put("Cannot copy partition file [to=").put(dstPath).put(']');
                    }
                }
            }
        }

        logEndOf("moving partitions");
    }

     public void process() throws TextException {

        if (this.queue.getCycle() <= 0) {
            throw TextException.$("Unable to process, the processing queue is misconfigured");
        }

        long startMs = getCurrentTimeMs();

        long fd = ff.openRO(inputFilePath);
        if (fd < 0) {
            throw TextException.$("Can't open input file [path='").put(inputFilePath).put("', errno=").put(ff.errno()).put(']');
        }
        long length = ff.length(fd);
        if (length < 1) {
            ff.close(fd);
            throw TextException.$("Ignoring file because it's empty [path='").put(inputFilePath).put(']');
        }

        try (TableWriter writer = parseStructure(fd)) {
            findChunkBoundaries(length);
            indexChunks();
            importPartitions();
            mergeSymbolTables(writer);
            updateSymbolKeys(writer);
            buildColumnIndexes(writer);
            try {
                movePartitions();
                attachPartitions(writer);
            } catch (Throwable t) {
                cleanUp(writer);
                throw t;
            }
        } catch (Throwable t) {
            cleanUp();
            throw t;
        } finally {
            removeWorkDir();
            ff.close(fd);
        }

        long endMs = getCurrentTimeMs();
        LOG.info().$("Finished importing file='").$(inputFileName).$("' time=").$((endMs - startMs) / 1000).$("s").$();
    }

    public byte getStatus() {
        return status;
    }

    private void setStatus(byte status) {
        this.status = status;
    }

    private void cleanUp(TableWriter writer) {
        if (targetTableStatus == TableUtils.TABLE_EXISTS && writer != null) {
            writer.truncate();
        }
    }

    private void cleanUp() {
        if (targetTableStatus == TableUtils.TABLE_DOES_NOT_EXIST && targetTableCreated) {
            cairoEngine.remove(securityContext, tmpPath, tableName);
        }
    }

    private void attachPartitions(TableWriter writer) throws TextException {
        checkCancelled(TextImportTask.PHASE_ATTACH_PARTITIONS);
        if (partitionNames.size() == 0) {
            throw TextException.$("No partitions to attach found");
        }

        logStartOf("attaching partitions");

        for (int i = 0, sz = partitionNames.size(); i < sz; i++) {
            final CharSequence partitionDirName = partitionNames.get(i);
            if (partitionDirName == null) {
                continue;
            }
            final long timestamp = PartitionBy.parsePartitionDirName(partitionDirName, partitionBy);
            try {
                writer.attachPartition(timestamp, false);
            } catch (CairoException e) {
                throw TextException.$("Can't attach partition ").put(partitionDirName).put(". ").put(e.getMessage());
            }
        }

        logEndOf("attaching partitions");
    }

    public void setMinChunkSize(int minChunkSize) {
        this.minChunkSize = minChunkSize;
    }

    private void collectChunkStats(final TextImportTask task) {
        checkStatus(task);
        final TextImportTask.CountQuotesStage countQuotesStage = task.getCountQuotesStage();
        final int chunkIndex = task.getTaskId();
        chunkStats.set(chunkIndex, countQuotesStage.getQuoteCount());
        chunkStats.set(chunkIndex + 1, countQuotesStage.getNewLineCountEven());
        chunkStats.set(chunkIndex + 2, countQuotesStage.getNewLineCountOdd());
        chunkStats.set(chunkIndex + 3, countQuotesStage.getNewLineOffsetEven());
        chunkStats.set(chunkIndex + 4, countQuotesStage.getNewLineOffsetOdd());
    }

    private void collectIndexStats(final TextImportTask task) {
        checkStatus(task);
        final TextImportTask.BuildPartitionIndexStage buildPartitionIndexStage = task.getBuildPartitionIndexStage();
        final LongList keys = buildPartitionIndexStage.getPartitionKeys();
        this.partitionKeys.add(keys);
    }

    private void collectDataImportStats(final TextImportTask task) {
        checkStatus(task);

        final TextImportTask.ImportPartitionDataStage stage = task.getImportPartitionDataStage();
        LongList rows = stage.getImportedRows();
        int offset = (int) stage.getLo();

        for (int i = 0, n = rows.size(); i < n; i++) {
            if (rows.get(i) == 0) {
                partitionNames.set(i + offset, null);//partition will be ignored in later stages 
            }
        }
    }

    private void collectStub(final TextImportTask task) {
        checkStatus(task);
    }

    private void checkCancelled(byte phase) {
        this.phase = phase;
        if (cancellationToken != null && cancellationToken.isCanceled()) {
            setStatus(TextImportTask.STATUS_CANCEL);
            errorMessage = "Cancelled";
        }
        throwErrorIfNotOk();
    }

    private void checkStatus(final TextImportTask task) {
        if (status == TextImportTask.STATUS_OK && task.getStatus() != TextImportTask.STATUS_OK) {
            setStatus(task.getStatus());
            phase = task.getPhase();
            errorMessage = task.getErrorMessage();
        }
        throwErrorIfNotOk();
        checkCancelled(task.getPhase());
    }

    private void throwErrorIfNotOk() {
        if (getStatus() != TextImportTask.STATUS_OK) {
            throw TextException.$("Import terminated in ").put(TextImportTask.getPhaseName(phase)).put(" phase. ").put(errorMessage);
        }
    }

    //returns list with N chunk boundaries
    LongList findChunkBoundaries(long fileLength) throws TextException {
        checkCancelled(TextImportTask.PHASE_BOUNDARY_CHECK);
        logStartOf("checking boundaries");

        assert (workerCount > 0 && minChunkSize > 0);

        if (workerCount == 1) {
            indexChunkStats.setPos(0);
            indexChunkStats.add(0);
            indexChunkStats.add(0);
            indexChunkStats.add(fileLength);
            indexChunkStats.add(0);
            return indexChunkStats;
        }

        long chunkSize = Math.max(minChunkSize, (fileLength + workerCount - 1) / workerCount);
        final int chunks = (int) Math.max((fileLength + chunkSize - 1) / chunkSize, 1);

        int queuedCount = 0;
        int collectedCount = 0;

        chunkStats.setPos(chunks * 5);
        chunkStats.zero(0);

        for (int i = 0; i < chunks; i++) {
            final long chunkLo = i * chunkSize;
            final long chunkHi = Long.min(chunkLo + chunkSize, fileLength);
            while (true) {
                final long seq = pubSeq.next();
                if (seq > -1) {
                    final TextImportTask task = queue.get(seq);
                    task.setTaskId(5 * i);
                    task.setCancellationToken(cancellationToken);
                    task.ofCountQuotesStage(ff, inputFilePath, chunkLo, chunkHi, bufferLength);
                    pubSeq.done(seq);
                    queuedCount++;
                    break;
                } else {
                    collectedCount += collect(queuedCount - collectedCount, this::collectChunkStats);
                }
            }
        }

        collectedCount += collect(queuedCount - collectedCount, this::collectChunkStats);
        assert collectedCount == queuedCount;
        processChunkStats(fileLength, chunks);
        logEndOf("checking boundaries");
        return indexChunkStats;
    }

    void indexChunks() throws TextException {
        checkCancelled(TextImportTask.PHASE_INDEXING);
        logStartOf("indexing");

        int queuedCount = 0;
        int collectedCount = 0;

        if (indexChunkStats.size() < 2) {
            throw TextException.$("No chunks found for indexing in file=").put(inputFilePath);
        }

        createWorkDir();

        boolean forceHeader = this.forceHeader;
        for (int i = 0, n = indexChunkStats.size() - 2; i < n; i += 2) {
            int colIdx = i / 2;

            final long chunkLo = indexChunkStats.get(i);
            final long lineNumber = indexChunkStats.get(i + 1);
            final long chunkHi = indexChunkStats.get(i + 2);

            while (true) {
                final long seq = pubSeq.next();
                if (seq > -1) {
                    final TextImportTask task = queue.get(seq);
                    task.setTaskId(colIdx);
                    task.setCancellationToken(cancellationToken);
                    task.ofBuildPartitionIndexStage(chunkLo, chunkHi, lineNumber, colIdx, configuration, inputFileName, importRoot, partitionBy, columnDelimiter, timestampIndex, timestampAdapter, forceHeader, bufferLength, atomicity);
                    if (forceHeader) {
                        forceHeader = false;
                    }
                    pubSeq.done(seq);
                    queuedCount++;
                    break;
                } else {
                    collectedCount += collect(queuedCount - collectedCount, this::collectIndexStats);
                }
            }
        }

        collectedCount += collect(queuedCount - collectedCount, this::collectIndexStats);
        assert collectedCount == queuedCount;
        processIndexStats();

        logEndOf("indexing");
    }

    private void buildColumnIndexes(TableWriter writer) throws TextException {
        checkCancelled(TextImportTask.PHASE_BUILD_INDEX);
        logStartOf("building column indexes");

        final RecordMetadata metadata = writer.getMetadata();
        final int columnCount = metadata.getColumnCount();
        final int tmpTableCount = getTmpTablesCount();

        boolean isAnyIndexed = false;
        for (int i = 0; i < columnCount; i++) {
            isAnyIndexed |= metadata.isColumnIndexed(i);
        }

        if (isAnyIndexed) {

            int queuedCount = 0;
            int collectedCount = 0;
            for (int t = 0; t < tmpTableCount; ++t) {
                while (true) {
                    final long seq = pubSeq.next();
                    if (seq > -1) {
                        final TextImportTask task = queue.get(seq);
                        task.setTaskId(t);
                        task.setCancellationToken(cancellationToken);
                        task.ofBuildSymbolColumnIndexStage(cairoEngine, targetTableStructure, importRoot, t, metadata);
                        pubSeq.done(seq);
                        queuedCount++;
                        break;
                    } else {
                        collectedCount += collect(queuedCount - collectedCount, this::collectStub);
                    }
                }
            }

            collectedCount += collect(queuedCount - collectedCount, this::collectStub);
            assert collectedCount == queuedCount;
        }

        logEndOf("building column indexes");
    }

    private void processIndexStats() {
        LongHashSet set = new LongHashSet();
        for (int i = 0, n = partitionKeys.size(); i < n; i++) {
            set.add(partitionKeys.get(i));
        }

        LongList uniquePartitionKeys = new LongList();
        for (int i = 0, n = set.size(); i < n; i++) {
            uniquePartitionKeys.add(set.get(i));
        }
        uniquePartitionKeys.sort();

        DateFormat dirFormat = PartitionBy.getPartitionDirFormatMethod(partitionBy);

        partitionNames.clear();
        tmpPath.of(importRoot).slash$();
        for (int i = 0, n = uniquePartitionKeys.size(); i < n; i++) {
            partitionNameSink.clear();
            dirFormat.format(uniquePartitionKeys.get(i), null, null, partitionNameSink);
            partitionNames.add(partitionNameSink.toString());
        }
    }

    @TestOnly
    void setBufferLength(int bufferSize) {
        this.bufferLength = bufferSize;
    }

    private boolean stealWork(RingQueue<TextImportTask> queue, Sequence subSeq) {
        long seq = subSeq.next();
        if (seq > -1) {
            queue.get(seq).run();
            subSeq.done(seq);
            return true;
        }
        Os.pause();
        return false;
    }

    private void processChunkStats(long fileLength, int chunks) {
        long quotes = chunkStats.get(0);

        indexChunkStats.setPos(0);
        //set first chunk offset and line number
        indexChunkStats.add(0);
        indexChunkStats.add(0);

        long lines;
        long totalLines = chunks > 0 ? chunkStats.get(1) + 1 : 1;

        for (int i = 1; i < chunks; i++) {
            long startPos;
            if ((quotes & 1) == 1) { // if number of quotes is odd then use odd starter
                startPos = chunkStats.get(5 * i + 4);
                lines = chunkStats.get(5 * i + 2);
            } else {
                startPos = chunkStats.get(5 * i + 3);
                lines = chunkStats.get(5 * i + 1);
            }

            //if whole chunk  belongs to huge quoted string or contains one very long line
            //then it should be ignored here and merged with previous chunk
            if (startPos > -1) {
                indexChunkStats.add(startPos);
                indexChunkStats.add(totalLines);
            }

            quotes += chunkStats.get(5 * i);
            totalLines += lines;
        }

        if (indexChunkStats.get(indexChunkStats.size() - 2) < fileLength) {
            indexChunkStats.add(fileLength);
            indexChunkStats.add(totalLines);//doesn't matter
        }
    }

    private void logTypeError(int i, int type) {
        LOG.info()
                .$("mis-detected [table=").$(tableName)
                .$(", column=").$(i)
                .$(", type=").$(ColumnType.nameOf(type))
                .$(']').$();
    }

    private TableWriter openWriterAndOverrideImportMetadata(
            ObjList<CharSequence> names,
            ObjList<TypeAdapter> types,
            CairoSecurityContext cairoSecurityContext,
            TypeManager typeManager
    ) throws TextException {
        TableWriter writer = cairoEngine.getWriter(cairoSecurityContext, tableName, LOCK_REASON);
        RecordMetadata metadata = writer.getMetadata();

        if (metadata.getColumnCount() < types.size()) {
            writer.close();
            throw TextException.$("column count mismatch [textColumnCount=").put(types.size())
                    .put(", tableColumnCount=").put(metadata.getColumnCount())
                    .put(", table=").put(tableName)
                    .put(']');
        }

        //remap index is only needed to adjust names and types
        //workers will import data into temp tables without remapping
        IntList remapIndex = new IntList();
        remapIndex.ensureCapacity(types.size());
        for (int i = 0, n = types.size(); i < n; i++) {

            final int columnIndex = metadata.getColumnIndexQuiet(names.getQuick(i));
            final int idx = (columnIndex > -1 && columnIndex != i) ? columnIndex : i; // check for strict match ?
            remapIndex.set(i, idx);

            final int columnType = metadata.getColumnType(idx);
            final TypeAdapter detectedAdapter = types.getQuick(i);
            final int detectedType = detectedAdapter.getType();
            if (detectedType != columnType) {
                // when DATE type is mis-detected as STRING we
                // would not have either date format nor locale to
                // use when populating this field
                switch (ColumnType.tagOf(columnType)) {
                    case ColumnType.DATE:
                        logTypeError(i, detectedType);
                        types.setQuick(i, BadDateAdapter.INSTANCE);
                        break;
                    case ColumnType.TIMESTAMP:
                        if (detectedAdapter instanceof TimestampCompatibleAdapter) {
                            types.setQuick(i, otherToTimestampAdapterPool.next().of((TimestampCompatibleAdapter) detectedAdapter));
                        } else {
                            logTypeError(i, detectedType);
                            types.setQuick(i, BadTimestampAdapter.INSTANCE);
                        }
                        break;
                    case ColumnType.BINARY:
                        writer.close();
                        throw CairoException.instance(0).put("cannot import text into BINARY column [index=").put(i).put(']');
                    default:
                        types.setQuick(i, typeManager.getTypeAdapter(columnType));
                        break;
                }
            }
        }

        //at this point we've to use target table columns names otherwise partition attach could fail on metadata differences
        //(if header names or synthetic names are different from table's)
        for (int i = 0, n = remapIndex.size(); i < n; i++) {
            names.set(i, metadata.getColumnName(remapIndex.get(i)));
        }

        //add table columns missing in input file
        if (names.size() < metadata.getColumnCount()) {
            for (int i = 0, n = metadata.getColumnCount(); i < n; i++) {
                boolean unused = true;

                for (int r = 0, rn = remapIndex.size(); r < rn; r++) {
                    if (remapIndex.get(r) == i) {
                        unused = false;
                        break;
                    }
                }

                if (unused) {
                    names.add(metadata.getColumnName(i));
                    types.add(typeManager.getTypeAdapter(metadata.getColumnType(i)));
                }
            }
        }

        return writer;
    }

    TableWriter prepareTable(
            CairoSecurityContext cairoSecurityContext,
            ObjList<CharSequence> names,
            ObjList<TypeAdapter> types,
            Path path,
            TypeManager typeManager
    ) throws TextException {
        if (types.size() == 0) {
            throw CairoException.instance(0).put("cannot determine text structure");
        }
        if (partitionBy == PartitionBy.NONE) {
            throw CairoException.instance(-1).put("partition by unit can't be NONE for parallel import");
        }

        if (partitionBy < 0) {
            partitionBy = PartitionBy.NONE;
        }

        if (timestampIndex == -1 && timestampColumn != null) {
            for (int i = 0, n = names.size(); i < n; i++) {
                if (Chars.equalsIgnoreCase(names.get(i), timestampColumn)) {
                    timestampIndex = i;
                    break;
                }
            }
        }

        TableWriter writer = null;

        try {
            targetTableStatus = cairoEngine.getStatus(cairoSecurityContext, path, tableName);
            switch (targetTableStatus) {
                case TableUtils.TABLE_DOES_NOT_EXIST:
                    if (partitionBy == PartitionBy.NONE) {
                        throw TextException.$("partition by unit must be set when importing to new table");
                    }
                    if (timestampColumn == null) {
                        throw TextException.$("timestamp column must be set when importing to new table");
                    }
                    if (timestampIndex == -1) {
                        throw TextException.$("timestamp column '").put(timestampColumn).put("' not found in file header");
                    }

                    validate(names, types, null, NO_INDEX);
                    targetTableStructure.of(tableName, names, types, timestampIndex, partitionBy);
                    createTable(ff, configuration.getMkDirMode(), configuration.getRoot(), tableName, targetTableStructure, (int) cairoEngine.getTableIdGenerator().getNextId(), configuration);
                    targetTableCreated = true;
                    writer = cairoEngine.getWriter(cairoSecurityContext, tableName, LOCK_REASON);
                    partitionBy = writer.getPartitionBy();
                    break;
                case TableUtils.TABLE_EXISTS:
                    writer = openWriterAndOverrideImportMetadata(names, types, cairoSecurityContext, typeManager);

                    if (writer.getRowCount() > 0) {
                        throw CairoException.instance(0).put("target table must be empty [table=").put(tableName).put(']');
                    }

                    CharSequence designatedTimestampColumnName = writer.getDesignatedTimestampColumnName();
                    int designatedTimestampIndex = writer.getMetadata().getTimestampIndex();
                    if (PartitionBy.isPartitioned(partitionBy) && partitionBy != writer.getPartitionBy()) {
                        throw CairoException.instance(-1).put("declared partition by unit doesn't match table's");
                    }
                    partitionBy = writer.getPartitionBy();
                    if (!PartitionBy.isPartitioned(partitionBy)) {
                        throw CairoException.instance(-1).put("target table is not partitioned");
                    }
                    validate(names, types, designatedTimestampColumnName, designatedTimestampIndex);
                    targetTableStructure.of(tableName, names, types, timestampIndex, partitionBy);
                    break;
                default:
                    throw CairoException.instance(0).put("name is reserved [table=").put(tableName).put(']');
            }

            inputFilePath.of(inputRoot).concat(inputFileName).$();//getStatus might override it 
            targetTableStructure.setIgnoreColumnIndexedFlag(true);

            if (timestampIndex == -1) {
                throw CairoException.instance(-1).put("timestamp column not found");
            }

            if (timestampAdapter == null && ColumnType.isTimestamp(types.getQuick(timestampIndex).getType())) {
                timestampAdapter = (TimestampAdapter) types.getQuick(timestampIndex);
            }
        } catch (Throwable t) {
            if (writer != null) {
                writer.close();
            }

            throw t;
        }

        return writer;
    }

    void validate(ObjList<CharSequence> names,
                  ObjList<TypeAdapter> types,
                  CharSequence designatedTimestampColumnName,
                  int designatedTimestampIndex) throws TextException {
        if (timestampColumn == null && designatedTimestampColumnName == null) {
            timestampIndex = NO_INDEX;
        } else if (timestampColumn != null) {
            timestampIndex = names.indexOf(timestampColumn);
            if (timestampIndex == NO_INDEX) {
                throw TextException.$("invalid timestamp column '").put(timestampColumn).put('\'');
            }
        } else {
            timestampIndex = names.indexOf(designatedTimestampColumnName);
            if (timestampIndex == NO_INDEX) {
                // columns in the imported file may not have headers, then use writer timestamp index
                timestampIndex = designatedTimestampIndex;
            }
        }

        if (timestampIndex != NO_INDEX) {
            final TypeAdapter timestampAdapter = types.getQuick(timestampIndex);
            final int typeTag = ColumnType.tagOf(timestampAdapter.getType());
            if ((typeTag != ColumnType.LONG && typeTag != ColumnType.TIMESTAMP) || timestampAdapter == BadTimestampAdapter.INSTANCE) {
                throw TextException.$("column no=").put(timestampIndex).put(", name='").put(timestampColumn).put("' is not a timestamp");
            }
        }
    }

    public static class TableStructureAdapter implements TableStructure {
        private final CairoConfiguration configuration;
        private final LongList columnBits = new LongList();
        private CharSequence tableName;
        private ObjList<CharSequence> columnNames;
        private int timestampColumnIndex;
        private int partitionBy;
        private boolean ignoreColumnIndexedFlag;

        public TableStructureAdapter(CairoConfiguration configuration) {
            this.configuration = configuration;
        }

        @Override
        public int getColumnCount() {
            return columnNames.size();
        }

        @Override
        public CharSequence getColumnName(int columnIndex) {
            return columnNames.getQuick(columnIndex);
        }

        @Override
        public int getColumnType(int columnIndex) {
            return Numbers.decodeLowInt(columnBits.getQuick(columnIndex));
        }

        @Override
        public long getColumnHash(int columnIndex) {
            return configuration.getRandom().nextLong();
        }

        @Override
        public int getIndexBlockCapacity(int columnIndex) {
            return configuration.getIndexValueBlockSize();
        }

        public int getSymbolColumnIndex(CharSequence symbolColumnName) {
            int index = -1;
            for (int i = 0, n = columnNames.size(); i < n; i++) {
                if (getColumnType(i) == ColumnType.SYMBOL) {
                    index++;
                }

                if (symbolColumnName.equals(columnNames.get(i))) {
                    return index;
                }
            }

            return -1;
        }

        @Override
        public boolean isIndexed(int columnIndex) {
            return !ignoreColumnIndexedFlag && Numbers.decodeHighInt(columnBits.getQuick(columnIndex)) != 0;
        }

        @Override
        public boolean isSequential(int columnIndex) {
            return false;
        }

        @Override
        public int getPartitionBy() {
            return partitionBy;
        }

        @Override
        public boolean getSymbolCacheFlag(int columnIndex) {
            return false;
        }

        @Override
        public int getSymbolCapacity(int columnIndex) {
            return configuration.getDefaultSymbolCapacity();
        }

        @Override
        public CharSequence getTableName() {
            return tableName;
        }

        @Override
        public int getTimestampIndex() {
            return timestampColumnIndex;
        }

        @Override
        public int getMaxUncommittedRows() {
            return configuration.getMaxUncommittedRows();
        }

        @Override
        public long getCommitLag() {
            return configuration.getCommitLag();
        }

        public void of(final CharSequence tableName,
                       final ObjList<CharSequence> names,
                       final ObjList<TypeAdapter> types,
                       final int timestampColumnIndex,
                       final int partitionBy
        ) {
            this.tableName = tableName;
            this.columnNames = names;
            this.ignoreColumnIndexedFlag = false;

            this.columnBits.clear();
            for (int i = 0, size = types.size(); i < size; i++) {
                final TypeAdapter adapter = types.getQuick(i);
                this.columnBits.add(Numbers.encodeLowHighInts(adapter.getType(), adapter.isIndexed() ? 1 : 0));
            }

            this.timestampColumnIndex = timestampColumnIndex;
            this.partitionBy = partitionBy;
        }

        public void setIgnoreColumnIndexedFlag(boolean flag) {
            this.ignoreColumnIndexedFlag = flag;
        }
    }
}
