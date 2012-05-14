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
package com.linkedin.helix.store.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.I0Itec.zkclient.DataUpdater;
import org.apache.commons.io.DirectoryWalker;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;

import com.linkedin.helix.manager.file.FileCallbackHandler;
import com.linkedin.helix.store.PropertyChangeListener;
import com.linkedin.helix.store.PropertyJsonComparator;
import com.linkedin.helix.store.PropertySerializer;
import com.linkedin.helix.store.PropertyStat;
import com.linkedin.helix.store.PropertyStore;
import com.linkedin.helix.store.PropertyStoreException;

/**
 *
 * property store that built upon a file system
 * since file systems usually have sophisticated cache mechanisms
 * there is no need for another cache for file property store
 *
 * NOTES:
 * lastModified timestamp provided by java file io has only second level precision
 * so it is possible that files have been modified without changing its lastModified timestamp
 * the solution is to use a map that caches the files changed in last second
 * and in the next round of refresh, check against this map to figure out whether a file
 * has been changed/created in the last second
 * @link http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6939260
 *
 * @author zzhang
 * @param <T>
 */

public class FilePropertyStore<T> implements PropertyStore<T>
{
  private static Logger logger = Logger.getLogger(FilePropertyStore.class);

  private final String ROOT = "/";
  private final long TIMEOUT = 30L;
  private final long REFRESH_PERIOD = 1000; // ms
  private final int _id = new Random().nextInt();
  private final String _rootNamespace;
  private PropertySerializer<T> _serializer;
  private final PropertyJsonComparator<T> _comparator;

  private Thread _refreshThread;
  private final AtomicBoolean _stopRefreshThread;
  private final CountDownLatch _firstRefreshCounter;
  private final ReadWriteLock _readWriteLock;

  private Map<String, T> _lastModifiedFiles = new HashMap<String, T>();
  private Map<String, T> _curModifiedFiles = new HashMap<String, T>();

  private final Map< String, CopyOnWriteArraySet<PropertyChangeListener<T> > > _fileChangeListeners; // map key to listener

  private class FilePropertyStoreRefreshThread implements Runnable
  {
    private final PropertyStoreDirWalker _dirWalker;

    public class PropertyStoreDirWalker extends DirectoryWalker
    {
      private final File _propertyStoreRootDir;
      private long _lastNotifiedTime = 0;
      private long _currentHighWatermark;

      public PropertyStoreDirWalker(String rootNamespace, ReadWriteLock readWriteLock)
      {
        _propertyStoreRootDir = new File(rootNamespace);
      }

      @SuppressWarnings({ "rawtypes", "unchecked" })
      @Override
      protected void handleFile(File file, int depth, Collection results) throws IOException
      {
        if (file.lastModified() < _lastNotifiedTime)
        {
          return;
        }

        String path = getRelativePath(file.getAbsolutePath());
        T newValue = null;
        try
        {
          newValue = getProperty(path);
        } catch (PropertyStoreException e)
        {
          logger.error("fail to get property, path:" + path, e);
        }

        if (file.lastModified() == _lastNotifiedTime && _lastModifiedFiles.containsKey(path))
        {
          T value = _lastModifiedFiles.get(path);

          if (_comparator.compare(value, newValue) == 0)
          {
            if (file.lastModified() == _currentHighWatermark)
            {
              _curModifiedFiles.put(path, newValue);
            }
            return;
          }
        }

        if (file.lastModified() > _currentHighWatermark)
        {
          _currentHighWatermark = file.lastModified();

          _curModifiedFiles.clear();
          _curModifiedFiles.put(path, newValue);
        }
        else if (file.lastModified() == _currentHighWatermark)
        {
          _curModifiedFiles.put(path, newValue);
        }

        // debug
//        logger.error("file: " + file.getAbsolutePath() + " changed@" + file.lastModified() + " (" +
//            new Date(file.lastModified()) + ")");
        results.add(file);
      }

