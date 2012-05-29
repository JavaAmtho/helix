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
package com.linkedin.helix.manager.zk;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;

import com.linkedin.helix.DataAccessor;
import com.linkedin.helix.HelixException;
import com.linkedin.helix.PropertyPathConfig;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.ZNRecordDecorator;

public class ZKDataAccessor implements DataAccessor
{
  private static Logger                            logger =
                                                              Logger.getLogger(ZKDataAccessor.class);

  protected final String                           _clusterName;
  protected final ZkClient                         _zkClient;

  /**
   * If a PropertyType has children (e.g. CONFIGS), then the parent path is the first key
   * and child path is the second key; If a PropertyType has no child (e.g. LEADER), then
   * no cache
   */
  private final Map<String, Map<String, ZNRecord>> _cache =
                                                              new ConcurrentHashMap<String, Map<String, ZNRecord>>();

  public ZKDataAccessor(String clusterName, ZkClient zkClient)
  {
    _clusterName = clusterName;
    _zkClient = zkClient;
  }

  @Override
  public boolean setProperty(PropertyType type, ZNRecordDecorator value, String... keys)
  {
    if (!value.isValid())
    {
      throw new HelixException("The ZNRecord for " + type + " is not valid.");
    }
    return setProperty(type, value.getRecord(), keys);
  }

