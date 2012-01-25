package com.linkedin.clustermanager.controller.stages;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.linkedin.clustermanager.ClusterDataAccessor;
import com.linkedin.clustermanager.PropertyType;
import com.linkedin.clustermanager.ZNRecord;
import com.linkedin.clustermanager.model.CurrentState;
import com.linkedin.clustermanager.model.IdealState;
import com.linkedin.clustermanager.model.InstanceConfig;
import com.linkedin.clustermanager.model.LiveInstance;
import com.linkedin.clustermanager.model.Message;
import com.linkedin.clustermanager.model.StateModelDefinition;
import com.linkedin.clustermanager.util.ZNRecordUtil;

/**
 * Reads the data from the cluster using data accessor. This output ClusterData
 * which provides useful methods to search/lookup properties
 * 
 * @author kgopalak
 * 
 */
public class ClusterDataCache
{
  private Map<String, LiveInstance> _liveInstanceMap;
  private Map<String, IdealState> _idealStateMap;
  private Map<String, StateModelDefinition> _stateModelDefMap;
  private Map<String, InstanceConfig> _instanceConfigMap;
  private Map<String, Map<String, Map<String, CurrentState>>> _currentStateMap;
  private Map<String, List<Message>> _messageMap;

  private static final Logger logger = Logger.getLogger(ClusterDataCache.class
      .getName());

  private <T extends Object> Map<String, T> retrieve(
      ClusterDataAccessor dataAccessor, PropertyType type, Class<T> clazz,
      String... keys)
  {
    List<ZNRecord> instancePropertyList = dataAccessor.getChildValues(type,
        keys);
    Map<String, T> map = ZNRecordUtil.convertListToTypedMap(
        instancePropertyList, clazz);
    return map;
  }

  private <T extends Object> Map<String, T> retrieve(
      ClusterDataAccessor dataAccessor, PropertyType type, Class<T> clazz)
  {

    List<ZNRecord> clusterPropertyList;
    clusterPropertyList = dataAccessor.getChildValues(type);
    Map<String, T> map = ZNRecordUtil.convertListToTypedMap(
        clusterPropertyList, clazz);
    return map;
  }

  public boolean refresh(ClusterDataAccessor dataAccessor)
  {
    _idealStateMap = retrieve(dataAccessor, PropertyType.IDEALSTATES,
        IdealState.class);
    _liveInstanceMap = retrieve(dataAccessor, PropertyType.LIVEINSTANCES,
        LiveInstance.class);

    for (LiveInstance instance : _liveInstanceMap.values())
    {
      logger.trace("live instance: " + instance.getInstanceName() + " "
          + instance.getSessionId());
    }
    _stateModelDefMap = retrieve(dataAccessor, PropertyType.STATEMODELDEFS,
        StateModelDefinition.class);
    _instanceConfigMap = retrieve(dataAccessor, PropertyType.CONFIGS,
        InstanceConfig.class);

    _messageMap = new HashMap<String, List<Message>>();
    for (String instanceName : _liveInstanceMap.keySet())
    {
      List<ZNRecord> childValues = dataAccessor.getChildValues(
          PropertyType.MESSAGES, instanceName);
      List<Message> messages = ZNRecordUtil.convertListToTypedList(childValues,
          Message.class);
      _messageMap.put(instanceName, messages);
    }

    _currentStateMap = new HashMap<String, Map<String, Map<String, CurrentState>>>();
    for (String instanceName : _liveInstanceMap.keySet())
    {
      LiveInstance liveInstance = _liveInstanceMap.get(instanceName);
      String sessionId = liveInstance.getSessionId();
      Map<String, CurrentState> resourceGroupStateMap = retrieve(dataAccessor,
          PropertyType.CURRENTSTATES, CurrentState.class, instanceName,
          sessionId);
      if (!_currentStateMap.containsKey(instanceName))
      {
        _currentStateMap.put(instanceName,
            new HashMap<String, Map<String, CurrentState>>());
      }
      if (!_currentStateMap.get(instanceName).containsKey(sessionId))
      {
        _currentStateMap.get(instanceName)
            .put(sessionId, resourceGroupStateMap);
      }

    }

    return true;
  }

  public Map<String, IdealState> getIdealStates()
  {
    return _idealStateMap;
  }

  public Map<String, LiveInstance> getLiveInstances()
  {
    return _liveInstanceMap;
  }

  public Map<String, CurrentState> getCurrentState(String instanceName,
      String clientSessionId)
  {
    return _currentStateMap.get(instanceName).get(clientSessionId);
  }

  public List<Message> getMessages(String instanceName)
  {

    List<Message> list = _messageMap.get(instanceName);
    if (list != null)
    {
      return list;
    } else
    {
      return Collections.emptyList();
    }
  }

  public StateModelDefinition getStateModelDef(String stateModelDefRef)
  {

    return _stateModelDefMap.get(stateModelDefRef);
  }

  public IdealState getIdealState(String resourceGroupName)
  {
    return _idealStateMap.get(resourceGroupName);
  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    sb.append("liveInstanceMap:" + _liveInstanceMap).append("\n");
    sb.append("idealStateMap:" + _idealStateMap).append("\n");
    sb.append("stateModelDefMap:" + _stateModelDefMap).append("\n");
    sb.append("instanceConfigMap:" + _instanceConfigMap).append("\n");
    sb.append("currentStateMap:" + _currentStateMap).append("\n");
    sb.append("messageMap:" + _messageMap).append("\n");
    
    return sb.toString();
  }
}