      @Override
      protected boolean handleDirectory(File dir, int depth, Collection results) throws IOException
      {
        if (dir.lastModified() < _lastNotifiedTime)
        {
          return true;
        }

        String path = getRelativePath(dir.getAbsolutePath());
        T newValue = null;
        try
        {
          newValue = getProperty(path);
        }
        catch (PropertyStoreException e)
        {
          logger.error("fail to get property, path:" + path, e);
        }

        if (dir.lastModified() == _lastNotifiedTime && _lastModifiedFiles.containsKey(path))
        {
          T value = _lastModifiedFiles.get(path);
          if (_comparator.compare(value, newValue) == 0)
          {
            if (dir.lastModified() == _currentHighWatermark)
            {
              _curModifiedFiles.put(path, newValue);
            }
            return true;
          }
        }
        _curModifiedFiles.put(path, newValue);

        if (dir.lastModified() > _currentHighWatermark)
        {
          _currentHighWatermark = dir.lastModified();

          _curModifiedFiles.clear();
          _curModifiedFiles.put(path, newValue);
        }
        else if (dir.lastModified() == _currentHighWatermark)
        {
          _curModifiedFiles.put(path, newValue);
        }

        logger.debug("dir: " + dir.getAbsolutePath() + " changed@" + dir.lastModified() +
            " (" + new Date(dir.lastModified()) + ")");
        results.add(dir);

        return true;
      }

      public void walk()
      {
        HashSet<File> files = new HashSet<File>();

        try
        {
          _currentHighWatermark = _lastNotifiedTime;
          _readWriteLock.readLock().lock();
          super.walk(_propertyStoreRootDir, files);
        }
        catch (IOException e)
        {
          logger.error("IO exception when walking through dir:" + _propertyStoreRootDir, e);
        }
        finally
        {
          _lastNotifiedTime = _currentHighWatermark;
          _lastModifiedFiles.clear();

          Map<String, T> temp = _lastModifiedFiles;
          _lastModifiedFiles = _curModifiedFiles;
          _curModifiedFiles = temp;
          _readWriteLock.readLock().unlock();
        }

        // TODO see if we can use DirectoryFileComparator.DIRECTORY_COMPARATOR.sort()
        File[] fileArray = new File[files.size()];
        fileArray = files.toArray(fileArray);
        Arrays.sort(fileArray, new Comparator<File>() {

          @Override
          public int compare(File file1, File file2)
          {
            return file1.getAbsoluteFile().compareTo(file2.getAbsoluteFile());
          }

        });


        // notify listeners
        for (int i = 0; i < fileArray.length; i++)
        {
          File file = fileArray[i];

          // debug
//          logger.error("Before send notification of " + file.getAbsolutePath() + " to listeners " + _fileChangeListeners);

          for (Map.Entry< String, CopyOnWriteArraySet<PropertyChangeListener<T> > > entry : _fileChangeListeners.entrySet())
          {
            String absPath = file.getAbsolutePath();
            if (absPath.startsWith(entry.getKey()))
            {
              for (PropertyChangeListener<T> listener : entry.getValue())
              {
                if (listener instanceof FileCallbackHandler)
                {
                  FileCallbackHandler handler = (FileCallbackHandler) listener;

                  // debug
//                  logger.error("Send notification of " + file.getAbsolutePath() + " to listener:" + handler.getListener());
                }
                listener.onPropertyChange(getRelativePath(absPath));
              }
            }
          }
        }
      }
    }

    public FilePropertyStoreRefreshThread(ReadWriteLock readWriteLock)
    {
      _dirWalker = new PropertyStoreDirWalker(_rootNamespace, readWriteLock);
    }

    @Override
    public void run()
    {
      while (!_stopRefreshThread.get())
      {
        _dirWalker.walk();
        _firstRefreshCounter.countDown();

        try
        {
          Thread.sleep(REFRESH_PERIOD);
//          System.out.println("refresh thread is running");
        }
        catch (InterruptedException ie)
        {
          // do nothing
        }
      }

      logger.info("Quitting file property store refresh thread");

    }

  }

//  public FilePropertyStore(final PropertySerializer<T> serializer)
//  {
//    this(serializer, System.getProperty("java.io.tmpdir"));
//  }

  public FilePropertyStore(final PropertySerializer<T> serializer, String rootNamespace,
      final PropertyJsonComparator<T> comparator)
  {
    _serializer = serializer;
    _comparator = comparator;
    _stopRefreshThread = new AtomicBoolean(false);
    _firstRefreshCounter = new CountDownLatch(1);
    _readWriteLock = new ReentrantReadWriteLock();

    _fileChangeListeners = new ConcurrentHashMap< String, CopyOnWriteArraySet<PropertyChangeListener<T> > >();

    // Strip off leading slash
    while (rootNamespace.startsWith("/"))
    {
      // rootNamespace = rootNamespace.substring(1, rootNamespace.length());
      rootNamespace = rootNamespace.substring(1);
    }
    _rootNamespace = "/" + rootNamespace;

    this.createRootNamespace();
  }


