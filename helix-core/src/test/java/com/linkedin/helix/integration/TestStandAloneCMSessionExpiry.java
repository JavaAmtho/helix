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
package com.linkedin.helix.integration;

import java.util.Date;

import org.apache.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.helix.InstanceType;
import com.linkedin.helix.TestHelper;
import com.linkedin.helix.ZkTestHelper;
import com.linkedin.helix.ZkTestHelper.TestZkHelixManager;
import com.linkedin.helix.mock.storage.MockMSModelFactory;
import com.linkedin.helix.mock.storage.MockParticipant;
import com.linkedin.helix.tools.ClusterSetup;
import com.linkedin.helix.tools.ClusterStateVerifier;

public class TestStandAloneCMSessionExpiry extends ZkIntegrationTestBase
{
  private static Logger LOG = Logger.getLogger(TestStandAloneCMSessionExpiry.class);

  @Test()
  public void testStandAloneCMSessionExpiry() throws Exception
  {
    // Logger.getRootLogger().setLevel(Level.DEBUG);
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;

    System.out.println("START " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

    TestHelper.setupCluster(clusterName,
                            ZK_ADDR,
                            12918,
                            PARTICIPANT_PREFIX,
                            "TestDB",
                            1,
                            20,
                            5,
                            3,
                            "MasterSlave",
                            true);

    MockParticipant[] participants = new MockParticipant[5];
    for (int i = 0; i < 5; i++)
    {
      String instanceName = "localhost_" + (12918 + i);
      TestZkHelixManager manager =
          new TestZkHelixManager(clusterName,
                                 instanceName,
                                 InstanceType.PARTICIPANT,
                                 ZK_ADDR);
      participants[i] = new MockParticipant(manager, new MockMSModelFactory(), null);
      participants[i].syncStart();
    }

    TestZkHelixManager controller =
        new TestZkHelixManager(clusterName,
                               "controller_0",
                               InstanceType.CONTROLLER,
                               ZK_ADDR);
    controller.connect();

    boolean result;
    result =
        ClusterStateVerifier.verifyByPolling(new ClusterStateVerifier.BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                                   clusterName));
    Assert.assertTrue(result);

    // participant session expiry
    TestZkHelixManager participantToExpire = (TestZkHelixManager)participants[1].getManager();

    System.out.println("Expire participant session");
    String oldSessionId = participantToExpire.getSessionId();
    
    ZkTestHelper.expireSession(participantToExpire.getZkClient());
    String newSessionId = participantToExpire.getSessionId();
    System.out.println("oldSessionId: " + oldSessionId + ", newSessionId: " + newSessionId);
    Assert.assertTrue(newSessionId.compareTo(oldSessionId) > 0, "Session id should be increased after expiry");

    ClusterSetup setupTool = new ClusterSetup(ZK_ADDR);
    setupTool.addResourceToCluster(clusterName, "TestDB1", 10, "MasterSlave");
    setupTool.rebalanceStorageCluster(clusterName, "TestDB1", 3);

    result =
        ClusterStateVerifier.verifyByPolling(new ClusterStateVerifier.BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                                   clusterName));
    Assert.assertTrue(result);

    // controller session expiry
    System.out.println("Expire controller session");
    oldSessionId = controller.getSessionId();
    ZkTestHelper.expireSession(controller.getZkClient());
    newSessionId = controller.getSessionId();
    System.out.println("oldSessionId: " + oldSessionId + ", newSessionId: " + newSessionId);
    Assert.assertTrue(newSessionId.compareTo(oldSessionId) > 0, "Session id should be increased after expiry");

    setupTool.addResourceToCluster(clusterName, "TestDB2", 8, "MasterSlave");
    setupTool.rebalanceStorageCluster(clusterName, "TestDB2", 3);

    result =
        ClusterStateVerifier.verifyByPolling(new ClusterStateVerifier.BestPossAndExtViewZkVerifier(ZK_ADDR,
                                                                                                   clusterName));
    Assert.assertTrue(result);

    // clean up
    System.out.println("Clean up ...");
    // Logger.getRootLogger().setLevel(Level.DEBUG);
    controller.disconnect();
    Thread.sleep(100);
    for (int i = 0; i < 5; i++)
    {
      participants[i].syncStop();
    }

    System.out.println("END " + clusterName + " at "
        + new Date(System.currentTimeMillis()));

  }

}
