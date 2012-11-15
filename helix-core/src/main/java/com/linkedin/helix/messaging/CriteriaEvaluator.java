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
package com.linkedin.helix.messaging;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.linkedin.helix.HelixManager;
import com.linkedin.helix.Criteria;
import com.linkedin.helix.josql.ClusterJosqlQueryProcessor;
import com.linkedin.helix.josql.ZNRecordRow;

public class CriteriaEvaluator
{
  private static Logger logger = Logger.getLogger(CriteriaEvaluator.class);
  
  public List<Map<String, String>> evaluateCriteria(Criteria recipientCriteria, HelixManager manager)
  {
    List<Map<String, String>> selected = new ArrayList<Map<String, String>>();
    
    String queryFields = 
        (!recipientCriteria.getInstanceName().equals("")  ? " " + ZNRecordRow.MAP_SUBKEY  : " ''") +","+
        (!recipientCriteria.getResource().equals("") ? " " + ZNRecordRow.ZNRECORD_ID : " ''") +","+
        (!recipientCriteria.getPartition().equals("")   ? " " + ZNRecordRow.MAP_KEY   : " ''") +","+
        (!recipientCriteria.getPartitionState().equals("") ? " " + ZNRecordRow.MAP_VALUE : " '' ");
    
    String matchCondition = 
        ZNRecordRow.MAP_SUBKEY   + " LIKE '" + (!recipientCriteria.getInstanceName().equals("") ? (recipientCriteria.getInstanceName() +"'") :   "%' ") + " AND "+
        ZNRecordRow.ZNRECORD_ID+ " LIKE '" + (!recipientCriteria.getResource().equals("") ? (recipientCriteria.getResource() +"'") : "%' ") + " AND "+
        ZNRecordRow.MAP_KEY   + " LIKE '" + (!recipientCriteria.getPartition().equals("")   ? (recipientCriteria.getPartition()  +"'") :  "%' ") + " AND "+
        ZNRecordRow.MAP_VALUE  + " LIKE '" + (!recipientCriteria.getPartitionState().equals("") ? (recipientCriteria.getPartitionState()+"'") :  "%' ") + " AND "+
        ZNRecordRow.MAP_SUBKEY   + " IN ((SELECT [*]id FROM :LIVEINSTANCES))";
        
    
    String queryTarget = recipientCriteria.getDataSource().toString() + ClusterJosqlQueryProcessor.FLATTABLE;
    
    String josql = "SELECT DISTINCT " + queryFields
                 + " FROM " + queryTarget + " WHERE "
                 + matchCondition;
    ClusterJosqlQueryProcessor p = new ClusterJosqlQueryProcessor(manager);
    List<Object> result = new ArrayList<Object>();
    try
    {
      logger.info("JOSQL query: " + josql);
      result = p.runJoSqlQuery(josql, null, null);
    } 
    catch (Exception e)
    {
      logger.error("", e);
      return selected;
    } 
    
    for(Object o : result)
    {
      Map<String, String> resultRow = new HashMap<String, String>();
      List<Object> row = (List<Object>)o;
      resultRow.put("instanceName", (String)(row.get(0)));
      resultRow.put("resourceName", (String)(row.get(1)));
      resultRow.put("partitionName", (String)(row.get(2)));
      resultRow.put("partitionState", (String)(row.get(3)));
      selected.add(resultRow);
    }
    logger.info("JOSQL query return " + selected.size() + " rows");
    return selected;
  }
}