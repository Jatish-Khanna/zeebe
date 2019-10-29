/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.util;

import static io.zeebe.engine.processor.StreamProcessorServiceNames.streamProcessorService;
import static io.zeebe.logstreams.impl.service.LogStreamServiceNames.logStreamServiceName;
import static io.zeebe.test.util.TestUtil.doRepeatedly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.zeebe.db.ZeebeDb;
import io.zeebe.db.ZeebeDbFactory;
import io.zeebe.engine.AtomixLogStorageRule;
import io.zeebe.engine.processor.AsyncSnapshotDirector;
import io.zeebe.engine.processor.CommandResponseWriter;
import io.zeebe.engine.processor.ReadonlyProcessingContext;
import io.zeebe.engine.processor.StreamProcessor;
import io.zeebe.engine.processor.StreamProcessorLifecycleAware;
import io.zeebe.engine.processor.TypedEventRegistry;
import io.zeebe.engine.processor.TypedRecordProcessorFactory;
import io.zeebe.engine.processor.TypedRecordProcessors;
import io.zeebe.engine.state.StateStorageFactory;
import io.zeebe.logstreams.impl.LogStreamBuilder;
import io.zeebe.logstreams.impl.service.LogStreamService;
import io.zeebe.logstreams.log.BufferedLogStreamReader;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.log.LogStreamBatchWriterImpl;
import io.zeebe.logstreams.log.LogStreamReader;
import io.zeebe.logstreams.log.LogStreamRecordWriter;
import io.zeebe.logstreams.log.LogStreamWriterImpl;
import io.zeebe.logstreams.log.LoggedEvent;
import io.zeebe.logstreams.state.StateSnapshotController;
import io.zeebe.logstreams.state.StateStorage;
import io.zeebe.msgpack.UnpackedObject;
import io.zeebe.protocol.Protocol;
import io.zeebe.protocol.impl.record.CopiedRecord;
import io.zeebe.protocol.impl.record.RecordMetadata;
import io.zeebe.protocol.record.RecordType;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.Intent;
import io.zeebe.servicecontainer.ServiceContainer;
import io.zeebe.servicecontainer.ServiceName;
import io.zeebe.test.util.AutoCloseableRule;
import io.zeebe.util.FileUtil;
import io.zeebe.util.Loggers;
import io.zeebe.util.sched.ActorScheduler;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.rules.TemporaryFolder;

public class TestStreams {
  private static final int MAX_MESSAGE_SIZE = 1024 * 1024 * 4;
  private static final Duration SNAPSHOT_INTERVAL = Duration.ofMinutes(1);
  private static final int MAX_SNAPSHOTS = 1;

  private static final Map<Class<?>, ValueType> VALUE_TYPES = new HashMap<>();

  static {
    TypedEventRegistry.EVENT_REGISTRY.forEach((v, c) -> VALUE_TYPES.put(c, v));
  }

  private final TemporaryFolder dataDirectory;
  private final AutoCloseableRule closeables;
  private final ServiceContainer serviceContainer;

  private final ActorScheduler actorScheduler;

  private final CommandResponseWriter mockCommandResponseWriter;
  private final Map<String, LogContext> logContextMap = new HashMap<>();
  private final Map<String, ProcessorContext> streamContextMap = new HashMap<>();

