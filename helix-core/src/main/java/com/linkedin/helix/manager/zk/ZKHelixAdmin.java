package com.linkedin.helix.manager.zk;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.log4j.Logger;

import com.linkedin.helix.ConfigAccessor;
import com.linkedin.helix.ConfigScope;
import com.linkedin.helix.ConfigScope.ConfigScopeProperty;
import com.linkedin.helix.DataAccessor;
import com.linkedin.helix.HelixAdmin;
import com.linkedin.helix.HelixConstants;
import com.linkedin.helix.HelixException;
import com.linkedin.helix.PropertyPathConfig;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.alerts.AlertsHolder;
import com.linkedin.helix.alerts.StatsHolder;
import com.linkedin.helix.model.Alerts;
import com.linkedin.helix.model.ExternalView;
import com.linkedin.helix.model.IdealState;
import com.linkedin.helix.model.IdealState.IdealStateModeProperty;
import com.linkedin.helix.model.IdealState.IdealStateProperty;
import com.linkedin.helix.model.InstanceConfig;
import com.linkedin.helix.model.LiveInstance;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.model.Message.MessageState;
import com.linkedin.helix.model.Message.MessageType;
import com.linkedin.helix.model.PersistentStats;
import com.linkedin.helix.model.StateModelDefinition;
import com.linkedin.helix.util.HelixUtil;

public class ZKHelixAdmin implements HelixAdmin
{

  private final ZkClient _zkClient;
  private final ConfigAccessor _configAccessor;

  private static Logger logger = Logger.getLogger(ZKHelixAdmin.class);

  public ZKHelixAdmin(ZkClient zkClient)
  {
    _zkClient = zkClient;
    _configAccessor = new ConfigAccessor(zkClient);
  }

