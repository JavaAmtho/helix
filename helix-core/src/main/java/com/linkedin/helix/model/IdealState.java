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
package com.linkedin.helix.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.log4j.Logger;

import com.linkedin.helix.HelixConstants;
import com.linkedin.helix.HelixProperty;
import com.linkedin.helix.ZNRecord;

/**
 * The ideal states of all partition in a resource
 */
public class IdealState extends HelixProperty
{
  public enum IdealStateProperty
  {
    NUM_PARTITIONS,
    STATE_MODEL_DEF_REF,
    STATE_MODEL_FACTORY_NAME,
    REPLICAS,
    IDEAL_STATE_MODE,
    REBALANCE_TIMER_PERIOD
  }

  public static final String QUERY_LIST = "PREFERENCE_LIST_QUERYS";

  public enum IdealStateModeProperty
  {
    AUTO, CUSTOMIZED, AUTO_REBALANCE, SEMI_CUSTOMIZED
  }

  private static final Logger logger = Logger.getLogger(IdealState.class.getName());

  public IdealState(String resourceName)
  {
    super(resourceName);
  }

  public IdealState(ZNRecord record)
  {
    super(record);
  }

  public String getResourceName()
  {
    return _record.getId();
  }

  public void setIdealStateMode(String mode)
  {
    _record.setSimpleField(IdealStateProperty.IDEAL_STATE_MODE.toString(), mode);
  }

  public IdealStateModeProperty getIdealStateMode()
  {
    String mode = _record.getSimpleField(IdealStateProperty.IDEAL_STATE_MODE.toString());
    try
    {
      return IdealStateModeProperty.valueOf(mode);
    }
    catch (Exception e)
    {
      return IdealStateModeProperty.AUTO;
    }
  }

  public void setPartitionState(String partitionName, String instanceName, String state)
  {
    Map<String, String> mapField = _record.getMapField(partitionName);
    if (mapField == null)
    {
      _record.setMapField(partitionName, new TreeMap<String, String>());
    }
    _record.getMapField(partitionName).put(instanceName, state);
  }

  public Set<String> getPartitionSet()
  {
      IdealStateModeProperty mode = getIdealStateMode();
    if (mode == IdealStateModeProperty.AUTO || mode == IdealStateModeProperty.AUTO_REBALANCE)
    {
      return _record.getListFields().keySet();
    }
    else if (mode == IdealStateModeProperty.CUSTOMIZED || mode == IdealStateModeProperty.SEMI_CUSTOMIZED)
    {
      return _record.getMapFields().keySet();
    }
    else
    {
      logger.error("Invalid ideal state mode:" + getResourceName());
      return Collections.emptySet();
    }
  }

  public Map<String, String> getInstanceStateMap(String partitionName)
  {
    return _record.getMapField(partitionName);
  }

  public Set<String> getInstanceSet(String partitionName)
  {
    if (getIdealStateMode() == IdealStateModeProperty.AUTO
        || getIdealStateMode() == IdealStateModeProperty.AUTO_REBALANCE)
    {
      List<String> prefList = _record.getListField(partitionName);
      if (prefList != null)
      {
        return new TreeSet<String>(prefList);
      }
      else
      {
        logger.warn(partitionName + " does NOT exist");
        return Collections.emptySet();
      }
    }
    else if (getIdealStateMode() == IdealStateModeProperty.CUSTOMIZED)
    {
      Map<String, String> stateMap = _record.getMapField(partitionName);
      if (stateMap != null)
      {
        return new TreeSet<String>(stateMap.keySet());
      }
      else
      {
        logger.warn(partitionName + " does NOT exist");
        return Collections.emptySet();
      }
    }
    else
    {
      logger.error("Invalid ideal state mode: " + getResourceName());
      return Collections.emptySet();
    }

  }

  public List<String> getPreferenceList(String partitionName)
  {
    List<String> instanceStateList = _record.getListField(partitionName);

    if (instanceStateList != null)
    {
      return instanceStateList;
    }
    logger.warn("Resource key:" + partitionName
        + " does not have a pre-computed preference list.");
    return null;
  }

