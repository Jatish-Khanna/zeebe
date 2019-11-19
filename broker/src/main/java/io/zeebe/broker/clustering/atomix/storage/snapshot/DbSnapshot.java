/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.clustering.atomix.storage.snapshot;

import io.atomix.protocols.raft.storage.snapshot.Snapshot;
import io.atomix.protocols.raft.storage.snapshot.SnapshotChunkReader;
import io.atomix.protocols.raft.storage.snapshot.impl.SnapshotReader;
import io.atomix.protocols.raft.storage.snapshot.impl.SnapshotWriter;
import io.atomix.utils.time.WallClockTimestamp;
import io.zeebe.util.ZbLogger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;

public class DbSnapshot implements Snapshot {
  // version currently hardcoded, could be used for backwards compatibility
  private static final int VERSION = 1;
  private static final Logger LOGGER = new ZbLogger(DbSnapshot.class);

  private final Path directory;
  private final DbSnapshotMetadata metadata;

  public DbSnapshot(
      final Path directory,
      final long index,
      final long term,
      final WallClockTimestamp timestamp,
      final long position) {
    this.directory = directory;
    this.metadata = new DbSnapshotMetadata(index, term, timestamp, position);
  }

  public DbSnapshot(final Path directory, final DbSnapshotMetadata metadata) {
    this.directory = directory;
    this.metadata = metadata;
  }

  public DbSnapshotMetadata getMetadata() {
    return metadata;
  }

  public Path getDirectory() {
    return directory;
  }

  @Override
  public WallClockTimestamp timestamp() {
    return metadata.getTimestamp();
  }

  @Override
  public int version() {
    return VERSION;
  }

  @Override
  public long index() {
    return metadata.getIndex();
  }

  @Override
  public long term() {
    return metadata.getTerm();
  }

  @Override
  public SnapshotChunkReader newChunkReader() {
    return DbSnapshotChunkReaderFactory.factory().ofSnapshot(this);
  }

  @Override
  public void close() {
    // nothing to be done
  }

  @Override
  public void delete() {
    try {
      Files.deleteIfExists(directory);
    } catch (final IOException e) {
      LOGGER.warn("Failed to delete snapshot {}", this, e);
    }
  }

  @Override
  public Snapshot complete() {
    throw new UnsupportedOperationException("Deprecated operation, use PendingSnapshot#commit");
  }

  @Override
  public SnapshotWriter openWriter() {
    throw new UnsupportedOperationException("Deprecated operation, use DbPendingSnapshot");
  }

  @Override
  public SnapshotReader openReader() {
    throw new UnsupportedOperationException("Deprecated operation, use SnapshotChunkReader");
  }

  @Override
  public void closeReader(final SnapshotReader reader) {
    throw new UnsupportedOperationException("Deprecated operation, use SnapshotChunkReader");
  }

  @Override
  public void closeWriter(final SnapshotWriter writer) {
    throw new UnsupportedOperationException("Deprecated operation, use DbPendingSnapshot");
  }
}