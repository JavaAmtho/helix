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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.I0Itec.zkclient.DataUpdater;
import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;

import com.linkedin.helix.ConfigScope.ConfigScopeProperty;
import com.linkedin.helix.PropertyPathConfig;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.ZNRecord;

public final class ZKUtil
{
  private static Logger                                         logger     =
                                                                               Logger.getLogger(ZKUtil.class);
  private static int                                            RETRYLIMIT = 3;

  private final static ConcurrentHashMap<String, ZNRecord>      namePool   =
                                                                               new ConcurrentHashMap<String, ZNRecord>();
  private final static ConcurrentHashMap<String, AtomicInteger> countPool  =
                                                                               new ConcurrentHashMap<String, AtomicInteger>();

  private ZKUtil()
  {
  }

  public static boolean isClusterSetup(String clusterName, ZkClient zkClient)
  {
    if (clusterName == null || zkClient == null)
    {
      return false;
    }

    boolean isValid =
        zkClient.exists(PropertyPathConfig.getPath(PropertyType.IDEALSTATES, clusterName))
            && zkClient.exists(PropertyPathConfig.getPath(PropertyType.CONFIGS,
                                                          clusterName,
                                                          ConfigScopeProperty.CLUSTER.toString(),
                                                          clusterName))
            && zkClient.exists(PropertyPathConfig.getPath(PropertyType.CONFIGS,
                                                          clusterName,
                                                          ConfigScopeProperty.PARTICIPANT.toString()))
            && zkClient.exists(PropertyPathConfig.getPath(PropertyType.CONFIGS,
                                                          clusterName,
                                                          ConfigScopeProperty.RESOURCE.toString()))
            && zkClient.exists(PropertyPathConfig.getPath(PropertyType.PROPERTYSTORE,
                                                          clusterName))
            && zkClient.exists(PropertyPathConfig.getPath(PropertyType.LIVEINSTANCES,
                                                          clusterName))
            && zkClient.exists(PropertyPathConfig.getPath(PropertyType.INSTANCES,
                                                          clusterName))
            && zkClient.exists(PropertyPathConfig.getPath(PropertyType.EXTERNALVIEW,
                                                          clusterName))
            && zkClient.exists(PropertyPathConfig.getPath(PropertyType.CONTROLLER,
                                                          clusterName))
            && zkClient.exists(PropertyPathConfig.getPath(PropertyType.STATEMODELDEFS,
                                                          clusterName))
            && zkClient.exists(PropertyPathConfig.getPath(PropertyType.MESSAGES_CONTROLLER,
                                                          clusterName))
            && zkClient.exists(PropertyPathConfig.getPath(PropertyType.ERRORS_CONTROLLER,
                                                          clusterName))
            && zkClient.exists(PropertyPathConfig.getPath(PropertyType.STATUSUPDATES_CONTROLLER,
                                                          clusterName))
            && zkClient.exists(PropertyPathConfig.getPath(PropertyType.HISTORY,
                                                          clusterName));

    return isValid;
  }

  public static void createChildren(ZkClient client,
                                    String parentPath,
                                    List<ZNRecord> list)
  {
    client.createPersistent(parentPath, true);
    if (list != null)
    {
      for (ZNRecord record : list)
      {
        createChildren(client, parentPath, record);
      }
    }
  }

  public static void createChildren(ZkClient client,
                                    String parentPath,
                                    ZNRecord nodeRecord)
  {
    client.createPersistent(parentPath, true);

    String id = nodeRecord.getId();
    String temp = parentPath + "/" + id;
    client.createPersistent(temp, nodeRecord);
  }

  public static void dropChildren(ZkClient client, String parentPath, List<ZNRecord> list)
  {
    // TODO: check if parentPath exists
    if (list != null)
    {
      for (ZNRecord record : list)
      {
        dropChildren(client, parentPath, record);
      }
    }
  }

  public static void dropChildren(ZkClient client, String parentPath, ZNRecord nodeRecord)
  {
    // TODO: check if parentPath exists
    String id = nodeRecord.getId();
    String temp = parentPath + "/" + id;
    client.deleteRecursive(temp);
  }

  public static List<ZNRecord> getChildren(ZkClient client, String path)
  {
    // parent watch will be set by zkClient
    List<String> children = client.getChildren(path);
    if (children == null || children.size() == 0)
    {
      return Collections.emptyList();
    }

    List<ZNRecord> childRecords = new ArrayList<ZNRecord>();
    for (String child : children)
    {
      String childPath = path + "/" + child;
      Stat newStat = new Stat();
      ZNRecord record = client.readDataAndStat(childPath, newStat, true);
      if (record != null)
      {

        record.setVersion(newStat.getVersion());
        record.setCreationTime(newStat.getCtime());
        record.setModifiedTime(newStat.getMtime());
        if (!record.getId().equals(child))
        {
          childRecords.add(new ZNRecord(record, child));
        }
        else
        {
          childRecords.add(record);
        }
      }
    }
    return childRecords;
  }

  public static void updateIfExists(ZkClient client,
                                    String path,
                                    final ZNRecord record,
                                    boolean mergeOnUpdate)
  {
    if (client.exists(path))
    {
      DataUpdater<Object> updater = new DataUpdater<Object>()
      {
        @Override
        public Object update(Object currentData)
        {
          return record;
        }
      };
      client.updateDataSerialized(path, updater);
    }
  }

