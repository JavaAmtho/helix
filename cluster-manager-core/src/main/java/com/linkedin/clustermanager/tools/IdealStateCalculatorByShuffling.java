package com.linkedin.clustermanager.tools;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;

import com.linkedin.clustermanager.ZNRecord;

/*
 * Ideal state calculator for the cluster manager V1. The ideal state is
 * calculated by randomly assign master partitions to storage nodes.
 *
 * Note that the following code is a native strategy and is for cluster manager V1 only. We will
 * use the other algorithm to calculate the ideal state in future milestones.
 *
 *
 * */

public class IdealStateCalculatorByShuffling
{
  /*
   * Given the number of nodes, partitions and replica number, calculate the
   * ideal state in the following manner: For the master partition assignment,
   * 1. construct Arraylist partitionList, with partitionList[i] = i; 2. Shuffle
   * the partitions array 3. Scan the shuffled array, then assign
   * partitionList[i] to node (i % nodes)
   * 
   * for the slave partitions, simply put them in the node after the node that
   * contains the master partition.
   * 
   * The result of the method is a ZNRecord, which contains a list of maps; each
   * map is from the name of nodes to either "MASTER" or "SLAVE".
   */


  public static ZNRecord calculateIdealState(List<String> instanceNames,
      int partitions, int replicas, String dbName, long randomSeed)
  {
    return calculateIdealState(instanceNames, partitions, replicas, dbName, randomSeed, "MASTER", "SLAVE");
  }
  public static ZNRecord calculateIdealState(List<String> instanceNames,
      int partitions, int replicas, String dbName, long randomSeed, String masterValue, String slaveValue)
  {
    if (instanceNames.size() <= replicas)
    {
      throw new IllegalArgumentException(
          "Replicas must be less than number of storage nodes");
    }

    Collections.sort(instanceNames);

    ZNRecord result = new ZNRecord(dbName);

    List<Integer> partitionList = new ArrayList<Integer>(partitions);
    for (int i = 0; i < partitions; i++)
    {
      partitionList.add(new Integer(i));
    }
    Random rand = new Random(randomSeed);
    // Shuffle the partition list
    Collections.shuffle(partitionList, rand);

    for (int i = 0; i < partitionList.size(); i++)
    {
      int partitionId = partitionList.get(i);
      Map<String, String> partitionAssignment = new TreeMap<String, String>();
      int masterNode = i % instanceNames.size();
      // the first in the list is the node that contains the master
      partitionAssignment.put(instanceNames.get(masterNode), masterValue);

      // for the jth replica, we put it on (masterNode + j) % nodes-th
      // node
      for (int j = 1; j <= replicas; j++)
      {
        partitionAssignment
            .put(instanceNames.get((masterNode + j) % instanceNames.size()),
                slaveValue);
      }
      String partitionName = dbName + "_" + partitionId;
      result.setMapField(partitionName, partitionAssignment);
    }
    result.setSimpleField("partitions", String.valueOf(partitions));
    return result;
  }

  public static ZNRecord calculateIdealState(List<String> instanceNames,
      int partitions, int replicas, String dbName)
  {
    long randomSeed = 888997632;
    // seed is a constant, so that the shuffle always give same result
    return calculateIdealState(instanceNames, partitions, replicas, dbName,
        randomSeed);
  }
}