  @Override
  public void addInstance(String clusterName, InstanceConfig instanceConfig)
  {
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient))
    {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }
    String instanceConfigsPath = PropertyPathConfig.getPath(PropertyType.CONFIGS, clusterName,
        ConfigScopeProperty.PARTICIPANT.toString());
    String nodeId = instanceConfig.getId();
    String instanceConfigPath = instanceConfigsPath + "/" + nodeId;

    if (_zkClient.exists(instanceConfigPath))
    {
      throw new HelixException("Node " + nodeId + " already exists in cluster " + clusterName);
    }

    ZKUtil.createChildren(_zkClient, instanceConfigsPath, instanceConfig.getRecord());

    _zkClient.createPersistent(HelixUtil.getMessagePath(clusterName, nodeId), true);
    _zkClient.createPersistent(HelixUtil.getCurrentStateBasePath(clusterName, nodeId), true);
    _zkClient.createPersistent(HelixUtil.getErrorsPath(clusterName, nodeId), true);
    _zkClient.createPersistent(HelixUtil.getStatusUpdatesPath(clusterName, nodeId), true);
  }

  @Override
  public void dropInstance(String clusterName, InstanceConfig instanceConfig)
  {
    // String instanceConfigsPath = HelixUtil.getConfigPath(clusterName);
    String instanceConfigsPath = PropertyPathConfig.getPath(PropertyType.CONFIGS, clusterName,
        ConfigScopeProperty.PARTICIPANT.toString());
    String nodeId = instanceConfig.getId();
    String instanceConfigPath = instanceConfigsPath + "/" + nodeId;
    String instancePath = HelixUtil.getInstancePath(clusterName, nodeId);

    if (!_zkClient.exists(instanceConfigPath))
    {
      throw new HelixException("Node " + nodeId + " does not exist in config for cluster "
          + clusterName);
    }

    if (!_zkClient.exists(instancePath))
    {
      throw new HelixException("Node " + nodeId + " does not exist in instances for cluster "
          + clusterName);
    }

    // delete config path
    ZKUtil.dropChildren(_zkClient, instanceConfigsPath, instanceConfig.getRecord());

    // delete instance path
    _zkClient.deleteRecursive(instancePath);
  }

  @Override
  public InstanceConfig getInstanceConfig(String clusterName, String instanceName)
  {
    // String instanceConfigsPath = HelixUtil.getConfigPath(clusterName);

    // String instanceConfigPath = instanceConfigsPath + "/" + instanceName;
    String instanceConfigPath = PropertyPathConfig.getPath(PropertyType.CONFIGS, clusterName,
        ConfigScopeProperty.PARTICIPANT.toString(), instanceName);
    if (!_zkClient.exists(instanceConfigPath))
    {
      throw new HelixException("instance" + instanceName + " does not exist in cluster "
          + clusterName);
    }

    DataAccessor accessor = new ZKDataAccessor(clusterName, _zkClient);
    return accessor.getProperty(InstanceConfig.class, PropertyType.CONFIGS,
        ConfigScopeProperty.PARTICIPANT.toString(),
        instanceName);
  }

  @Override
  public void enableInstance(String clusterName, String instanceName, boolean enabled)
  {
    String path = PropertyPathConfig.getPath(PropertyType.CONFIGS, clusterName,
        ConfigScopeProperty.PARTICIPANT.toString(),
        instanceName);

    if (_zkClient.exists(path))
    {
      DataAccessor accessor = new ZKDataAccessor(clusterName, _zkClient);
      InstanceConfig nodeConfig = accessor.getProperty(InstanceConfig.class, PropertyType.CONFIGS,
          ConfigScopeProperty.PARTICIPANT.toString(), instanceName);

      nodeConfig.setInstanceEnabled(enabled);
      accessor.setProperty(PropertyType.CONFIGS, nodeConfig,
          ConfigScopeProperty.PARTICIPANT.toString(),
          instanceName);
    } else
    {
      throw new HelixException("Cluster " + clusterName + ", instance config for " + instanceName
          + " does not exist");
    }
  }

  @Override
  public void enablePartition(String clusterName, String instanceName, String resourceName,
      String partition, boolean enabled)
  {
    String path = PropertyPathConfig.getPath(PropertyType.CONFIGS, clusterName,
        ConfigScopeProperty.PARTICIPANT.toString(),
        instanceName);
    if (_zkClient.exists(path))
    {
      DataAccessor accessor = new ZKDataAccessor(clusterName, _zkClient);
      InstanceConfig nodeConfig = accessor.getProperty(InstanceConfig.class, PropertyType.CONFIGS,
          ConfigScopeProperty.PARTICIPANT.toString(), instanceName);

      nodeConfig.setInstanceEnabledForPartition(partition, enabled);
      accessor.setProperty(PropertyType.CONFIGS, nodeConfig,
          ConfigScopeProperty.PARTICIPANT.toString(),
          instanceName);
    } else
    {
      throw new HelixException("Cluster " + clusterName + ", instance config for " + instanceName
          + " does not exist");
    }
  }

  @Override
  public void resetPartition(String clusterName, String instanceName, String resourceName,
      String partition)
  {
    DataAccessor accessor = new ZKDataAccessor(clusterName, _zkClient);
    LiveInstance liveInstance = accessor.getProperty(LiveInstance.class,
        PropertyType.LIVEINSTANCES, instanceName);

    if (liveInstance == null)
    {
      throw new HelixException("Can't reset state for " + resourceName + "/" + partition
          + " on " + instanceName + ", because " + instanceName + " is not alive");
    }

    String sessionId = liveInstance.getSessionId();

    IdealState idealState = accessor.getProperty(IdealState.class, PropertyType.IDEALSTATES,
        resourceName);

    if (idealState == null)
    {
      throw new HelixException("Can't reset state for " + resourceName + "/" + partition
          + " on " + instanceName + ", because " + resourceName + " is not added");
    }

    String stateModelDef = idealState.getStateModelDefRef();
    StateModelDefinition stateModel = accessor.getProperty(StateModelDefinition.class,
        PropertyType.STATEMODELDEFS, stateModelDef);

    if (stateModel == null)
    {
      throw new HelixException("Can't reset state for " + resourceName + "/" + partition
          + " on " + instanceName + ", because " + stateModelDef + " is not found");
    }

    String adminName = null;
    try
    {
      adminName = InetAddress.getLocalHost().getCanonicalHostName() + "-ADMIN";
    } catch (UnknownHostException e)
    {
      // can ignore it
      logger.info("Unable to get host name. Will set it to UNKNOWN, mostly ignorable", e);
      adminName = "UNKNOWN";
    }

    String msgId = UUID.randomUUID().toString();
    Message message = new Message(MessageType.STATE_TRANSITION, msgId);
    message.setSrcName(adminName);
    message.setTgtName(instanceName);
    message.setMsgState(MessageState.NEW);
    message.setPartitionName(partition);
    message.setResourceName(resourceName);
    message.setTgtSessionId(sessionId);
    message.setStateModelDef(stateModelDef);
    message.setFromState("ERROR");
    message.setToState(stateModel.getInitialState());
    message.setStateModelFactoryName(idealState.getStateModelFactoryName());

    accessor.setProperty(PropertyType.MESSAGES, message, instanceName, message.getId());
  }

  @Override
  public void addCluster(String clusterName, boolean overwritePrevRecord)
  {
    String root = "/" + clusterName;
    String path;

    // TODO For ease of testing only, should remove later
    if (_zkClient.exists(root))
    {
      logger.warn("Root directory exists.Cleaning the root directory:" + root
          + " overwritePrevRecord: " + overwritePrevRecord);
      if (overwritePrevRecord)
      {
        _zkClient.deleteRecursive(root);
      } else
      {
        throw new HelixException("Cluster " + clusterName + " already exists");
      }
    }

    _zkClient.createPersistent(root);

    // IDEAL STATE
    _zkClient.createPersistent(HelixUtil.getIdealStatePath(clusterName));
    // CONFIGURATIONS
    // _zkClient.createPersistent(HelixUtil.getConfigPath(clusterName));
    path = PropertyPathConfig.getPath(PropertyType.CONFIGS, clusterName,
        ConfigScopeProperty.CLUSTER.toString(), clusterName);
    _zkClient.createPersistent(path, true);
    _zkClient.writeData(path, new ZNRecord(clusterName));
    path = PropertyPathConfig.getPath(PropertyType.CONFIGS, clusterName,
        ConfigScopeProperty.PARTICIPANT.toString());
    _zkClient.createPersistent(path);
    path = PropertyPathConfig.getPath(PropertyType.CONFIGS, clusterName,
        ConfigScopeProperty.RESOURCE.toString());
    _zkClient.createPersistent(path);
    // PROPERTY STORE
    path = PropertyPathConfig.getPath(PropertyType.PROPERTYSTORE, clusterName);
    _zkClient.createPersistent(path);
    // LIVE INSTANCES
    _zkClient.createPersistent(HelixUtil.getLiveInstancesPath(clusterName));
    // MEMBER INSTANCES
    _zkClient.createPersistent(HelixUtil.getMemberInstancesPath(clusterName));
    // External view
    _zkClient.createPersistent(HelixUtil.getExternalViewPath(clusterName));
    // State model definition
    _zkClient.createPersistent(HelixUtil.getStateModelDefinitionPath(clusterName));

    // controller
    _zkClient.createPersistent(HelixUtil.getControllerPath(clusterName));
    path = PropertyPathConfig.getPath(PropertyType.HISTORY, clusterName);
    final ZNRecord emptyHistory = new ZNRecord(PropertyType.HISTORY.toString());
    final List<String> emptyList = new ArrayList<String>();
    emptyHistory.setListField(clusterName, emptyList);
    _zkClient.createPersistent(path, emptyHistory);

    path = PropertyPathConfig.getPath(PropertyType.MESSAGES_CONTROLLER, clusterName);
    _zkClient.createPersistent(path);

    path = PropertyPathConfig.getPath(PropertyType.STATUSUPDATES_CONTROLLER, clusterName);
    _zkClient.createPersistent(path);

    path = PropertyPathConfig.getPath(PropertyType.ERRORS_CONTROLLER, clusterName);
    _zkClient.createPersistent(path);
  }

  @Override
  public List<String> getInstancesInCluster(String clusterName)
  {
    String memberInstancesPath = HelixUtil.getMemberInstancesPath(clusterName);
    return _zkClient.getChildren(memberInstancesPath);
  }

  @Override
  public void addResource(String clusterName, String dbName, int partitions, String stateModelRef)
  {
    addResource(clusterName, dbName, partitions, stateModelRef,
        IdealStateModeProperty.AUTO.toString());
  }

  @Override
  public void addResource(String clusterName, String dbName, int partitions, String stateModelRef,
      String idealStateMode)
  {
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient))
    {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }
    
    IdealStateModeProperty mode = IdealStateModeProperty.AUTO;
    try
    {
      mode = IdealStateModeProperty.valueOf(idealStateMode);
    }
    catch(Exception e)
    {
      logger.error("", e);
    }
    ZNRecord idealState = new ZNRecord(dbName);
    idealState.setSimpleField(IdealStateProperty.NUM_PARTITIONS.toString(),
                              String.valueOf(partitions));
    idealState.setSimpleField(IdealStateProperty.STATE_MODEL_DEF_REF.toString(),
                              stateModelRef);
    idealState.setSimpleField(IdealStateProperty.IDEAL_STATE_MODE.toString(),
                              mode.toString());
    idealState.setSimpleField(IdealStateProperty.REPLICAS.toString(), 0 + "");
    idealState.setSimpleField(IdealStateProperty.STATE_MODEL_FACTORY_NAME.toString(),
                              HelixConstants.DEFAULT_STATE_MODEL_FACTORY);
    
    String stateModelDefPath =
        PropertyPathConfig.getPath(PropertyType.STATEMODELDEFS,
                                   clusterName,
                                   stateModelRef);
    if (!_zkClient.exists(stateModelDefPath))
    {
      throw new HelixException("State model " + stateModelRef
          + " not found in the cluster STATEMODELDEFS path");
    }

    String idealStatePath = HelixUtil.getIdealStatePath(clusterName);
    String dbIdealStatePath = idealStatePath + "/" + dbName;
    if (_zkClient.exists(dbIdealStatePath))
    {
      logger.warn("Skip the operation. DB ideal state directory exists:" + dbIdealStatePath);
      return;
    }
    ZKUtil.createChildren(_zkClient, idealStatePath, idealState);
  }

  @Override
  public List<String> getClusters()
  {
    List<String> zkToplevelPathes = _zkClient.getChildren("/");
    List<String> result = new ArrayList<String>();
    for (String pathName : zkToplevelPathes)
    {
      if (ZKUtil.isClusterSetup(pathName, _zkClient))
      {
        result.add(pathName);
      }
    }
    return result;
  }

  @Override
  public List<String> getResourcesInCluster(String clusterName)
  {
    return _zkClient.getChildren(HelixUtil.getIdealStatePath(clusterName));
  }

  @Override
  public IdealState getResourceIdealState(String clusterName, String dbName)
  {
    ZKDataAccessor accessor = new ZKDataAccessor(clusterName, _zkClient);
    return accessor.getProperty(IdealState.class, PropertyType.IDEALSTATES, dbName);
  }

  @Override
  public void setResourceIdealState(String clusterName, String dbName, IdealState idealState)
  {
    new ZKDataAccessor(clusterName, _zkClient).setProperty(PropertyType.IDEALSTATES, idealState,
        dbName);
  }

  @Override
  public ExternalView getResourceExternalView(String clusterName, String resourceName)
  {
    ZKDataAccessor accessor = new ZKDataAccessor(clusterName, _zkClient);
    return accessor.getProperty(ExternalView.class, PropertyType.EXTERNALVIEW, resourceName);
  }

  @Override
  public void addStateModelDef(String clusterName, String stateModelDef,
      StateModelDefinition stateModel)
  {
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient))
    {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }
    String stateModelDefPath = HelixUtil.getStateModelDefinitionPath(clusterName);
    String stateModelPath = stateModelDefPath + "/" + stateModelDef;
    if (_zkClient.exists(stateModelPath))
    {
      logger.warn("Skip the operation.State Model directory exists:" + stateModelPath);
      throw new HelixException("State model path " + stateModelPath + " already exists.");
    }

    new ZKDataAccessor(clusterName, _zkClient).setProperty(PropertyType.STATEMODELDEFS, stateModel,
        stateModel.getId());
  }

  @Override
  public void dropResource(String clusterName, String resourceName)
  {
    new ZKDataAccessor(clusterName, _zkClient).removeProperty(PropertyType.IDEALSTATES,
        resourceName);
  }

  @Override
  public List<String> getStateModelDefs(String clusterName)
  {
    return _zkClient.getChildren(HelixUtil.getStateModelDefinitionPath(clusterName));
  }

  @Override
  public StateModelDefinition getStateModelDef(String clusterName, String stateModelName)
  {
    ZKDataAccessor accessor = new ZKDataAccessor(clusterName, _zkClient);
    return accessor.getProperty(StateModelDefinition.class, PropertyType.STATEMODELDEFS,
        stateModelName);
  }

  @Override
  public void addStat(String clusterName, String statName)
  {
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient))
    {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }

    String persistentStatsPath = HelixUtil.getPersistentStatsPath(clusterName);
    ZKDataAccessor accessor = new ZKDataAccessor(clusterName, _zkClient);
    if (!_zkClient.exists(persistentStatsPath))
    {
      // ZKUtil.createChildren(_zkClient, persistentStatsPath, statsRec);
      _zkClient.createPersistent(persistentStatsPath);
    }
    ZNRecord statsRec = accessor.getProperty(PropertyType.PERSISTENTSTATS);
    if (statsRec == null)
    {
      statsRec = new ZNRecord(PersistentStats.nodeName); // TODO: fix naming of
                                                         // this record, if it
                                                         // matters
    }

    Map<String, Map<String, String>> currStatMap = statsRec.getMapFields();
    Map<String, Map<String, String>> newStatMap = StatsHolder.parseStat(statName);
    for (String newStat : newStatMap.keySet())
    {
      if (!currStatMap.containsKey(newStat))
      {
        currStatMap.put(newStat, newStatMap.get(newStat));
      }
    }
    statsRec.setMapFields(currStatMap);
    accessor.setProperty(PropertyType.PERSISTENTSTATS, statsRec);
  }

  @Override
  public void addAlert(String clusterName, String alertName)
  {
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient))
    {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }

    ZKDataAccessor accessor = new ZKDataAccessor(clusterName, _zkClient);
    String alertsPath = HelixUtil.getAlertsPath(clusterName);
    if (!_zkClient.exists(alertsPath))
    {
      // ZKUtil.createChildren(_zkClient, alertsPath, alertsRec);
      _zkClient.createPersistent(alertsPath);
    }
    ZNRecord alertsRec = accessor.getProperty(PropertyType.ALERTS);
    if (alertsRec == null)
    {
      alertsRec = new ZNRecord(Alerts.nodeName); // TODO: fix naming of this
                                                 // record, if it matters
    }

    Map<String, Map<String, String>> currAlertMap = alertsRec.getMapFields();
    StringBuilder newStatName = new StringBuilder();
    Map<String, String> newAlertMap = new HashMap<String, String>();
    // use AlertsHolder to get map of new stats and map for this alert
    AlertsHolder.parseAlert(alertName, newStatName, newAlertMap);

    // add stat
    addStat(clusterName, newStatName.toString());
    // add alert
    currAlertMap.put(alertName, newAlertMap);

    alertsRec.setMapFields(currAlertMap);
    accessor.setProperty(PropertyType.ALERTS, alertsRec);
  }

  @Override
  public void dropCluster(String clusterName)
  {
    logger.info("Deleting cluster " + clusterName);
    ZKDataAccessor accessor = new ZKDataAccessor(clusterName, _zkClient);
    String root = "/" + clusterName;
    if(accessor.getChildNames(PropertyType.LIVEINSTANCES).size() > 0)
    {
      throw new HelixException("There are still live instances in the cluster, shut them down first.");
    }

    if(accessor.getProperty(PropertyType.LEADER) != null)
    {
      throw new HelixException("There are still LEADER in the cluster, shut them down first.");
    }

    _zkClient.deleteRecursive(root);
  }

  @Override
  public void dropStat(String clusterName, String statName)
  {
    if (!ZKUtil.isClusterSetup(clusterName, _zkClient))
    {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }

    String persistentStatsPath = HelixUtil.getPersistentStatsPath(clusterName);
    ZKDataAccessor accessor = new ZKDataAccessor(clusterName, _zkClient);
    if (!_zkClient.exists(persistentStatsPath))
    {
      throw new HelixException("No stats node in ZK, nothing to drop");
    }
    ZNRecord statsRec = accessor.getProperty(PropertyType.PERSISTENTSTATS);
    if (statsRec == null)
    {
      throw new HelixException("No stats record in ZK, nothing to drop");
    }
    Map<String, Map<String, String>> currStatMap = statsRec.getMapFields();
    Map<String, Map<String, String>> newStatMap = StatsHolder.parseStat(statName);
    // delete each stat from stat map
    for (String newStat : newStatMap.keySet())
    {
      if (currStatMap.containsKey(newStat))
      {
        currStatMap.remove(newStat);
      }
    }
    statsRec.setMapFields(currStatMap);
    accessor.setProperty(PropertyType.PERSISTENTSTATS, statsRec);
  }

  @Override
  public void dropAlert(String clusterName, String alertName)
  {

    if (!ZKUtil.isClusterSetup(clusterName, _zkClient))
    {
      throw new HelixException("cluster " + clusterName + " is not setup yet");
    }

    String alertsPath = HelixUtil.getAlertsPath(clusterName);
    ZKDataAccessor accessor = new ZKDataAccessor(clusterName, _zkClient);
    if (!_zkClient.exists(alertsPath))
    {
      throw new HelixException("No alerts node in ZK, nothing to drop");
    }
    ZNRecord alertsRec = accessor.getProperty(PropertyType.ALERTS);
    if (alertsRec == null)
    {
      throw new HelixException("No alerts record in ZK, nothing to drop");
    }

    Map<String, Map<String, String>> currAlertMap = alertsRec.getMapFields();
    currAlertMap.remove(alertName);

    alertsRec.setMapFields(currAlertMap);
    accessor.setProperty(PropertyType.ALERTS, alertsRec);
  }

  @Override
  public void addClusterToGrandCluster(String clusterName, String grandCluster)
  {
    if (!ZKUtil.isClusterSetup(grandCluster, _zkClient))
    {
      throw new HelixException("Grand cluster " + grandCluster + " is not setup yet");
    }

    if (!ZKUtil.isClusterSetup(clusterName, _zkClient))
    {
      throw new HelixException("Cluster " + clusterName + " is not setup yet");
    }
    
    IdealState idealState = new IdealState(clusterName);

    idealState.setNumPartitions(1);
    idealState.setStateModelDefRef("LeaderStandby");


    List<String> controllers = getInstancesInCluster(grandCluster);
    if(controllers.size() == 0)
    {
      throw new HelixException("Grand cluster " + grandCluster + " has no instances");
    }
    Collections.shuffle(controllers);
    idealState.getRecord().setListField(clusterName, controllers);
    idealState.setPartitionState(clusterName, controllers.get(0), "LEADER");
    for(int i = 1; i<controllers.size();i++)
    {
      idealState.setPartitionState(clusterName, controllers.get(i), "STANDBY");
    }
    new ZKDataAccessor(grandCluster, _zkClient).setProperty(
        PropertyType.IDEALSTATES, idealState.getRecord(), idealState.getResourceName());
  }

  @Override
  public void setConfig(ConfigScope scope, Map<String, String> properties)
  {
    for (String key : properties.keySet())
    {
      _configAccessor.set(scope, key, properties.get(key));
    }
  }

  @Override
  public Map<String, String> getConfig(ConfigScope scope, Set<String> keys)
  {
    Map<String, String> properties = new TreeMap<String, String>();

    if (keys == null)
    {
      // read all simple fields

    } else
    {
      for (String key : keys)
      {
        String value = _configAccessor.get(scope, key);
        if (value == null)
        {
          logger.error("Config doesn't exist for key: " + key);
          continue;
        }
        properties.put(key, value);
      }
    }

    return properties;
  }

  @Override
  public List<String> getConfigKeys(ConfigScopeProperty scope, String clusterName, String... keys)
  {
    return _configAccessor.getKeys(scope, clusterName, keys);
  }
}
