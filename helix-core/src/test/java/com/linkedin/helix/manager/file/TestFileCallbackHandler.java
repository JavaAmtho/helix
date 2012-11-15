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

import org.testng.annotations.Test;
import org.testng.AssertJUnit;
import org.apache.zookeeper.Watcher.Event.EventType;

import com.linkedin.helix.InstanceType;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.HelixConstants.ChangeType;
import com.linkedin.helix.manager.MockListener;
import com.linkedin.helix.manager.file.FileCallbackHandler;
import com.linkedin.helix.store.PropertyJsonComparator;
import com.linkedin.helix.store.PropertyJsonSerializer;
import com.linkedin.helix.store.PropertyStoreException;
import com.linkedin.helix.store.file.FilePropertyStore;

public class TestFileCallbackHandler
{
  @Test(groups = { "unitTest" })
  public void testFileCallbackHandler()
  {
    final String clusterName = "TestFileCallbackHandler";
    final String rootNamespace = "/tmp/" + clusterName;
    final String instanceName = "controller_0";
    MockListener listener = new MockListener();

    PropertyJsonSerializer<ZNRecord> serializer =
        new PropertyJsonSerializer<ZNRecord>(ZNRecord.class);
    PropertyJsonComparator<ZNRecord> comparator =
        new PropertyJsonComparator<ZNRecord>(ZNRecord.class);
    FilePropertyStore<ZNRecord> store =
        new FilePropertyStore<ZNRecord>(serializer, rootNamespace, comparator);
    try
    {
      store.removeRootNamespace();
    }
    catch (PropertyStoreException e)
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    listener.reset();
    MockFileHelixManager manager =
        new MockFileHelixManager(clusterName, instanceName, InstanceType.CONTROLLER, store);
    FileCallbackHandler handler =
        new FileCallbackHandler(manager,
                                   rootNamespace,
                                   listener,
                                   new EventType[] { EventType.NodeChildrenChanged,
                                       EventType.NodeDeleted, EventType.NodeCreated },
                                   ChangeType.CONFIG);
    AssertJUnit.assertEquals(listener, handler.getListener());
    AssertJUnit.assertEquals(rootNamespace, handler.getPath());
    AssertJUnit.assertTrue(listener.isConfigChangeListenerInvoked);

    handler =
        new FileCallbackHandler(manager,
                                   rootNamespace,
                                   listener,
                                   new EventType[] { EventType.NodeChildrenChanged,
                                       EventType.NodeDeleted, EventType.NodeCreated },
                                   ChangeType.EXTERNAL_VIEW);
    AssertJUnit.assertTrue(listener.isExternalViewChangeListenerInvoked);

    EventType[] eventTypes = new EventType[] { EventType.NodeChildrenChanged,
        EventType.NodeDeleted, EventType.NodeCreated };
    handler =
        new FileCallbackHandler(manager,
                                   rootNamespace,
                                   listener,
                                   eventTypes,
                                   ChangeType.CONTROLLER);
    AssertJUnit.assertEquals(handler.getEventTypes(), eventTypes);
    AssertJUnit.assertTrue(listener.isControllerChangeListenerInvoked);
    
    listener.reset();
    handler.reset();
    AssertJUnit.assertTrue(listener.isControllerChangeListenerInvoked);

    listener.reset();
    handler.onPropertyChange(rootNamespace);
    AssertJUnit.assertTrue(listener.isControllerChangeListenerInvoked);

    store.stop();
  }
}
