package com.linkedin.helix.integration;

import java.util.Date;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.helix.TestHelper;
import com.linkedin.helix.controller.HelixControllerMain;
import com.linkedin.helix.mock.storage.MockParticipant;
import com.linkedin.helix.tools.ClusterStateVerifier;

public class TestSchemataSM extends ZkIntegrationTestBase
{
  @Test
  public void testSchemataSM() throws Exception
  {
    String testName = "TestSchemataSM";
    String clusterName = testName;

    MockParticipant[] participants = new MockParticipant[5];
//    Logger.getRootLogger().setLevel(Level.INFO);

    System.out.println("START " + testName + " at " + new Date(System.currentTimeMillis()));

    TestHelper.setupCluster(clusterName, ZK_ADDR, 12918, // participant start
                                                         // port
                            "localhost", // participant name prefix
                            "TestSchemata", // resource name prefix
                            1, // resources
                            10, // partitions per resource
                            5, // number of nodes
                            1, // replicas
                            "STORAGE_DEFAULT_SM_SCHEMATA",
                            true); // do rebalance

    TestHelper.startController(clusterName,
                               "controller_0",
                               ZK_ADDR,
                               HelixControllerMain.STANDALONE);
    // start participants
    for (int i = 0; i < 5; i++)
    {
      String instanceName = "localhost_" + (12918 + i);

      participants[i] =
          new MockParticipant(clusterName,
                              instanceName,
                              ZK_ADDR,
                              null);
      new Thread(participants[i]).start();
    }

    boolean result =
        ClusterStateVerifier.verify(new ClusterStateVerifier.BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                          clusterName));
    Assert.assertTrue(result);
    for (int i = 0; i < 5; i++)
    {
      participants[i].stop();
    }

    System.out.println("END " + testName + " at " + new Date(System.currentTimeMillis()));
  }
}