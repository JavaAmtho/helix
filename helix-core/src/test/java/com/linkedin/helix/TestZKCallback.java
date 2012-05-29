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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.linkedin.helix.manager.zk.ZNRecordSerializer;
import com.linkedin.helix.manager.zk.ZkClient;
import com.linkedin.helix.model.CurrentState;
import com.linkedin.helix.model.ExternalView;
import com.linkedin.helix.model.IdealState;
import com.linkedin.helix.model.InstanceConfig;
import com.linkedin.helix.model.LiveInstance;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.model.Message.MessageType;
import com.linkedin.helix.tools.ClusterSetup;

public class TestZKCallback extends ZkUnitTestBase
{
  private final String clusterName = CLUSTER_PREFIX + "_" + getShortClassName();

  ZkClient _zkClient;

  private static String[] createArgs(String str)
  {
    String[] split = str.split("[ ]+");
    System.out.println(Arrays.toString(split));
    return split;
  }

  public class TestCallbackListener implements MessageListener, LiveInstanceChangeListener,
      ConfigChangeListener, CurrentStateChangeListener, ExternalViewChangeListener,
      IdealStateChangeListener
  {
    boolean externalViewChangeReceived = false;
    boolean liveInstanceChangeReceived = false;
    boolean configChangeReceived = false;
    boolean currentStateChangeReceived = false;
    boolean messageChangeReceived = false;
    boolean idealStateChangeReceived = false;

    @Override
    public void onExternalViewChange(List<ExternalView> externalViewList,
        NotificationContext changeContext)
    {
      externalViewChangeReceived = true;
    }

    @Override
    public void onStateChange(String instanceName, List<CurrentState> statesInfo,
        NotificationContext changeContext)
    {
      currentStateChangeReceived = true;
    }

    @Override
    public void onConfigChange(List<InstanceConfig> configs, NotificationContext changeContext)
    {
      configChangeReceived = true;
    }

    @Override
    public void onLiveInstanceChange(List<LiveInstance> liveInstances,
        NotificationContext changeContext)
    {
      liveInstanceChangeReceived = true;
    }

    @Override
    public void onMessage(String instanceName, List<Message> messages,
        NotificationContext changeContext)
    {
      messageChangeReceived = true;
    }

    void Reset()
    {
      externalViewChangeReceived = false;
      liveInstanceChangeReceived = false;
      configChangeReceived = false;
      currentStateChangeReceived = false;
      messageChangeReceived = false;
      idealStateChangeReceived = false;
    }

    @Override
    public void onIdealStateChange(List<IdealState> idealState, NotificationContext changeContext)
    {
      // TODO Auto-generated method stub
      idealStateChangeReceived = true;
    }
  }

