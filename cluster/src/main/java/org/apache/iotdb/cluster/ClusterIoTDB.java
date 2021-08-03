/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.cluster;

import org.apache.iotdb.cluster.client.async.AsyncMetaClient;
import org.apache.iotdb.cluster.client.sync.SyncClientAdaptor;
import org.apache.iotdb.cluster.config.ClusterConfig;
import org.apache.iotdb.cluster.config.ClusterDescriptor;
import org.apache.iotdb.cluster.coordinator.Coordinator;
import org.apache.iotdb.cluster.exception.ConfigInconsistentException;
import org.apache.iotdb.cluster.exception.StartUpCheckFailureException;
import org.apache.iotdb.cluster.metadata.CMManager;
import org.apache.iotdb.cluster.metadata.MetaPuller;
import org.apache.iotdb.cluster.partition.slot.SlotPartitionTable;
import org.apache.iotdb.cluster.partition.slot.SlotStrategy;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.server.ClusterRPCService;
import org.apache.iotdb.cluster.server.RaftTSMetaServiceImpl;
import org.apache.iotdb.cluster.server.Response;
import org.apache.iotdb.cluster.server.clusterinfo.ClusterInfoServer;
import org.apache.iotdb.cluster.server.member.MetaGroupMember;
import org.apache.iotdb.cluster.server.raft.MetaRaftHeartBeatService;
import org.apache.iotdb.cluster.server.raft.MetaRaftService;
import org.apache.iotdb.cluster.server.service.MetaAsyncService;
import org.apache.iotdb.cluster.server.service.MetaSyncService;
import org.apache.iotdb.cluster.utils.ClusterUtils;
import org.apache.iotdb.cluster.utils.nodetool.ClusterMonitor;
import org.apache.iotdb.db.conf.IoTDBConfigCheck;
import org.apache.iotdb.db.conf.IoTDBConstant;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.exception.StartupException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.service.IoTDB;
import org.apache.iotdb.db.service.JMXService;
import org.apache.iotdb.db.service.RegisterManager;
import org.apache.iotdb.db.service.thrift.ThriftServiceThread;
import org.apache.iotdb.db.utils.TestOnly;
import org.apache.thrift.TException;
import org.apache.thrift.async.TAsyncClientManager;
import org.apache.thrift.protocol.TBinaryProtocol.Factory;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.apache.iotdb.cluster.utils.ClusterUtils.UNKNOWN_CLIENT_IP;

// we do not inherent IoTDB instance, as it may break the singleton mode of IoTDB.
public class ClusterIoTDB {

  private static final Logger logger = LoggerFactory.getLogger(ClusterIoTDB.class);
  private final String mbeanName =
      String.format(
          "%s:%s=%s", IoTDBConstant.IOTDB_PACKAGE, IoTDBConstant.JMX_TYPE, "ClusterIoTDB");

  // establish the cluster as a seed
  private static final String MODE_START = "-s";
  // join an established cluster
  private static final String MODE_ADD = "-a";
  // send a request to remove a node, more arguments: ip-of-removed-node
  // metaport-of-removed-node
  private static final String MODE_REMOVE = "-r";

  private MetaGroupMember metaGroupEngine;
  private Node thisNode;
  private Coordinator coordinator;

  private IoTDB iotdb = IoTDB.getInstance();

  // Cluster IoTDB uses a individual registerManager with its parent.
  private RegisterManager registerManager = new RegisterManager();

  private ClusterIoTDB() {
    ClusterConfig config = ClusterDescriptor.getInstance().getConfig();
    thisNode = new Node();
    // set internal rpc ip and ports
    thisNode.setInternalIp(config.getInternalIp());
    thisNode.setMetaPort(config.getInternalMetaPort());
    thisNode.setDataPort(config.getInternalDataPort());
    // set client rpc ip and ports
    thisNode.setClientPort(config.getClusterRpcPort());
    thisNode.setClientIp(IoTDBDescriptor.getInstance().getConfig().getRpcAddress());
  }

