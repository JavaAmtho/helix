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
package com.linkedin.helix;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.linkedin.helix.ConfigScope.ConfigScopeProperty;
import com.linkedin.helix.model.ExternalView;
import com.linkedin.helix.model.IdealState;
import com.linkedin.helix.model.InstanceConfig;
import com.linkedin.helix.model.StateModelDefinition;

public interface HelixAdmin
{
  /**
   * Get a list of clusters under "/"
   * 
   * @return
   */
  List<String> getClusters();

  /**
   * Get a list of instances under a cluster
   * 
   * @param clusterName
   * @return
   */
  List<String> getInstancesInCluster(String clusterName);

  /**
   * Get instance configs
   * 
   * @param clusterName
   * @param instanceName
   * @return
   */
  InstanceConfig getInstanceConfig(String clusterName, String instanceName);

  /**
   * Get a list of resources in a cluster
   * 
   * @param clusterName
   * @return
   */
  List<String> getResourcesInCluster(String clusterName);

  /**
   * Add a cluster
   * 
   * @param clusterName
   * @param overwritePrevRecord
   */
  void addCluster(String clusterName, boolean overwritePrevRecord);

  /**
   * Add a cluster and also add this cluster as a resource group in the super cluster
   * 
   * @param clusterName
   * @param grandCluster
   */
  void addClusterToGrandCluster(String clusterName, String grandCluster);

  /**
   * Add a resource to a cluster, using the default ideal state mode AUTO
   * 
   * @param clusterName
   * @param resourceName
   * @param numResources
   * @param stateModelRef
   */
  void addResource(String clusterName,
                   String resourceName,
                   int numResources,
                   String stateModelRef);

  /**
   * Add a resource to a cluster
   * 
   * @param clusterName
   * @param resourceName
   * @param numResources
   * @param stateModelRef
   * @param idealStateMode
   */
  void addResource(String clusterName,
                   String resourceName,
                   int numResources,
                   String stateModelRef,
                   String idealStateMode);

  /**
   * Add a resource to a cluster, using a bucket size > 1
   * 
   * @param clusterName
   * @param resourceName
   * @param numResources
   * @param stateModelRef
   * @param idealStateMode
   * @param bucketSize
   * @param groupMsgMode
   */
  void addResource(String clusterName,
                   String resourceName,
                   int numResources,
                   String stateModelRef,
                   String idealStateMode,
                   int bucketSize,
                   boolean groupMsgMode);

  /**
   * Add an instance to a cluster
   * 
   * @param clusterName
   * @param instanceConfig
   */
  void addInstance(String clusterName, InstanceConfig instanceConfig);

  /**
   * Drop an instance from a cluster
   * 
   * @param clusterName
   * @param instanceConfig
   */
  void dropInstance(String clusterName, InstanceConfig instanceConfig);

  /**
   * Get ideal state for a resource
   * 
   * @param clusterName
   * @param dbName
   * @return
   */
  IdealState getResourceIdealState(String clusterName, String dbName);

  /**
   * Set ideal state for a resource
   * 
   * @param clusterName
   * @param resourceName
   * @param idealState
   */
  void setResourceIdealState(String clusterName,
                             String resourceName,
                             IdealState idealState);

  /**
   * Disable or enable an instance
   * 
   * @param clusterName
   * @param instanceName
   * @param enabled
   */
  void enableInstance(String clusterName, String instanceName, boolean enabled);

  /**
   * Disable or enable a list of partitions on an instance
   * 
   * @param enabled
   * @param clusterName
   * @param instanceName
   * @param resourceName
   * @param partitionNames
   */
  void enablePartition(boolean enabled,
                       String clusterName,
                       String instanceName,
                       String resourceName,
                       List<String> partitionNames);

  /**
   * Disable or enable a cluster
   * 
   * @param clusterName
   * @param enabled
   */
  void enableCluster(String clusterName, boolean enabled);