  @Test()
  public void testInvocation() throws Exception
  {

    HelixManager testHelixManager = HelixManagerFactory.getZKHelixManager(clusterName,
        "localhost_8900", InstanceType.PARTICIPANT, ZK_ADDR);
    testHelixManager.connect();

    TestZKCallback test = new TestZKCallback();

    TestZKCallback.TestCallbackListener testListener = test.new TestCallbackListener();

    testHelixManager.addMessageListener(testListener, "localhost_8900");
    testHelixManager.addCurrentStateChangeListener(testListener, "localhost_8900",
        testHelixManager.getSessionId());
    testHelixManager.addConfigChangeListener(testListener);
    testHelixManager.addIdealStateChangeListener(testListener);
//    testHelixManager.addExternalViewChangeListener(testListener);
    testHelixManager.addLiveInstanceChangeListener(testListener);
    // Initial add listener should trigger the first execution of the
    // listener callbacks
    AssertJUnit.assertTrue(testListener.configChangeReceived
        & testListener.currentStateChangeReceived
        & testListener.idealStateChangeReceived & testListener.liveInstanceChangeReceived
        & testListener.messageChangeReceived);

    testListener.Reset();
    DataAccessor dataAccessor = testHelixManager.getDataAccessor();
    ExternalView extView = new ExternalView("db-12345");
    dataAccessor.setProperty(PropertyType.EXTERNALVIEW, extView, "db-12345");
    Thread.sleep(100);
//    AssertJUnit.assertTrue(testListener.externalViewChangeReceived);
    testListener.Reset();

    CurrentState curState = new CurrentState("db-12345");
    curState.setSessionId("sessionId");
    curState.setStateModelDefRef("StateModelDef");
    dataAccessor.setProperty(PropertyType.CURRENTSTATES, curState, "localhost_8900",
        testHelixManager.getSessionId(), curState.getId());
    Thread.sleep(100);
    AssertJUnit.assertTrue(testListener.currentStateChangeReceived);
    testListener.Reset();

    IdealState idealState = new IdealState("db-1234");
    idealState.setNumPartitions(400);
    idealState.setReplicas(Integer.toString(2));
    idealState.setStateModelDefRef("StateModeldef");
    dataAccessor.setProperty(PropertyType.IDEALSTATES, idealState, "db-1234");
    Thread.sleep(100);
    AssertJUnit.assertTrue(testListener.idealStateChangeReceived);
    testListener.Reset();

    // dummyRecord = new ZNRecord("db-12345");
    // dataAccessor.setProperty(PropertyType.IDEALSTATES, idealState, "db-12345"
    // );
    // Thread.sleep(100);
    // AssertJUnit.assertTrue(testListener.idealStateChangeReceived);
    // testListener.Reset();

    // dummyRecord = new ZNRecord("localhost:8900");
    // List<ZNRecord> recList = new ArrayList<ZNRecord>();
    // recList.add(dummyRecord);

    testListener.Reset();
    Message message = new Message(MessageType.STATE_TRANSITION, UUID.randomUUID().toString());
    message.setTgtSessionId("*");
    message.setResourceName("testResource");
    message.setPartitionName("testPartitionKey");
    message.setStateModelDef("MasterSlave");
    message.setToState("toState");
    message.setFromState("fromState");
    message.setTgtName("testTarget");
    message.setStateModelFactoryName(HelixConstants.DEFAULT_STATE_MODEL_FACTORY);

    dataAccessor.setProperty(PropertyType.MESSAGES, message, "localhost_8900", message.getId());
    Thread.sleep(500);
    AssertJUnit.assertTrue(testListener.messageChangeReceived);

    // dummyRecord = new ZNRecord("localhost_9801");
    LiveInstance liveInstance = new LiveInstance("localhost_9801");
    liveInstance.setSessionId(UUID.randomUUID().toString());
    liveInstance.setHelixVersion(UUID.randomUUID().toString());
    dataAccessor.setProperty(PropertyType.LIVEINSTANCES, liveInstance, "localhost_9801");
    Thread.sleep(500);
    AssertJUnit.assertTrue(testListener.liveInstanceChangeReceived);
    testListener.Reset();

    // dataAccessor.setNodeConfigs(recList); Thread.sleep(100);
    // AssertJUnit.assertTrue(testListener.configChangeReceived);
    // testListener.Reset();
  }

  @BeforeClass()
  public void beforeClass() throws IOException, Exception
  {
    _zkClient = new ZkClient(ZK_ADDR);
    _zkClient.setZkSerializer(new ZNRecordSerializer());
    if (_zkClient.exists("/" + clusterName))
    {
      _zkClient.deleteRecursive("/" + clusterName);
    }

    ClusterSetup.processCommandLineArgs(createArgs("-zkSvr " + ZK_ADDR + " -addCluster "
        + clusterName));
    // ClusterSetup
    // .processCommandLineArgs(createArgs("-zkSvr " + ZK_ADDR +
    // " -addCluster relay-cluster-12345"));
    ClusterSetup.processCommandLineArgs(createArgs("-zkSvr " + ZK_ADDR + " -addResource "
        + clusterName + " db-12345 120 MasterSlave"));
    ClusterSetup.processCommandLineArgs(createArgs("-zkSvr " + ZK_ADDR + " -addNode " + clusterName
        + " localhost:8900"));
    ClusterSetup.processCommandLineArgs(createArgs("-zkSvr " + ZK_ADDR + " -addNode " + clusterName
        + " localhost:8901"));
    ClusterSetup.processCommandLineArgs(createArgs("-zkSvr " + ZK_ADDR + " -addNode " + clusterName
        + " localhost:8902"));
    ClusterSetup.processCommandLineArgs(createArgs("-zkSvr " + ZK_ADDR + " -addNode " + clusterName
        + " localhost:8903"));
    ClusterSetup.processCommandLineArgs(createArgs("-zkSvr " + ZK_ADDR + " -addNode " + clusterName
        + " localhost:8904"));
    ClusterSetup.processCommandLineArgs(createArgs("-zkSvr " + ZK_ADDR + " -rebalance "
        + clusterName + " db-12345 3"));
  }

  @AfterClass()
  public void afterClass()
  {
    _zkClient.close();
  }

}
