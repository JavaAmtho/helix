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
package com.linkedin.helix.tools;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;

import com.linkedin.helix.ConfigScope;
import com.linkedin.helix.ConfigScopeBuilder;
import com.linkedin.helix.HelixAdmin;
import com.linkedin.helix.HelixConstants.StateModelToken;
import com.linkedin.helix.HelixException;
import com.linkedin.helix.PropertyKey.Builder;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.manager.zk.ZKHelixAdmin;
import com.linkedin.helix.manager.zk.ZKHelixDataAccessor;
import com.linkedin.helix.manager.zk.ZNRecordSerializer;
import com.linkedin.helix.manager.zk.ZkBaseDataAccessor;
import com.linkedin.helix.manager.zk.ZkClient;
import com.linkedin.helix.model.ExternalView;
import com.linkedin.helix.model.IdealState;
import com.linkedin.helix.model.IdealState.IdealStateModeProperty;
import com.linkedin.helix.model.InstanceConfig;
import com.linkedin.helix.model.LiveInstance;
import com.linkedin.helix.model.StateModelDefinition;
import com.linkedin.helix.util.ZKClientPool;

public class ClusterSetup
{
  private static Logger logger = Logger.getLogger(ClusterSetup.class);
  public static final String zkServerAddress = "zkSvr";

  // List info about the cluster / DB/ Instances
  public static final String listClusters = "listClusters";
  public static final String listResources = "listResources";
  public static final String listInstances = "listInstances";

  // Add, drop, and rebalance
  public static final String addCluster = "addCluster";
  public static final String activateCluster = "activateCluster";
  public static final String dropCluster = "dropCluster";
  public static final String dropResource = "dropResource";
  public static final String addInstance = "addNode";
  public static final String addResource = "addResource";
  public static final String addStateModelDef = "addStateModelDef";
  public static final String addIdealState = "addIdealState";
  public static final String swapInstance = "swapInstance";
  public static final String dropInstance = "dropNode";
  public static final String rebalance = "rebalance";
  public static final String rebalanceV8 = "rebalanceV8";
  public static final String expandCluster = "expandCluster";
  public static final String expandResource = "expandResource";
  public static final String mode = "mode";
  public static final String bucketSize = "bucketSize";
  public static final String groupMsgMode = "groupMsgMode";
  public static final String resourceKeyPrefix = "key";
  public static final String addResourceProperty = "addResourceProperty";
  public static final String removeResourceProperty = "removeResourceProperty";

  // Query info (TBD in V2)
  public static final String listClusterInfo = "listClusterInfo";
  public static final String listInstanceInfo = "listInstanceInfo";
  public static final String listResourceInfo = "listResourceInfo";
  public static final String listPartitionInfo = "listPartitionInfo";
  public static final String listStateModels = "listStateModels";
  public static final String listStateModel = "listStateModel";

  // enable/disable/reset instances/cluster/resource/partition
  public static final String enableInstance = "enableInstance";
  public static final String enablePartition = "enablePartition";
  public static final String enableCluster = "enableCluster";
  public static final String resetPartition = "resetPartition";
  public static final String resetInstance = "resetInstance";
  public static final String resetResource = "resetResource";

  // help
  public static final String help = "help";

  // stats/alerts
  public static final String addStat = "addStat";
  public static final String addAlert = "addAlert";
  public static final String dropStat = "dropStat";
  public static final String dropAlert = "dropAlert";

  // get/set configs
  public static final String getConfig = "getConfig";
  public static final String setConfig = "setConfig";
  public static final String removeConfig = "removeConfig";

  static Logger _logger = Logger.getLogger(ClusterSetup.class);
  String _zkServerAddress;
  ZkClient _zkClient;
  HelixAdmin _admin;

  public ClusterSetup(String zkServerAddress)
  {
    _zkServerAddress = zkServerAddress;
    _zkClient = ZKClientPool.getZkClient(_zkServerAddress);
    _admin = new ZKHelixAdmin(_zkClient);
  }

  public ClusterSetup(ZkClient zkClient)
  {
    _zkServerAddress = zkClient.getServers();
    _zkClient = zkClient;
    _admin = new ZKHelixAdmin(_zkClient);
  }

  public void addCluster(String clusterName, boolean overwritePrevious)
  {
    _admin.addCluster(clusterName, overwritePrevious);

    StateModelConfigGenerator generator = new StateModelConfigGenerator();
    addStateModelDef(clusterName,
                     "MasterSlave",
                     new StateModelDefinition(generator.generateConfigForMasterSlave()));
    addStateModelDef(clusterName,
                     "LeaderStandby",
                     new StateModelDefinition(generator.generateConfigForLeaderStandby()));
    addStateModelDef(clusterName,
                     "StorageSchemata",
                     new StateModelDefinition(generator.generateConfigForStorageSchemata()));
    addStateModelDef(clusterName,
                     "OnlineOffline",
                     new StateModelDefinition(generator.generateConfigForOnlineOffline()));
  }

  public void activateCluster(String clusterName, String grandCluster, boolean enable)
  {
    if (enable)
    {
      _admin.addClusterToGrandCluster(clusterName, grandCluster);
    }
    else
    {
      _admin.dropResource(grandCluster, clusterName);
    }
  }

  public void deleteCluster(String clusterName)
  {
    _admin.dropCluster(clusterName);
  }

  public void addInstancesToCluster(String clusterName, String[] InstanceInfoArray)
  {
    for (String InstanceInfo : InstanceInfoArray)
    {
      // the storage Instance info must be hostname:port format.
      if (InstanceInfo.length() > 0)
      {
        addInstanceToCluster(clusterName, InstanceInfo);
      }
    }
  }

  public void addInstanceToCluster(String clusterName, String InstanceAddress)
  {
    // InstanceAddress must be in host:port format
    int lastPos = InstanceAddress.lastIndexOf("_");
    if (lastPos <= 0)
    {
      lastPos = InstanceAddress.lastIndexOf(":");
    }
    if (lastPos <= 0)
    {
      String error = "Invalid storage Instance info format: " + InstanceAddress;
      _logger.warn(error);
      throw new HelixException(error);
    }
    String host = InstanceAddress.substring(0, lastPos);
    String portStr = InstanceAddress.substring(lastPos + 1);
    int port = Integer.parseInt(portStr);
    addInstanceToCluster(clusterName, host, port);
  }

  public void addInstanceToCluster(String clusterName, String host, int port)
  {
    String instanceId = host + "_" + port;
    InstanceConfig config = new InstanceConfig(instanceId);
    config.setHostName(host);
    config.setPort(Integer.toString(port));
    config.setInstanceEnabled(true);
    _admin.addInstance(clusterName, config);
  }

  public void dropInstancesFromCluster(String clusterName, String[] InstanceInfoArray)
  {
    for (String InstanceInfo : InstanceInfoArray)
    {
      // the storage Instance info must be hostname:port format.
      if (InstanceInfo.length() > 0)
      {
        dropInstanceFromCluster(clusterName, InstanceInfo);
      }
    }
  }

  public void dropInstanceFromCluster(String clusterName, String InstanceAddress)
  {
    // InstanceAddress must be in host:port format
    int lastPos = InstanceAddress.lastIndexOf("_");
    if (lastPos <= 0)
    {
      lastPos = InstanceAddress.lastIndexOf(":");
    }
    if (lastPos <= 0)
    {
      String error = "Invalid storage Instance info format: " + InstanceAddress;
      _logger.warn(error);
      throw new HelixException(error);
    }
    String host = InstanceAddress.substring(0, lastPos);
    String portStr = InstanceAddress.substring(lastPos + 1);
    int port = Integer.parseInt(portStr);
    dropInstanceFromCluster(clusterName, host, port);
  }

  public void dropInstanceFromCluster(String clusterName, String host, int port)
  {
    String instanceId = host + "_" + port;

    ZKHelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor<ZNRecord>(_zkClient));
    Builder keyBuilder = accessor.keyBuilder();

    // ensure node is stopped
    LiveInstance liveInstance = accessor.getProperty(keyBuilder.liveInstance(instanceId));
    if (liveInstance != null)
    {
      throw new HelixException("Can't drop " + instanceId + ", please stop " + instanceId
          + " before drop it");
    }

