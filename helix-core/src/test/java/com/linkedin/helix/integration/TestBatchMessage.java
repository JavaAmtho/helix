package com.linkedin.helix.integration;

import java.util.Date;
import java.util.List;

import org.I0Itec.zkclient.IZkChildListener;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.helix.PropertyKey.Builder;
import com.linkedin.helix.TestHelper;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.manager.zk.ZKHelixDataAccessor;
import com.linkedin.helix.manager.zk.ZkBaseDataAccessor;
import com.linkedin.helix.mock.controller.ClusterController;
import com.linkedin.helix.mock.storage.MockParticipant;
import com.linkedin.helix.model.IdealState;
import com.linkedin.helix.tools.ClusterStateVerifier;
import com.linkedin.helix.tools.ClusterStateVerifier.BestPossAndExtViewZkVerifier;

public class TestBatchMessage extends ZkIntegrationTestBase
{
  class TestZkChildListener implements IZkChildListener
  {
    int _maxNbOfChilds = 0;
    
    @Override
    public void handleChildChange(String parentPath, List<String> currentChilds) throws Exception
    {
      System.out.println(parentPath + " has " + currentChilds.size() + " messages");
      if (currentChilds.size() > _maxNbOfChilds)
      {
        _maxNbOfChilds = currentChilds.size();
      }
    }
  }
  
  @Test
  public void testBasic() throws Exception
  {
    // Logger.getRootLogger().setLevel(Level.INFO);
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;
    int n = 2;

    System.out.println("START " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

    TestHelper.setupCluster(clusterName, ZK_ADDR, 12918, // participant port
                            "localhost", // participant name prefix
                            "TestDB", // resource name prefix
                            1, // resources
                            32, // partitions per resource
                            n, // number of nodes
                            2, // replicas
                            "MasterSlave",
                            true); // do rebalance
    
    // enable group message
    ZKHelixDataAccessor accessor = new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_gZkClient));
    Builder keyBuilder = accessor.keyBuilder();
    IdealState idealState = accessor.getProperty(keyBuilder.idealStates("TestDB0"));
    idealState.setGroupMessageMode(true);
    accessor.setProperty(keyBuilder.idealStates("TestDB0"), idealState);

    // registry a message listener so we know how many message generated
    TestZkChildListener listener = new TestZkChildListener();
    _gZkClient.subscribeChildChanges(keyBuilder.messages("localhost_12918").getPath(), listener);

    
    ClusterController controller =
        new ClusterController(clusterName, "controller_0", ZK_ADDR);
    controller.syncStart();

    // start participants
    MockParticipant[] participants = new MockParticipant[n];
    for (int i = 0; i < n; i++)
    {
      String instanceName = "localhost_" + (12918 + i);

      participants[i] = new MockParticipant(clusterName, instanceName, ZK_ADDR);
      participants[i].syncStart();
    }

    boolean result =
        ClusterStateVerifier.verifyByZkCallback(new BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                 clusterName));
    Assert.assertTrue(result);
    Assert.assertTrue(listener._maxNbOfChilds <= 2, "Should get no more than 2 messages (O->S and S->M)");
    
    // clean up
    // wait for all zk callbacks done
    Thread.sleep(1000);
    controller.syncStop();
    for (int i = 0; i < n; i++)
    {
      participants[i].syncStop();
    }

    System.out.println("END " + clusterName + " at "
        + new Date(System.currentTimeMillis()));
  }
  
  // a non-group-message run followed by a group-message-enabled run
  @Test
  public void testChangeBatchMessageMode() throws Exception
  {
    // Logger.getRootLogger().setLevel(Level.INFO);
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;
    int n = 2;

    System.out.println("START " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

    TestHelper.setupCluster(clusterName, ZK_ADDR, 12918, // participant port
                            "localhost", // participant name prefix
                            "TestDB", // resource name prefix
                            1, // resources
                            32, // partitions per resource
                            n, // number of nodes
                            2, // replicas
                            "MasterSlave",
                            true); // do rebalance
    
    ClusterController controller =
        new ClusterController(clusterName, "controller_0", ZK_ADDR);
    controller.syncStart();

    // start participants
    MockParticipant[] participants = new MockParticipant[n];
    for (int i = 0; i < n; i++)
    {
      String instanceName = "localhost_" + (12918 + i);

      participants[i] = new MockParticipant(clusterName, instanceName, ZK_ADDR);
      participants[i].syncStart();
    }

    boolean result =
        ClusterStateVerifier.verifyByZkCallback(new BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                 clusterName));
    Assert.assertTrue(result);
    
    // stop all participants
    Thread.sleep(1000);
    for (int i = 0; i < n; i++)
    {
      participants[i].syncStop();
    }
    
    // enable batch message
    ZKHelixDataAccessor accessor = new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_gZkClient));
    Builder keyBuilder = accessor.keyBuilder();
    IdealState idealState = accessor.getProperty(keyBuilder.idealStates("TestDB0"));
    idealState.setGroupMessageMode(true);
    accessor.setProperty(keyBuilder.idealStates("TestDB0"), idealState);

    // registry a message listener so we know how many message generated
    TestZkChildListener listener = new TestZkChildListener();
    _gZkClient.subscribeChildChanges(keyBuilder.messages("localhost_12918").getPath(), listener);

    // restart all participants
    for (int i = 0; i < n; i++)
    {
      String instanceName = "localhost_" + (12918 + i);

      participants[i] = new MockParticipant(clusterName, instanceName, ZK_ADDR);
      participants[i].syncStart();
    }
    
    result =
        ClusterStateVerifier.verifyByZkCallback(new BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                 clusterName));
    Assert.assertTrue(result);
    Assert.assertTrue(listener._maxNbOfChilds <= 2, "Should get no more than 2 messages (O->S and S->M)");

    
    // clean up
    // wait for all zk callbacks done
    Thread.sleep(1000);
    controller.syncStop();
    for (int i = 0; i < n; i++)
    {
      participants[i].syncStop();
    }

    System.out.println("END " + clusterName + " at "
        + new Date(System.currentTimeMillis()));
  }
}