  public static void main(String[] args) {
    if (args.length < 1) {
      logger.error(
          "Usage: <-s|-a|-r> "
              + "[-D{} <configure folder>] \n"
              + "-s: start the node as a seed\n"
              + "-a: start the node as a new node\n"
              + "-r: remove the node out of the cluster\n",
          IoTDBConstant.IOTDB_CONF);
      return;
    }

    try {
      IoTDBConfigCheck.getInstance().checkConfig();
    } catch (IOException e) {
      logger.error("meet error when doing start checking", e);
    }

    // init server's configuration first, because the cluster configuration may read settings from
    // the server's configuration.
    IoTDBDescriptor.getInstance().getConfig().setSyncEnable(false);
    // auto create schema is took over by cluster module, so we disable it in the server module.
    IoTDBDescriptor.getInstance().getConfig().setAutoCreateSchemaEnabled(false);

    // params check
    try {
      ClusterDescriptor.getInstance().replaceHostnameWithIp();
    } catch (Exception e) {
      logger.error("replace hostname with ip failed, {}", e.getMessage());
      return;
    }

    String mode = args[0];
    logger.info("Running mode {}", mode);

    ClusterIoTDB cluster = ClusterIoTDBHolder.INSTANCE;
    // we start IoTDB kernel first.
    // cluster.iotdb.active();

    // then we start the cluster module.
    if (MODE_START.equals(mode)) {
      cluster.activeStartNodeMode();
    } else if (MODE_ADD.equals(mode)) {
      cluster.activeAddNodeMode();
    } else if (MODE_REMOVE.equals(mode)) {
      try {
        cluster.doRemoveNode(args);
      } catch (IOException e) {
        logger.error("Fail to remove node in cluster", e);
      }
    } else {
      logger.error("Unrecognized mode {}", mode);
    }
  }