  @Override
  public boolean start()
  {
    // check if start has already been invoked
    if (_firstRefreshCounter.getCount() == 0)
      return true;

    logger.debug("starting file property store polling thread, id=" + _id);

    _stopRefreshThread.set(false);
    _refreshThread = new Thread(new FilePropertyStoreRefreshThread(_readWriteLock),
                                "FileRefreshThread_" + _id);
    // _refreshThread.setDaemon(true);
    _refreshThread.start();

    try
    {
      boolean timeout = !_firstRefreshCounter.await(TIMEOUT, TimeUnit.SECONDS);
      if (timeout)
      {
        throw new Exception("Timeout while waiting for the first refresh to complete");
      }
    }
    catch (InterruptedException e)
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    catch (Exception e)
    {
      e.printStackTrace();
    }

    return true;
  }

  @Override
  public boolean stop()
  {
    if (_stopRefreshThread.compareAndSet(false, true))
    {
      // _stopRefreshThread.set(true);
      if (_refreshThread != null)
      {
        try
        {
          _refreshThread.join();
        }
        catch (InterruptedException e)
        {
        }
      }
    }
    return true;
  }

  private String getPath(String key)
  {
    // Strip off leading slash
    while (key.startsWith("/"))
    {
      // key = key.substring(1, key.length());
      key = key.substring(1);
    }

    // String path = key.equals(ROOT) ? _rootNamespace : (_rootNamespace + "/" + key);
    String path = key.equals("") ? _rootNamespace : (_rootNamespace + "/" + key);
    return path;
  }

  private String getRelativePath(String path)
  {
    // strip off rootPath from path
    if (!path.startsWith(_rootNamespace))
    {
      logger.warn("path does NOT start with: " + _rootNamespace);
      return path;
    }

    if (path.equals(_rootNamespace))
      return ROOT;

    // path = path.substring(_rootNamespace.length() + 1);
    path = path.substring(_rootNamespace.length());

    return path;
  }

  public void createRootNamespace()
  {
    createPropertyNamespace(ROOT);
  }

  @Override
  public void createPropertyNamespace(String prefix)
  {
    String path = getPath(prefix);
    File dir = new File(path);
    try
    {
      _readWriteLock.writeLock().lock();
      if (dir.exists())
      {
        logger.warn(path + " already exists");
      }
      else
      {
        if (!dir.mkdirs())
        {
          logger.warn("Failed to create: " + path);
        }
      }
    }
    catch (Exception e)
    {
      logger.error("Failed to create dir: " + path + "\nexception:" + e);
    }
    finally
    {
      _readWriteLock.writeLock().unlock();
    }

  }

  @Override
  public void setProperty(String key, T value) throws PropertyStoreException
  {
    String path = getPath(key);
    File file = new File(path);  // null;
    // FileLock fLock = null;
    FileOutputStream fout = null;

    // TODO create non-exist dirs recursively
    try
    {
      _readWriteLock.writeLock().lock();
      // file = new File(path);
      if (!file.exists())
      {
        FileUtils.touch(file);
      }

      fout = new FileOutputStream(file);
      // FileChannel fChannel = fout.getChannel();

      // TODO need a timeout on lock operation
      // fLock = fChannel.lock();

      byte[] bytes = _serializer.serialize(value);
      fout.write(bytes);
    }
//    catch (FileNotFoundException e)
//    {
//      logger.error("fail to set property, key:" + key +
//          "\nfile not found exception:" + e);
//    }
    catch (IOException e)
    {
      logger.error("fail to set property. key:" + key +
          "value:" + value, e);
    }
    finally
    {
      _readWriteLock.writeLock().unlock();
      try
      {
        // if (fLock != null && fLock.isValid())
        //   fLock.release();

        if (fout != null)
        {
          fout.close();
        }
      }
      catch (IOException e)
      {
        logger.error("fail to close file. key:" + key, e);
      }

    }

  }

  @Override
  public T getProperty(String key) throws PropertyStoreException
  {
    return this.getProperty(key, null);
  }