  public static void createOrUpdate(ZkClient client,
                                    final String path,
                                    final ZNRecord record,
                                    final boolean persistent,
                                    final boolean mergeOnUpdate)
  {
    int retryCount = 0;
    while (retryCount < RETRYLIMIT)
    {
      try
      {
        if (client.exists(path))
        {
          AtomicInteger count = null;

          count = countPool.putIfAbsent(path, new AtomicInteger(0));
          if (count == null)
          {
            count = countPool.get(path);
          }
          count.incrementAndGet();

          // synchronized (namePool)
          // {
          // if (!namePool.containsKey(path))
          // {
          // namePool.put(path, new ZNRecord(record));
          // }
          ZNRecord newRec = namePool.putIfAbsent(path, new ZNRecord(record));
          if (newRec == null)
          {
            newRec = namePool.get(path);
          }

          // else
          // {
          // while (namePool.containsKey(path)) // already being locked
          // {
          // namePool.wait(); // wait for release
          // }
          // }

          if (count.decrementAndGet() == 0)
          {
            client.updateDataSerialized(path, new DataUpdater<ZNRecord>()
            {
              @Override
              public ZNRecord update(ZNRecord currentData)
              {
                if (currentData != null && mergeOnUpdate)
                {
                  currentData.merge(namePool.get(path));
                  return currentData;
                }
                return record;
              }
            });
            namePool.remove(path);
            newRec.notifyAll();
          }
          else
          {
            ZNRecord curRecord = namePool.get(path);
            curRecord.merge(record);
            newRec.wait(); // wait for release
          }
          // }

          // client.updateDataSerialized(path, updater);

          // synchronized (namePool)
          // {
          // namePool.remove(path);
          // namePool.notifyAll();
          // }
        }
        else
        {
          CreateMode mode = (persistent) ? CreateMode.PERSISTENT : CreateMode.EPHEMERAL;
          if (record.getDeltaList().size() > 0)
          {
            ZNRecord value = new ZNRecord(record.getId());
            value.merge(record);
            client.create(path, value, mode);
          }
          else
          {
            client.create(path, record, mode);
          }
        }
        break;
      }
      catch (Exception e)
      {
        retryCount = retryCount + 1;
        logger.warn("Exception trying to update " + path + ". Will retry.", e);
      }
    }
  }

  /**
   * Async zk create/write: used by STATUSUPDATES, MESSAGES
   */
  public static void asyncCreateOrUpdate(ZkClient client,
                                         String path,
                                         final ZNRecord record,
                                         final boolean persistent,
                                         final boolean mergeOnUpdate)
  {
    try
    {
      if (client.exists(path))
      {
        if (mergeOnUpdate)
        {
          // no guarantee on atomic update
          // since can't use ZkClient.updateDataSerialized(path, updater)
          ZNRecord curRecord = client.readData(path);
          if (curRecord != null)
          {
            curRecord.merge(record);
            client.asyncWriteData(path, curRecord);
          }
          else
          {
            client.asyncWriteData(path, record);
          }
        }
        else
        {
          client.asyncWriteData(path, record);
        }
      }
      else
      {
        CreateMode mode = (persistent) ? CreateMode.PERSISTENT : CreateMode.EPHEMERAL;
        if (record.getDeltaList().size() > 0)
        {
          ZNRecord newRecord = new ZNRecord(record.getId());
          newRecord.merge(record);
          client.asyncCreate(path, newRecord, mode);
        }
        else
        {
          client.asyncCreate(path, record, CreateMode.PERSISTENT_SEQUENTIAL);
        }
      }
    }
    catch (Exception e)
    {
      logger.error("Exception in async create or update " + path + ". Give up.", e);
    }
  }

  public static void createOrReplace(ZkClient client,
                                     String path,
                                     final ZNRecord record,
                                     final boolean persistent)
  {
    int retryCount = 0;
    while (retryCount < RETRYLIMIT)
    {
      try
      {
        if (client.exists(path))
        {
          DataUpdater<Object> updater = new DataUpdater<Object>()
          {
            @Override
            public Object update(Object currentData)
            {
              return record;
            }
          };
          client.updateDataSerialized(path, updater);
        }
        else
        {
          CreateMode mode = (persistent) ? CreateMode.PERSISTENT : CreateMode.EPHEMERAL;
          client.create(path, record, mode);
        }
        break;
      }
      catch (Exception e)
      {
        retryCount = retryCount + 1;
        logger.warn("Exception trying to createOrReplace " + path + " Exception:"
            + e.getMessage() + ". Will retry.");
      }
    }
  }

  public static void subtract(ZkClient client,
                              String path,
                              final ZNRecord recordTosubtract)
  {
    int retryCount = 0;
    while (retryCount < RETRYLIMIT)
    {
      try
      {
        if (client.exists(path))
        {
          DataUpdater<ZNRecord> updater = new DataUpdater<ZNRecord>()
          {
            @Override
            public ZNRecord update(ZNRecord currentData)
            {
              currentData.subtract(recordTosubtract);
              return currentData;
            }
          };
          client.updateDataSerialized(path, updater);
          break;
        }
      }
      catch (Exception e)
      {
        retryCount = retryCount + 1;
        logger.warn("Exception trying to createOrReplace " + path + " Exception:"
            + e.getMessage() + ". Will retry.");
        e.printStackTrace();
      }
    }

  }
}
