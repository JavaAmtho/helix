package com.linkedin.helix.integration;

import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.helix.NotificationContext;
import com.linkedin.helix.TestHelper;
import com.linkedin.helix.controller.HelixControllerMain;
import com.linkedin.helix.mock.storage.MockMSModelFactory;
import com.linkedin.helix.mock.storage.MockParticipant;
import com.linkedin.helix.mock.storage.MockTransition;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.tools.ClusterStateVerifier;
import com.linkedin.helix.tools.ClusterStateVerifier.BestPossAndExtViewZkVerifier;

public class TestRestartParticipant extends ZkIntegrationTestBase
{
  public class KillOtherTransition extends MockTransition
  {
    final AtomicReference<MockParticipant> _other;

    public KillOtherTransition(MockParticipant other)
    {
      _other = new AtomicReference<MockParticipant>(other);
    }

    @Override
    public void doTransition(Message message, NotificationContext context)
    {
      MockParticipant other = _other.getAndSet(null);
      if (other != null)
      {
        System.err.println("Kill " + other.getInstanceName()
            + ". Interrupted exceptions are IGNORABLE");
        other.syncStop();
      }
    }
  }

  @Test()
  public void testRestartParticipant() throws Exception
  {
    // Logger.getRootLogger().setLevel(Level.INFO);
    System.out.println("START testRestartParticipant at "
        + new Date(System.currentTimeMillis()));

    String clusterName = getShortClassName();
    MockParticipant[] participants = new MockParticipant[5];

    TestHelper.setupCluster(clusterName, ZK_ADDR, 12918, // participant port
                            "localhost", // participant name prefix
                            "TestDB", // resource name prefix
                            1, // resources
                            10, // partitions per resource
                            5, // number of nodes
                            3, // replicas
                            "MasterSlave",
                            true); // do rebalance

    TestHelper.startController(clusterName,
                               "controller_0",
                               ZK_ADDR,
                               HelixControllerMain.STANDALONE);
    // start participants
    for (int i = 0; i < 5; i++)
    {
      String instanceName = "localhost_" + (12918 + i);

      if (i == 4)
      {
        participants[i] =
            new MockParticipant(clusterName,
                                instanceName,
                                ZK_ADDR,
                                new MockMSModelFactory(new KillOtherTransition(participants[0])));
      }
      else
      {
        participants[i] =
            new MockParticipant(clusterName,
                                instanceName,
                                ZK_ADDR,
                                new MockMSModelFactory());
//        Thread.sleep(100);
      }

      participants[i].syncStart();
    }

    boolean result =
        ClusterStateVerifier.verifyByZkCallback(new BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                 clusterName));
    Assert.assertTrue(result);

    // restart
    Thread.sleep(500);
    MockParticipant participant =
        new MockParticipant(participants[0].getClusterName(),
                            participants[0].getInstanceName(),
                            ZK_ADDR,
                            null);
    System.err.println("Restart " + participant.getInstanceName());
    participant.syncStart();
    result =
        ClusterStateVerifier.verifyByZkCallback(new BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                 clusterName));
    Assert.assertTrue(result);

    System.out.println("START testRestartParticipant at "
        + new Date(System.currentTimeMillis()));

  }
}
