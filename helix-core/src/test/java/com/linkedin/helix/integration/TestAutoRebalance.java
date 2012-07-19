package com.linkedin.helix.integration;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.linkedin.helix.HelixDataAccessor;
import com.linkedin.helix.PropertyKey.Builder;
import com.linkedin.helix.TestHelper;
import com.linkedin.helix.TestHelper.StartCMResult;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.controller.HelixControllerMain;
import com.linkedin.helix.controller.stages.ClusterDataCache;
import com.linkedin.helix.manager.zk.ZKHelixDataAccessor;
import com.linkedin.helix.manager.zk.ZNRecordSerializer;
import com.linkedin.helix.manager.zk.ZkBaseDataAccessor;
import com.linkedin.helix.manager.zk.ZkClient;
import com.linkedin.helix.model.IdealState.IdealStateModeProperty;
import com.linkedin.helix.tools.ClusterSetup;
import com.linkedin.helix.tools.ClusterStateVerifier;
import com.linkedin.helix.tools.ClusterStateVerifier.ZkVerifier;

public class TestAutoRebalance extends ZkStandAloneCMTestBase
{
  private static final Logger LOG = Logger.getLogger(TestAutoRebalance.class.getName());
  
  @Override
  @BeforeClass
  public void beforeClass() throws Exception
  {
    // Logger.getRootLogger().setLevel(Level.INFO);
    System.out.println("START " + CLASS_NAME + " at "
        + new Date(System.currentTimeMillis()));

    _zkClient = new ZkClient(ZK_ADDR);
    _zkClient.setZkSerializer(new ZNRecordSerializer());
    String namespace = "/" + CLUSTER_NAME;
    if (_zkClient.exists(namespace))
    {
      _zkClient.deleteRecursive(namespace);
    }
    _setupTool = new ClusterSetup(ZK_ADDR);

    // setup storage cluster
    _setupTool.addCluster(CLUSTER_NAME, true);
    _setupTool.addResourceToCluster(CLUSTER_NAME, TEST_DB, _PARTITIONS, STATE_MODEL, IdealStateModeProperty.AUTO_REBALANCE+"", 0);
    for (int i = 0; i < NODE_NR; i++)
    {
      String storageNodeName = PARTICIPANT_PREFIX + ":" + (START_PORT + i);
      _setupTool.addInstanceToCluster(CLUSTER_NAME, storageNodeName);
    }
    _setupTool.rebalanceStorageCluster(CLUSTER_NAME, TEST_DB, _replica);
    
    // start dummy participants
    for (int i = 0; i < NODE_NR; i++)
    {
      String instanceName = PARTICIPANT_PREFIX + "_" + (START_PORT + i);
      if (_startCMResultMap.get(instanceName) != null)
      {
        LOG.error("fail to start particpant:" + instanceName
            + "(participant with same name already exists)");
      }
      else
      {
        StartCMResult result =
            TestHelper.startDummyProcess(ZK_ADDR, CLUSTER_NAME, instanceName);
        _startCMResultMap.put(instanceName, result);
      }
    }

    // start controller
    String controllerName = CONTROLLER_PREFIX + "_0";
    StartCMResult startResult =
        TestHelper.startController(CLUSTER_NAME,
                                   controllerName,
                                   ZK_ADDR,
                                   HelixControllerMain.STANDALONE);
    _startCMResultMap.put(controllerName, startResult);

    boolean result =
        ClusterStateVerifier.verifyByZkCallback(new ExternalViewBalancedVerifier(_zkClient,
                                                                              CLUSTER_NAME, TEST_DB));

    Assert.assertTrue(result);
  }
  
