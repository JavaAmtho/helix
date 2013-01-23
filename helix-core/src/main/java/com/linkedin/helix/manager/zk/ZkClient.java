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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import org.I0Itec.zkclient.IZkConnection;
import org.I0Itec.zkclient.ZkConnection;
import org.I0Itec.zkclient.exception.ZkException;
import org.I0Itec.zkclient.exception.ZkInterruptedException;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.I0Itec.zkclient.serialize.SerializableSerializer;
import org.I0Itec.zkclient.serialize.ZkSerializer;
import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;

import com.linkedin.helix.manager.zk.ZkAsyncCallbacks.CreateCallbackHandler;
import com.linkedin.helix.manager.zk.ZkAsyncCallbacks.DeleteCallbackHandler;
import com.linkedin.helix.manager.zk.ZkAsyncCallbacks.ExistsCallbackHandler;
import com.linkedin.helix.manager.zk.ZkAsyncCallbacks.GetDataCallbackHandler;
import com.linkedin.helix.manager.zk.ZkAsyncCallbacks.SetDataCallbackHandler;

/**
 * ZKClient does not provide some functionalities, this will be used for quick fixes if
 * any bug found in ZKClient or if we need additional features but can't wait for the new
 * ZkClient jar Ideally we should commit the changes we do here to ZKClient.
 *
 * @author kgopalak
 *
 */

public class ZkClient extends org.I0Itec.zkclient.ZkClient
{
  private static Logger LOG = Logger.getLogger(ZkClient.class);
  public static final int DEFAULT_CONNECTION_TIMEOUT = 60 * 1000;
  public static final int DEFAULT_SESSION_TIMEOUT = 30 * 1000;
  // public static String sessionId;
  // public static String sessionPassword;

  private PathBasedZkSerializer _zkSerializer;

  public ZkClient(IZkConnection connection, int connectionTimeout,
                  PathBasedZkSerializer zkSerializer)
  {
    super(connection, connectionTimeout, new ByteArraySerializer());
    _zkSerializer = zkSerializer;

    StackTraceElement[] calls = Thread.currentThread().getStackTrace();
    LOG.debug("create a new zkclient. " + Arrays.asList(calls));
  }

  public ZkClient(IZkConnection connection, int connectionTimeout,
                  ZkSerializer zkSerializer)
  {
    this(connection, connectionTimeout, new BasicZkSerializer(zkSerializer));
  }

  public ZkClient(IZkConnection connection, int connectionTimeout)
  {
    this(connection, connectionTimeout, new SerializableSerializer());
  }

  public ZkClient(IZkConnection connection)
  {
    this(connection, Integer.MAX_VALUE, new SerializableSerializer());
  }

  public ZkClient(String zkServers, int sessionTimeout, int connectionTimeout,
                  ZkSerializer zkSerializer)
  {
    this(new ZkConnection(zkServers, sessionTimeout), connectionTimeout, zkSerializer);
  }

  public ZkClient(String zkServers, int sessionTimeout, int connectionTimeout,
                  PathBasedZkSerializer zkSerializer)
  {
    this(new ZkConnection(zkServers, sessionTimeout), connectionTimeout, zkSerializer);
  }

  public ZkClient(String zkServers, int sessionTimeout, int connectionTimeout)
  {
    this(new ZkConnection(zkServers, sessionTimeout),
         connectionTimeout,
         new SerializableSerializer());
  }

  public ZkClient(String zkServers, int connectionTimeout)
  {
    this(new ZkConnection(zkServers), connectionTimeout, new SerializableSerializer());
  }

  public ZkClient(String zkServers)
  {
    this(new ZkConnection(zkServers), Integer.MAX_VALUE, new SerializableSerializer());
  }

  {
  }

  @Override
  public void setZkSerializer(ZkSerializer zkSerializer)
  {
    _zkSerializer = new BasicZkSerializer(zkSerializer);
  }