    InstanceConfig config = accessor.getProperty(keyBuilder.instanceConfig(instanceId));
    if (config == null)
    {
      String error = "Node " + instanceId + " does not exist, cannot drop";
      _logger.warn(error);
      throw new HelixException(error);
    }

    // ensure node is disabled, otherwise fail
    if (config.getInstanceEnabled())
    {
      String error = "Node " + instanceId + " is enabled, cannot drop";
      _logger.warn(error);
      throw new HelixException(error);
    }
    _admin.dropInstance(clusterName, config);
  }

  public void swapInstance(String clusterName,
                           String oldInstanceName,
                           String newInstanceName)
  {
    ZKHelixDataAccessor accessor =
        new ZKHelixDataAccessor(clusterName, new ZkBaseDataAccessor(_zkClient));
    Builder keyBuilder = accessor.keyBuilder();

    InstanceConfig oldConfig =
        accessor.getProperty(keyBuilder.instanceConfig(oldInstanceName));
    if (oldConfig == null)
    {
      String error = "Old instance " + oldInstanceName + " does not exist, cannot swap";
      _logger.warn(error);
      throw new HelixException(error);
    }

    InstanceConfig newConfig =
        accessor.getProperty(keyBuilder.instanceConfig(newInstanceName));
    if (newConfig == null)
    {
      String error = "New instance " + newInstanceName + " does not exist, cannot swap";
      _logger.warn(error);
      throw new HelixException(error);
    }

    // ensure old instance is disabled, otherwise fail
    if (oldConfig.getInstanceEnabled())
    {
      String error =
          "Old instance " + oldInstanceName
              + " is enabled, it need to be disabled and turned off";
      _logger.warn(error);
      throw new HelixException(error);
    }
    // ensure old instance is down, otherwise fail
    List<String> liveInstanceNames =
        accessor.getChildNames(accessor.keyBuilder().liveInstances());

    if (liveInstanceNames.contains(oldInstanceName))
    {
      String error =
          "Old instance " + oldInstanceName
              + " is still on, it need to be disabled and turned off";
      _logger.warn(error);
      throw new HelixException(error);
    }

    dropInstanceFromCluster(clusterName, oldInstanceName);

    List<IdealState> existingIdealStates =
        accessor.getChildValues(accessor.keyBuilder().idealStates());
    for (IdealState idealState : existingIdealStates)
    {
      swapInstanceInIdealState(idealState, oldInstanceName, newInstanceName);
      accessor.setProperty(accessor.keyBuilder()
                                   .idealStates(idealState.getResourceName()), idealState);
    }
  }

  void swapInstanceInIdealState(IdealState idealState,
                                String oldInstance,
                                String newInstance)
  {
    for (String partition : idealState.getRecord().getMapFields().keySet())
    {
      Map<String, String> valMap = idealState.getRecord().getMapField(partition);
      if (valMap.containsKey(oldInstance))
      {
        valMap.put(newInstance, valMap.get(oldInstance));
        valMap.remove(oldInstance);
      }
    }

    for (String partition : idealState.getRecord().getListFields().keySet())
    {
      List<String> valList = idealState.getRecord().getListField(partition);
      for (int i = 0; i < valList.size(); i++)
      {
        if (valList.get(i).equals(oldInstance))
        {
          valList.remove(i);
          valList.add(i, newInstance);
        }
      }
    }
  }

  public HelixAdmin getClusterManagementTool()
  {
    return _admin;
  }

  public void addStateModelDef(String clusterName,
                               String stateModelDef,
                               StateModelDefinition record)
  {
    _admin.addStateModelDef(clusterName, stateModelDef, record);
  }

  public void addResourceToCluster(String clusterName,
                                   String resourceName,
                                   int numResources,
                                   String stateModelRef)
  {
    addResourceToCluster(clusterName,
                         resourceName,
                         numResources,
                         stateModelRef,
                         IdealStateModeProperty.AUTO.toString());
  }

  public void addResourceToCluster(String clusterName,
                                   String resourceName,
                                   int numResources,
                                   String stateModelRef,
                                   String idealStateMode)
  {
    _admin.addResource(clusterName,
                       resourceName,
                       numResources,
                       stateModelRef,
                       idealStateMode);
  }

  public void addResourceToCluster(String clusterName,
                                   String resourceName,
                                   int numResources,
                                   String stateModelRef,
                                   String idealStateMode,
                                   int bucketSize,
                                   boolean groupMsgMode)
  {
    _admin.addResource(clusterName,
                       resourceName,
                       numResources,
                       stateModelRef,
                       idealStateMode,
                       bucketSize,
                       groupMsgMode);
  }

  public void dropResourceFromCluster(String clusterName, String resourceName)
  {
    _admin.dropResource(clusterName, resourceName);
  }

  // TODO: remove this. has moved to ZkHelixAdmin
  public void rebalanceStorageCluster(String clusterName, String resourceName, int replica)
  {
    rebalanceStorageCluster(clusterName, resourceName, replica, resourceName);
  }
  
  public void reblanceResource(String clusterName, String resourceName, int replica)
  {
    rebalanceStorageCluster(clusterName, resourceName, replica, resourceName); 
  }

  // rebalancer for v08 cluster
  public void rebalanceV08(String clusterName, String resourceName, int replica)
  {
    List<String> instanceNames = _admin.getInstancesInCluster(clusterName);
    Collections.sort(instanceNames);

    IdealState idealState = _admin.getResourceIdealState(clusterName, resourceName);
    if (idealState == null)
    {
      throw new HelixException("Resource: " + resourceName + " has NOT been added yet");
    }

    idealState.setReplicas(Integer.toString(replica));
    int partitions = idealState.getNumPartitions();

    final int nodeNb = instanceNames.size();
    final int groupNb = nodeNb / replica;
    final int partitionsPerGroup = partitions/groupNb;
    
    IdealState newIdealState = new IdealState(resourceName);
    newIdealState.getRecord().setSimpleFields(idealState.getRecord().getSimpleFields());
    
    for (int i = 0; i < groupNb; i++)
    {
      // int master = 8901 + i * replica;
      List<String> preferenceList = new ArrayList<String>();
      int masterIdx = i * replica;
      String master = instanceNames.get(masterIdx);
      preferenceList.add(master);
      
      // is.setPartitionState(partitionName, "localhost_" + master, "MASTER");
      for (int j = masterIdx + 1; j < masterIdx + replica; j++)
      {
        // is.setPartitionState(partitionName, "localhost_" + j, "SLAVE");
        String slave = instanceNames.get(j);
        preferenceList.add(slave);
      }

      for (int k = 0; k < partitionsPerGroup; k++)
      {
        int partition = i * partitionsPerGroup + k;
        String partitionName = resourceName + "_" + partition;
        newIdealState.getRecord().setListField(partitionName, preferenceList);
      }
    }
    
    // System.out.println(newIdealState);
    _admin.setResourceIdealState(clusterName, resourceName, newIdealState);
  }

  public void expandResource(String clusterName, String resourceName)
  {
    IdealState idealState = _admin.getResourceIdealState(clusterName, resourceName);
    if (idealState.getIdealStateMode() == IdealStateModeProperty.AUTO_REBALANCE
        || idealState.getIdealStateMode() == IdealStateModeProperty.CUSTOMIZED)
    {
      _logger.info("Skipping idealState " + idealState.getResourceName() + " "
          + idealState.getIdealStateMode());
      return;
    }
    boolean anyLiveInstance = false;
    for (List<String> list : idealState.getRecord().getListFields().values())
    {
      if (list.contains(StateModelToken.ANY_LIVEINSTANCE.toString()))
      {
        _logger.info("Skipping idealState " + idealState.getResourceName()
            + " with ANY_LIVEINSTANCE");
        anyLiveInstance = true;
        continue;
      }
    }
    if (anyLiveInstance)
    {
      return;
    }
    try
    {
      int replica = Integer.parseInt(idealState.getReplicas());
    }
    catch (Exception e)
    {
      _logger.error("", e);
      return;
    }
    if (idealState.getRecord().getListFields().size() == 0)
    {
      _logger.warn("Resource " + resourceName + " not balanced, skip");
      return;
    }
    IdealState newIdealState = balanceIdealState(clusterName, idealState);
    _admin.setResourceIdealState(clusterName, resourceName, newIdealState);
  }

  public void expandCluster(String clusterName)
  {
    List<String> resources = _admin.getResourcesInCluster(clusterName);
    for (String resourceName : resources)
    {
      expandResource(clusterName, resourceName);
    }
  }

  public String[] parseStates(String clusterName, String stateModelName)
  {
    String[] result = new String[2];
    String masterStateValue = null, slaveStateValue = null;
    StateModelDefinition stateModDef =
        _admin.getStateModelDef(clusterName, stateModelName);

    if (stateModDef == null)
    {
      throw new HelixException("cannot find state model: " + stateModelName);
    }
    // StateModelDefinition def = new StateModelDefinition(stateModDef);

    List<String> statePriorityList = stateModDef.getStatesPriorityList();

    for (String state : statePriorityList)
    {
      String count = stateModDef.getNumInstancesPerState(state);
      if (count.equals("1"))
      {
        if (masterStateValue != null)
        {
          throw new HelixException("Invalid or unsupported state model definition");
        }
        masterStateValue = state;
      }
      else if (count.equalsIgnoreCase("R"))
      {
        if (slaveStateValue != null)
        {
          throw new HelixException("Invalid or unsupported state model definition");
        }
        slaveStateValue = state;
      }
      else if (count.equalsIgnoreCase("N"))
      {
        if (!(masterStateValue == null && slaveStateValue == null))
        {
          throw new HelixException("Invalid or unsupported state model definition");
        }
        masterStateValue = slaveStateValue = state;
      }
    }
    if (masterStateValue == null && slaveStateValue == null)
    {
      throw new HelixException("Invalid or unsupported state model definition");
    }

    if (masterStateValue == null)
    {
      masterStateValue = slaveStateValue;
    }
    result[0] = masterStateValue;
    result[1] = slaveStateValue;
    return result;
  }

  public IdealState balanceIdealState(String clusterName, IdealState idealState)
  {
    // The new instances are added into the cluster already. So we need to find out the
    // instances that
    // already have partitions assigned to them.
    List<String> instanceNames = _admin.getInstancesInCluster(clusterName);
    Set<String> activeInstances = new HashSet<String>();
    for (String partition : idealState.getPartitionSet())
    {
      activeInstances.addAll(idealState.getRecord().getListField(partition));
    }
    instanceNames.removeAll(activeInstances);
    Map<String, Object> previousIdealState = buildInternalIdealState(idealState);

    Map<String, Object> balancedRecord =
        IdealStateCalculatorForStorageNode.calculateNextIdealState(instanceNames,
                                                                   previousIdealState);

    String[] states = parseStates(clusterName, idealState.getStateModelDefRef());

    ZNRecord newIdealStateRecord =
        IdealStateCalculatorForStorageNode.convertToZNRecord(balancedRecord,
                                                             idealState.getResourceName(),
                                                             states[0],
                                                             states[1]);
    Set<String> partitionSet = new HashSet<String>();
    partitionSet.addAll(newIdealStateRecord.getMapFields().keySet());
    partitionSet.addAll(newIdealStateRecord.getListFields().keySet());

    Map<String, String> reversePartitionIndex =
        (Map<String, String>) balancedRecord.get("reversePartitionIndex");
    for (String partition : partitionSet)
    {
      if (reversePartitionIndex.containsKey(partition))
      {
        String originPartitionName = reversePartitionIndex.get(partition);
        if (partition.equals(originPartitionName))
        {
          continue;
        }
        newIdealStateRecord.getMapFields()
                           .put(originPartitionName,
                                newIdealStateRecord.getMapField(partition));
        newIdealStateRecord.getMapFields().remove(partition);

        newIdealStateRecord.getListFields()
                           .put(originPartitionName,
                                newIdealStateRecord.getListField(partition));
        newIdealStateRecord.getListFields().remove(partition);
      }
    }

    newIdealStateRecord.getSimpleFields()
                       .putAll(idealState.getRecord().getSimpleFields());
    return new IdealState(newIdealStateRecord);

  }

  public static Map<String, Object> buildInternalIdealState(IdealState state)
  {
    // Try parse the partition number from name DB_n. If not, sort the partitions and
    // assign id
    Map<String, Integer> partitionIndex = new HashMap<String, Integer>();
    Map<String, String> reversePartitionIndex = new HashMap<String, String>();
    boolean indexInPartitionName = true;
    for (String partitionId : state.getPartitionSet())
    {
      int lastPos = partitionId.lastIndexOf("_");
      if (lastPos < 0)
      {
        indexInPartitionName = false;
        break;
      }
      try
      {
        String idStr = partitionId.substring(lastPos + 1);
        int partition = Integer.parseInt(idStr);
        partitionIndex.put(partitionId, partition);
        reversePartitionIndex.put(state.getResourceName() + "_" + partition, partitionId);
      }
      catch (Exception e)
      {
        indexInPartitionName = false;
        partitionIndex.clear();
        reversePartitionIndex.clear();
        break;
      }
    }

    if (indexInPartitionName == false)
    {
      List<String> partitions = new ArrayList<String>();
      partitions.addAll(state.getPartitionSet());
      Collections.sort(partitions);
      for (int i = 0; i < partitions.size(); i++)
      {
        partitionIndex.put(partitions.get(i), i);
        reversePartitionIndex.put(state.getResourceName() + "_" + i, partitions.get(i));
      }
    }

    Map<String, List<Integer>> nodeMasterAssignmentMap =
        new TreeMap<String, List<Integer>>();
    Map<String, Map<String, List<Integer>>> combinedNodeSlaveAssignmentMap =
        new TreeMap<String, Map<String, List<Integer>>>();
    for (String partition : state.getPartitionSet())
    {
      List<String> instances = state.getRecord().getListField(partition);
      String master = instances.get(0);
      if (!nodeMasterAssignmentMap.containsKey(master))
      {
        nodeMasterAssignmentMap.put(master, new ArrayList<Integer>());
      }
      if (!combinedNodeSlaveAssignmentMap.containsKey(master))
      {
        combinedNodeSlaveAssignmentMap.put(master, new TreeMap<String, List<Integer>>());
      }
      nodeMasterAssignmentMap.get(master).add(partitionIndex.get(partition));
      for (int i = 1; i < instances.size(); i++)
      {
        String instance = instances.get(i);
        Map<String, List<Integer>> slaveMap = combinedNodeSlaveAssignmentMap.get(master);
        if (!slaveMap.containsKey(instance))
        {
          slaveMap.put(instance, new ArrayList<Integer>());
        }
        slaveMap.get(instance).add(partitionIndex.get(partition));
      }
    }

    Map<String, Object> result = new TreeMap<String, Object>();
    result.put("MasterAssignmentMap", nodeMasterAssignmentMap);
    result.put("SlaveAssignmentMap", combinedNodeSlaveAssignmentMap);
    result.put("replicas", Integer.parseInt(state.getReplicas()));
    result.put("partitions", new Integer(state.getRecord().getListFields().size()));
    result.put("reversePartitionIndex", reversePartitionIndex);
    return result;
  }

  // TODO: remove this. has moved to ZkHelixAdmin
  public void rebalanceStorageCluster(String clusterName,
                                      String resourceName,
                                      int replica,
                                      String keyPrefix)
  {
    List<String> InstanceNames = _admin.getInstancesInCluster(clusterName);
    // ensure we get the same idealState with the same set of instances
    Collections.sort(InstanceNames);

    IdealState idealState = _admin.getResourceIdealState(clusterName, resourceName);
    if (idealState == null)
    {
      throw new HelixException("Resource: " + resourceName + " has NOT been added yet");
    }

    idealState.setReplicas(Integer.toString(replica));
    int partitions = idealState.getNumPartitions();
    String stateModelName = idealState.getStateModelDefRef();
    StateModelDefinition stateModDef =
        _admin.getStateModelDef(clusterName, stateModelName);

    if (stateModDef == null)
    {
      throw new HelixException("cannot find state model: " + stateModelName);
    }
    // StateModelDefinition def = new StateModelDefinition(stateModDef);

    List<String> statePriorityList = stateModDef.getStatesPriorityList();

    String masterStateValue = null;
    String slaveStateValue = null;
    replica--;

    for (String state : statePriorityList)
    {
      String count = stateModDef.getNumInstancesPerState(state);
      if (count.equals("1"))
      {
        if (masterStateValue != null)
        {
          throw new HelixException("Invalid or unsupported state model definition");
        }
        masterStateValue = state;
      }
      else if (count.equalsIgnoreCase("R"))
      {
        if (slaveStateValue != null)
        {
          throw new HelixException("Invalid or unsupported state model definition");
        }
        slaveStateValue = state;
      }
      else if (count.equalsIgnoreCase("N"))
      {
        if (!(masterStateValue == null && slaveStateValue == null))
        {
          throw new HelixException("Invalid or unsupported state model definition");
        }
        replica = InstanceNames.size() - 1;
        masterStateValue = slaveStateValue = state;
      }
    }
    if (masterStateValue == null && slaveStateValue == null)
    {
      throw new HelixException("Invalid or unsupported state model definition");
    }

    if (masterStateValue == null)
    {
      masterStateValue = slaveStateValue;
    }
    if (idealState.getIdealStateMode() != IdealStateModeProperty.AUTO_REBALANCE)
    {
      ZNRecord newIdealState =
          IdealStateCalculatorForStorageNode.calculateIdealState(InstanceNames,
                                                                 partitions,
                                                                 replica,
                                                                 keyPrefix,
                                                                 masterStateValue,
                                                                 slaveStateValue);

      // for now keep mapField in AUTO mode and remove listField in CUSTOMIZED mode
      if (idealState.getIdealStateMode() == IdealStateModeProperty.AUTO)
      {
        idealState.getRecord().setListFields(newIdealState.getListFields());
        idealState.getRecord().setMapFields(newIdealState.getMapFields());
      }
      if (idealState.getIdealStateMode() == IdealStateModeProperty.CUSTOMIZED)
      {
        idealState.getRecord().setMapFields(newIdealState.getMapFields());
      }
    }
    else
    {
      for (int i = 0; i < partitions; i++)
      {
        String partitionName = keyPrefix + "_" + i;
        idealState.getRecord().setMapField(partitionName, new HashMap<String, String>());
        idealState.getRecord().setListField(partitionName, new ArrayList<String>());
      }
    }
    _admin.setResourceIdealState(clusterName, resourceName, idealState);
  }

  /**
   * setConfig
   * 
   * @param scopeStr
   *          : scope=value, ... where scope=CLUSTER, RESOURCE, PARTICIPANT, PARTITION
   * @param properitesStr
   *          : key=value, ... which represents a Map<String, String>
   */
  public void setConfig(String scopesStr, String propertiesStr)
  {
    ConfigScope scope = new ConfigScopeBuilder().build(scopesStr);

    // parse properties
    String[] properties = propertiesStr.split("[\\s,]");
    Map<String, String> propertiesMap = new TreeMap<String, String>();
    for (String property : properties)
    {
      int idx = property.indexOf('=');
      if (idx == -1)
      {
        logger.error("Invalid property string: " + property);
        continue;
      }

      String key = property.substring(0, idx);
      String value = property.substring(idx + 1);
      propertiesMap.put(key, value);
    }
    logger.debug("propertiesMap: " + propertiesMap);

    _admin.setConfig(scope, propertiesMap);
  }

  public void removeConfig(String scopesStr, String keysStr)
  {
    ConfigScope scope = new ConfigScopeBuilder().build(scopesStr);

    // parse keys
    String[] keys = keysStr.split("[\\s,]");
    Set<String> keysSet = new HashSet<String>(Arrays.asList(keys));

    _admin.removeConfig(scope, keysSet);
  }

  public String getConfig(String scopesStr, String keysStr)
  {
    ConfigScope scope = new ConfigScopeBuilder().build(scopesStr);

    // parse keys
    String[] keys = keysStr.split("[\\s,]");
    Set<String> keysSet = new HashSet<String>(Arrays.asList(keys));

    Map<String, String> propertiesMap = _admin.getConfig(scope, keysSet);
    StringBuffer sb = new StringBuffer();
    for (String key : keys)
    {
      if (propertiesMap.containsKey(key))
      {
        if (sb.length() > 0)
        {
          sb.append("," + key + "=" + propertiesMap.get(key));
        }
        else
        {
          // sb.length()==0 means the first key=value
          sb.append(key + "=" + propertiesMap.get(key));
        }
      }
      else
      {
        logger.error("Config doesn't exist for key: " + key);
      }
    }

    System.out.println(sb.toString());
    return sb.toString();
  }

  /**
   * Sets up a cluster with 6 Instances[localhost:8900 to localhost:8905], 1
   * resource[EspressoDB] with a replication factor of 3
   * 
   * @param clusterName
   */
  public void setupTestCluster(String clusterName)
  {
    addCluster(clusterName, true);
    String storageInstanceInfoArray[] = new String[6];
    for (int i = 0; i < storageInstanceInfoArray.length; i++)
    {
      storageInstanceInfoArray[i] = "localhost:" + (8900 + i);
    }
    addInstancesToCluster(clusterName, storageInstanceInfoArray);
    addResourceToCluster(clusterName, "TestDB", 10, "MasterSlave");
    rebalanceStorageCluster(clusterName, "TestDB", 3);
  }

  public static void printUsage(Options cliOptions)
  {
    HelpFormatter helpFormatter = new HelpFormatter();
    helpFormatter.setWidth(1000);
    helpFormatter.printHelp("java " + ClusterSetup.class.getName(), cliOptions);
  }

  @SuppressWarnings("static-access")
  private static Options constructCommandLineOptions()
  {
    Option helpOption =
        OptionBuilder.withLongOpt(help)
                     .withDescription("Prints command-line options info")
                     .create();

    Option zkServerOption =
        OptionBuilder.withLongOpt(zkServerAddress)
                     .withDescription("Provide zookeeper address")
                     .create();
    zkServerOption.setArgs(1);
    zkServerOption.setRequired(true);
    zkServerOption.setArgName("ZookeeperServerAddress(Required)");

    Option listClustersOption =
        OptionBuilder.withLongOpt(listClusters)
                     .withDescription("List existing clusters")
                     .create();
    listClustersOption.setArgs(0);
    listClustersOption.setRequired(false);

    Option listResourceOption =
        OptionBuilder.withLongOpt(listResources)
                     .withDescription("List resources hosted in a cluster")
                     .create();
    listResourceOption.setArgs(1);
    listResourceOption.setRequired(false);
    listResourceOption.setArgName("clusterName");

    Option listInstancesOption =
        OptionBuilder.withLongOpt(listInstances)
                     .withDescription("List Instances in a cluster")
                     .create();
    listInstancesOption.setArgs(1);
    listInstancesOption.setRequired(false);
    listInstancesOption.setArgName("clusterName");

    Option addClusterOption =
        OptionBuilder.withLongOpt(addCluster)
                     .withDescription("Add a new cluster")
                     .create();
    addClusterOption.setArgs(1);
    addClusterOption.setRequired(false);
    addClusterOption.setArgName("clusterName");

    Option activateClusterOption =
        OptionBuilder.withLongOpt(activateCluster)
                     .withDescription("Enable/disable a cluster in distributed controller mode")
                     .create();
    activateClusterOption.setArgs(3);
    activateClusterOption.setRequired(false);
    activateClusterOption.setArgName("clusterName grandCluster true/false");

    Option deleteClusterOption =
        OptionBuilder.withLongOpt(dropCluster)
                     .withDescription("Delete a cluster")
                     .create();
    deleteClusterOption.setArgs(1);
    deleteClusterOption.setRequired(false);
    deleteClusterOption.setArgName("clusterName");

    Option addInstanceOption =
        OptionBuilder.withLongOpt(addInstance)
                     .withDescription("Add a new Instance to a cluster")
                     .create();
    addInstanceOption.setArgs(2);
    addInstanceOption.setRequired(false);
    addInstanceOption.setArgName("clusterName InstanceAddress(host:port)");

    Option addResourceOption =
        OptionBuilder.withLongOpt(addResource)
                     .withDescription("Add a resource to a cluster")
                     .create();
    addResourceOption.setArgs(4);
    addResourceOption.setRequired(false);
    addResourceOption.setArgName("clusterName resourceName partitionNum stateModelRef <-mode modeValue>");

    Option expandResourceOption =
        OptionBuilder.withLongOpt(expandResource)
                     .withDescription("Expand resource to additional nodes")
                     .create();
    expandResourceOption.setArgs(2);
    expandResourceOption.setRequired(false);
    expandResourceOption.setArgName("clusterName resourceName");

    Option expandClusterOption =
        OptionBuilder.withLongOpt(expandCluster)
                     .withDescription("Expand a cluster and all the resources")
                     .create();
    expandClusterOption.setArgs(1);
    expandClusterOption.setRequired(false);
    expandClusterOption.setArgName("clusterName");

    Option resourceModeOption =
        OptionBuilder.withLongOpt(mode)
                     .withDescription("Specify resource mode, used with addResourceGroup command")
                     .create();
    resourceModeOption.setArgs(1);
    resourceModeOption.setRequired(false);
    resourceModeOption.setArgName("IdealState mode");

    Option resourceBucketSizeOption =
        OptionBuilder.withLongOpt(bucketSize)
                     .withDescription("Specify size of a bucket, used with addResourceGroup command")
                     .create();
    resourceBucketSizeOption.setArgs(1);
    resourceBucketSizeOption.setRequired(false);
    resourceBucketSizeOption.setArgName("Size of a bucket for a resource");

    Option resourceGroupMsgModeOption =
        OptionBuilder.withLongOpt(groupMsgMode)
                     .withDescription("Specify group message mode, used with addResourceGroup command")
                     .create();
    resourceGroupMsgModeOption.setArgs(1);
    resourceGroupMsgModeOption.setRequired(false);
    resourceGroupMsgModeOption.setArgName("Group message mode for a resource");

    
    Option resourceKeyOption =
        OptionBuilder.withLongOpt(resourceKeyPrefix)
                     .withDescription("Specify resource key prefix, used with rebalance command")
                     .create();
    resourceKeyOption.setArgs(1);
    resourceKeyOption.setRequired(false);
    resourceKeyOption.setArgName("Resource key prefix");

    Option addStateModelDefOption =
        OptionBuilder.withLongOpt(addStateModelDef)
                     .withDescription("Add a State model to a cluster")
                     .create();
    addStateModelDefOption.setArgs(2);
    addStateModelDefOption.setRequired(false);
    addStateModelDefOption.setArgName("clusterName <filename>");

    Option addIdealStateOption =
        OptionBuilder.withLongOpt(addIdealState)
                     .withDescription("Add a State model to a cluster")
                     .create();
    addIdealStateOption.setArgs(3);
    addIdealStateOption.setRequired(false);
    addIdealStateOption.setArgName("clusterName resourceName <filename>");

    Option dropInstanceOption =
        OptionBuilder.withLongOpt(dropInstance)
                     .withDescription("Drop an existing Instance from a cluster")
                     .create();
    dropInstanceOption.setArgs(2);
    dropInstanceOption.setRequired(false);
    dropInstanceOption.setArgName("clusterName InstanceAddress(host:port)");

    Option swapInstanceOption =
        OptionBuilder.withLongOpt(swapInstance)
                     .withDescription("Swap an old instance from a cluster with a new instance")
                     .create();
    swapInstanceOption.setArgs(3);
    swapInstanceOption.setRequired(false);
    swapInstanceOption.setArgName("clusterName oldInstance newInstance");

    Option dropResourceOption =
        OptionBuilder.withLongOpt(dropResource)
                     .withDescription("Drop an existing resource from a cluster")
                     .create();
    dropResourceOption.setArgs(2);
    dropResourceOption.setRequired(false);
    dropResourceOption.setArgName("clusterName resourceName");

    Option rebalanceOption =
        OptionBuilder.withLongOpt(rebalance)
                     .withDescription("Rebalance a resource in a cluster")
                     .create();
    rebalanceOption.setArgs(3);
    rebalanceOption.setRequired(false);
    rebalanceOption.setArgName("clusterName resourceName replicas");

    // v8 rebalancer
    Option rebalanceV8Option =
        OptionBuilder.withLongOpt(rebalanceV8)
                     .withDescription("Rebalance a resource in a v8 cluster")
                     .create();
    rebalanceV8Option.setArgs(3);
    rebalanceV8Option.setRequired(false);
    rebalanceV8Option.setArgName("clusterName resourceName replicas");

    
    Option instanceInfoOption =
        OptionBuilder.withLongOpt(listInstanceInfo)
                     .withDescription("Query info of a Instance in a cluster")
                     .create();
    instanceInfoOption.setArgs(2);
    instanceInfoOption.setRequired(false);
    instanceInfoOption.setArgName("clusterName InstanceName");

    Option clusterInfoOption =
        OptionBuilder.withLongOpt(listClusterInfo)
                     .withDescription("Query info of a cluster")
                     .create();
    clusterInfoOption.setArgs(1);
    clusterInfoOption.setRequired(false);
    clusterInfoOption.setArgName("clusterName");

    Option resourceInfoOption =
        OptionBuilder.withLongOpt(listResourceInfo)
                     .withDescription("Query info of a resource")
                     .create();
    resourceInfoOption.setArgs(2);
    resourceInfoOption.setRequired(false);
    resourceInfoOption.setArgName("clusterName resourceName");

    Option addResourcePropertyOption =
        OptionBuilder.withLongOpt(addResourceProperty)
                     .withDescription("Add a resource property")
                     .create();
    addResourcePropertyOption.setArgs(4);
    addResourcePropertyOption.setRequired(false);
    addResourcePropertyOption.setArgName("clusterName resourceName propertyName propertyValue");

    Option removeResourcePropertyOption =
        OptionBuilder.withLongOpt(removeResourceProperty)
                     .withDescription("Remove a resource property")
                     .create();
    removeResourcePropertyOption.setArgs(3);
    removeResourcePropertyOption.setRequired(false);
    removeResourcePropertyOption.setArgName("clusterName resourceName propertyName");

    Option partitionInfoOption =
        OptionBuilder.withLongOpt(listPartitionInfo)
                     .withDescription("Query info of a partition")
                     .create();
    partitionInfoOption.setArgs(3);
    partitionInfoOption.setRequired(false);
    partitionInfoOption.setArgName("clusterName resourceName partitionName");

    Option enableInstanceOption =
        OptionBuilder.withLongOpt(enableInstance)
                     .withDescription("Enable/disable a Instance")
                     .create();
    enableInstanceOption.setArgs(3);
    enableInstanceOption.setRequired(false);
    enableInstanceOption.setArgName("clusterName InstanceName true/false");

    Option enablePartitionOption =
        OptionBuilder.hasArgs()
                     .withLongOpt(enablePartition)
                     .withDescription("Enable/disable partitions")
                     .create();
    enablePartitionOption.setRequired(false);
    enablePartitionOption.setArgName("true/false clusterName instanceName resourceName partitionName1...");

    Option enableClusterOption =
        OptionBuilder.withLongOpt(enableCluster)
                     .withDescription("pause/resume the controller of a cluster")
                     .create();
    enableClusterOption.setArgs(2);
    enableClusterOption.setRequired(false);
    enableClusterOption.setArgName("clusterName true/false");

    Option resetPartitionOption =
        OptionBuilder.withLongOpt(resetPartition)
                     .withDescription("Reset a partition in error state")
                     .create();
    resetPartitionOption.setArgs(4);
    resetPartitionOption.setRequired(false);
    resetPartitionOption.setArgName("clusterName instanceName resourceName partitionName");

    Option resetInstanceOption =
        OptionBuilder.withLongOpt(resetInstance)
                     .withDescription("Reset all partitions in error state for an instance")
                     .create();
    resetInstanceOption.setArgs(2);
    resetInstanceOption.setRequired(false);
    resetInstanceOption.setArgName("clusterName instanceName");

    Option resetResourceOption =
        OptionBuilder.withLongOpt(resetResource)
                     .withDescription("Reset all partitions in error state for a resource")
                     .create();
    resetResourceOption.setArgs(2);
    resetResourceOption.setRequired(false);
    resetResourceOption.setArgName("clusterName resourceName");

    Option listStateModelsOption =
        OptionBuilder.withLongOpt(listStateModels)
                     .withDescription("Query info of state models in a cluster")
                     .create();
    listStateModelsOption.setArgs(1);
    listStateModelsOption.setRequired(false);
    listStateModelsOption.setArgName("clusterName");

    Option listStateModelOption =
        OptionBuilder.withLongOpt(listStateModel)
                     .withDescription("Query info of a state model in a cluster")
                     .create();
    listStateModelOption.setArgs(2);
    listStateModelOption.setRequired(false);
    listStateModelOption.setArgName("clusterName stateModelName");

    Option addStatOption =
        OptionBuilder.withLongOpt(addStat)
                     .withDescription("Add a persistent stat")
                     .create();
    addStatOption.setArgs(2);
    addStatOption.setRequired(false);
    addStatOption.setArgName("clusterName statName");
    Option addAlertOption =
        OptionBuilder.withLongOpt(addAlert).withDescription("Add an alert").create();
    addAlertOption.setArgs(2);
    addAlertOption.setRequired(false);
    addAlertOption.setArgName("clusterName alertName");

    Option dropStatOption =
        OptionBuilder.withLongOpt(dropStat)
                     .withDescription("Drop a persistent stat")
                     .create();
    dropStatOption.setArgs(2);
    dropStatOption.setRequired(false);
    dropStatOption.setArgName("clusterName statName");
    Option dropAlertOption =
        OptionBuilder.withLongOpt(dropAlert).withDescription("Drop an alert").create();
    dropAlertOption.setArgs(2);
    dropAlertOption.setRequired(false);
    dropAlertOption.setArgName("clusterName alertName");

    // set/get configs option
    Option setConfOption =
        OptionBuilder.withLongOpt(setConfig).withDescription("Set a config").create();
    setConfOption.setArgs(2);
    setConfOption.setRequired(false);
    setConfOption.setArgName("ConfigScope(e.g. CLUSTER=cluster,RESOURCE=rc,...) KeyValueMap(e.g. k1=v1,k2=v2,...)");

    Option getConfOption =
        OptionBuilder.withLongOpt(getConfig).withDescription("Get a config").create();
    getConfOption.setArgs(2);
    getConfOption.setRequired(false);
    getConfOption.setArgName("ConfigScope(e.g. CLUSTER=cluster,RESOURCE=rc,...) KeySet(e.g. k1,k2,...)");

    OptionGroup group = new OptionGroup();
    group.setRequired(true);
    group.addOption(rebalanceOption);
    group.addOption(rebalanceV8Option);
    group.addOption(addResourceOption);
    group.addOption(resourceModeOption);
    group.addOption(resourceBucketSizeOption);
    group.addOption(resourceGroupMsgModeOption);
    group.addOption(expandResourceOption);
    group.addOption(expandClusterOption);
    group.addOption(resourceKeyOption);
    group.addOption(addClusterOption);
    group.addOption(activateClusterOption);
    group.addOption(deleteClusterOption);
    group.addOption(addInstanceOption);
    group.addOption(listInstancesOption);
    group.addOption(listResourceOption);
    group.addOption(listClustersOption);
    group.addOption(addIdealStateOption);
//    group.addOption(rebalanceOption);
    group.addOption(dropInstanceOption);
    group.addOption(swapInstanceOption);
    group.addOption(dropResourceOption);
    group.addOption(instanceInfoOption);
    group.addOption(clusterInfoOption);
    group.addOption(resourceInfoOption);
    group.addOption(partitionInfoOption);
    group.addOption(enableInstanceOption);
    group.addOption(enablePartitionOption);
    group.addOption(enableClusterOption);
    group.addOption(resetPartitionOption);
    group.addOption(resetInstanceOption);
    group.addOption(resetResourceOption);
    group.addOption(addStateModelDefOption);
    group.addOption(listStateModelsOption);
    group.addOption(listStateModelOption);
    group.addOption(addStatOption);
    group.addOption(addAlertOption);
    group.addOption(dropStatOption);
    group.addOption(dropAlertOption);
    group.addOption(setConfOption);
    group.addOption(getConfOption);
    group.addOption(addResourcePropertyOption);
    group.addOption(removeResourcePropertyOption);

    Options options = new Options();
    options.addOption(helpOption);
    options.addOption(zkServerOption);
    options.addOptionGroup(group);
    return options;
  }
  
  // TODO: remove this. has moved to ZkHelixAdmin
  private static byte[] readFile(String filePath) throws IOException
  {
    File file = new File(filePath);

    int size = (int) file.length();
    byte[] bytes = new byte[size];
    DataInputStream dis = new DataInputStream(new FileInputStream(file));
    int read = 0;
    int numRead = 0;
    while (read < bytes.length
        && (numRead = dis.read(bytes, read, bytes.length - read)) >= 0)
    {
      read = read + numRead;
    }
    return bytes;
  }

  public static int processCommandLineArgs(String[] cliArgs) throws Exception
  {
    CommandLineParser cliParser = new GnuParser();
    Options cliOptions = constructCommandLineOptions();
    CommandLine cmd = null;

    try
    {
      cmd = cliParser.parse(cliOptions, cliArgs);
    }
    catch (ParseException pe)
    {
      System.err.println("CommandLineClient: failed to parse command-line options: "
          + pe.toString());
      printUsage(cliOptions);
      System.exit(1);
    }

    ClusterSetup setupTool = new ClusterSetup(cmd.getOptionValue(zkServerAddress));

    if (cmd.hasOption(addCluster))
    {
      String clusterName = cmd.getOptionValue(addCluster);
      setupTool.addCluster(clusterName, false);
      return 0;
    }

    if (cmd.hasOption(activateCluster))
    {
      String clusterName = cmd.getOptionValues(activateCluster)[0];
      String grandCluster = cmd.getOptionValues(activateCluster)[1];
      boolean enable = Boolean.parseBoolean(cmd.getOptionValues(activateCluster)[2]);
      setupTool.activateCluster(clusterName, grandCluster, enable);
      return 0;
    }

    if (cmd.hasOption(dropCluster))
    {
      String clusterName = cmd.getOptionValue(dropCluster);
      setupTool.deleteCluster(clusterName);
      return 0;
    }

    if (cmd.hasOption(addInstance))
    {
      String clusterName = cmd.getOptionValues(addInstance)[0];
      String InstanceAddressInfo = cmd.getOptionValues(addInstance)[1];
      String[] InstanceAddresses = InstanceAddressInfo.split(";");
      setupTool.addInstancesToCluster(clusterName, InstanceAddresses);
      return 0;
    }

    if (cmd.hasOption(addResource))
    {
      String[] optionValues = cmd.getOptionValues(addResource);
      String clusterName = optionValues[0];
      String resourceName = optionValues[1];
      int partitions = Integer.parseInt(optionValues[2]);
      String stateModelRef = optionValues[3];
      String modeValue = IdealStateModeProperty.AUTO.toString();
      if (cmd.hasOption(mode))
      {
        modeValue = cmd.getOptionValues(mode)[0];
      }

      int bucketSizeVal = 0;
      if (cmd.hasOption(bucketSize))
      {
        bucketSizeVal = Integer.parseInt(cmd.getOptionValues(bucketSize)[0]);
      }

      boolean groupMsgMode = false;
      if (cmd.hasOption(ClusterSetup.groupMsgMode))
      {
        groupMsgMode = Boolean.parseBoolean(cmd.getOptionValues(ClusterSetup.groupMsgMode)[0]);
      }
      
      setupTool.addResourceToCluster(clusterName,
                                     resourceName,
                                     partitions,
                                     stateModelRef,
                                     modeValue,
                                     bucketSizeVal,
                                     groupMsgMode);
      return 0;
    }

    if (cmd.hasOption(rebalance))
    {
      String clusterName = cmd.getOptionValues(rebalance)[0];
      String resourceName = cmd.getOptionValues(rebalance)[1];
      int replicas = Integer.parseInt(cmd.getOptionValues(rebalance)[2]);
      if (cmd.hasOption(resourceKeyPrefix))
      {
        setupTool.rebalanceStorageCluster(clusterName,
                                          resourceName,
                                          replicas,
                                          cmd.getOptionValue(resourceKeyPrefix));
        return 0;
      }
      setupTool.rebalanceStorageCluster(clusterName, resourceName, replicas);
      return 0;
    }

    if (cmd.hasOption(rebalanceV8))
    {
      String clusterName = cmd.getOptionValues(rebalanceV8)[0];
      String resourceName = cmd.getOptionValues(rebalanceV8)[1];
      int replicas = Integer.parseInt(cmd.getOptionValues(rebalanceV8)[2]);

      setupTool.rebalanceV08(clusterName, resourceName, replicas);
    }
    
    if (cmd.hasOption(expandCluster))
    {
      String clusterName = cmd.getOptionValues(expandCluster)[0];

      setupTool.expandCluster(clusterName);
      return 0;
    }

    if (cmd.hasOption(expandResource))
    {
      String clusterName = cmd.getOptionValues(expandResource)[0];
      String resourceName = cmd.getOptionValues(expandResource)[1];
      setupTool.expandResource(clusterName, resourceName);
      return 0;
    }

    if (cmd.hasOption(dropInstance))
    {
      String clusterName = cmd.getOptionValues(dropInstance)[0];
      String InstanceAddressInfo = cmd.getOptionValues(dropInstance)[1];
      String[] InstanceAddresses = InstanceAddressInfo.split(";");
      setupTool.dropInstancesFromCluster(clusterName, InstanceAddresses);
      return 0;
    }

    if (cmd.hasOption(listClusters))
    {
      List<String> clusters = setupTool.getClusterManagementTool().getClusters();

      System.out.println("Existing clusters:");
      for (String cluster : clusters)
      {
        System.out.println(cluster);
      }
      return 0;
    }

    if (cmd.hasOption(listResources))
    {
      String clusterName = cmd.getOptionValue(listResources);
      List<String> resourceNames =
          setupTool.getClusterManagementTool().getResourcesInCluster(clusterName);

      System.out.println("Existing resources in cluster " + clusterName + ":");
      for (String resourceName : resourceNames)
      {
        System.out.println(resourceName);
      }
      return 0;
    }
    else if (cmd.hasOption(listClusterInfo))
    {
      String clusterName = cmd.getOptionValue(listClusterInfo);
      List<String> resourceNames =
          setupTool.getClusterManagementTool().getResourcesInCluster(clusterName);
      List<String> Instances =
          setupTool.getClusterManagementTool().getInstancesInCluster(clusterName);

      System.out.println("Existing resources in cluster " + clusterName + ":");
      for (String resourceName : resourceNames)
      {
        System.out.println(resourceName);
      }

      System.out.println("Instances in cluster " + clusterName + ":");
      for (String InstanceName : Instances)
      {
        System.out.println(InstanceName);
      }
      return 0;
    }
    else if (cmd.hasOption(listInstances))
    {
      String clusterName = cmd.getOptionValue(listInstances);
      List<String> Instances =
          setupTool.getClusterManagementTool().getInstancesInCluster(clusterName);

      System.out.println("Instances in cluster " + clusterName + ":");
      for (String InstanceName : Instances)
      {
        System.out.println(InstanceName);
      }
      return 0;
    }
    else if (cmd.hasOption(listInstanceInfo))
    {
      String clusterName = cmd.getOptionValues(listInstanceInfo)[0];
      String instanceName = cmd.getOptionValues(listInstanceInfo)[1];
      InstanceConfig config =
          setupTool.getClusterManagementTool().getInstanceConfig(clusterName,
                                                                 instanceName);

      String result = new String(new ZNRecordSerializer().serialize(config.getRecord()));
      System.out.println("InstanceConfig: " + result);
      return 0;
    }
    else if (cmd.hasOption(listResourceInfo))
    {
      // print out partition number, db name and replication number
      // Also the ideal states and current states
      String clusterName = cmd.getOptionValues(listResourceInfo)[0];
      String resourceName = cmd.getOptionValues(listResourceInfo)[1];
      IdealState idealState =
          setupTool.getClusterManagementTool().getResourceIdealState(clusterName,
                                                                     resourceName);
      ExternalView externalView =
          setupTool.getClusterManagementTool().getResourceExternalView(clusterName,
                                                                       resourceName);

      if (idealState != null)
      {
        System.out.println("IdealState for " + resourceName + ":");
        System.out.println(new String(new ZNRecordSerializer().serialize(idealState.getRecord())));
      }
      else
      {
        System.out.println("No idealState for " + resourceName);
      }

      System.out.println();

      if (externalView != null)
      {
        System.out.println("ExternalView for " + resourceName + ":");
        System.out.println(new String(new ZNRecordSerializer().serialize(externalView.getRecord())));
      }
      else
      {
        System.out.println("No externalView for " + resourceName);
      }
      return 0;

    }
    else if (cmd.hasOption(listPartitionInfo))
    {
      // print out where the partition master / slaves locates
      String clusterName = cmd.getOptionValues(listPartitionInfo)[0];
      String resourceName = cmd.getOptionValues(listPartitionInfo)[1];
      String partitionName = cmd.getOptionValues(listPartitionInfo)[2];
      IdealState idealState =
          setupTool.getClusterManagementTool().getResourceIdealState(clusterName,
                                                                     resourceName);
      ExternalView externalView =
          setupTool.getClusterManagementTool().getResourceExternalView(clusterName,
                                                                       resourceName);

      if (idealState != null)
      {
        ZNRecord partInfo = new ZNRecord(resourceName + "/" + partitionName);
        ZNRecord idealStateRec = idealState.getRecord();
        partInfo.setSimpleFields(idealStateRec.getSimpleFields());
        if (idealStateRec.getMapField(partitionName) != null)
        {
          partInfo.setMapField(partitionName, idealStateRec.getMapField(partitionName));
        }
        if (idealStateRec.getListField(partitionName) != null)
        {
          partInfo.setListField(partitionName, idealStateRec.getListField(partitionName));
        }
        System.out.println("IdealState for " + resourceName + "/" + partitionName + ":");
        System.out.println(new String(new ZNRecordSerializer().serialize(partInfo)));
      }
      else
      {
        System.out.println("No idealState for " + resourceName + "/" + partitionName);
      }

      System.out.println();

      if (externalView != null)
      {
        ZNRecord partInfo = new ZNRecord(resourceName + "/" + partitionName);
        ZNRecord extViewRec = externalView.getRecord();
        partInfo.setSimpleFields(extViewRec.getSimpleFields());
        if (extViewRec.getMapField(partitionName) != null)
        {
          partInfo.setMapField(partitionName, extViewRec.getMapField(partitionName));
        }
        if (extViewRec.getListField(partitionName) != null)
        {
          partInfo.setListField(partitionName, extViewRec.getListField(partitionName));
        }

        System.out.println("ExternalView for " + resourceName + "/" + partitionName + ":");
        System.out.println(new String(new ZNRecordSerializer().serialize(partInfo)));
      }
      else
      {
        System.out.println("No externalView for " + resourceName + "/" + partitionName);
      }
      return 0;

    }
    else if (cmd.hasOption(enableInstance))
    {
      String clusterName = cmd.getOptionValues(enableInstance)[0];
      String instanceName = cmd.getOptionValues(enableInstance)[1];
      if (instanceName.contains(":"))
      {
        instanceName = instanceName.replaceAll(":", "_");
      }
      boolean enabled =
          Boolean.parseBoolean(cmd.getOptionValues(enableInstance)[2].toLowerCase());

      setupTool.getClusterManagementTool().enableInstance(clusterName,
                                                          instanceName,
                                                          enabled);
      return 0;
    }
    else if (cmd.hasOption(enablePartition))
    {
      String[] args = cmd.getOptionValues(enablePartition);

      boolean enabled = Boolean.parseBoolean(args[0].toLowerCase());
      String clusterName = args[1];
      String instanceName = args[2];
      String resourceName = args[3];

      List<String> partitionNames =
          Arrays.asList(Arrays.copyOfRange(args, 4, args.length));
      setupTool.getClusterManagementTool().enablePartition(enabled,
                                                           clusterName,
                                                           instanceName,
                                                           resourceName,
                                                           partitionNames);
      return 0;
    }
    else if (cmd.hasOption(resetPartition))
    {
      String[] args = cmd.getOptionValues(resetPartition);

      String clusterName = args[0];
      String instanceName = args[1];
      String resourceName = args[2];
      List<String> partitionNames =
          Arrays.asList(Arrays.copyOfRange(args, 3, args.length));

      setupTool.getClusterManagementTool().resetPartition(clusterName,
                                                          instanceName,
                                                          resourceName,
                                                          partitionNames);
      return 0;
    }
    else if (cmd.hasOption(resetInstance))
    {
      String[] args = cmd.getOptionValues(resetInstance);

      String clusterName = args[0];
      List<String> instanceNames =
          Arrays.asList(Arrays.copyOfRange(args, 1, args.length));

      setupTool.getClusterManagementTool().resetInstance(clusterName, instanceNames);
      return 0;
    }
    else if (cmd.hasOption(resetResource))
    {
      String[] args = cmd.getOptionValues(resetResource);

      String clusterName = args[0];
      List<String> resourceNames =
          Arrays.asList(Arrays.copyOfRange(args, 1, args.length));

      setupTool.getClusterManagementTool().resetResource(clusterName, resourceNames);
      return 0;
    }
    else if (cmd.hasOption(enableCluster))
    {
      String[] params = cmd.getOptionValues(enableCluster);
      String clusterName = params[0];
      boolean enabled = Boolean.parseBoolean(params[1].toLowerCase());
      setupTool.getClusterManagementTool().enableCluster(clusterName, enabled);

      return 0;
    }
    else if (cmd.hasOption(listStateModels))
    {
      String clusterName = cmd.getOptionValues(listStateModels)[0];

      List<String> stateModels =
          setupTool.getClusterManagementTool().getStateModelDefs(clusterName);

      System.out.println("Existing state models:");
      for (String stateModel : stateModels)
      {
        System.out.println(stateModel);
      }
      return 0;
    }
    else if (cmd.hasOption(listStateModel))
    {
      String clusterName = cmd.getOptionValues(listStateModel)[0];
      String stateModel = cmd.getOptionValues(listStateModel)[1];
      StateModelDefinition stateModelDef =
          setupTool.getClusterManagementTool().getStateModelDef(clusterName, stateModel);
      String result =
          new String(new ZNRecordSerializer().serialize(stateModelDef.getRecord()));
      System.out.println("StateModelDefinition: " + result);
      return 0;
    }
    else if (cmd.hasOption(addStateModelDef))
    {
      String clusterName = cmd.getOptionValues(addStateModelDef)[0];
      String stateModelFile = cmd.getOptionValues(addStateModelDef)[1];

      ZNRecord stateModelRecord =
          (ZNRecord) (new ZNRecordSerializer().deserialize(readFile(stateModelFile)));
      if (stateModelRecord.getId() == null || stateModelRecord.getId().length() == 0)
      {
        throw new IllegalArgumentException("ZNRecord for state model definition must have an id");
      }
      setupTool.getClusterManagementTool()
               .addStateModelDef(clusterName,
                                 stateModelRecord.getId(),
                                 new StateModelDefinition(stateModelRecord));
      return 0;
    }
    else if (cmd.hasOption(addIdealState))
    {
      String clusterName = cmd.getOptionValues(addIdealState)[0];
      String resourceName = cmd.getOptionValues(addIdealState)[1];
      String idealStateFile = cmd.getOptionValues(addIdealState)[2];

      setupTool.addIdealState(clusterName, resourceName, idealStateFile);
      return 0;
    }
    else if (cmd.hasOption(addStat))
    {
      String clusterName = cmd.getOptionValues(addStat)[0];
      String statName = cmd.getOptionValues(addStat)[1];

      setupTool.getClusterManagementTool().addStat(clusterName, statName);
    }
    else if (cmd.hasOption(addAlert))
    {
      String clusterName = cmd.getOptionValues(addAlert)[0];
      String alertName = cmd.getOptionValues(addAlert)[1];

      setupTool.getClusterManagementTool().addAlert(clusterName, alertName);
    }
    else if (cmd.hasOption(dropStat))
    {
      String clusterName = cmd.getOptionValues(dropStat)[0];
      String statName = cmd.getOptionValues(dropStat)[1];

      setupTool.getClusterManagementTool().dropStat(clusterName, statName);
    }
    else if (cmd.hasOption(dropAlert))
    {
      String clusterName = cmd.getOptionValues(dropAlert)[0];
      String alertName = cmd.getOptionValues(dropAlert)[1];

      setupTool.getClusterManagementTool().dropAlert(clusterName, alertName);
    }
    else if (cmd.hasOption(dropResource))
    {
      String clusterName = cmd.getOptionValues(dropResource)[0];
      String resourceName = cmd.getOptionValues(dropResource)[1];

      setupTool.getClusterManagementTool().dropResource(clusterName, resourceName);
    }
    else if (cmd.hasOption(swapInstance))
    {
      String clusterName = cmd.getOptionValues(swapInstance)[0];
      String oldInstanceName = cmd.getOptionValues(swapInstance)[1];
      String newInstanceName = cmd.getOptionValues(swapInstance)[2];

      setupTool.swapInstance(clusterName, oldInstanceName, newInstanceName);
    }
    else if (cmd.hasOption(setConfig))
    {
      String scopeStr = cmd.getOptionValues(setConfig)[0];
      String propertiesStr = cmd.getOptionValues(setConfig)[1];
      setupTool.setConfig(scopeStr, propertiesStr);
    }
    else if (cmd.hasOption(getConfig))
    {
      String scopeStr = cmd.getOptionValues(getConfig)[0];
      String keysStr = cmd.getOptionValues(getConfig)[1];
      setupTool.getConfig(scopeStr, keysStr);
    }
    else if (cmd.hasOption(help))
    {
      printUsage(cliOptions);
      return 0;
    }
    else if (cmd.hasOption(addResourceProperty))
    {
      String clusterName = cmd.getOptionValues(addResourceProperty)[0];
      String resourceName = cmd.getOptionValues(addResourceProperty)[1];
      String propertyKey = cmd.getOptionValues(addResourceProperty)[2];
      String propertyVal = cmd.getOptionValues(addResourceProperty)[3];

      setupTool.addResourceProperty(clusterName, resourceName, propertyKey, propertyVal);
      return 0;
    }
    else if (cmd.hasOption(removeResourceProperty))
    {
      String clusterName = cmd.getOptionValues(removeResourceProperty)[0];
      String resourceName = cmd.getOptionValues(removeResourceProperty)[1];
      String propertyKey = cmd.getOptionValues(removeResourceProperty)[2];

      setupTool.removeResourceProperty(clusterName, resourceName, propertyKey);
      return 0;
    }
    return 0;
  }

  // TODO: remove this. has moved to ZkHelixAdmin
  public void addIdealState(String clusterName, String resourceName, String idealStateFile) throws IOException
  {
    ZNRecord idealStateRecord =
        (ZNRecord) (new ZNRecordSerializer().deserialize(readFile(idealStateFile)));
    if (idealStateRecord.getId() == null
        || !idealStateRecord.getId().equals(resourceName))
    {
      throw new IllegalArgumentException("ideal state must have same id as resource name");
    }
    _admin.setResourceIdealState(clusterName,
                                 resourceName,
                                 new IdealState(idealStateRecord));
  }

  public void addResourceProperty(String clusterName,
                                  String resourceName,
                                  String propertyKey,
                                  String propertyVal)
  {
    IdealState idealState = _admin.getResourceIdealState(clusterName, resourceName);
    if (idealState == null)
    {
      throw new HelixException("Resource: " + resourceName + " has NOT been added yet");
    }
    idealState.getRecord().setSimpleField(propertyKey, propertyVal);
    _admin.setResourceIdealState(clusterName, resourceName, idealState);
  }

  public void removeResourceProperty(String clusterName,
                                     String resourceName,
                                     String propertyKey)
  {
    IdealState idealState = _admin.getResourceIdealState(clusterName, resourceName);
    if (idealState == null)
    {
      throw new HelixException("Resource: " + resourceName + " has NOT been added yet");
    }
    idealState.getRecord().getSimpleFields().remove(propertyKey);
    _admin.setResourceIdealState(clusterName, resourceName, idealState);
  }

  /**
   * @param args
   * @throws Exception
   * @throws JsonMappingException
   * @throws JsonGenerationException
   */
  public static void main(String[] args) throws Exception
  {
    // debug
    // System.out.println("args(" + args.length + "): " + Arrays.asList(args));

    if (args.length == 1 && args[0].equals("setup-test-cluster"))
    {
      System.out.println("By default setting up ");
      new ClusterSetup("localhost:2181").setupTestCluster("storage-integration-cluster");
      new ClusterSetup("localhost:2181").setupTestCluster("relay-integration-cluster");
      System.exit(0);
    }

    int ret = processCommandLineArgs(args);
    System.exit(ret);
  }
}
