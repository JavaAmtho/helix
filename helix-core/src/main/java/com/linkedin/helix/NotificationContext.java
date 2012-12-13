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

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NotificationContext
{
  // keys used for object map
  public static final String TASK_EXECUTOR_KEY = "TASK_EXECUTOR";
  
  // for group zk-writes from helix tasks; e.g. hwm property store
  private final ConcurrentLinkedQueue<ZkItem<ZNRecord>> _zkItemList = new ConcurrentLinkedQueue<ZkItem<ZNRecord>>();

  // for group zk-reads from helix tasks
  public final ConcurrentHashMap<String, ZNRecord> _zkReadMap = new ConcurrentHashMap<String, ZNRecord>();
  
  private Map<String, Object> _map;

  private HelixManager _manager;
  private Type _type;
  private String _pathChanged;
  private String _eventName;

  public String getEventName()
  {
    return _eventName;
  }

  public void setEventName(String eventName)
  {
    _eventName = eventName;
  }

  public NotificationContext(HelixManager manager)
  {
    _manager = manager;
    _map = new HashMap<String, Object>();
  }

  public HelixManager getManager()
  {
    return _manager;
  }

  public Map<String, Object> getMap()
  {
    return _map;
  }

  public Type getType()
  {
    return _type;
  }

  public void setManager(HelixManager manager)
  {
    this._manager = manager;
  }

  public void add(String key, Object value)
  {
    _map.put(key, value);
  }

  public void setMap(Map<String, Object> map)
  {
    this._map = map;
  }

  public void setType(Type type)
  {
    this._type = type;
  }

  public Object get(String key)
  {
    return _map.get(key);
  }

  public enum Type
  {
    INIT, CALLBACK, FINALIZE
  }

  public String getPathChanged()
  {
    return _pathChanged;
  }

  public void setPathChanged(String pathChanged)
  {
    this._pathChanged = pathChanged;
  }
  
  public void addZkItem(ZkItem<ZNRecord> item)
  {
      _zkItemList.add(item);
  }
  
  public Queue<ZkItem<ZNRecord>> getZkItemList()
  {
      return _zkItemList;
  }
}
