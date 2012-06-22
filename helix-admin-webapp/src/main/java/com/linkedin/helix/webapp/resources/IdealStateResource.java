package com.linkedin.helix.webapp.resources;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.restlet.Context;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.resource.Representation;
import org.restlet.resource.Resource;
import org.restlet.resource.StringRepresentation;
import org.restlet.resource.Variant;

import com.linkedin.helix.DataAccessor;
import com.linkedin.helix.HelixException;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.manager.zk.ZkClient;
import com.linkedin.helix.tools.ClusterSetup;
import com.linkedin.helix.webapp.RestAdminApplication;

public class IdealStateResource extends Resource
{
  public static final String _replicas = "replicas"; 
  public static final String _resourceKeyPrefix = "key";
  public IdealStateResource(Context context,
      Request request,
      Response response) 
  {
    super(context, request, response);
    getVariants().add(new Variant(MediaType.TEXT_PLAIN));
    getVariants().add(new Variant(MediaType.APPLICATION_JSON));
  }

  public boolean allowGet()
  {
    return true;
  }
  
  public boolean allowPost()
  {
    return true;
  }
  
  public boolean allowPut()
  {
    return false;
  }
  
  public boolean allowDelete()
  {
    return false;
  }
  
  public Representation represent(Variant variant)
  {
    StringRepresentation presentation = null;
    try
    {
      String zkServer = (String)getContext().getAttributes().get(RestAdminApplication.ZKSERVERADDRESS);
      String clusterName = (String)getRequest().getAttributes().get("clusterName");
      String resourceName = (String)getRequest().getAttributes().get("resourceName");
      presentation = getIdealStateRepresentation(zkServer, clusterName, resourceName);
    }
    
    catch(Exception e)
    {
      String error = ClusterRepresentationUtil.getErrorAsJsonStringFromException(e);
      presentation = new StringRepresentation(error, MediaType.APPLICATION_JSON);
      
      e.printStackTrace();
    }  
    return presentation;
  }
  
  StringRepresentation getIdealStateRepresentation(String zkServerAddress, String clusterName, String resourceName) throws JsonGenerationException, JsonMappingException, IOException
  {
    String message = ClusterRepresentationUtil.getClusterPropertyAsString(zkServerAddress, clusterName, PropertyType.IDEALSTATES, resourceName, MediaType.APPLICATION_JSON);
    
    StringRepresentation representation = new StringRepresentation(message, MediaType.APPLICATION_JSON);
    
    return representation;
  }
  
  public void acceptRepresentation(Representation entity)
  {
    try
    {
      String zkServer = (String)getContext().getAttributes().get(RestAdminApplication.ZKSERVERADDRESS);
      String clusterName = (String)getRequest().getAttributes().get("clusterName");
      String resourceName = (String)getRequest().getAttributes().get("resourceName");
      
      Form form = new Form(entity);
      
      Map<String, String> paraMap 
      = ClusterRepresentationUtil.getFormJsonParameters(form);
        
      if(paraMap.get(ClusterRepresentationUtil._managementCommand).equalsIgnoreCase(ClusterRepresentationUtil._alterIdealStateCommand))
      {
        String newIdealStateString = form.getFirstValue(ClusterRepresentationUtil._newIdealState, true);
        
        ObjectMapper mapper = new ObjectMapper();
        ZNRecord newIdealState = mapper.readValue(new StringReader(newIdealStateString),
            ZNRecord.class);
        
        DataAccessor accessor = ClusterRepresentationUtil.getClusterDataAccessor(zkServer,  clusterName);
        accessor.removeProperty(PropertyType.IDEALSTATES, resourceName);
        
        accessor.setProperty(PropertyType.IDEALSTATES, newIdealState,resourceName);
        
      }
      else if(paraMap.get(ClusterRepresentationUtil._managementCommand).equalsIgnoreCase(ClusterRepresentationUtil._rebalanceCommand))
      {
        int replicas = Integer.parseInt(paraMap.get(_replicas));
        ClusterSetup setupTool = new ClusterSetup(zkServer);
        if(paraMap.containsKey(_resourceKeyPrefix))
        {
          setupTool.rebalanceStorageCluster(clusterName, resourceName, replicas, paraMap.get(_resourceKeyPrefix));
        }
        else
        {
          setupTool.rebalanceStorageCluster(clusterName, resourceName, replicas);
        }
      }
      else
      {
        new HelixException("Missing '"+ ClusterRepresentationUtil._alterIdealStateCommand+"' or '"+ClusterRepresentationUtil._rebalanceCommand+"' command");
      }
      getResponse().setEntity(getIdealStateRepresentation(zkServer, clusterName, resourceName));
      getResponse().setStatus(Status.SUCCESS_OK);
    }

    catch(Exception e)
    {
      getResponse().setEntity(ClusterRepresentationUtil.getErrorAsJsonStringFromException(e),
          MediaType.APPLICATION_JSON);
      getResponse().setStatus(Status.SUCCESS_OK);
    }  
  }
}
