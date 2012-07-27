package com.linkedin.helix.integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.omg.PortableInterceptor.INACTIVE;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.helix.DataAccessor;
import com.linkedin.helix.HelixManager;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.TestHelper;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.controller.pipeline.Pipeline;
import com.linkedin.helix.controller.stages.AttributeName;
import com.linkedin.helix.controller.stages.BestPossibleStateCalcStage;
import com.linkedin.helix.controller.stages.BestPossibleStateOutput;
import com.linkedin.helix.controller.stages.ClusterEvent;
import com.linkedin.helix.controller.stages.CurrentStateComputationStage;
import com.linkedin.helix.controller.stages.ReadClusterDataStage;
import com.linkedin.helix.controller.stages.ResourceComputationStage;
import com.linkedin.helix.josql.DataAccessorBasedTupleReader;
import com.linkedin.helix.josql.ZNRecordQueryProcessor;
import com.linkedin.helix.model.IdealState;
import com.linkedin.helix.model.Partition;

public class TestZNRecordQueryProcessorWithZK extends ZkStandAloneCMTestBase
{
  //@Test
  public void testClusterQuery() throws Exception
  {
    HelixManager manager = ((TestHelper.StartCMResult) (_startCMResultMap.values().toArray()[0]))._manager;

    DataAccessorBasedTupleReader tupleReader = new DataAccessorBasedTupleReader(manager.getDataAccessor(), manager.getClusterName());
    
    ZNRecordQueryProcessor queryProcessor = new ZNRecordQueryProcessor();

    String partition = TEST_DB + "_4";

    String sql = "select T2.instance, T1.listIndex, T2.id from " +  
    "explodeList(IDEALSTATES," + partition + ") as T1 join " + 
     "LIVEINSTANCES as T2 using (T1.listVal, T2.id)";
    List<ZNRecord> result = queryProcessor.execute(sql, tupleReader);
    System.out.println(result);
    Assert.assertEquals(result.size(), 3);
  }
  
  
  //@Test
  public void testWildcardExpansion() throws Exception 
  {
    HelixManager manager = ((TestHelper.StartCMResult) (_startCMResultMap.values().toArray()[0]))._manager;
    DataAccessor accessor = manager.getDataAccessor();
    
    List<String> instancesInCluster = manager.getClusterManagmentTool().getInstancesInCluster(manager.getClusterName());
    for(String instance : instancesInCluster)
    {
      ZNRecord record = new ZNRecord("scnTable");
      record.setSimpleField("k1", "v1");
      accessor.setProperty(PropertyType.HEALTHREPORT, record, instance, "scnTable");
    }
    
    String path = "INSTANCES/*/HEALTHREPORT/scnTable";
    DataAccessorBasedTupleReader tupleReader = new DataAccessorBasedTupleReader(accessor, manager.getClusterName());
    List<ZNRecord> tuples = tupleReader.get(path);
    System.out.println(tuples);
    Assert.assertEquals(tuples.size(), instancesInCluster.size());
  }
  
  @Test
  public void testNewMasterSelection() throws Exception
  {
    HelixManager manager = ((TestHelper.StartCMResult) (_startCMResultMap.values().toArray()[0]))._manager;
    DataAccessor accessor = manager.getDataAccessor();
    
    IdealState resourceIdealState = manager.getClusterManagmentTool().getResourceIdealState(manager.getClusterName(), TEST_DB);
    Map<String, Map<String, Integer>> scnMap = new HashMap<String, Map<String, Integer>>();
    
    List<String> instancesInCluster = manager.getClusterManagmentTool().getInstancesInCluster(manager.getClusterName());
    List<String> instances = new ArrayList<String>();
    instances.addAll(instancesInCluster);
    //instances.add(instancesInCluster.get(0));
    instances.add("deadInstance");
    System.out.println(instances.size());
    
    int seq = 50;
    for(String instance : instances)
    {
      ZNRecord scnRecord = new ZNRecord("scnTable");
      scnRecord.setSimpleField("instance", instance);
      for(int i = 0; i < _PARTITIONS; i++)
      {
        Map<String, String> scnDetails = new HashMap<String, String>();

        String partition = TEST_DB + "_" + i;
        List<String> idealStatePrefList =
            resourceIdealState.getPreferenceList(partition, null);
        String idealStateMaster = idealStatePrefList.get(0);
        
        scnDetails.put("gen", "4");

        if (instance.equals(idealStateMaster))
        {
          scnDetails.put("seq", "" + (seq - 25));
        }
        else
        {
          scnDetails.put("seq", "" + seq++);
        }
        scnRecord.setMapField(partition, scnDetails);
      }
      accessor.setProperty(PropertyType.HEALTHREPORT, scnRecord, instance, "scnTable");
    }
    
    ZNRecordQueryProcessor processor = new ZNRecordQueryProcessor();
    DataAccessorBasedTupleReader tupleReader = new DataAccessorBasedTupleReader(accessor, manager.getClusterName());
    
    String scnTableQuery = "SELECT T1.instance as instance, T1.mapField as partition, T1.gen as gen, T1.seq as seq " +
    		"FROM explodeMap(`INSTANCES/*/HEALTHREPORT/scnTable`) AS T1" +
    		" JOIN LIVEINSTANCES as T2 using (T1.instance, T2.id)";
    List<ZNRecord> scnTable = processor.execute(scnTableQuery, tupleReader);
    tupleReader.setTempTable("scnTable", scnTable);
    
    String rankQuery = "SELECT instance, partition, gen, seq, T1.listIndex AS instanceRank " +
    		" FROM scnTable JOIN explodeList(`IDEALSTATES/" + TEST_DB + "`) AS T1 " +
    				"USING (scnTable.instance, T1.listVal) WHERE scnTable.partition=T1.listField";
    List<ZNRecord> rankTable = processor.execute(rankQuery, tupleReader);
    System.out.println(rankTable.size());
    tupleReader.setTempTable("rankTable", rankTable);

    String masterSelectionQuery = "SELECT instance, partition, instanceRank, gen, (T.maxSeq-seq) AS seqDiff, seq FROM rankTable JOIN " +
    		" (SELECT partition, max(to_number(seq)) AS maxSeq FROM rankTable GROUP BY partition) AS T USING(rankTable.partition, T.partition) " +
    		" WHERE to_number(seqDiff) < 10 " +
    		" ORDER BY partition, to_number(gen) desc, to_number(instanceRank), to_number(seqDiff)";
    
    List<ZNRecord> masterSelectionTable = processor.execute(masterSelectionQuery, tupleReader);
    System.out.println(masterSelectionTable.size());
    for(ZNRecord record : masterSelectionTable)
    {
      System.out.println(record);
    }
  }
}
