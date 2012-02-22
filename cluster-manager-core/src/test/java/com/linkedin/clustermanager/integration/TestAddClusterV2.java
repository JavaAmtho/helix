package com.linkedin.clustermanager.integration;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.linkedin.clustermanager.ClusterManager;
import com.linkedin.clustermanager.TestHelper;
import com.linkedin.clustermanager.TestHelper.StartCMResult;
import com.linkedin.clustermanager.agent.zk.ZNRecordSerializer;
import com.linkedin.clustermanager.agent.zk.ZkClient;
import com.linkedin.clustermanager.controller.ClusterManagerMain;
import com.linkedin.clustermanager.tools.ClusterSetup;

public class TestAddClusterV2 extends ZkIntegrationTestBase
{
  private static Logger logger = Logger.getLogger(TestAddClusterV2.class);

  protected static final int CLUSTER_NR = 4;
  protected static final int NODE_NR = 5;
  protected static final int START_PORT = 12918;
  protected static final String STATE_MODEL = "MasterSlave";
  protected ClusterSetup _setupTool = null;
  protected Map<String, Thread> _threadMap = new HashMap<String, Thread>();
  protected Map<String, ClusterManager> _managerMap = new HashMap<String, ClusterManager>();
  protected final String CLASS_NAME = getShortClassName();
  protected final String CONTROLLER_CLUSTER = CONTROLLER_CLUSTER_PREFIX + "_" + CLASS_NAME;
  protected final String CONTROLLER_PREFIX = "controller";
  protected final String PARTICIPANT_PREFIX = "localhost";
  
  private static final String TEST_DB = "TestDB";
  ZkClient _zkClient;
  
  @BeforeClass
  public void beforeClass() throws Exception
  {
    // logger.info("START " + CLASS_NAME + " at " + new Date(System.currentTimeMillis()));
    System.out.println("START " + CLASS_NAME + " at " + new Date(System.currentTimeMillis()));
    _zkClient = new ZkClient(ZK_ADDR);
    _zkClient.setZkSerializer(new ZNRecordSerializer());
    
    String namespace = "/" + CONTROLLER_CLUSTER; 
    if (_zkClient.exists(namespace))
    {
      _zkClient.deleteRecursive(namespace);
    }
    for (int i = 0; i < CLUSTER_NR; i++)
    {
      namespace = "/" + CLUSTER_PREFIX + "_" + CLASS_NAME + "_" + i;
      if (_zkClient.exists(namespace))
      {
        _zkClient.deleteRecursive(namespace);
      }
    }
    
    _setupTool = new ClusterSetup(ZK_ADDR);

    // setup cluster of ESPRESSO_STORAGE clusters
   
    // setup CONTROLLER_CLUSTER
    _setupTool.addCluster(CONTROLLER_CLUSTER, true);
    setupStorageCluster(_setupTool, CONTROLLER_CLUSTER, 
          CLUSTER_PREFIX + "_" + CLASS_NAME, CLUSTER_NR, CONTROLLER_PREFIX, 0, "LeaderStandby", false);
     
    for (int i = 0; i < CLUSTER_NR; i++)
    {
      String clusterName = CLUSTER_PREFIX + "_" + CLASS_NAME + "_" + i;
      _setupTool.addCluster(clusterName, true, CONTROLLER_CLUSTER);
    }
    
    final String firstCluster = CLUSTER_PREFIX + "_" + CLASS_NAME + "_0";
    setupStorageCluster(_setupTool, firstCluster, TEST_DB, 20, PARTICIPANT_PREFIX, START_PORT, STATE_MODEL, true);
    
    // start dummy participants for the first ESPRESSO_STORAGE cluster
    Thread thread;
    for (int i = 0; i < NODE_NR; i++)
    {
      String instanceName = PARTICIPANT_PREFIX + "_" + (START_PORT + i);
      if (_threadMap.get(instanceName) != null)
      {
        logger.error("fail to start participant:" + instanceName + 
          " because there is already a thread with same instanceName running");
      }
      else
      {
        StartCMResult result = TestHelper.startDummyProcess(ZK_ADDR, firstCluster, instanceName, null);
        thread = result._thread;
        _threadMap.put(instanceName, thread);
        _managerMap.put(instanceName, result._manager);
      }
    }

    // start distributed cluster controllers
    for (int i = 0; i < NODE_NR; i++)
    {
      String controllerName = CONTROLLER_PREFIX + "_" + i;
      if (_threadMap.get(controllerName) != null)
      {
        logger.error("fail to start controller:" + controllerName + 
          " because there is already a thread with same controllerName running");
      }
      else
      {
        StartCMResult result = TestHelper.startClusterController(CONTROLLER_CLUSTER, controllerName, ZK_ADDR, 
           ClusterManagerMain.DISTRIBUTED, null);
        _threadMap.put(controllerName, result._thread);
        _managerMap.put(controllerName, result._manager);
      }
    }

    // Thread.sleep(2000);
    
    List<String> clusterNames = new ArrayList<String>();
    clusterNames.add(CONTROLLER_CLUSTER);
    clusterNames.add(firstCluster);
    verifyIdealAndCurrentStateTimeout(clusterNames);
  }
  
  @Test
  public void Test()
  {}
  
  @AfterClass
  public void afterClass() throws Exception
  {
    // logger.info("END at " + new Date(System.currentTimeMillis()));
    System.out.println("Shutting down " + CLASS_NAME + " at " + new Date(System.currentTimeMillis()));
    
    // _setupTool.dropResourceGroupToCluster(CONTROLLER_CLUSTER, CLUSTER_PREFIX + "_" + CLASS_NAME);
    // Thread.sleep(10000);
    
    /*
    List<String> instanceNames = new ArrayList<String>();
    for (String instance : _threadMap.keySet())
    {
      if (instance.startsWith(CONTROLLER_PREFIX))
      {
        instanceNames.add(instance);
      }
    }
    verifyEmtpyCurrentStateTimeout(CONTROLLER_CLUSTER, CLUSTER_PREFIX + "_" + CLASS_NAME, instanceNames);
    */
    String leader = getCurrentLeader(_zkClient, CONTROLLER_CLUSTER);
    
    for (Map.Entry<String, Thread> entry : _threadMap.entrySet())
    {
      String controller = entry.getKey();
      if (!controller.equals(leader))
      {
        _managerMap.get(controller).disconnect();
        _threadMap.get(controller).interrupt();
      }
    }
    
    Thread.sleep(10000);
    _managerMap.get(leader).disconnect();
    _threadMap.get(leader).interrupt();
    
    _zkClient.close();
    // Thread.sleep(3000);
    // logger.info("END " + CLASS_NAME + " at " + new Date(System.currentTimeMillis()));
    System.out.println("END " + CLASS_NAME + " at " + new Date(System.currentTimeMillis()));
  }
  
  protected void setupStorageCluster(ClusterSetup setupTool, String clusterName, 
       String dbName, int partitionNr, String prefix, int startPort, String stateModel, boolean rebalance)
  {
    for (int i = 0; i < NODE_NR; i++)
    {
      String instanceName = prefix + ":" + (startPort + i);
      setupTool.addInstanceToCluster(clusterName, instanceName);
    }
    if(rebalance)
    {
      setupTool.addResourceGroupToCluster(clusterName, dbName, partitionNr, stateModel);
      setupTool.rebalanceStorageCluster(clusterName, dbName, 3);
    }
  }
  
  
}