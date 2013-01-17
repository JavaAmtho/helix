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
package com.linkedin.helix.manager.file;

import com.linkedin.helix.ClusterMessagingService;
import com.linkedin.helix.ConfigAccessor;
import com.linkedin.helix.ConfigChangeListener;
import com.linkedin.helix.ConfigScope.ConfigScopeProperty;
import com.linkedin.helix.ControllerChangeListener;
import com.linkedin.helix.CurrentStateChangeListener;
import com.linkedin.helix.DataAccessor;
import com.linkedin.helix.ExternalViewChangeListener;
import com.linkedin.helix.HealthStateChangeListener;
import com.linkedin.helix.HelixAdmin;
import com.linkedin.helix.HelixDataAccessor;
import com.linkedin.helix.HelixManager;
import com.linkedin.helix.IdealStateChangeListener;
import com.linkedin.helix.InstanceConfigChangeListener;
import com.linkedin.helix.InstanceType;
import com.linkedin.helix.LiveInstanceChangeListener;
import com.linkedin.helix.MessageListener;
import com.linkedin.helix.PreConnectCallback;
import com.linkedin.helix.ScopedConfigChangeListener;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.healthcheck.ParticipantHealthReportCollector;
import com.linkedin.helix.participant.StateMachineEngine;
import com.linkedin.helix.store.PropertyStore;
import com.linkedin.helix.store.file.FilePropertyStore;
import com.linkedin.helix.store.zk.ZkHelixPropertyStore;

public class MockFileHelixManager implements HelixManager
{
//  private final FileDataAccessor _accessor;
  private final HelixDataAccessor _accessor;
  private final String _instanceName;
  private final String _clusterName;
  private final InstanceType _type;

  public MockFileHelixManager(String clusterName, String instanceName, InstanceType type,
                              FilePropertyStore<ZNRecord> store)
  {
    _instanceName = instanceName;
    _clusterName = clusterName;
    _type = type;
//    _accessor = new FileDataAccessor(store, clusterName);
    _accessor = new FileHelixDataAccessor(store, clusterName);
  }

  @Override
  public void connect() throws Exception
  {
    // TODO Auto-generated method stub

  }

  @Override
  public boolean isConnected()
  {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void disconnect()
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void addIdealStateChangeListener(IdealStateChangeListener listener) throws Exception
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void addLiveInstanceChangeListener(LiveInstanceChangeListener listener) throws Exception
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void addConfigChangeListener(ConfigChangeListener listener) throws Exception
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void addMessageListener(MessageListener listener, String instanceName) throws Exception
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void addCurrentStateChangeListener(CurrentStateChangeListener listener,
                                            String instanceName,
                                            String sessionId) throws Exception
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void addExternalViewChangeListener(ExternalViewChangeListener listener) throws Exception
  {
    // TODO Auto-generated method stub

  }

  @Override
  public boolean removeListener(Object listener)
  {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public DataAccessor getDataAccessor()
  {
    return null;
  }

  @Override
  public String getClusterName()
  {
    return _clusterName;
  }

  @Override
  public String getInstanceName()
  {
    return _instanceName;
  }

  @Override
  public String getSessionId()
  {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public long getLastNotificationTime()
  {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public void addControllerListener(ControllerChangeListener listener)
  {
    // TODO Auto-generated method stub

  }

  @Override
  public HelixAdmin getClusterManagmentTool()
  {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public PropertyStore<ZNRecord> getPropertyStore()
  {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ClusterMessagingService getMessagingService()
  {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ParticipantHealthReportCollector getHealthReportCollector()
  {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public InstanceType getInstanceType()
  {
    return _type;
  }


  @Override
  public void addHealthStateChangeListener(HealthStateChangeListener listener,
  		String instanceName) throws Exception {
  	// TODO Auto-generated method stub

  }

  @Override
  public String getVersion()
  {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public StateMachineEngine getStateMachineEngine()
  {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public boolean isLeader()
  {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public ConfigAccessor getConfigAccessor()
  {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public void startTimerTasks()
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void stopTimerTasks()
  {
    // TODO Auto-generated method stub

  }

  @Override
  public HelixDataAccessor getHelixDataAccessor()
  {
    return _accessor;
  }

  @Override
  public void addPreConnectCallback(PreConnectCallback callback)
  {
    // TODO Auto-generated method stub
    
  }

  @Override
  public ZkHelixPropertyStore<ZNRecord> getHelixPropertyStore()
  {
    // TODO Auto-generated method stub
    return null;
  }

@Override
public void addInstanceConfigChangeListener(InstanceConfigChangeListener listener) throws Exception {
	// TODO Auto-generated method stub
	
}

@Override
public void addConfigChangeListener(ScopedConfigChangeListener listener, ConfigScopeProperty scope)
        throws Exception {
	// TODO Auto-generated method stub
	
}

}
