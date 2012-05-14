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
package com.linkedin.helix.store;

import org.apache.zookeeper.data.Stat;

public class PropertyStat
{
  private long _lastModifiedTime;   // time in milliseconds from epoch when this property was last modified
  private int _version;   // latest version number
  private long _creationTime;
  
  public PropertyStat()
  {
//    this(0, 0);
  }
  
  public static void copyStat(Stat fromStat, PropertyStat toStat)
  {
	  toStat.setLastModifiedTime(fromStat.getMtime());
	  toStat.setVersion(fromStat.getVersion());
	  toStat.setCreationTime(fromStat.getCtime());
  }
  
//  public PropertyStat(long lastModifiedTime, int version)
//  {
//    _lastModifiedTime = lastModifiedTime;
//    _version = version;
//  }
    
  public long getLastModifiedTime()
  {
    return _lastModifiedTime;
  }
  
  public int getVersion()
  {
    return _version;
  }
  
  public void setLastModifiedTime(long lastModifiedTime)
  {
    
    _lastModifiedTime = lastModifiedTime;
  }
  
  public void setVersion(int version)
  {
    _version = version;
  }
  
  public void setCreationTime(long creationTime)
  {
	  _creationTime = creationTime;
  }
}
