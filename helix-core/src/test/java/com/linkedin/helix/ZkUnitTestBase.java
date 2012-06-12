/**
 * Copyright (C) 2012 LinkedIn Inc <opensource@linkedin.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.helix;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.I0Itec.zkclient.IZkStateListener;
import org.I0Itec.zkclient.ZkConnection;
import org.I0Itec.zkclient.ZkServer;
import org.apache.log4j.Logger;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.testng.Assert;
import org.testng.AssertJUnit;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;

import com.linkedin.helix.ConfigScope.ConfigScopeProperty;
import com.linkedin.helix.controller.pipeline.Pipeline;
import com.linkedin.helix.controller.pipeline.Stage;
import com.linkedin.helix.controller.pipeline.StageContext;
import com.linkedin.helix.controller.stages.ClusterEvent;
import com.linkedin.helix.manager.zk.ZKDataAccessor;
import com.linkedin.helix.manager.zk.ZKHelixAdmin;
import com.linkedin.helix.manager.zk.ZNRecordSerializer;
import com.linkedin.helix.manager.zk.ZkClient;
import com.linkedin.helix.model.IdealState;
import com.linkedin.helix.model.IdealState.IdealStateModeProperty;
import com.linkedin.helix.model.InstanceConfig;
import com.linkedin.helix.model.LiveInstance;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.model.Message.Attributes;
import com.linkedin.helix.model.Message.MessageType;
import com.linkedin.helix.model.StateModelDefinition;
import com.linkedin.helix.tools.StateModelConfigGenerator;
import com.linkedin.helix.util.HelixUtil;

// TODO merge code with ZkIntegrationTestBase
public class ZkUnitTestBase
{
  private static Logger LOG = Logger.getLogger(ZkUnitTestBase.class);
  protected static ZkServer _zkServer = null;
  protected static ZkClient _gZkClient;

  public static final String ZK_ADDR = "localhost:2185";
  protected static final String CLUSTER_PREFIX = "CLUSTER";
  protected static final String CONTROLLER_CLUSTER_PREFIX = "CONTROLLER_CLUSTER";

  @BeforeSuite
  public void beforeSuite() throws Exception
  {
    _zkServer = TestHelper.startZkSever(ZK_ADDR);
    AssertJUnit.assertTrue(_zkServer != null);

    System.out.println("Number of open zkClient before ZkUnitTests: "
        + ZkClient.getNumberOfConnections());

    _gZkClient = new ZkClient(ZK_ADDR);
    _gZkClient.setZkSerializer(new ZNRecordSerializer());
  }

  @AfterSuite
  public void afterTest()
  {
    TestHelper.stopZkServer(_zkServer);
    _zkServer = null;
    _gZkClient.close();

    System.out.println("Number of open zkClient after ZkUnitTests: "
        + ZkClient.getNumberOfConnections());

  }

  protected String getShortClassName()
  {
    String className = this.getClass().getName();
    return className.substring(className.lastIndexOf('.') + 1);
  }

  protected String getCurrentLeader(ZkClient zkClient, String clusterName)
  {
    String leaderPath = HelixUtil.getControllerPropertyPath(clusterName, PropertyType.LEADER);
    ZNRecord leaderRecord = zkClient.<ZNRecord> readData(leaderPath);
    if (leaderRecord == null)
    {
      return null;
    }

    String leader = leaderRecord.getSimpleField(PropertyType.LEADER.toString());
    return leader;
  }

  protected void stopCurrentLeader(ZkClient zkClient, String clusterName,
      Map<String, Thread> threadMap, Map<String, HelixManager> managerMap)
  {
    String leader = getCurrentLeader(zkClient, clusterName);
    Assert.assertTrue(leader != null);
    System.out.println("stop leader:" + leader + " in " + clusterName);
    Assert.assertTrue(leader != null);

    HelixManager manager = managerMap.remove(leader);
    Assert.assertTrue(manager != null);
    manager.disconnect();

    Thread thread = threadMap.remove(leader);
    Assert.assertTrue(thread != null);
    thread.interrupt();

    boolean isNewLeaderElected = false;
    try
    {
      // Thread.sleep(2000);
      for (int i = 0; i < 5; i++)
      {
        Thread.sleep(1000);
        String newLeader = getCurrentLeader(zkClient, clusterName);
        if (!newLeader.equals(leader))
        {
          isNewLeaderElected = true;
          System.out.println("new leader elected: " + newLeader + " in " + clusterName);
          break;
        }
      }
    } catch (InterruptedException e)
    {
      e.printStackTrace();
    }
    if (isNewLeaderElected == false)
    {
      System.out.println("fail to elect a new leader elected in " + clusterName);
    }
    AssertJUnit.assertTrue(isNewLeaderElected);
  }

  public void verifyInstance(ZkClient zkClient, String clusterName, String instance,
      boolean wantExists)
  {
    // String instanceConfigsPath = HelixUtil.getConfigPath(clusterName);
    String instanceConfigsPath = PropertyPathConfig.getPath(PropertyType.CONFIGS, clusterName,
        ConfigScopeProperty.PARTICIPANT.toString());
    String instanceConfigPath = instanceConfigsPath + "/" + instance;
    String instancePath = HelixUtil.getInstancePath(clusterName, instance);
    AssertJUnit.assertEquals(wantExists, zkClient.exists(instanceConfigPath));
    AssertJUnit.assertEquals(wantExists, zkClient.exists(instancePath));
  }

  public void verifyResource(ZkClient zkClient, String clusterName, String resource,
      boolean wantExists)
  {
    String resourcePath = HelixUtil.getIdealStatePath(clusterName) + "/" + resource;
    AssertJUnit.assertEquals(wantExists, zkClient.exists(resourcePath));
  }

  public void verifyEnabled(ZkClient zkClient, String clusterName, String instance,
      boolean wantEnabled)
  {
    DataAccessor accessor = new ZKDataAccessor(clusterName, zkClient);
    InstanceConfig config = accessor.getProperty(InstanceConfig.class, PropertyType.CONFIGS,
        ConfigScopeProperty.PARTICIPANT.toString(), instance);
    AssertJUnit.assertEquals(wantEnabled, config.getInstanceEnabled());
  }

  public void verifyReplication(ZkClient zkClient, String clusterName, String resource, int repl)
  {
    DataAccessor accessor = new ZKDataAccessor(clusterName, zkClient);
    IdealState idealState = accessor.getProperty(IdealState.class, PropertyType.IDEALSTATES,
        resource);
    for (String partitionName : idealState.getPartitionSet())
    {
      AssertJUnit.assertEquals(repl, idealState.getInstanceStateMap(partitionName).size());
    }
  }

  protected void simulateSessionExpiry(ZkConnection zkConnection) throws IOException,
      InterruptedException
  {
    ZooKeeper oldZookeeper = zkConnection.getZookeeper();
    LOG.info("Old sessionId = " + oldZookeeper.getSessionId());

    Watcher watcher = new Watcher() {
      @Override
      public void process(WatchedEvent event)
      {
        LOG.info("In New connection, process event:" + event);
      }
    };

    ZooKeeper newZookeeper = new ZooKeeper(zkConnection.getServers(),
        oldZookeeper.getSessionTimeout(), watcher, oldZookeeper.getSessionId(),
        oldZookeeper.getSessionPasswd());
    LOG.info("New sessionId = " + newZookeeper.getSessionId());
    // Thread.sleep(3000);
    newZookeeper.close();
    Thread.sleep(10000);
    oldZookeeper = zkConnection.getZookeeper();
    LOG.info("After session expiry sessionId = " + oldZookeeper.getSessionId());
  }

  protected void simulateSessionExpiry(ZkClient zkClient) throws IOException, InterruptedException
  {
    IZkStateListener listener = new IZkStateListener() {
      @Override
      public void handleStateChanged(KeeperState state) throws Exception
      {
        LOG.info("In Old connection, state changed:" + state);
      }

      @Override
      public void handleNewSession() throws Exception
      {
        LOG.info("In Old connection, new session");
      }
    };
    zkClient.subscribeStateChanges(listener);
    ZkConnection connection = ((ZkConnection) zkClient.getConnection());
    ZooKeeper oldZookeeper = connection.getZookeeper();
    LOG.info("Old sessionId = " + oldZookeeper.getSessionId());

    Watcher watcher = new Watcher() {
      @Override
      public void process(WatchedEvent event)
      {
        LOG.info("In New connection, process event:" + event);
      }
    };

    ZooKeeper newZookeeper = new ZooKeeper(connection.getServers(),
        oldZookeeper.getSessionTimeout(), watcher, oldZookeeper.getSessionId(),
        oldZookeeper.getSessionPasswd());
    LOG.info("New sessionId = " + newZookeeper.getSessionId());
    // Thread.sleep(3000);
    newZookeeper.close();
    Thread.sleep(10000);
    connection = (ZkConnection) zkClient.getConnection();
    oldZookeeper = connection.getZookeeper();
    LOG.info("After session expiry sessionId = " + oldZookeeper.getSessionId());
  }

  protected void setupStateModel(String clusterName)
  {
    DataAccessor accessor = new ZKDataAccessor(clusterName, _gZkClient);
    StateModelConfigGenerator generator = new StateModelConfigGenerator();
    StateModelDefinition masterSlave =
        new StateModelDefinition(generator.generateConfigForMasterSlave());
    accessor.setProperty(PropertyType.STATEMODELDEFS, masterSlave, masterSlave.getId());
    StateModelDefinition leaderStandby =
        new StateModelDefinition(generator.generateConfigForLeaderStandby());
    accessor.setProperty(PropertyType.STATEMODELDEFS,
                          leaderStandby,
                          leaderStandby.getId());
    StateModelDefinition onlineOffline =
        new StateModelDefinition(generator.generateConfigForOnlineOffline());
    accessor.setProperty(PropertyType.STATEMODELDEFS,
                          onlineOffline,
                          onlineOffline.getId());
  }

  protected List<IdealState> setupIdealState(String clusterName,
                                             int[] nodes,
                                             String[] resources,
                                             int partitions,
                                             int replicas)
  {
    DataAccessor accessor = new ZKDataAccessor(clusterName, _gZkClient);

    List<IdealState> idealStates = new ArrayList<IdealState>();
    List<String> instances = new ArrayList<String>();
    for (int i : nodes)
    {
      instances.add("localhost_" + i);
    }

    for (String resourceName : resources)
    {
      IdealState idealState = new IdealState(resourceName);
      for (int p = 0; p < partitions; p++)
      {
        List<String> value = new ArrayList<String>();
        for (int r = 0; r < replicas; r++)
        {
          int n = nodes[(p + r) % nodes.length];
          value.add("localhost_" + n);
        }
        idealState.getRecord().setListField(resourceName + "_" + p, value);
      }

      idealState.setReplicas(Integer.toString(replicas));
      idealState.setStateModelDefRef("MasterSlave");
      idealState.setIdealStateMode(IdealStateModeProperty.AUTO.toString());
      idealState.setNumPartitions(partitions);
      idealStates.add(idealState);

      // System.out.println(idealState);
      accessor.setProperty(PropertyType.IDEALSTATES, idealState, resourceName);
    }
    return idealStates;
  }

  protected void setupLiveInstances(String clusterName, int[] liveInstances)
  {
    DataAccessor accessor = new ZKDataAccessor(clusterName, _gZkClient);

    for (int i = 0; i < liveInstances.length; i++)
    {
      String instance = "localhost_" + liveInstances[i];
      LiveInstance liveInstance = new LiveInstance(instance);
      liveInstance.setSessionId("session_" + liveInstances[i]);
      liveInstance.setHelixVersion("0.0.0");
      accessor.setProperty(PropertyType.LIVEINSTANCES, liveInstance, instance);
    }
  }
  protected void setupInstances(String clusterName, int[] instances)
  {
    HelixAdmin admin = new ZKHelixAdmin(_gZkClient);
    for (int i = 0; i < instances.length; i++)
    {
      String instance = "localhost_" + instances[i];
      InstanceConfig instanceConfig = new InstanceConfig(instance);
      instanceConfig.setHostName("localhost");
      instanceConfig.setPort(""+instances[i]);
      instanceConfig.setInstanceEnabled(true);
      admin.addInstance(clusterName, instanceConfig);
    }
  }

  protected void runPipeline(ClusterEvent event, Pipeline pipeline)
  {
    try
    {
      pipeline.handle(event);
      pipeline.finish();
    }
    catch (Exception e)
    {
      LOG.error("Exception while executing pipeline:" + pipeline
          + ". Will not continue to next pipeline", e);
    }
  }

  protected void runStage(ClusterEvent event, Stage stage) throws Exception
  {
    StageContext context = new StageContext();
    stage.init(context);
    stage.preProcess();
    stage.process(event);
    stage.postProcess();
  }

  protected Message createMessage(MessageType type,
                                String msgId,
                                String fromState,
                                String toState,
                                String resourceName,
                                String tgtName)
  {
    Message msg = new Message(type.toString(), msgId);
    msg.setFromState(fromState);
    msg.setToState(toState);
    msg.getRecord().setSimpleField(Attributes.RESOURCE_NAME.toString(), resourceName);
    msg.setTgtName(tgtName);
    return msg;
  }

}