  public void activeStartNodeMode() {
    try {
      startServerCheck();
      preStartCustomize();

      coordinator = new Coordinator();
      // register MetaGroupMember. MetaGroupMember has the same position with "StorageEngine" in the
      // cluster moduel.
      // TODO fixme it is better to remove coordinator out of metaGroupEngine

      // local engine
      metaGroupEngine =
          new MetaGroupMember(
              ThriftServiceThread.getProtocolFactory(
                  IoTDBDescriptor.getInstance().getConfig().isRpcThriftCompressionEnable()),
              thisNode,
              coordinator);
      IoTDB.setMetaManager(CMManager.getInstance());
      ((CMManager) IoTDB.metaManager).setMetaGroupMember(metaGroupEngine);
      ((CMManager) IoTDB.metaManager).setCoordinator(coordinator);
      MetaPuller.getInstance().init(metaGroupEngine);
      iotdb.active();

      registerManager.register(metaGroupEngine);

      metaGroupEngine.buildCluster();

      // rpc service
      if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
        MetaAsyncService metaAsyncService = new MetaAsyncService(metaGroupEngine);
        MetaRaftHeartBeatService.getInstance().initAsyncedServiceImpl(metaAsyncService);
        MetaRaftService.getInstance().initAsyncedServiceImpl(metaAsyncService);
      } else {
        MetaSyncService syncService = new MetaSyncService(metaGroupEngine);
        MetaRaftHeartBeatService.getInstance().initSyncedServiceImpl(syncService);
        MetaRaftService.getInstance().initSyncedServiceImpl(syncService);
      }

      // meta group heart beat rpc
      registerManager.register(MetaRaftHeartBeatService.getInstance());
      registerManager.register(MetaRaftService.getInstance());

      // Currently, we do not register ClusterInfoService as a JMX Bean,
      // so we use startService() rather than start()
      ClusterInfoServer.getInstance().startService();
      // JMX based DBA API
      registerManager.register(ClusterMonitor.INSTANCE);
      // we must wait until the metaGroup established.
      // So that the ClusterRPCService can work.
      registerManager.register(ClusterRPCService.getInstance());
    } catch (StartupException
        | QueryProcessException
        | StartUpCheckFailureException
        | ConfigInconsistentException e) {
      stop();
      logger.error("Fail to start meta server", e);
    }
  }

  public void activeAddNodeMode() {
    //    try {
    //      long startTime = System.currentTimeMillis();
    //      metaServer = new RaftTSMetaServiceImpl();
    //      preStartCustomize();
    //      metaServer.start();
    //      metaServer.joinCluster();
    //      // Currently, we do not register ClusterInfoService as a JMX Bean,
    //      // so we use startService() rather than start()
    //      ClusterInfoServer.getInstance().startService();
    //      // JMX based DBA API
    //      registerManager.register(ClusterMonitor.INSTANCE);
    //      // finally, we start the RPC service
    //      registerManager.register(ClusterRPCService.getInstance());
    //      logger.info(
    //          "Adding this node {} to cluster costs {} ms",
    //          metaServer.getMember().getThisNode(),
    //          (System.currentTimeMillis() - startTime));
    //    } catch (TTransportException
    //        | StartupException
    //        | QueryProcessException
    //        | StartUpCheckFailureException
    //        | ConfigInconsistentException e) {
    //      stop();
    //      logger.error("Fail to join cluster", e);
    //    }
  }

  private void startServerCheck() throws StartupException {
    ClusterConfig config = ClusterDescriptor.getInstance().getConfig();
    // check the initial replicateNum and refuse to start when the replicateNum <= 0
    if (config.getReplicationNum() <= 0) {
      String message =
          String.format(
              "ReplicateNum should be greater than 0 instead of %d.", config.getReplicationNum());
      throw new StartupException(metaServer.getMember().getName(), message);
    }
    // check the initial cluster size and refuse to start when the size < quorum
    int quorum = config.getReplicationNum() / 2 + 1;
    if (config.getSeedNodeUrls().size() < quorum) {
      String message =
          String.format(
              "Seed number less than quorum, seed number: %s, quorum: " + "%s.",
              config.getSeedNodeUrls().size(), quorum);
      throw new StartupException(metaServer.getMember().getName(), message);
    }

    // assert not duplicated nodes
    Set<Node> seedNodes = new HashSet<>();
    for (String url : config.getSeedNodeUrls()) {
      Node node = ClusterUtils.parseNode(url);
      if (seedNodes.contains(node)) {
        String message =
            String.format(
                "SeedNodes must not repeat each other. SeedNodes: %s", config.getSeedNodeUrls());
        throw new StartupException(metaServer.getMember().getName(), message);
      }
      seedNodes.add(node);
    }

    // assert this node is in all nodes when restart
    if (!metaServer.getMember().getAllNodes().isEmpty()) {
      if (!metaServer.getMember().getAllNodes().contains(metaServer.getMember().getThisNode())) {
        String message =
            String.format(
                "All nodes in partitionTables must contains local node in start-server mode. "
                    + "LocalNode: %s, AllNodes: %s",
                metaServer.getMember().getThisNode(), metaServer.getMember().getAllNodes());
        throw new StartupException(metaServer.getMember().getName(), message);
      } else {
        return;
      }
    }

    // assert this node is in seed nodes list

    if (!seedNodes.contains(thisNode)) {
      String message =
          String.format(
              "SeedNodes must contains local node in start-server mode. LocalNode: %s ,SeedNodes: %s",
              thisNode.toString(), config.getSeedNodeUrls());
      throw new StartupException(metaServer.getMember().getName(), message);
    }
  }

  private void doRemoveNode(String[] args) throws IOException {
    if (args.length != 3) {
      logger.error("Usage: -r <ip> <metaPort>");
      return;
    }
    String ip = args[1];
    int metaPort = Integer.parseInt(args[2]);
    ClusterConfig config = ClusterDescriptor.getInstance().getConfig();
    TProtocolFactory factory =
        config.isRpcThriftCompressionEnabled() ? new TCompactProtocol.Factory() : new Factory();
    Node nodeToRemove = new Node();
    nodeToRemove.setInternalIp(ip).setMetaPort(metaPort).setClientIp(UNKNOWN_CLIENT_IP);
    // try sending the request to each seed node
    for (String url : config.getSeedNodeUrls()) {
      Node node = ClusterUtils.parseNode(url);
      if (node == null) {
        continue;
      }
      AsyncMetaClient client = new AsyncMetaClient(factory, new TAsyncClientManager(), node, null);
      Long response = null;
      long startTime = System.currentTimeMillis();
      try {
        logger.info("Start removing node {} with the help of node {}", nodeToRemove, node);
        response = SyncClientAdaptor.removeNode(client, nodeToRemove);
      } catch (TException e) {
        logger.warn("Cannot send remove node request through {}, try next node", node);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.warn("Cannot send remove node request through {}, try next node", node);
      }
      if (response != null) {
        handleNodeRemovalResp(response, nodeToRemove, startTime);
        return;
      }
    }
  }

  private void handleNodeRemovalResp(Long response, Node nodeToRemove, long startTime) {
    if (response == Response.RESPONSE_AGREE) {
      logger.info(
          "Node {} is successfully removed, cost {}ms",
          nodeToRemove,
          (System.currentTimeMillis() - startTime));
    } else if (response == Response.RESPONSE_CLUSTER_TOO_SMALL) {
      logger.error("Cluster size is too small, cannot remove any node");
    } else if (response == Response.RESPONSE_REJECT) {
      logger.error("Node {} is not found in the cluster, please check", nodeToRemove);
    } else if (response == Response.RESPONSE_DATA_MIGRATION_NOT_FINISH) {
      logger.warn(
          "The data migration of the previous membership change operation is not finished. Please try again later");
    } else {
      logger.error("Unexpected response {}", response);
    }
  }

  public RaftTSMetaServiceImpl getMetaServer() {
    return metaServer;
  }

  /** Developers may perform pre-start customizations here for debugging or experiments. */
  @SuppressWarnings("java:S125") // leaving examples
  private void preStartCustomize() {
    // customize data distribution
    // The given example tries to divide storage groups like "root.sg_1", "root.sg_2"... into k
    // nodes evenly, and use default strategy for other groups
    SlotPartitionTable.setSlotStrategy(
        new SlotStrategy() {
          SlotStrategy defaultStrategy = new SlotStrategy.DefaultStrategy();
          int k = 3;

          @Override
          public int calculateSlotByTime(String storageGroupName, long timestamp, int maxSlotNum) {
            int sgSerialNum = extractSerialNumInSGName(storageGroupName) % k;
            if (sgSerialNum >= 0) {
              return maxSlotNum / k * sgSerialNum;
            } else {
              return defaultStrategy.calculateSlotByTime(storageGroupName, timestamp, maxSlotNum);
            }
          }

          @Override
          public int calculateSlotByPartitionNum(
              String storageGroupName, long partitionId, int maxSlotNum) {
            int sgSerialNum = extractSerialNumInSGName(storageGroupName) % k;
            if (sgSerialNum >= 0) {
              return maxSlotNum / k * sgSerialNum;
            } else {
              return defaultStrategy.calculateSlotByPartitionNum(
                  storageGroupName, partitionId, maxSlotNum);
            }
          }

          private int extractSerialNumInSGName(String storageGroupName) {
            String[] s = storageGroupName.split("_");
            if (s.length != 2) {
              return -1;
            }
            try {
              return Integer.parseInt(s[1]);
            } catch (NumberFormatException e) {
              return -1;
            }
          }
        });
  }

  @TestOnly
  public void setMetaClusterServer(RaftTSMetaServiceImpl RaftTSMetaServiceImpl) {
    metaServer = RaftTSMetaServiceImpl;
  }

  public void stop() {
    deactivate();
  }

  private void deactivate() {
    logger.info("Deactivating Cluster IoTDB...");
    metaServer.stop();
    registerManager.deregisterAll();
    JMXService.deregisterMBean(mbeanName);
    logger.info("ClusterIoTDB is deactivated.");
    // stop the iotdb kernel
    iotdb.stop();
  }

  public static ClusterIoTDB getInstance() {
    return ClusterIoTDBHolder.INSTANCE;
  }

  private static class ClusterIoTDBHolder {

    private static final ClusterIoTDB INSTANCE = new ClusterIoTDB();

    private ClusterIoTDBHolder() {}
  }
}
