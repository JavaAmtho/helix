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
package com.linkedin.helix.controller.stages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.linkedin.helix.HelixDataAccessor;
import com.linkedin.helix.HelixManager;
import com.linkedin.helix.PropertyKey;
import com.linkedin.helix.controller.pipeline.AbstractBaseStage;
import com.linkedin.helix.controller.pipeline.StageException;
import com.linkedin.helix.model.ExternalView;
import com.linkedin.helix.model.Partition;
import com.linkedin.helix.model.Resource;
import com.linkedin.helix.monitoring.mbeans.ClusterStatusMonitor;

public class ExternalViewComputeStage extends AbstractBaseStage
{
  private static Logger log = Logger.getLogger(ExternalViewComputeStage.class);

  @Override
  public void process(ClusterEvent event) throws Exception
  {
    long startTime = System.currentTimeMillis();
    log.info("START ExternalViewComputeStage.process()");

    HelixManager manager = event.getAttribute("helixmanager");
    Map<String, Resource> resourceMap =
        event.getAttribute(AttributeName.RESOURCES.toString());
    ClusterDataCache cache = event.getAttribute("ClusterDataCache");

    if (manager == null || resourceMap == null || cache == null)
    {
      throw new StageException("Missing attributes in event:" + event
          + ". Requires ClusterManager|RESOURCES|DataCache");
    }

    HelixDataAccessor dataAccessor = manager.getHelixDataAccessor();

    CurrentStateOutput currentStateOutput =
        event.getAttribute(AttributeName.CURRENT_STATE.toString());

    List<ExternalView> newExtViews = new ArrayList<ExternalView>();
    List<PropertyKey> keys = new ArrayList<PropertyKey>();
    
    for (String resourceName : resourceMap.keySet())
    {
      ExternalView view = new ExternalView(resourceName);
      // if resource ideal state has bucket size, set it
      // otherwise resource has been dropped, use bucket size from current state instead
      Resource resource = resourceMap.get(resourceName);
      if (resource.getBucketSize() > 0)
      {
        view.setBucketSize(resource.getBucketSize());
      } else
      {
        view.setBucketSize(currentStateOutput.getBucketSize(resourceName));
      }
      
      for (Partition partition : resource.getPartitions())
      {
        Map<String, String> currentStateMap =
            currentStateOutput.getCurrentStateMap(resourceName, partition);
        if (currentStateMap != null && currentStateMap.size() > 0)
        {
          // Set<String> disabledInstances
          // = cache.getDisabledInstancesForResource(resource.toString());
          for (String instance : currentStateMap.keySet())
          {
            // if (!disabledInstances.contains(instance))
            // {
            view.setState(partition.getPartitionName(),
                          instance,
                          currentStateMap.get(instance));
            // }
          }
        }
      }
      // Update cluster status monitor mbean
      ClusterStatusMonitor clusterStatusMonitor =
          (ClusterStatusMonitor) event.getAttribute("clusterStatusMonitor");
      if (clusterStatusMonitor != null)
      {
        clusterStatusMonitor.onExternalViewChange(view,
                                                  cache._idealStateMap.get(view.getResourceName()));
      }
      
      // compare the new external view with current one, set only on different
      Map<String, ExternalView> curExtViews =
          dataAccessor.getChildValuesMap(manager.getHelixDataAccessor()
                                             .keyBuilder()
                                             .externalViews());

      ExternalView curExtView = curExtViews.get(resourceName);
      if (curExtView == null || !curExtView.getRecord().equals(view.getRecord()))
      {
        keys.add(manager.getHelixDataAccessor().keyBuilder().externalView(resourceName));
        newExtViews.add(view);
        // dataAccessor.setProperty(PropertyType.EXTERNALVIEW, view,
        // resourceName);
      }
    }

    if (newExtViews.size() > 0)
    {
      dataAccessor.setChildren(keys, newExtViews);
    }
    
    long endTime = System.currentTimeMillis();
    log.info("END ExternalViewComputeStage.process(). took: " + (endTime - startTime) + " ms");
  }

}