  /**
   * Reset a list of partitions in error state for an instance
   * 
   * The partitions are assume to be in error state and reset will bring them from error
   * to initial state. An error to initial state transition is required for reset.
   * 
   * @param clusterName
   * @param instanceName
   * @param resourceName
   * @param partitionNames
   */
  void resetPartition(String clusterName,
                      String instanceName,
                      String resourceName,
                      List<String> partitionNames);

  /**
   * Reset all the partitions in error state for a list of instances
   * 
   * @param clusterName
   * @param instanceNames
   */
  void resetInstance(String clusterName, List<String> instanceNames);

  /**
   * Reset all partitions in error state for a list of resources
   * 
   * @param clusterName
   * @param resourceNames
   */
  void resetResource(String clusterName, List<String> resourceNames);

  /**
   * Add a state model definition
   * 
   * @param clusterName
   * @param stateModelDef
   * @param record
   */
  void addStateModelDef(String clusterName,
                        String stateModelDef,
                        StateModelDefinition record);

  /**
   * Drop a resource from a cluster
   * 
   * @param clusterName
   * @param resourceName
   */
  void dropResource(String clusterName, String resourceName);

  /**
   * Add a statistics to a cluster
   * 
   * @param clusterName
   * @param statName
   */
  void addStat(String clusterName, String statName);

  /**
   * Add an alert to a cluster
   * 
   * @param clusterName
   * @param alertName
   */
  void addAlert(String clusterName, String alertName);

  /**
   * Drop a statistics from a cluster
   * 
   * @param clusterName
   * @param statName
   */
  void dropStat(String clusterName, String statName);

  /**
   * Drop an alert from a cluster
   * 
   * @param clusterName
   * @param alertName
   */
  void dropAlert(String clusterName, String alertName);

  /**
   * Get a list of state model definitions in a cluster
   * 
   * @param clusterName
   * @return
   */
  List<String> getStateModelDefs(String clusterName);

  /**
   * Get a state model definition in a cluster
   * 
   * @param clusterName
   * @param stateModelName
   * @return
   */
  StateModelDefinition getStateModelDef(String clusterName, String stateModelName);

  /**
   * Get external view for a resource
   * 
   * @param clusterName
   * @param resourceName
   * @return
   */
  ExternalView getResourceExternalView(String clusterName, String resourceName);

  /**
   * Drop a cluster
   * 
   * @param clusterName
   */
  void dropCluster(String clusterName);

  /**
   * Set configuration values
   * 
   * @param scope
   * @param properties
   */
  void setConfig(ConfigScope scope, Map<String, String> properties);

  /**
   * Remove configuration values
   * 
   * @param scope
   * @param keys
   */
  void removeConfig(ConfigScope scope, Set<String> keys);

  /**
   * Get configuration values
   * 
   * @param scope
   * @param keys
   * @return
   */
  Map<String, String> getConfig(ConfigScope scope, Set<String> keys);

  /**
   * Get configuration keys
   * 
   * @param scope
   * @param clusterName
   * @param keys
   * @return
   */
  List<String> getConfigKeys(ConfigScopeProperty scope,
                             String clusterName,
                             String... keys);

  /**
   * Rebalance a resource in cluster
   * 
   * @param clusterName
   * @param resourceName
   * @param replica
   * @param keyPrefix
   */
  void rebalance(String clusterName, String resourceName, int replica);

  /**
   * Add ideal state using a json format file
   * 
   * @param clusterName
   * @param resourceName
   * @param idealStateFile
   * @throws IOException
   */
  void addIdealState(String clusterName, String resourceName, String idealStateFile) throws IOException;

  /**
   * Add state model definition using a json format file
   * 
   * @param clusterName
   * @param resourceName
   * @param idealStateFile
   * @throws IOException
   */
  void addStateModelDef(String clusterName,
                        String stateModelDefName,
                        String stateModelDefFile) throws IOException;

  /**
   * Add a message contraint
   * 
   * @param constraintId
   * @param constraints
   */
  void addMessageConstraint(String clusterName,
                            String constraintId,
                            Map<String, String> constraints);
}
