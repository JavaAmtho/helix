package com.linkedin.helix.integration;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.helix.TestHelper;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.PropertyKey.Builder;
import com.linkedin.helix.manager.zk.ZKHelixDataAccessor;
import com.linkedin.helix.manager.zk.ZkBaseDataAccessor;
import com.linkedin.helix.mock.controller.ClusterController;
import com.linkedin.helix.mock.storage.MockMSModelFactory;
import com.linkedin.helix.mock.storage.MockParticipant;
import com.linkedin.helix.mock.storage.MockParticipant.ErrTransition;
import com.linkedin.helix.model.ExternalView;
import com.linkedin.helix.tools.ClusterSetup;
import com.linkedin.helix.tools.ClusterStateVerifier;
import com.linkedin.helix.tools.ClusterStateVerifier.BestPossAndExtViewZkVerifier;

public class TestDrop extends ZkIntegrationTestBase
{
  @Test
  public void testDropErrorPartition() throws Exception
  {
    // Logger.getRootLogger().setLevel(Level.INFO);
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;
    final int n = 5;

    System.out.println("START " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

    MockParticipant[] participants = new MockParticipant[n];

    TestHelper.setupCluster(clusterName, ZK_ADDR, 12918, // participant port
                            "localhost", // participant name prefix
                            "TestDB", // resource name prefix
                            1, // resources
                            10, // partitions per resource
                            n, // number of nodes
                            3, // replicas
                            "MasterSlave",
                            true); // do rebalance

    // start controller
    ClusterController controller =
        new ClusterController(clusterName, "controller_0", ZK_ADDR);
    controller.syncStart();
    
    // start participants
    Map<String, Set<String>> errTransitions = new HashMap<String, Set<String>>();
    errTransitions.put("SLAVE-MASTER", TestHelper.setOf("TestDB0_4"));
    errTransitions.put("OFFLINE-SLAVE", TestHelper.setOf("TestDB0_8"));

    for (int i = 0; i < n; i++)
    {
      String instanceName = "localhost_" + (12918 + i);

      if (i == 0)
      {
        participants[i] =
            new MockParticipant(clusterName,
                                instanceName,
                                ZK_ADDR,
                                new MockMSModelFactory(new ErrTransition(errTransitions)));
      }
      else
      {
        participants[i] = new MockParticipant(clusterName, instanceName, ZK_ADDR, null);
      }
      participants[i].syncStart();
    }

    Map<String, Map<String, String>> errStateMap =
        new HashMap<String, Map<String, String>>();
    errStateMap.put("TestDB0", new HashMap<String, String>());
    errStateMap.get("TestDB0").put("TestDB0_4", "localhost_12918");
    errStateMap.get("TestDB0").put("TestDB0_8", "localhost_12918");
    boolean result =
        ClusterStateVerifier.verifyByZkCallback(new BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                 clusterName,
                                                                                 errStateMap));
    Assert.assertTrue(result);
    
    // drop resource containing error partitions should not change partitions in error state
    ClusterSetup.processCommandLineArgs(new String[]{"--zkSvr", ZK_ADDR, "--dropResource", clusterName, "TestDB0"});


    // make sure TestDB0_4 and TestDB0_8 partitions stay in ERROR state
    result =
        ClusterStateVerifier.verifyByZkCallback(new BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                 clusterName,
                                                                                 errStateMap));
    Assert.assertTrue(result);
    
    // clean up
    // wait for all zk callbacks done
    Thread.sleep(1000);
    controller.syncStop();
    for (int i = 0; i < 5; i++)
    {
      participants[i].syncStop();
    }

    System.out.println("END " + clusterName + " at "
        + new Date(System.currentTimeMillis())); 
  }
  
  @Test
  public void testDropSchemataResource() throws Exception
  {
    // Logger.getRootLogger().setLevel(Level.INFO);
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;
    final int n = 5;

    System.out.println("START " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

    MockParticipant[] participants = new MockParticipant[n];

    TestHelper.setupCluster(clusterName, ZK_ADDR, 12918, // participant port
                            "localhost", // participant name prefix
                            "TestDB", // resource name prefix
                            1, // resources
                            8, // partitions per resource
                            n, // number of nodes
                            3, // replicas
                            "MasterSlave",
                            true); // do rebalance

    // start controller
    ClusterController controller =
        new ClusterController(clusterName, "controller_0", ZK_ADDR);
    controller.syncStart();
    
    // start participants
    for (int i = 0; i < n; i++)
    {
      String instanceName = "localhost_" + (12918 + i);

      participants[i] = new MockParticipant(clusterName, instanceName, ZK_ADDR, null);
      participants[i].syncStart();
    }

    boolean result =
        ClusterStateVerifier.verifyByZkCallback(new BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                 clusterName));
    Assert.assertTrue(result);

    // add schemata resource group
    String command = "--zkSvr " + ZK_ADDR +" --addResource " + clusterName + 
        " schemata 1 STORAGE_DEFAULT_SM_SCHEMATA";
    ClusterSetup.processCommandLineArgs(command.split("\\s+"));
    command = "--zkSvr " + ZK_ADDR +" --rebalance " + clusterName + 
        " schemata 1";
    ClusterSetup.processCommandLineArgs(command.split("\\s+"));
    
    result =
        ClusterStateVerifier.verifyByZkCallback(new BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                 clusterName));
    Assert.assertTrue(result);
    
    // drop schemata resource group
    System.out.println("Dropping schemata resource group...");
    command = "--zkSvr " + ZK_ADDR +" --dropResource " + clusterName + 
        " schemata";
    ClusterSetup.processCommandLineArgs(command.split("\\s+"));
    result =
        ClusterStateVerifier.verifyByZkCallback(new BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                 clusterName));
    Assert.assertTrue(result);

    // make sure schemata external view is empty
    ZkBaseDataAccessor<ZNRecord> baseAccessor =
        new ZkBaseDataAccessor<ZNRecord>(_gZkClient);
    ZKHelixDataAccessor accessor = new ZKHelixDataAccessor(clusterName, baseAccessor);
    Builder keyBuilder = accessor.keyBuilder();
    ExternalView extView = accessor.getProperty(keyBuilder.externalView("schemata"));
    Assert.assertEquals(extView.getPartitionSet().size(), 0, "schemata externalView should be empty but was \"" + extView + "\"");
    
    // clean up
    // wait for all zk callbacks done
    Thread.sleep(1000);
    controller.syncStop();
    for (int i = 0; i < 5; i++)
    {
      participants[i].syncStop();
    }

    System.out.println("END " + clusterName + " at "
        + new Date(System.currentTimeMillis())); 
  }
}