  @Override
  public boolean setProperty(PropertyType type, ZNRecord value, String... keys)
  {
    String path = PropertyPathConfig.getPath(type, _clusterName, keys);
    if (type.equals(PropertyType.MESSAGES))
    {
      _zkClient.asyncCreate(path, value, CreateMode.PERSISTENT);
      return true;
    }
    String parent = new File(path).getParent();
    if (!_zkClient.exists(parent))
    {
      _zkClient.createPersistent(parent, true);
    }

    if (_zkClient.exists(path))
    {
      if (type.isCreateOnlyIfAbsent())
      {
        return false;
      }
      else
      {
        ZKUtil.createOrUpdate(_zkClient, path, value, type.isPersistent(), false);
      }
    }
    else
    {
      try
      {
        CreateMode mode =
            (type.isPersistent()) ? CreateMode.PERSISTENT : CreateMode.EPHEMERAL;
        if (!type.isAsync())
        {
          _zkClient.create(path, value, mode);
        }
        else
        {
          ZKUtil.asyncCreateOrUpdate(_zkClient,
                                     path,
                                     value,
                                     type.isPersistent(),
                                     type.isMergeOnUpdate());
        }
      }
      catch (Exception e)
      {
        logger.warn("Exception while creating path:" + path
            + " Most likely due to race condition(Ignorable).", e);
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean updateProperty(PropertyType type,
                                ZNRecordDecorator value,
                                String... keys)
  {
    return updateProperty(type, value.getRecord(), keys);
  }

  @Override
  public boolean updateProperty(PropertyType type, ZNRecord value, String... keys)
  {
    String path = PropertyPathConfig.getPath(type, _clusterName, keys);
    if (type.isUpdateOnlyOnExists())
    {
      ZKUtil.updateIfExists(_zkClient, path, value, type.isMergeOnUpdate());
    }
    else
    {
      String parent = new File(path).getParent();

      if (!_zkClient.exists(parent))
      {
        _zkClient.createPersistent(parent, true);
      }

      // HACK: using write through cache for CurrentState update
      // if (type == PropertyType.CURRENTSTATES)
      // {
      // String csPath = path.substring(0, path.lastIndexOf('/'));
      // String resourceName = path.substring(path.lastIndexOf('/') + 1);
      //
      // synchronized (_cache)
      // {
      // if (_cache.containsKey(csPath) && _cache.get(csPath).containsKey(resourceName))
      // {
      // int curVersion = 0;
      // Stat stat;
      // try
      // {
      // ZNRecord csRecord = _cache.get(csPath).get(resourceName);
      // curVersion = csRecord.getVersion();
      // csRecord.merge(value);
      // _zkClient.writeData(path, csRecord, curVersion);
      // stat = _zkClient.getStat(path);
      // csRecord.setVersion(stat.getVersion());
      // _cache.get(csPath).put(resourceName, csRecord);
      // return true;
      // } catch (ZkBadVersionException e)
      // {
      // stat = _zkClient.getStat(path);
      // logger.error("bad zk version. cached:" + curVersion + ", actual:" +
      // stat.getVersion());
      // }
      // } else
      // {
      // if (!_cache.containsKey(csPath))
      // {
      // _cache.put(csPath, new HashMap<String, ZNRecord>());
      // }
      // _cache.get(csPath).put(resourceName, value);
      // }
      // }
      // }

      if (!type.isAsync())
      {
        ZKUtil.createOrUpdate(_zkClient,
                              path,
                              value,
                              type.isPersistent(),
                              type.isMergeOnUpdate());
      }
      else
      {
        ZKUtil.asyncCreateOrUpdate(_zkClient,
                                   path,
                                   value,
                                   type.isPersistent(),
                                   type.isMergeOnUpdate());
      }
    }

    return true;
  }

  @Override
  public <T extends ZNRecordDecorator> T getProperty(Class<T> clazz,
                                                     PropertyType type,
                                                     String... keys)
  {
    return ZNRecordDecorator.convertToTypedInstance(clazz, getProperty(type, keys));
  }

  @Override
  public ZNRecord getProperty(PropertyType type, String... keys)
  {
    String path = PropertyPathConfig.getPath(type, _clusterName, keys);

    // HACK
    // if (type == PropertyType.CURRENTSTATES)
    // {
    // ZNRecord record = null;
    // String csPath = path.substring(0, path.lastIndexOf('/'));
    // String resourceName = path.substring(path.lastIndexOf('/') + 1);
    //
    // if (_cache.containsKey(csPath))
    // {
    // record = _cache.get(csPath).get(resourceName);
    // }
    // return record;
    // }

    if (!type.isCached())
    {
      return _zkClient.readData(path, true);
    }
    else
    {
      int len = keys.length;
      if (len == 0)
      {
        return _zkClient.readData(path, true);
      }
      else
      {
        String[] subkeys = Arrays.copyOfRange(keys, 0, len - 1);
        Map<String, ZNRecord> newChilds = refreshChildValuesCache(type, subkeys);
        return newChilds.get(keys[len - 1]);
      }
    }

  }

  @Override
  public boolean removeProperty(PropertyType type, String... keys)
  {
    String path = PropertyPathConfig.getPath(type, _clusterName, keys);
    return _zkClient.delete(path);
  }

  @Override
  public List<String> getChildNames(PropertyType type, String... keys)
  {
    String path = PropertyPathConfig.getPath(type, _clusterName, keys);
    if (_zkClient.exists(path))
    {
      return _zkClient.getChildren(path);
    }
    else
    {
      return Collections.emptyList();
    }
  }

  @Override
  public <T extends ZNRecordDecorator> List<T> getChildValues(Class<T> clazz,
                                                              PropertyType type,
                                                              String... keys)
  {
    List<ZNRecord> newChilds = getChildValues(type, keys);
    if (newChilds.size() > 0)
    {
      return ZNRecordDecorator.convertToTypedList(clazz, newChilds);
    }
    return Collections.emptyList();
  }

  @Override
  public List<ZNRecord> getChildValues(PropertyType type, String... keys)

  {
    String path = PropertyPathConfig.getPath(type, _clusterName, keys);
    // if (path == null)
    // {
    // System.err.println("path is null");
    // }

    if (_zkClient.exists(path))
    {
      if (!type.isCached())
      {
        return ZKUtil.getChildren(_zkClient, path);
      }
      else
      {
        Map<String, ZNRecord> newChilds = refreshChildValuesCache(type, keys);
        return new ArrayList<ZNRecord>(newChilds.values());
      }
    }

    return Collections.emptyList();
  }

  public void reset()
  {
    _cache.clear();
  }

  private Map<String, ZNRecord> refreshChildValuesCache(PropertyType type, String... keys)
  {
    if (!type.isCached())
    {
      throw new IllegalArgumentException("Type:" + type + " is NOT cached");
    }

    String path = PropertyPathConfig.getPath(type, _clusterName, keys);

    Map<String, ZNRecord> newChilds = refreshChildValues(path, _cache.get(path));
    if (newChilds != null && newChilds.size() > 0)
    {
      _cache.put(path, newChilds);
      return newChilds;
    }
    else
    {
      _cache.remove(path);
      return Collections.emptyMap();
    }
  }

  /**
   * Read a zookeeper node only if it's data has been changed since last read
   * 
   * @param parentPath
   * @param oldChildRecords
   * @return newChildRecords
   */
  private Map<String, ZNRecord> refreshChildValues(String parentPath,
                                                   Map<String, ZNRecord> oldChildRecords)
  {
    List<String> childs = _zkClient.getChildren(parentPath);
    if (childs == null || childs.size() == 0)
    {
      return Collections.emptyMap();
    }

    Stat newStat = new Stat();
    Map<String, ZNRecord> newChildRecords = new HashMap<String, ZNRecord>();
    for (String child : childs)
    {
      String childPath = parentPath + "/" + child;

      // assume record.id should be the last part of zookeeper path
      if (oldChildRecords == null || !oldChildRecords.containsKey(child))
      {
        ZNRecord record = _zkClient.readDataAndStat(childPath, newStat, true);
        if (record != null)
        {
          record.setVersion(newStat.getVersion());
          newChildRecords.put(child, record);
        }
      }
      else
      {
        ZNRecord oldChild = oldChildRecords.get(child);

        int oldVersion = oldChild.getVersion();
        newStat = _zkClient.getStat(childPath);
        if (newStat != null)
        {
          // System.out.print(child + " oldStat:" + oldStat);
          // System.out.print(child + " newStat:" + newStat);

          if (oldVersion < newStat.getVersion())
          {
            ZNRecord record = _zkClient.readDataAndStat(childPath, newStat, true);
            if (record != null)
            {
              record.setVersion(newStat.getVersion());
              record.setCreationTime(newStat.getCtime());
              record.setModifiedTime(newStat.getMtime());
              newChildRecords.put(child, record);
            }
          }
          else
          {
            newChildRecords.put(child, oldChild);
          }
        }
      }
    }

    return newChildRecords; // Collections.unmodifiableMap(newChildRecords);
  }

  @Override
  public <T extends ZNRecordDecorator> Map<String, T> getChildValuesMap(Class<T> clazz,
                                                                        PropertyType type,
                                                                        String... keys)
  {
    List<T> list = getChildValues(clazz, type, keys);
    return Collections.unmodifiableMap(ZNRecordDecorator.convertListToMap(list));
  }
}