  public void setZkSerializer(PathBasedZkSerializer zkSerializer)
  {
    _zkSerializer = zkSerializer;
  }

  public IZkConnection getConnection()
  {
    return _connection;
  }

  @Override
  public void close() throws ZkInterruptedException
  {
    StackTraceElement[] calls = Thread.currentThread().getStackTrace();
    LOG.debug("closing a zkclient. callStack: " + Arrays.asList(calls));
    
//    LOG.info("closing a zkclient. zookeeper: "
//        + (_connection == null ? "null" : ((ZkConnection) _connection).getZookeeper())
//        + ", callStack: " + Arrays.asList(calls));

    super.close();
  }

  public Stat getStat(final String path)
  {
    long startT = System.nanoTime();

    try
    {
      Stat stat = retryUntilConnected(new Callable<Stat>()
      {

        @Override
        public Stat call() throws Exception
        {
          Stat stat = ((ZkConnection) _connection).getZookeeper().exists(path, false);
          return stat;
        }
      });

      return stat;
    }
    finally
    {
      long endT = System.nanoTime();
      if (LOG.isDebugEnabled())
      {
        LOG.debug("exists, path: " + path + ", time: " + (endT - startT) + " ns");
      }
    }
  }

  // override exists(path, watch), so we can record all exists requests
  @Override
  protected boolean exists(final String path, final boolean watch)
  {
    long startT = System.nanoTime();

    try
    {
      return retryUntilConnected(new Callable<Boolean>()
      {
        @Override
        public Boolean call() throws Exception
        {
          return _connection.exists(path, watch);
        }
      });
    }
    finally
    {
      long endT = System.nanoTime();
      if (LOG.isDebugEnabled())
      {
        LOG.debug("exists, path: " + path + ", time: " + (endT - startT) + " ns");
      }
    }
  }

  // override getChildren(path, watch), so we can record all getChildren requests
  @Override
  protected List<String> getChildren(final String path, final boolean watch)
  {
    long startT = System.nanoTime();

    try
    {
      return retryUntilConnected(new Callable<List<String>>()
      {
        @Override
        public List<String> call() throws Exception
        {
          return _connection.getChildren(path, watch);
        }
      });
    }
    finally
    {
      long endT = System.nanoTime();
      if (LOG.isDebugEnabled())
      {
        LOG.debug("getChildren, path: " + path + ", time: " + (endT - startT) + " ns");
      }
    }
  }

  @SuppressWarnings("unchecked")
  public <T extends Object> T deserialize(byte[] data, String path)
  {
    if (data == null)
    {
      return null;
    }
    return (T) _zkSerializer.deserialize(data, path);
  }

  // override readData(path, stat, watch), so we can record all read requests
  @Override
  @SuppressWarnings("unchecked")
  protected <T extends Object> T readData(final String path,
                                          final Stat stat,
                                          final boolean watch)
  {
    long startT = System.nanoTime();
    try
    {
      byte[] data = retryUntilConnected(new Callable<byte[]>()
      {

        @Override
        public byte[] call() throws Exception
        {
          return _connection.readData(path, stat, watch);
        }
      });
      return (T) deserialize(data, path);
    }
    finally
    {
      long endT = System.nanoTime();
      if (LOG.isDebugEnabled())
      {
        LOG.debug("getData, path: " + path + ", time: " + (endT - startT) + " ns");
      }
    }
  }

  @SuppressWarnings("unchecked")
  public <T extends Object> T readDataAndStat(String path,
                                              Stat stat,
                                              boolean returnNullIfPathNotExists)
  {
    T data = null;
    try
    {
      data = (T) super.readData(path, stat);
    }
    catch (ZkNoNodeException e)
    {
      if (!returnNullIfPathNotExists)
      {
        throw e;
      }
    }
    return data;
  }

  public String getServers()
  {
    return _connection.getServers();
  }

  public byte[] serialize(Object data, String path)
  {
    return _zkSerializer.serialize(data, path);
  }