  @Override
  public T getProperty(String key, PropertyStat propertyStat) throws PropertyStoreException
  {

    String path = getPath(key);
    File file = null;
    // FileLock fLock = null;
    FileInputStream fin = null;

    try
    {
      // TODO need a timeout on lock operation
      _readWriteLock.readLock().lock();

      file = new File(path);
      if (!file.exists())
      {
        return null;
      }

      fin = new FileInputStream(file);
      // FileChannel fChannel = fin.getChannel();
      // fLock = fChannel.lock(0L, Long.MAX_VALUE, true);

      int availableBytes = fin.available();
      if (availableBytes == 0)
      {
        return null;
      }

      byte[] bytes = new byte[availableBytes];
      fin.read(bytes);

      if (propertyStat != null)
      {
        propertyStat.setLastModifiedTime(file.lastModified());
      }

      return _serializer.deserialize(bytes);
    }
    catch (FileNotFoundException e)
    {
      return null;
    }
    catch (IOException e)
    {
      logger.error("fail to get property. key:" + key, e);
    }
    finally
    {
      _readWriteLock.readLock().unlock();
      try
      {
        // if (fLock != null && fLock.isValid())
        //   fLock.release();
        if (fin != null)
        {
          fin.close();
        }
      }
      catch (IOException e)
      {
        logger.error("fail to close file. key:" + key, e);
      }

    }

    return null;
  }

  @Override
  public void removeProperty(String key) throws PropertyStoreException
  {
    String path = getPath(key);
    File file = new File(path);

    try
    {
      _readWriteLock.writeLock().lock();
      if (!file.exists())
      {
        return;
      }

      boolean success = file.delete();
      if (!success)
      {
        logger.error("fail to remove file. path:" + path);
      }
    }
    catch (Exception e)
    {
      logger.error("fail to remove file. path:" + path, e);
    }
    finally
    {
      _readWriteLock.writeLock().unlock();
    }


  }

  public void removeRootNamespace() throws PropertyStoreException
  {
    removeNamespace(ROOT);
  }

  @Override
  public void removeNamespace(String prefix) throws PropertyStoreException
  {
    String path = getPath(prefix);

    try
    {
      _readWriteLock.writeLock().lock();
      FileUtils.deleteDirectory(new File(path));
    }
    catch (IOException e)
    {
      logger.error("fail to remove namespace, path:" + path, e);
    }
    finally
    {
      _readWriteLock.writeLock().unlock();
    }
  }

  private void doGetPropertyNames(String path, List<String> leafNodes)
  throws PropertyStoreException
  {
    File file = new File(path);
    if (!file.exists())
    {
      return;
    }

    // List<String> childs = _zkClient.getChildren(path);
    if (file.isDirectory())
    {
      String[] childs = file.list();
      if (childs == null || childs.length == 0)
      {
        return;
      }
      for (String child : childs)
      {
        String pathToChild = path + "/" + child;
        doGetPropertyNames(pathToChild, leafNodes);
      }
    }
    else if (file.isFile())
    {
      // getProperty(getRelativePath(path));
      leafNodes.add(getRelativePath(path));
      return;
    }
  }


  @Override
  public List<String> getPropertyNames(String prefix) throws PropertyStoreException
  {
    String path = getPath(prefix);
    List<String> propertyNames = new ArrayList<String>();

    try
    {
      _readWriteLock.readLock().lock();
      doGetPropertyNames(path, propertyNames);
    }
    finally
    {
      _readWriteLock.readLock().unlock();
    }

    // sort it to get deterministic order
    Collections.sort(propertyNames);

    return propertyNames;
  }

  @Override
  public void setPropertyDelimiter(String delimiter) throws PropertyStoreException
  {
    throw new UnsupportedOperationException(
        "setPropertyDelimiter() is NOT supported by FilePropertyStore");
  }

  @Override
  public void subscribeForPropertyChange(String prefix, PropertyChangeListener<T> listener)
  throws PropertyStoreException
  {
    if (null != listener)
    {
      String path = getPath(prefix);
      synchronized (_fileChangeListeners)
      {
        CopyOnWriteArraySet<PropertyChangeListener <T> > listeners = _fileChangeListeners.get(path);
        if (listeners == null) {
            listeners = new CopyOnWriteArraySet<PropertyChangeListener <T> >();
            _fileChangeListeners.put(path, listeners);
        }
        listeners.add(listener);
      }
    }

  }

