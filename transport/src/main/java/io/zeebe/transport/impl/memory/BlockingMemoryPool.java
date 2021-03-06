/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.transport.impl.memory;

import io.zeebe.transport.Loggers;
import io.zeebe.util.ByteValue;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;

/**
 * Manages a fixed capacity of of memory.
 *
 * <p>Current implementation does not actually "pool" or "recycle" the memory, it leaves that to GC.
 *
 * <p>It does however put a fixed size on how much memory can be in use at any given point in time.
 * When no more memory is available, it blocks for the specified time until one of the following
 * events occurs: a) the maxBlocktime is reached. In this case the allocation times out and no
 * memory can be allocated. b) memory becomes available and can be allocated
 *
 * <p>The main usecase for this pool is the zeebe client where it is desired behavior to block
 * client threads on memory backpressure
 */
public class BlockingMemoryPool implements TransportMemoryPool {
  private static final Logger LOG = Loggers.TRANSPORT_MEMORY_LOGGER;

  private final ReentrantLock lock = new ReentrantLock(true);
  private final Condition memoryReclaimed = lock.newCondition();
  private final long maxBlockTimeMs;
  private long availableCapacity = 0;

  public BlockingMemoryPool(final ByteValue capacity, final long maxBlockTimeMs) {
    availableCapacity = capacity.toBytes();
    this.maxBlockTimeMs = maxBlockTimeMs;
  }

  @Override
  public ByteBuffer allocate(final int requestedCapacity) {
    LOG.trace("Attempting to allocate {} bytes", requestedCapacity);

    final long deadline = System.currentTimeMillis() + maxBlockTimeMs;

    boolean canAllocte = false;

    try {
      lock.lock();

      do {
        LOG.trace("Allocation attempt");

        final long newRemaining = availableCapacity - requestedCapacity;
        canAllocte = newRemaining >= 0;

        if (canAllocte) {
          availableCapacity = newRemaining;
        } else {
          try {
            memoryReclaimed.await(1, TimeUnit.MILLISECONDS);
          } catch (final InterruptedException e) {
            LOG.debug("Interrupted while waiting for memory to be reclaimed.");
            break;
          }
        }
      } while (System.currentTimeMillis() < deadline && !canAllocte);

    } finally {
      lock.unlock();
    }

    if (canAllocte) {
      LOG.trace("Attocated {} bytes", requestedCapacity);
      return ByteBuffer.allocate(requestedCapacity);
    } else {
      LOG.trace("Failed to allocate {} bytes", requestedCapacity);
      return null;
    }
  }

  @Override
  public void reclaim(final ByteBuffer buffer) {
    final int bytesReclaimed = buffer.capacity();

    LOG.trace("Reclaiming {} bytes", bytesReclaimed);

    try {
      lock.lock();

      availableCapacity += bytesReclaimed;

      memoryReclaimed.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public long capacity() {
    return availableCapacity;
  }
}
