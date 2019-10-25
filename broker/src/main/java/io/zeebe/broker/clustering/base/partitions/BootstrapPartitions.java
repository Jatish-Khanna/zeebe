/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.clustering.base.partitions;

import static io.zeebe.broker.clustering.base.ClusterBaseLayerServiceNames.POSITION_BROADCASTER_SERVICE;
import static io.zeebe.broker.clustering.base.partitions.Partition.getPartitionName;
import static io.zeebe.broker.clustering.base.partitions.PartitionServiceNames.partitionInstallServiceName;

import io.atomix.cluster.MemberId;
import io.atomix.core.Atomix;
import io.atomix.protocols.raft.partition.RaftPartition;
import io.atomix.protocols.raft.partition.RaftPartitionGroup;
import io.zeebe.broker.system.configuration.BrokerCfg;
import io.zeebe.distributedlog.StorageConfiguration;
import io.zeebe.distributedlog.StorageConfigurationManager;
import io.zeebe.servicecontainer.Injector;
import io.zeebe.servicecontainer.Service;
import io.zeebe.servicecontainer.ServiceName;
import io.zeebe.servicecontainer.ServiceStartContext;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Always installed on broker startup: reads configuration of all locally available partitions and
 * starts the corresponding services (logstream, partition ...)
 */
public class BootstrapPartitions implements Service<Void> {
  private final Injector<StorageConfigurationManager> configurationManagerInjector =
      new Injector<>();
  private final BrokerCfg brokerCfg;
  private final Injector<Atomix> atomixInjector = new Injector<>();
  private StorageConfigurationManager configurationManager;
  private ServiceStartContext startContext;

  public BootstrapPartitions(final BrokerCfg brokerCfg) {
    this.brokerCfg = brokerCfg;
  }

  @Override
  public void start(final ServiceStartContext startContext) {
    configurationManager = configurationManagerInjector.getValue();
    final Atomix atomix = atomixInjector.getValue();

    final RaftPartitionGroup partitionGroup =
        (RaftPartitionGroup) atomix.getPartitionService().getPartitionGroup(Partition.GROUP_NAME);

    final MemberId nodeId = atomix.getMembershipService().getLocalMember().id();
    final List<RaftPartition> owningPartitions =
        partitionGroup.getPartitions().stream()
            .filter(partition -> partition.members().contains(nodeId))
            .map(RaftPartition.class::cast)
            .collect(Collectors.toList());

    this.startContext = startContext;
    startContext.run(
        () -> {
          for (final RaftPartition owningPartition : owningPartitions) {
            installPartition(owningPartition);
          }
        });
  }

  @Override
  public Void get() {
    return null;
  }

  private void installPartition(final RaftPartition partition) {
    final StorageConfiguration configuration =
        configurationManager.createConfiguration(partition.id().id()).join();
    installPartition(startContext, configuration, partition);
  }

  private void installPartition(
      final ServiceStartContext startContext,
      final StorageConfiguration configuration,
      final RaftPartition partition) {
    final String partitionName = getPartitionName(configuration.getPartitionId());
    final ServiceName<Void> serviceName = partitionInstallServiceName(partitionName);

    final PartitionInstallService service =
        new PartitionInstallService(partition, configuration, brokerCfg);

    startContext
        .createService(serviceName, service)
        .dependency(atomixInjector.getInjectedServiceName(), service.getAtomixInjector())
        .dependency(POSITION_BROADCASTER_SERVICE, service.getPositionBroadcasterInjector())
        .install();
  }

  public Injector<StorageConfigurationManager> getConfigurationManagerInjector() {
    return configurationManagerInjector;
  }

  public Injector<Atomix> getAtomixInjector() {
    return this.atomixInjector;
  }
}