  @Test()
  public void testAutoRebalance() throws Exception
  {
    
    // kill 1 node
    String instanceName = PARTICIPANT_PREFIX + "_" + (START_PORT + 0);
    _startCMResultMap.get(instanceName)._manager.disconnect();
    Thread.currentThread().sleep(1000);
    _startCMResultMap.get(instanceName)._thread.interrupt();
    
    //verifyBalanceExternalView();
    boolean result =
        ClusterStateVerifier.verifyByZkCallback(new ExternalViewBalancedVerifier(_zkClient,
                                                                              CLUSTER_NAME, TEST_DB));
    Assert.assertTrue(result);
    
    // add 2 nodes
    for (int i = 0; i < 2; i++)
    {
      String storageNodeName = PARTICIPANT_PREFIX + ":" + (1000 + i);
      _setupTool.addInstanceToCluster(CLUSTER_NAME, storageNodeName);
      
      StartCMResult resultx =
          TestHelper.startDummyProcess(ZK_ADDR, CLUSTER_NAME, storageNodeName.replace(':', '_'));
      _startCMResultMap.put(storageNodeName, resultx);
    }
    Thread.sleep(1000);
    result =
        ClusterStateVerifier.verifyByZkCallback(new ExternalViewBalancedVerifier(_zkClient,
                                                                              CLUSTER_NAME, TEST_DB));
    Assert.assertTrue(result);
  }
  
  static boolean verifyBalanceExternalView(ZNRecord externalView, int partitionCount, String masterState, int replica, int instances)
  {
    Map<String, Integer> masterPartitionsCountMap = new HashMap<String, Integer>();
    for(String partitionName : externalView.getMapFields().keySet())
    {
      Map<String, String> assignmentMap = externalView.getMapField(partitionName);
      //Assert.assertTrue(assignmentMap.size() >= replica);
      for(String instance : assignmentMap.keySet())
      {
        if(assignmentMap.get(instance).equals(masterState))
        {
          if(!masterPartitionsCountMap.containsKey(instance))
          {
            masterPartitionsCountMap.put(instance, 0);
          }
          masterPartitionsCountMap.put(instance, masterPartitionsCountMap.get(instance) + 1);
        }
      }
    }
    
    int perInstancePartition = partitionCount / instances;
    
    int totalCount = 0;
    for(String instanceName : masterPartitionsCountMap.keySet())
    {
      int instancePartitionCount = masterPartitionsCountMap.get(instanceName);
      totalCount += instancePartitionCount;
      if(!(instancePartitionCount == perInstancePartition || instancePartitionCount == perInstancePartition +1 ))
      {
        return false;
      }
      if(instancePartitionCount == perInstancePartition +1)
      {
        if(partitionCount % instances == 0)
        {
          return false;
        }
      }
    }
    if(partitionCount != totalCount)
    {
      return false;
    }
    return true;
    
  }
  
  public static class ExternalViewBalancedVerifier implements ZkVerifier
  {
    ZkClient _client;
    String _clusterName;
    String _resourceName;
    
    public ExternalViewBalancedVerifier(ZkClient client, String clusterName, String resourceName)
    {
      _client = client;
      _clusterName = clusterName;
      _resourceName = resourceName;
    }
    @Override
    public boolean verify()
    {
      HelixDataAccessor accessor = new ZKHelixDataAccessor( _clusterName, new ZkBaseDataAccessor(_client));
      Builder keyBuilder = accessor.keyBuilder();
      int numberOfPartitions = accessor.getProperty(keyBuilder.idealStates(_resourceName)).getRecord().getListFields().size();
      ClusterDataCache cache = new ClusterDataCache();
      cache.refresh(accessor);
      String masterValue = cache.getStateModelDef(cache.getIdealState(_resourceName).getStateModelDefRef()).getStatesPriorityList().get(0);
      int replicas = Integer.parseInt(cache.getIdealState(_resourceName).getReplicas());
      return verifyBalanceExternalView(accessor.getProperty(keyBuilder.externalView(_resourceName)).getRecord(), numberOfPartitions, masterValue, replicas, cache.getLiveInstances().size());
    }

    @Override
    public ZkClient getZkClient()
    {
      return _client;
    }

    @Override
    public String getClusterName()
    {
      return _clusterName;
    }
    
  }
}
