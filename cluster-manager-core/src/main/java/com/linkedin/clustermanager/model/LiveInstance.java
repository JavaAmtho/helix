package com.linkedin.clustermanager.model;

import static com.linkedin.clustermanager.CMConstants.ZNAttribute.*;
import com.linkedin.clustermanager.ZNRecord;

public class LiveInstance
{

  private final ZNRecord _record;

  public LiveInstance(ZNRecord record)
  {
    _record = record;

  }
  public void setSessionId(String sessionId){
    _record.setSimpleField(SESSION_ID.toString(), sessionId);
  }
  public String getSessionId()
  {
    return _record.getSimpleField(SESSION_ID.toString());
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
