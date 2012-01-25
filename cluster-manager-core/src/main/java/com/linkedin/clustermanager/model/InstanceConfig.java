package com.linkedin.clustermanager.model;

import com.linkedin.clustermanager.CMConstants;
import com.linkedin.clustermanager.ZNRecord;

public class InstanceConfig
{
  private final ZNRecord _record;

  public InstanceConfig(ZNRecord record)
  {
    _record = record;
  }

  public String getHostName()
  {
    return _record.getSimpleField(CMConstants.ZNAttribute.HOST.toString());
  }

  public void setHostName(String hostName)
  {
    _record.setSimpleField(CMConstants.ZNAttribute.HOST.toString(), hostName);
  }

  public String getPort()
  {
    return _record.getSimpleField(CMConstants.ZNAttribute.PORT.toString());
  }

  public void setPort(String port)
  {
    _record.setSimpleField(CMConstants.ZNAttribute.HOST.toString(), port);
  }

  @Override
  public boolean equals(Object obj)
  {
    if (obj instanceof InstanceConfig)
    {
      InstanceConfig that = (InstanceConfig) obj;

      if (this.getHostName().equals(that.getHostName()) && this.getPort().equals(that.getPort()))
      {
        return true;
      }
    }
    return false;
  }

  @Override
  public int hashCode()
  {

    StringBuffer sb = new StringBuffer();
    sb.append(this.getHostName());
    sb.append("_");
    sb.append(this.getPort());
    return sb.toString().hashCode();
  }

  public String getInstanceName()
  {
    return _record.getId();
  }
  
  @Override
  public String toString()
  {
    return _record.toString();
  }
}