  public TestStreams(
      final TemporaryFolder dataDirectory,
      final AutoCloseableRule closeables,
      final ServiceContainer serviceContainer,
      final ActorScheduler actorScheduler) {
    this.dataDirectory = dataDirectory;
    this.closeables = closeables;
    this.serviceContainer = serviceContainer;
    this.actorScheduler = actorScheduler;

    mockCommandResponseWriter = mock(CommandResponseWriter.class);
    when(mockCommandResponseWriter.intent(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.key(anyLong())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.partitionId(anyInt())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.recordType(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.rejectionType(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.rejectionReason(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.valueType(any())).thenReturn(mockCommandResponseWriter);
    when(mockCommandResponseWriter.valueWriter(any())).thenReturn(mockCommandResponseWriter);

    when(mockCommandResponseWriter.tryWriteResponse(anyInt(), anyLong())).thenReturn(true);
  }

  public CommandResponseWriter getMockedResponseWriter() {
    return mockCommandResponseWriter;
  }

  public LogStream createLogStream(final String name) {
    return createLogStream(name, 0);
  }

  public LogStream createLogStream(final String name, final int partitionId) {
    final AtomixLogStorageRule logStorageRule;
    try {
      logStorageRule = new AtomixLogStorageRule(dataDirectory, partitionId);
      logStorageRule.open(b -> b.withMaxEntrySize(MAX_MESSAGE_SIZE));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    final LogStreamService logStream =
        spy(
            new LogStreamBuilder(partitionId)
                .logStorage(logStorageRule.getStorage())
                .logName(name)
                .maxBlockSize(MAX_MESSAGE_SIZE)
                .build());

    serviceContainer.createService(logStreamServiceName(name), logStream).install().join();
    logStorageRule.setPositionListener(logStream::setCommitPosition);

    logStream.openAppender().join();

    final LogContext logContext = LogContext.createLogContext(logStream, logStorageRule);
    logContextMap.put(name, logContext);
    closeables.manage(logContext);

    return logStream;
  }

  public LogStream getLogStream(final String name) {
    return logContextMap.get(name).getLogStream();
  }

  public Stream<LoggedEvent> events(final String logName) {
    final LogStream logStream = getLogStream(logName);

    final LogStreamReader reader = new BufferedLogStreamReader(logStream);
    closeables.manage(reader);

    reader.seekToFirstEvent();

    final Iterable<LoggedEvent> iterable = () -> reader;

    return StreamSupport.stream(iterable.spliterator(), false);
  }

  public FluentLogWriter newRecord(final String logName) {
    final LogStream logStream = getLogStream(logName);
    return new FluentLogWriter(logStream);
  }

  public StateStorageFactory getStateStorageFactory(final LogStream stream) {
    File rocksDBDirectory;
    try {
      rocksDBDirectory = dataDirectory.newFolder(stream.getLogName(), "state");
    } catch (final IOException e) {
      if (!e.getMessage().contains("exists")) {
        throw new RuntimeException(e);
      }
      rocksDBDirectory = new File(new File(dataDirectory.getRoot(), stream.getLogName()), "state");
    }

    return new StateStorageFactory(rocksDBDirectory);
  }

  public StreamProcessor startStreamProcessor(
      final String log,
      final ZeebeDbFactory zeebeDbFactory,
      final TypedRecordProcessorFactory typedRecordProcessorFactory) {
    final LogStream stream = getLogStream(log);
    return buildStreamProcessor(
        stream, zeebeDbFactory, typedRecordProcessorFactory, MAX_SNAPSHOTS, SNAPSHOT_INTERVAL);
  }

  private StreamProcessor buildStreamProcessor(
      final LogStream stream,
      final ZeebeDbFactory zeebeDbFactory,
      final TypedRecordProcessorFactory factory,
      final int maxSnapshot,
      final Duration snapshotInterval) {

    final StateStorage stateStorage = getStateStorageFactory(stream).create();
    final StateSnapshotController currentSnapshotController =
        spy(new StateSnapshotController(zeebeDbFactory, stateStorage, maxSnapshot));
    currentSnapshotController.setDeletionService(
        (position) -> {
          if (stateStorage.existSnapshot(position)) {
            final File snapshotDirectory = stateStorage.getSnapshotDirectoryFor(position);
            try {
              FileUtil.deleteFolder(snapshotDirectory.getPath());
            } catch (final IOException e) {
              Loggers.IO_LOGGER.error("Failed to delete snapshot {}.", snapshotDirectory, e);
            }
          }
        });
    final String logName = stream.getLogName();

    final ActorFuture<Void> openFuture = new CompletableActorFuture<>();

    try {
      currentSnapshotController.recover();
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
    final var zeebeDb = currentSnapshotController.openDb();
    final StreamProcessor processorService =
        StreamProcessor.builder()
            .logStream(stream)
            .zeebeDb(zeebeDb)
            .actorScheduler(actorScheduler)
            .serviceContainer(serviceContainer)
            .commandResponseWriter(mockCommandResponseWriter)
            .streamProcessorFactory(
                (context) -> {
                  final TypedRecordProcessors processors = factory.createProcessors(context);
                  processors.withListener(
                      new StreamProcessorLifecycleAware() {
                        @Override
                        public void onOpen(final ReadonlyProcessingContext context) {
                          openFuture.complete(null);
                        }
                      });
                  return processors;
                })
            .build()
            .join();
    openFuture.join();

    final var asyncSnapshotDirector =
        new AsyncSnapshotDirector(
            processorService, currentSnapshotController, stream, snapshotInterval);
    actorScheduler.submitActor(asyncSnapshotDirector);

    final LogContext context = logContextMap.get(logName);
    final ProcessorContext processorContext =
        ProcessorContext.createStreamContext(
            context, serviceContainer, currentSnapshotController, asyncSnapshotDirector, zeebeDb);
    streamContextMap.put(logName, processorContext);
    closeables.manage(processorContext);

    return processorService;
  }

  public StateSnapshotController getStateSnapshotController(final String stream) {
    return streamContextMap.get(stream).getStateSnapshotController();
  }

  public void closeProcessor(final String streamName) throws Exception {
    streamContextMap.get(streamName).close();
  }

  public long writeBatch(final String logName, final RecordToWrite[] recordToWrites) {
    final LogStream logStream = getLogStream(logName);
    final LogStreamBatchWriterImpl logStreamBatchWriter = new LogStreamBatchWriterImpl(logStream);

    for (final RecordToWrite recordToWrite : recordToWrites) {
      logStreamBatchWriter
          .event()
          .key(recordToWrite.getKey())
          .sourceIndex(recordToWrite.getSourceIndex())
          .metadataWriter(recordToWrite.getRecordMetadata())
          .valueWriter(recordToWrite.getUnifiedRecordValue())
          .done();
    }
    return logStreamBatchWriter.tryWrite();
  }

  public static class FluentLogWriter {

    protected RecordMetadata metadata = new RecordMetadata();
    protected UnpackedObject value;
    protected LogStream logStream;
    protected long key = -1;
    private long sourceRecordPosition = -1;

    public FluentLogWriter(final LogStream logStream) {
      this.logStream = logStream;

      metadata.protocolVersion(Protocol.PROTOCOL_VERSION);
    }

    public FluentLogWriter record(final CopiedRecord record) {
      intent(record.getIntent());
      key(record.getKey());
      sourceRecordPosition(record.getSourceRecordPosition());
      recordType(record.getRecordType());
      event(record.getValue());
      return this;
    }

    public FluentLogWriter intent(final Intent intent) {
      this.metadata.intent(intent);
      return this;
    }

    public FluentLogWriter requestId(final long requestId) {
      this.metadata.requestId(requestId);
      return this;
    }

    public FluentLogWriter sourceRecordPosition(final long sourceRecordPosition) {
      this.sourceRecordPosition = sourceRecordPosition;
      return this;
    }

    public FluentLogWriter requestStreamId(final int requestStreamId) {
      this.metadata.requestStreamId(requestStreamId);
      return this;
    }

    public FluentLogWriter recordType(final RecordType recordType) {
      this.metadata.recordType(recordType);
      return this;
    }

    public FluentLogWriter key(final long key) {
      this.key = key;
      return this;
    }

    public FluentLogWriter event(final UnpackedObject event) {
      final ValueType eventType = VALUE_TYPES.get(event.getClass());
      if (eventType == null) {
        throw new RuntimeException("No event type registered for getValue " + event.getClass());
      }

      this.metadata.valueType(eventType);
      this.value = event;
      return this;
    }

    public long write() {
      final LogStreamRecordWriter writer = new LogStreamWriterImpl(logStream);

      writer.sourceRecordPosition(sourceRecordPosition);

      if (key >= 0) {
        writer.key(key);
      } else {
        writer.keyNull();
      }

      writer.metadataWriter(metadata);
      writer.valueWriter(value);

      return doRepeatedly(() -> writer.tryWrite()).until(p -> p >= 0);
    }
  }

  private static final class LogContext implements AutoCloseable {
    private final LogStream logStream;
    private final AtomixLogStorageRule logStorageRule;

    private LogContext(final LogStream logStream, final AtomixLogStorageRule logStorageRule) {
      this.logStream = logStream;
      this.logStorageRule = logStorageRule;
    }

    public static LogContext createLogContext(
        final LogStream logStream, final AtomixLogStorageRule logStorageRule) {
      return new LogContext(logStream, logStorageRule);
    }

    @Override
    public void close() {
      logStream.close();
      logStorageRule.close();
    }

    public LogStream getLogStream() {
      return logStream;
    }
  }

  private static final class ProcessorContext implements AutoCloseable {

    private final LogContext logContext;
    private final StateSnapshotController stateSnapshotController;
    private final AsyncSnapshotDirector asyncSnapshotDirector;
    private final ZeebeDb zeebeDb;
    private final ServiceContainer serviceContainer;

    private boolean closed = false;

    private ProcessorContext(
        final LogContext logContext,
        final ServiceContainer serviceContainer,
        final StateSnapshotController stateSnapshotController,
        final AsyncSnapshotDirector asyncSnapshotDirector,
        final ZeebeDb zeebeDb) {
      this.logContext = logContext;
      this.serviceContainer = serviceContainer;
      this.stateSnapshotController = stateSnapshotController;
      this.asyncSnapshotDirector = asyncSnapshotDirector;
      this.zeebeDb = zeebeDb;
    }

    public static ProcessorContext createStreamContext(
        final LogContext logContext,
        final ServiceContainer serviceContainer,
        final StateSnapshotController stateSnapshotController,
        final AsyncSnapshotDirector asyncSnapshotDirector,
        final ZeebeDb zeebeDb) {
      return new ProcessorContext(
          logContext, serviceContainer, stateSnapshotController, asyncSnapshotDirector, zeebeDb);
    }

    public LogStream getLogStream() {
      return logContext.getLogStream();
    }

    public StateSnapshotController getStateSnapshotController() {
      return stateSnapshotController;
    }

    @Override
    public void close() throws Exception {
      if (closed) {
        return;
      }

      asyncSnapshotDirector.closeAsync().join();
      final String streamName = logContext.getLogStream().getLogName();
      final ServiceName<StreamProcessor> serviceName = streamProcessorService(streamName);

      Loggers.IO_LOGGER.debug("Close stream processor {}", serviceName);
      serviceContainer.removeService(serviceName).join();
      zeebeDb.close();
      closed = true;
    }
  }
}
