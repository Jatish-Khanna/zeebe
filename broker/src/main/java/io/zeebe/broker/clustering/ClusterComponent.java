/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.clustering;

import static io.zeebe.broker.clustering.base.ClusterBaseLayerServiceNames.ATOMIX_JOIN_SERVICE;
import static io.zeebe.broker.clustering.base.ClusterBaseLayerServiceNames.ATOMIX_SERVICE;
import static io.zeebe.broker.clustering.base.ClusterBaseLayerServiceNames.CLUSTERING_BASE_LAYER;
import static io.zeebe.broker.clustering.base.ClusterBaseLayerServiceNames.FOLLOWER_PARTITION_GROUP_NAME;
import static io.zeebe.broker.clustering.base.ClusterBaseLayerServiceNames.GATEWAY_SERVICE;
import static io.zeebe.broker.clustering.base.ClusterBaseLayerServiceNames.LEADER_PARTITION_GROUP_NAME;
import static io.zeebe.broker.clustering.base.ClusterBaseLayerServiceNames.PARTITIONS_BOOTSTRAP_SERVICE;
import static io.zeebe.broker.clustering.base.ClusterBaseLayerServiceNames.TOPOLOGY_MANAGER_SERVICE;

import io.zeebe.broker.clustering.atomix.AtomixJoinService;
import io.zeebe.broker.clustering.atomix.AtomixService;
import io.zeebe.broker.clustering.base.EmbeddedGatewayService;
import io.zeebe.broker.clustering.base.partitions.BootstrapPartitions;
import io.zeebe.broker.clustering.base.topology.TopologyManagerService;
import io.zeebe.broker.system.Component;
import io.zeebe.broker.system.SystemContext;
import io.zeebe.broker.system.configuration.BrokerCfg;
import io.zeebe.broker.system.configuration.NetworkCfg;
import io.zeebe.protocol.impl.encoding.BrokerInfo;
import io.zeebe.servicecontainer.CompositeServiceBuilder;
import io.zeebe.servicecontainer.ServiceContainer;

/** Installs the clustering component into the broker. */
public class ClusterComponent implements Component {

  @Override
  public void init(final SystemContext context) {
    final ServiceContainer serviceContainer = context.getServiceContainer();

    initClusterBaseLayer(context, serviceContainer);
  }

  private void initClusterBaseLayer(
      final SystemContext context, final ServiceContainer serviceContainer) {
    final BrokerCfg brokerConfig = context.getBrokerConfiguration();
    final NetworkCfg networkCfg = brokerConfig.getNetwork();
    final CompositeServiceBuilder baseLayerInstall =
        serviceContainer.createComposite(CLUSTERING_BASE_LAYER);

    final BrokerInfo localMember =
        new BrokerInfo(
            brokerConfig.getCluster().getNodeId(),
            networkCfg.getCommandApi().getAdvertisedAddress().toString());

    final TopologyManagerService topologyManagerService =
        new TopologyManagerService(localMember, brokerConfig.getCluster());

    baseLayerInstall
        .createService(TOPOLOGY_MANAGER_SERVICE, topologyManagerService)
        .dependency(ATOMIX_SERVICE, topologyManagerService.getAtomixInjector())
        .groupReference(
            LEADER_PARTITION_GROUP_NAME, topologyManagerService.getLeaderInstallReference())
        .groupReference(
            FOLLOWER_PARTITION_GROUP_NAME, topologyManagerService.getFollowerInstallReference())
        .install();

    if (brokerConfig.getGateway().isEnable()) {
      initGateway(baseLayerInstall, brokerConfig);
    }

    initAtomix(baseLayerInstall, context);
    initPartitions(baseLayerInstall, context);

    context.addRequiredStartAction(baseLayerInstall.install());
  }

  private void initGateway(
      final CompositeServiceBuilder baseLayerInstall, final BrokerCfg brokerConfig) {
    final EmbeddedGatewayService gatewayService = new EmbeddedGatewayService(brokerConfig);
    baseLayerInstall
        .createService(GATEWAY_SERVICE, gatewayService)
        .dependency(ATOMIX_SERVICE, gatewayService.getAtomixClusterInjector())
        .dependency(ATOMIX_JOIN_SERVICE)
        .install();
  }

  private void initAtomix(
      final CompositeServiceBuilder baseLayerInstall, final SystemContext context) {

    final AtomixService atomixService = new AtomixService(context.getBrokerConfiguration());
    baseLayerInstall.createService(ATOMIX_SERVICE, atomixService).install();

    final AtomixJoinService atomixJoinService = new AtomixJoinService();
    // With RaftPartitionGroup AtomixJoinService completes only when majority of brokers have
    // started and join the group. Hence don't add the service to the baselayer.
    context
        .getServiceContainer()
        .createService(ATOMIX_JOIN_SERVICE, atomixJoinService)
        .dependency(TOPOLOGY_MANAGER_SERVICE)
        .dependency(ATOMIX_SERVICE, atomixJoinService.getAtomixInjector())
        .install();
  }

  private void initPartitions(
      final CompositeServiceBuilder baseLayerInstall, final SystemContext context) {
    final BootstrapPartitions partitionBootstrapService =
        new BootstrapPartitions(context.getBrokerConfiguration(), context.getServiceContainer());
    context
        .getServiceContainer()
        .createService(PARTITIONS_BOOTSTRAP_SERVICE, partitionBootstrapService)
        .dependency(ATOMIX_SERVICE, partitionBootstrapService.getAtomixInjector())
        .dependency(ATOMIX_JOIN_SERVICE)
        .install();
  }
}