  public String getStateModelDefRef()
  {
    return _record.getSimpleField(IdealStateProperty.STATE_MODEL_DEF_REF.toString());
  }

  public void setStateModelDefRef(String stateModel)
  {
    _record.setSimpleField(IdealStateProperty.STATE_MODEL_DEF_REF.toString(), stateModel);
  }

  public void setNumPartitions(int numPartitions)
  {
    _record.setSimpleField(IdealStateProperty.NUM_PARTITIONS.toString(),
                           String.valueOf(numPartitions));
  }

  public int getNumPartitions()
  {
    String numPartitionStr =
        _record.getSimpleField(IdealStateProperty.NUM_PARTITIONS.toString());

    try
    {
      return Integer.parseInt(numPartitionStr);
    }
    catch (Exception e)
    {
      logger.error("Can't parse number of partitions: " + numPartitionStr, e);
      return -1;
    }
  }

  public void setReplicas(String replicas)
  {
    _record.setSimpleField(IdealStateProperty.REPLICAS.toString(), replicas);
  }

  public String getReplicas()
  {
    // HACK: if replica doesn't exists, use the length of the first list field instead
    // TODO: remove it when Dbus fixed the IdealState writer
    String replica = _record.getSimpleField(IdealStateProperty.REPLICAS.toString());
    if (replica == null)
    {
      String firstPartition = null;
      switch (getIdealStateMode())
      {
      case AUTO:
        if (_record.getListFields().size() == 0)
        {
          replica = "0";
        }
        else
        {
          firstPartition = new ArrayList<String>(_record.getListFields().keySet()).get(0);
          replica = Integer.toString(firstPartition == null ? 0 : _record.getListField(firstPartition).size());
        }
        logger.warn("could NOT found replicas in idealState. Use size of the first list instead. replica: "
            + replica + ", 1st partition: " + firstPartition);
        break;
      case CUSTOMIZED:
        if (_record.getMapFields().size() == 0)
        {
          replica = "0";
        }
        else
        {
          firstPartition = new ArrayList<String>(_record.getMapFields().keySet()).get(0);
          replica = Integer.toString(firstPartition == null ? 0 : _record.getMapField(firstPartition).size());
        }
        logger.warn("could NOT found replicas in idealState. Use size of the first map instead. replica: "
            + replica + ", 1st partition: " + firstPartition);
        break;
      default:
        replica = "0";
        logger.error("could NOT determine replicas. set to 0");
        break;
      }
    }

    return replica;
  }

  public void setStateModelFactoryName(String name)
  {
    _record.setSimpleField(IdealStateProperty.STATE_MODEL_FACTORY_NAME.toString(), name);
  }

  public String getStateModelFactoryName()
  {
    String ftyName = _record.getSimpleField(IdealStateProperty.STATE_MODEL_FACTORY_NAME.toString());
    if (ftyName == null)
    {
      ftyName = HelixConstants.DEFAULT_STATE_MODEL_FACTORY;
    }
    return ftyName; 
  }

  public int getRebalanceTimerPeriod()
  {
    if (_record.getSimpleFields()
               .containsKey(IdealStateProperty.REBALANCE_TIMER_PERIOD.toString()))
    {
      try
      {
        int result =
            Integer.parseInt(_record.getSimpleField(IdealStateProperty.REBALANCE_TIMER_PERIOD.toString()));
        return result;
      }
      catch (Exception e)
      {
        logger.error("", e);
      }
    }
    return -1;
  }

  @Override
  public boolean isValid()
  {
    if (getNumPartitions() < 0)
    {
      logger.error("idealState:" + _record + " does not have number of partitions (was "
          + getNumPartitions() + ").");
      return false;
    }

    if (getStateModelDefRef() == null)
    {
      logger.error("idealStates:" + _record + " does not have state model definition.");
      return false;
    }

    if (getIdealStateMode() == IdealStateModeProperty.AUTO && getReplicas() == null)
    {
      logger.error("idealStates:" + _record + " does not have replica.");
      return false;
    }
    return true;
  }
}