  @Override
  public void unsubscribeForPropertyChange(String prefix, PropertyChangeListener<T> listener)
  throws PropertyStoreException
  {
    if (null != listener)
    {
      String path = getPath(prefix);
      synchronized (_fileChangeListeners)
      {
        final Set<PropertyChangeListener<T> > listeners = _fileChangeListeners.get(path);
        if (listeners != null)
        {
            listeners.remove(listener);
        }
        if (listeners == null || listeners.isEmpty())
        {
            _fileChangeListeners.remove(path);
        }
      }
    }

  }

  @Override
  public boolean canParentStoreData()
  {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public String getPropertyRootNamespace()
  {
    return _rootNamespace;
  }

  @Override
  public void updatePropertyUntilSucceed(String key, DataUpdater<T> updater)
  {
    updatePropertyUntilSucceed(key, updater, true);
  }

  @Override
  public void updatePropertyUntilSucceed(String key, DataUpdater<T> updater,
      boolean createIfAbsent)
  {
    String path = getPath(key);
    File file = new File(path);
    RandomAccessFile raFile = null;
    FileLock fLock = null;

    try
    {
      _readWriteLock.writeLock().lock();
      if (!file.exists())
      {
        FileUtils.touch(file);
      }

      raFile = new RandomAccessFile(file, "rw");
      FileChannel fChannel = raFile.getChannel();
      fLock = fChannel.lock();

      T current = getProperty(key);
      T update = updater.update(current);
      setProperty(key, update);
    }
    catch (Exception e)
    {
      logger.error("fail to updatePropertyUntilSucceed, path:" + path, e);
    }
    finally
    {
      _readWriteLock.writeLock().unlock();
      try
      {
        if (fLock != null && fLock.isValid())
        {
           fLock.release();
        }

        if (raFile != null)
        {
          raFile.close();
        }
      }
      catch (IOException e)
      {
        logger.error("fail to close file, path:" + path, e);
      }
    }
  }


  @Override
  public void setPropertySerializer(PropertySerializer<T> serializer)
  {
    _readWriteLock.writeLock().lock();
    _serializer = serializer;
    _readWriteLock.writeLock().unlock();
  }

//  @Override
//  public boolean compareAndSet(String key, T expected, T update, Comparator<T> comparator)
//  {
//    return compareAndSet(key, expected, update, comparator, false);
//  }
//
//  @Override
//  public boolean compareAndSet(String key, T expected, T update, Comparator<T> comparator,
//                               boolean createIfAbsent)
//  {
//    String path = getPath(key);
//    File file = new File(path);
////    FileInputStream fin = null;
////    FileOutputStream fout = null;
//    RandomAccessFile raFile = null;
//    FileLock fLock = null;
//
//    try
//    {
//      _readWriteLock.writeLock().lock();
//
//      if (createIfAbsent)
//      {
//        file.createNewFile();
//      }
//
////      fin = new FileInputStream(file);
////      FileChannel fChannel = fin.getChannel();
//      raFile = new RandomAccessFile(file, "rw");
//      FileChannel fChannel = raFile.getChannel();
//      fLock = fChannel.lock();
//
//      T current = getProperty(key);
//      if (comparator.compare(current, expected) == 0)
//      {
////        fout = new FileOutputStream(file);
////
////        byte[] bytes = _serializer.serialize(update);
////        fout.write(bytes);
//        setProperty(key, update);
//        return true;
//      }
//
//      return false;
//    }
//    catch (FileNotFoundException e)
//    {
//      logger.error("fail to compareAndSet. path:" + path, e);
//      return false;
//    }
//    catch (Exception e)
//    {
//      logger.error("fail to compareAndSet. path:" + path, e);
//      return false;
//    }
//    finally
//    {
//      _readWriteLock.writeLock().unlock();
//      try
//      {
//        if (fLock != null && fLock.isValid())
//        {
//           fLock.release();
//        }
//
//        if (raFile != null)
//        {
//          raFile.close();
//        }
//
////        if (fin != null)
////        {
////          fin.close();
////        }
////
////        if (fout != null)
////        {
////          fout.close();
////        }
//      }
//      catch (IOException e)
//      {
//        logger.error("fail to close file. path:" + path, e);
//      }
//    }
//
//  }

  @Override
  public boolean exists(String key)
  {
    String path = getPath(key);
    File file = new File(path);
    _readWriteLock.readLock().lock();

    boolean ret = file.exists();
    _readWriteLock.readLock().unlock();
    return ret;
  }
}
