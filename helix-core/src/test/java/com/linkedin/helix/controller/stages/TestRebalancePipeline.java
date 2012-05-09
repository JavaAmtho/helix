package com.linkedin.helix.controller.stages;

import java.util.Date;
import java.util.List;

import org.apache.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.helix.DataAccessor;
import com.linkedin.helix.HelixAdmin;
import com.linkedin.helix.HelixManager;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.TestHelper;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.ZkUnitTestBase;
import com.linkedin.helix.controller.HelixControllerMain;
import com.linkedin.helix.controller.pipeline.Pipeline;
import com.linkedin.helix.manager.zk.ZKDataAccessor;
import com.linkedin.helix.manager.zk.ZKHelixAdmin;
import com.linkedin.helix.model.CurrentState;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.model.Message.Attributes;
import com.linkedin.helix.model.Partition;

public class TestRebalancePipeline extends ZkUnitTestBase
{
  private static final Logger LOG =
      Logger.getLogger(TestRebalancePipeline.class.getName());
  final String _className = getShortClassName();

  @Test
  public void testDuplicateMsg()
  {
    String clusterName = "CLUSTER_" + _className + "_dup";
    System.out.println("START " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

    DataAccessor accessor = new ZKDataAccessor(clusterName, _gZkClient);
    HelixManager manager = new DummyClusterManager(clusterName, accessor);
    ClusterEvent event = new ClusterEvent("testEvent");
    event.addAttribute("helixmanager", manager);

    final String resourceName = "testResource_dup";
    String[] resourceGroups = new String[] { resourceName };
    // ideal state: node0 is MASTER, node1 is SLAVE
    // replica=2 means 1 master and 1 slave
    setupIdealState(clusterName, new int[] { 0, 1 }, resourceGroups, 1, 2);
    setupLiveInstances(clusterName, new int[] { 0, 1 });
    setupStateModel(clusterName);

    // cluster data cache refresh pipeline
    Pipeline dataRefresh = new Pipeline();
    dataRefresh.addStage(new ReadClusterDataStage());

    // rebalance pipeline
    Pipeline rebalancePipeline = new Pipeline();
    rebalancePipeline.addStage(new ResourceComputationStage());
    rebalancePipeline.addStage(new CurrentStateComputationStage());
    rebalancePipeline.addStage(new BestPossibleStateCalcStage());
    rebalancePipeline.addStage(new MessageGenerationPhase());
    rebalancePipeline.addStage(new MessageSelectionStage());
    rebalancePipeline.addStage(new TaskAssignmentStage());

    // round1: set node0 currentState to OFFLINE and node1 currentState to SLAVE
    setCurrentState(clusterName,
                    "localhost_0",
                    resourceName,
                    resourceName + "_0",
                    "session_0",
                    "OFFLINE");
    setCurrentState(clusterName,
                    "localhost_1",
                    resourceName,
                    resourceName + "_0",
                    "session_1",
                    "SLAVE");

    runPipeline(event, dataRefresh);
    runPipeline(event, rebalancePipeline);
    MessageSelectionStageOutput msgSelOutput =
        event.getAttribute(AttributeName.MESSAGES_SELECTED.toString());
    List<Message> messages =
        msgSelOutput.getMessages(resourceName, new Partition(resourceName
            + "_0"));
    Assert.assertEquals(messages.size(),
                        1,
                        "Should output 1 message: OFFLINE-SLAVE for node0");
    Message message = messages.get(0);
    Assert.assertEquals(message.getFromState(), "OFFLINE");
    Assert.assertEquals(message.getToState(), "SLAVE");
    Assert.assertEquals(message.getTgtName(), "localhost_0");

    // round2: updates node0 currentState to SLAVE but keep the
    // message, make sure controller should not send S->M until removal is done
    setCurrentState(clusterName,
                    "localhost_0",
                    resourceName,
                    resourceName + "_0",
                    "session_1",
                    "SLAVE");

    runPipeline(event, dataRefresh);
    runPipeline(event, rebalancePipeline);
    msgSelOutput = event.getAttribute(AttributeName.MESSAGES_SELECTED.toString());
    messages = msgSelOutput.getMessages(resourceName, new Partition(resourceName + "_0"));
    Assert.assertEquals(messages.size(),
                        0,
                        "Should NOT output 1 message: SLAVE-MASTER for node1");

    System.out.println("END " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

  }

  @Test
  public void testMsgTriggeredRebalance() throws Exception
  {
    String clusterName = "CLUSTER_" + _className + "_msgTrigger";
    System.out.println("START " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

    DataAccessor accessor = new ZKDataAccessor(clusterName, _gZkClient);
    HelixManager manager = new DummyClusterManager(clusterName, accessor);
    ClusterEvent event = new ClusterEvent("testEvent");

    final String resourceName = "testResource_dup";
    String[] resourceGroups = new String[] { resourceName };

    TestHelper.setupEmptyCluster(_gZkClient, clusterName);

    // ideal state: node0 is MASTER, node1 is SLAVE
    // replica=2 means 1 master and 1 slave
    setupIdealState(clusterName, new int[] { 0, 1 }, resourceGroups, 1, 2);
    setupStateModel(clusterName);
    setupInstances(clusterName, new int[]{0,1});
    setupLiveInstances(clusterName, new int[] { 0, 1 });

    TestHelper.startController(clusterName, "controller_0", ZK_ADDR, HelixControllerMain.STANDALONE);


    // round1: controller sends O->S to both node0 and node1
    Thread.sleep(1000);
    List<String> messages = accessor.getChildNames(PropertyType.MESSAGES, "localhost_0");
    Assert.assertEquals(messages.size(), 1);
    messages = accessor.getChildNames(PropertyType.MESSAGES, "localhost_1");
    Assert.assertEquals(messages.size(), 1);

    // round2: node0 and node1 update current states but not removing messages
    // controller's rebalance pipeline should be triggered but since messages are not removed
    // no new messages will be sent
    setCurrentState(clusterName,
                    "localhost_0",
                    resourceName,
                    resourceName + "_0",
                    "session_0",
                    "SLAVE");
    setCurrentState(clusterName,
                    "localhost_1",
                    resourceName,
                    resourceName + "_0",
                    "session_1",
                    "SLAVE");
    Thread.sleep(1000);
    messages = accessor.getChildNames(PropertyType.MESSAGES, "localhost_0");
    Assert.assertEquals(messages.size(), 1);
    messages = accessor.getChildNames(PropertyType.MESSAGES, "localhost_1");
    Assert.assertEquals(messages.size(), 1);

    // round3: node0 removes message and controller's rebalance pipeline should be triggered
    //  and sends S->M to node0
    messages = accessor.getChildNames(PropertyType.MESSAGES, "localhost_0");
    accessor.removeProperty(PropertyType.MESSAGES, "localhost_0", messages.get(0));
    Thread.sleep(1000);
    messages = accessor.getChildNames(PropertyType.MESSAGES, "localhost_0");
    Assert.assertEquals(messages.size(), 1);
    ZNRecord msg = accessor.getProperty(PropertyType.MESSAGES, "localhost_0", messages.get(0));
    String toState = msg.getSimpleField(Attributes.TO_STATE.toString());
    Assert.assertEquals(toState, "MASTER");

    System.out.println("END " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

  }

  @Test
  public void testChangeIdealStateWithPendingMsg()
  {
    String clusterName = "CLUSTER_" + _className + "_pending";
    System.out.println("START " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

    DataAccessor accessor = new ZKDataAccessor(clusterName, _gZkClient);
    HelixManager manager = new DummyClusterManager(clusterName, accessor);
    ClusterEvent event = new ClusterEvent("testEvent");
    event.addAttribute("helixmanager", manager);

    final String resourceName = "testResource_pending";
    String[] resourceGroups = new String[] { resourceName };
    // ideal state: node0 is MASTER, node1 is SLAVE
    // replica=2 means 1 master and 1 slave
    setupIdealState(clusterName, new int[] { 0, 1 }, resourceGroups, 1, 2);
    setupLiveInstances(clusterName, new int[] { 0, 1 });
    setupStateModel(clusterName);

    // cluster data cache refresh pipeline
    Pipeline dataRefresh = new Pipeline();
    dataRefresh.addStage(new ReadClusterDataStage());

    // rebalance pipeline
    Pipeline rebalancePipeline = new Pipeline();
    rebalancePipeline.addStage(new ResourceComputationStage());
    rebalancePipeline.addStage(new CurrentStateComputationStage());
    rebalancePipeline.addStage(new BestPossibleStateCalcStage());
    rebalancePipeline.addStage(new MessageGenerationPhase());
    rebalancePipeline.addStage(new MessageSelectionStage());
    rebalancePipeline.addStage(new TaskAssignmentStage());

    // round1: set node0 currentState to OFFLINE and node1 currentState to SLAVE
    setCurrentState(clusterName,
                    "localhost_0",
                    resourceName,
                    resourceName + "_0",
                    "session_0",
                    "OFFLINE");
    setCurrentState(clusterName,
                    "localhost_1",
                    resourceName,
                    resourceName + "_0",
                    "session_1",
                    "SLAVE");

    runPipeline(event, dataRefresh);
    runPipeline(event, rebalancePipeline);
    MessageSelectionStageOutput msgSelOutput =
        event.getAttribute(AttributeName.MESSAGES_SELECTED.toString());
    List<Message> messages =
        msgSelOutput.getMessages(resourceName, new Partition(resourceName
            + "_0"));
    Assert.assertEquals(messages.size(),
                        1,
                        "Should output 1 message: OFFLINE-SLAVE for node0");
    Message message = messages.get(0);
    Assert.assertEquals(message.getFromState(), "OFFLINE");
    Assert.assertEquals(message.getToState(), "SLAVE");
    Assert.assertEquals(message.getTgtName(), "localhost_0");

    // round2: drop resource, but keep the
    // message, make sure controller should not send O->DROPPEDN until O->S is done
    HelixAdmin admin = new ZKHelixAdmin(_gZkClient);
    admin.dropResource(clusterName, resourceName);
    
    runPipeline(event, dataRefresh);
    runPipeline(event, rebalancePipeline);
    msgSelOutput = event.getAttribute(AttributeName.MESSAGES_SELECTED.toString());
    messages = msgSelOutput.getMessages(resourceName, new Partition(resourceName + "_0"));
    Assert.assertEquals(messages.size(),
                        1,
                        "Should output only 1 message: OFFLINE->DROPPED for localhost_1");

    message = messages.get(0);
    Assert.assertEquals(message.getFromState(), "SLAVE");
    Assert.assertEquals(message.getToState(), "OFFLINE");
    Assert.assertEquals(message.getTgtName(), "localhost_1");

    // round3: remove O->S for localhost_0, controller should now send O->DROPPED to localhost_0
    List<String> msgIds = accessor.getChildNames(PropertyType.MESSAGES, "localhost_0");
    accessor.removeProperty(PropertyType.MESSAGES, "localhost_0", msgIds.get(0));
    runPipeline(event, dataRefresh);
    runPipeline(event, rebalancePipeline);
    msgSelOutput = event.getAttribute(AttributeName.MESSAGES_SELECTED.toString());
    messages = msgSelOutput.getMessages(resourceName, new Partition(resourceName + "_0"));
    Assert.assertEquals(messages.size(),
                        1,
                        "Should output 1 message: OFFLINE->DROPPED for localhost_0");
    message = messages.get(0);
    Assert.assertEquals(message.getFromState(), "OFFLINE");
    Assert.assertEquals(message.getToState(), "DROPPED");
    Assert.assertEquals(message.getTgtName(), "localhost_0");

    
    System.out.println("END " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

  }
  
  protected void setCurrentState(String clusterName,
                                 String instance,
                                 String resourceGroupName,
                                 String resourceKey,
                                 String sessionId,
                                 String state)
  {
    DataAccessor accessor = new ZKDataAccessor(clusterName, _gZkClient);
    CurrentState curState = new CurrentState(resourceGroupName);
    curState.setState(resourceKey, state);
    curState.setSessionId(sessionId);
    curState.setStateModelDefRef("MasterSlave");
    accessor.setProperty(PropertyType.CURRENTSTATES,
                          curState,
                          instance,
                          sessionId,
                          resourceGroupName);
  }
}