  @Override
  public void writeData(final String path, Object datat, final int expectedVersion)
  {
    long startT = System.nanoTime();
    try
    {
      final byte[] data = serialize(datat, path);

      retryUntilConnected(new Callable<Object>()
      {

        @Override
        public Object call() throws Exception
        {
          _connection.writeData(path, data, expectedVersion);
          return null;
        }
      });
    }
    finally
    {
      long endT = System.nanoTime();
      if (LOG.isDebugEnabled())
      {
        LOG.debug("setData, path: " + path + ", time: " + (endT - startT) + " ns");
      }
    }
  }

  public Stat writeDataGetStat(final String path, Object datat, final int expectedVersion) throws InterruptedException
  {
    Stat stat = null;
    long start = System.nanoTime();
    try
    {
      byte[] bytes = _zkSerializer.serialize(datat, path);
      stat =
          ((ZkConnection) _connection).getZookeeper().setData(path,
                                                              bytes,
                                                              expectedVersion);
      return stat;
    }
    catch (KeeperException e)
    {
      throw ZkException.create(e);
    }
    finally
    {
      long end = System.nanoTime();
      if (LOG.isDebugEnabled())
      {
        LOG.debug("setData, path: " + path + ", time: " + (end - start) + " ns");
      }
    }
  }
  
  @Override
  public String create(final String path, Object data, final CreateMode mode) throws ZkInterruptedException,
      IllegalArgumentException,
      ZkException,
      RuntimeException
  {
    if (path == null)
    {
      throw new NullPointerException("path must not be null.");
    }

    long startT = System.nanoTime();
    try
    {
      final byte[] bytes = data == null ? null : serialize(data, path);

      return retryUntilConnected(new Callable<String>()
      {

        @Override
        public String call() throws Exception
        {
          return _connection.create(path, bytes, mode);
        }
      });
    }
    finally
    {
      long endT = System.nanoTime();
      if (LOG.isDebugEnabled())
      {
        LOG.debug("create, path: " + path + ", time: " + (endT - startT) + " ns");
      }
    }
  }

  @Override
  public boolean delete(final String path)
  {
    long startT = System.nanoTime();
    try
    {
      try
      {
        retryUntilConnected(new Callable<Object>()
        {

          @Override
          public Object call() throws Exception
          {
            _connection.delete(path);
            return null;
          }
        });

        return true;
      }
      catch (ZkNoNodeException e)
      {
        return false;
      }
    }
    finally
    {
      long endT = System.nanoTime();
      if (LOG.isDebugEnabled())
      {
        LOG.debug("delete, path: " + path + ", time: " + (endT - startT) + " ns");
      }
    }
  }

  public void asyncCreate(final String path,
                          Object datat,
                          CreateMode mode,
                          CreateCallbackHandler cb)
  {
    byte[] data = null;
    if (datat != null)
    {
      data = serialize(datat, path);
    }
    ((ZkConnection) _connection).getZookeeper().create(path, data, Ids.OPEN_ACL_UNSAFE, // Arrays.asList(DEFAULT_ACL),
                                                       mode,
                                                       cb,
                                                       null);
  }

  public void asyncSetData(final String path,
                           Object datat,
                           int version,
                           SetDataCallbackHandler cb)
  {
    final byte[] data = serialize(datat, path);
    ((ZkConnection) _connection).getZookeeper().setData(path, data, version, cb, null);

  }

  public void asyncGetData(final String path, GetDataCallbackHandler cb)
  {
    ((ZkConnection) _connection).getZookeeper().getData(path, null, cb, null);
  }

  public void asyncExists(final String path, ExistsCallbackHandler cb)
  {
    ((ZkConnection) _connection).getZookeeper().exists(path, null, cb, null);

  }

  public void asyncDelete(String path, DeleteCallbackHandler cb)
  {
    ((ZkConnection) _connection).getZookeeper().delete(path, -1, cb, null);
  }

}
