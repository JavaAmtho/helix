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
package com.linkedin.helix.participant.statemachine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class StateModelFactory<T extends StateModel>
{
  private final ConcurrentMap<String, T> _stateModelMap = new ConcurrentHashMap<String, T>();

  /**
   * This method will be invoked only once per partitionName per session
   * 
   * @param partitionName
   * @return
   */
  public abstract T createNewStateModel(String partitionName);

  /**
   * Add a state model for a partition
   * 
   * @param partitionName
   * @return
   */
  public void addStateModel(String partitionName, T stateModel)
  {
    _stateModelMap.put(partitionName, stateModel);
  }
  
  /**
   * Create a state model for a partition
   * 
   * @param partitionName
   */
  public void createAndAddStateModel(String partitionName)
  {
    _stateModelMap.put(partitionName, createNewStateModel(partitionName));
  }

  /**
   * Get the state model for a partition
   * 
   * @param partitionName
   * @return
   */
  public T getStateModel(String partitionName)
  {
    return _stateModelMap.get(partitionName);
  }

  public List<T> getStateModel(List<String> partitionNames)
  {
    List<T> stateModels = new ArrayList<T>();
    for (String partitionName : partitionNames) {
	stateModels.add(_stateModelMap.get(partitionName));
    }
    return stateModels;
  }

  /**
   * Get the state model map
   * 
   * @return
   */
  public Map<String, T> getStateModelMap()
  {
    return _stateModelMap;
  }
}
