package com.linkedin.helix.controller;

import java.io.StringWriter;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;

import com.linkedin.helix.ConfigChangeListener;
import com.linkedin.helix.ControllerChangeListener;
import com.linkedin.helix.CurrentStateChangeListener;
import com.linkedin.helix.DataAccessor;
import com.linkedin.helix.ExternalViewChangeListener;
import com.linkedin.helix.HealthStateChangeListener;
import com.linkedin.helix.HelixManager;
import com.linkedin.helix.IdealStateChangeListener;
import com.linkedin.helix.LiveInstanceChangeListener;
import com.linkedin.helix.MessageListener;
import com.linkedin.helix.NotificationContext;
import com.linkedin.helix.NotificationContext.Type;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.controller.pipeline.Pipeline;
import com.linkedin.helix.controller.pipeline.PipelineRegistry;
import com.linkedin.helix.controller.stages.BestPossibleStateCalcStage;
import com.linkedin.helix.controller.stages.ClusterEvent;
import com.linkedin.helix.controller.stages.CompatibilityCheckStage;
import com.linkedin.helix.controller.stages.CurrentStateComputationStage;
import com.linkedin.helix.controller.stages.ExternalViewComputeStage;
import com.linkedin.helix.controller.stages.MessageGenerationPhase;
import com.linkedin.helix.controller.stages.MessageSelectionStage;
import com.linkedin.helix.controller.stages.ReadClusterDataStage;
import com.linkedin.helix.controller.stages.ResourceComputationStage;
import com.linkedin.helix.controller.stages.TaskAssignmentStage;
import com.linkedin.helix.model.CurrentState;
import com.linkedin.helix.model.ExternalView;
import com.linkedin.helix.model.HealthStat;
import com.linkedin.helix.model.IdealState;
import com.linkedin.helix.model.InstanceConfig;
import com.linkedin.helix.model.LiveInstance;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.model.PauseSignal;
import com.linkedin.helix.monitoring.mbeans.ClusterStatusMonitor;

/**
 * Cluster Controllers main goal is to keep the cluster state as close as possible to
 * Ideal State. It does this by listening to changes in cluster state and scheduling new
 * tasks to get cluster state to best possible ideal state. Every instance of this class
 * can control can control only one cluster
 *
 *
 * Get all the partitions use IdealState, CurrentState and Messages <br>
 * foreach partition <br>
 * 1. get the (instance,state) from IdealState, CurrentState and PendingMessages <br>
 * 2. compute best possible state (instance,state) pair. This needs previous step data and
 * state model constraints <br>
 * 3. compute the messages/tasks needed to move to 1 to 2 <br>
 * 4. select the messages that can be sent, needs messages and state model constraints <br>
 * 5. send messages
 */
public class GenericHelixController implements
    ConfigChangeListener,
    IdealStateChangeListener,
    LiveInstanceChangeListener,
    MessageListener,
    CurrentStateChangeListener,
    ExternalViewChangeListener,
    ControllerChangeListener,
    HealthStateChangeListener
{
  private static final Logger logger =
      Logger.getLogger(GenericHelixController.class.getName());
  volatile boolean init = false;
  private final PipelineRegistry _registry;

  /**
   * Since instance current state is per-session-id, we need to track the session-ids of
   * the current states that the ClusterController is observing.
   */
  private final Set<String> _instanceCurrentStateChangeSubscriptionSessionIds;
  private final Set<String> _instanceSubscriptionNames;
  // private final ExternalViewGenerator _externalViewGenerator;
  ClusterStatusMonitor _clusterStatusMonitor;

  /**
   * The _paused flag is checked by function handleEvent(), while if the flag is set
   * handleEvent() will be no-op. Other event handling logic keeps the same when the flag
   * is set.
   */
  private boolean _paused;

  /**
   * Default constructor that creates a default pipeline registry. This is sufficient in
   * most cases, but if there is a some thing specific needed use another constructor
   * where in you can pass a pipeline registry
   */
  public GenericHelixController()
  {
    this(createDefaultRegistry());

  }

  private static PipelineRegistry createDefaultRegistry()
  {
    logger.info("createDefaultRegistry");
    synchronized (GenericHelixController.class)
    {
      PipelineRegistry registry = new PipelineRegistry();

      // cluster data cache refresh
      Pipeline dataRefresh = new Pipeline();
      dataRefresh.addStage(new ReadClusterDataStage());

      // rebalance pipeline
      Pipeline rebalancePipeline = new Pipeline();
      rebalancePipeline.addStage(new ResourceComputationStage());
      rebalancePipeline.addStage(new CurrentStateComputationStage());
      rebalancePipeline.addStage(new BestPossibleStateCalcStage());
      rebalancePipeline.addStage(new MessageGenerationPhase());
      rebalancePipeline.addStage(new MessageSelectionStage());
      rebalancePipeline.addStage(new TaskAssignmentStage());

      // external view generation
      Pipeline externalViewPipeline = new Pipeline();
      externalViewPipeline.addStage(new ExternalViewComputeStage());

      // backward compatibility check
      Pipeline liveInstancePipeline = new Pipeline();
      liveInstancePipeline.addStage(new CompatibilityCheckStage());

      registry.register("idealStateChange", dataRefresh, rebalancePipeline);
      registry.register("currentStateChange",
                        dataRefresh,
                        rebalancePipeline,
                        externalViewPipeline);
      registry.register("configChange", dataRefresh, rebalancePipeline);
      registry.register("liveInstanceChange",
                        dataRefresh,
                        liveInstancePipeline,
                        rebalancePipeline,
                        externalViewPipeline);

      registry.register("messageChange",
                        dataRefresh,
                        rebalancePipeline,
                        externalViewPipeline);
      registry.register("externalView", dataRefresh);
      registry.register("resume", dataRefresh, rebalancePipeline, externalViewPipeline);

      // health stats pipeline
      // Pipeline healthStatsAggregationPipeline = new Pipeline();
      // StatsAggregationStage statsStage = new StatsAggregationStage();
      // healthStatsAggregationPipeline.addStage(new ReadHealthDataStage());
      // healthStatsAggregationPipeline.addStage(statsStage);
      // registry.register("healthChange", healthStatsAggregationPipeline);

      return registry;
    }
  }

  public GenericHelixController(PipelineRegistry registry)
  {
    _paused = false;
    _registry = registry;
    _instanceCurrentStateChangeSubscriptionSessionIds =
        new ConcurrentSkipListSet<String>();
    _instanceSubscriptionNames = new ConcurrentSkipListSet<String>();
    // _externalViewGenerator = new ExternalViewGenerator();
  }

  /**
   * lock-always: caller always needs to obtain an external lock before call,
   *    calls to handleEvent() should be serialized
   * @param event
   */
  protected void handleEvent(ClusterEvent event)
  {
    HelixManager manager = event.getAttribute("helixmanager");
    if (manager == null)
    {
      logger.error("No cluster manager in event:" + event.getName());
      return;
    }

    if (!manager.isLeader())
    {
      logger.error("Cluster manager: " + manager.getInstanceName()
          + " is not leader. Pipeline will not be invoked");
      return;
    }

    if (_paused)
    {
      logger.info("Cluster is paused. Ignoring the event:" + event.getName());
      return;
    }

    NotificationContext context = null;
    if (event.getAttribute("changeContext") != null)
    {
      context = (NotificationContext) (event.getAttribute("changeContext"));
    }

    // Initialize _clusterStatusMonitor
    if (context != null)
    {
      if (context.getType() == Type.FINALIZE)
      {
        if (_clusterStatusMonitor != null)
        {
          _clusterStatusMonitor.reset();
          _clusterStatusMonitor = null;
        }
      }
      else
      {
        if (_clusterStatusMonitor == null)
        {
          _clusterStatusMonitor = new ClusterStatusMonitor(manager.getClusterName());
        }
        event.addAttribute("clusterStatusMonitor", _clusterStatusMonitor);
      }
    }

    List<Pipeline> pipelines = _registry.getPipelinesForEvent(event.getName());
    if (pipelines == null || pipelines.size() == 0)
    {
      logger.info("No pipeline to run for event:" + event.getName());
      return;
    }
    
    if(logger.isTraceEnabled())
    {
      ObjectMapper mapper = new ObjectMapper();

      SerializationConfig serializationConfig = mapper.getSerializationConfig();
      serializationConfig.set(SerializationConfig.Feature.INDENT_OUTPUT, true);
      serializationConfig.set(SerializationConfig.Feature.AUTO_DETECT_FIELDS, true);
      serializationConfig.set(SerializationConfig.Feature.CAN_OVERRIDE_ACCESS_MODIFIERS,
                              true);
      StringWriter sw = new StringWriter();
      try
      {
        mapper.writeValue(sw, event);
        logger.info("ClusterEvent=\n"+ sw);
      }
      catch(Exception e)
      {
        logger.warn(e);
      }
    }
    for (Pipeline pipeline : pipelines)
    {
      try
      {
        pipeline.handle(event);
        pipeline.finish();
      }
      catch (Exception e)
      {
        logger.error("Exception while executing pipeline: " + pipeline
            + ". Will not continue to next pipeline", e);
        break;
      }
    }
  }

//  private boolean isEnabled(String eventName, HelixManager manager)
//  {
//    // check if pipeline trigger (onXXXChange) has been disabled
//    ConfigAccessor configAccessor = manager.getConfigAccessor();
//    boolean enabled = true;
//    if (eventName.equalsIgnoreCase("healthChange"))
//    {
//      // for now healthcheck has to be explicitly enabled
//      enabled = false;
//    }
//    if (configAccessor != null)
//    {
//      // zk-based cluster manager
//      ConfigScope scope =
//          new ConfigScopeBuilder().forCluster(manager.getClusterName()).build();
//      String isEnabled = configAccessor.get(scope, eventName + ".enabled");
//      if (isEnabled != null)
//      {
//        enabled = new Boolean(isEnabled);
//      }
//    }
//    else
//    {
//      logger.debug("File-based cluster manager doesn't support disable pipeline trigger");
//    }
//    return enabled;
//  }

  // TODO since we read data in pipeline, we can get rid of reading from zookeeper in
  // callback

  @Override
  public void onExternalViewChange(List<ExternalView> externalViewList,
                                   NotificationContext changeContext)
  {
    logger.info("START: GenericClusterController.onExternalViewChange()");
    ClusterEvent event = new ClusterEvent("externalViewChange");
    event.addAttribute("helixmanager", changeContext.getManager());
    event.addAttribute("changeContext", changeContext);
    event.addAttribute("eventData", externalViewList);
    // handleEvent(event);
    logger.info("END: GenericClusterController.onExternalViewChange()");
  }

  @Override
  public void onStateChange(String instanceName,
                            List<CurrentState> statesInfo,
                            NotificationContext changeContext)
  {
    logger.info("START: GenericClusterController.onStateChange()");
    ClusterEvent event = new ClusterEvent("currentStateChange");
    event.addAttribute("helixmanager", changeContext.getManager());
    event.addAttribute("instanceName", instanceName);
    event.addAttribute("changeContext", changeContext);
    event.addAttribute("eventData", statesInfo);
    handleEvent(event);
    logger.info("END: GenericClusterController.onStateChange()");
  }

  @Override
  public void onHealthChange(String instanceName,
                             List<HealthStat> reports,
                             NotificationContext changeContext)
  {
    /**
     * When there are more participant ( > 20, can be in hundreds), This callback can be
     * called quite frequently as each participant reports health stat every minute. Thus
     * we change the health check pipeline to run in a timer callback.
     * */
    // logger.info("START: GenericClusterController.onHealthChange()");
    // ClusterEvent event = new ClusterEvent("healthChange");
    // event.addAttribute("helixmanager", changeContext.getManager());
    // event.addAttribute("instanceName", instanceName);
    // event.addAttribute("changeContext", changeContext);
    // event.addAttribute("eventData", reports);
    // handleEvent(event);
    // logger.info("END: GenericClusterController.onHealthChange()");
  }

  @Override
  public void onMessage(String instanceName,
                        List<Message> messages,
                        NotificationContext changeContext)
  {
    logger.info("START: GenericClusterController.onMessage()");
    ClusterEvent event = new ClusterEvent("messageChange");
    event.addAttribute("helixmanager", changeContext.getManager());
    event.addAttribute("instanceName", instanceName);
    event.addAttribute("changeContext", changeContext);
    event.addAttribute("eventData", messages);
    handleEvent(event);
    logger.info("END: GenericClusterController.onMessage()");
  }

  @Override
  public void onLiveInstanceChange(List<LiveInstance> liveInstances,
                                   NotificationContext changeContext)
  {
    logger.info("START: Generic GenericClusterController.onLiveInstanceChange()");
    if (liveInstances == null)
    {
      liveInstances = Collections.emptyList();
    }
    // Go though the live instance list and make sure that we are observing them
    // accordingly. The action is done regardless of the paused flag.
    checkLiveInstancesObservation(liveInstances, changeContext);

    ClusterEvent event = new ClusterEvent("liveInstanceChange");
    event.addAttribute("helixmanager", changeContext.getManager());
    event.addAttribute("changeContext", changeContext);
    event.addAttribute("eventData", liveInstances);
    handleEvent(event);
    logger.info("END: Generic GenericClusterController.onLiveInstanceChange()");
  }

  @Override
  public void onIdealStateChange(List<IdealState> idealStates,
                                 NotificationContext changeContext)
  {
    logger.info("START: Generic GenericClusterController.onIdealStateChange()");
    ClusterEvent event = new ClusterEvent("idealStateChange");
    event.addAttribute("helixmanager", changeContext.getManager());
    event.addAttribute("changeContext", changeContext);
    event.addAttribute("eventData", idealStates);
    handleEvent(event);
    logger.info("END: Generic GenericClusterController.onIdealStateChange()");
  }

  @Override
  public void onConfigChange(List<InstanceConfig> configs,
                             NotificationContext changeContext)
  {
    logger.info("START: GenericClusterController.onConfigChange()");
    ClusterEvent event = new ClusterEvent("configChange");
    event.addAttribute("changeContext", changeContext);
    event.addAttribute("helixmanager", changeContext.getManager());
    event.addAttribute("eventData", configs);
    handleEvent(event);
    if (changeContext.getType() == Type.INIT)
    {
      for (InstanceConfig config : configs)
      {
        String instanceName = config.getInstanceName();
        try
        {
          if (!_instanceSubscriptionNames.contains(instanceName))
          {
            logger.info("Adding msg/health listeners for " + instanceName);
            changeContext.getManager().addMessageListener(this, instanceName);
            _instanceSubscriptionNames.add(instanceName);
          }
        }
        catch (Exception e)
        {
          logger.error("Exception adding current state and message listener for instance:"
                           + instanceName,
                       e);
        }
      }
    }
    logger.info("END: GenericClusterController.onConfigChange()");
  }

  @Override
  public void onControllerChange(NotificationContext changeContext)
  {
    logger.info("START: GenericClusterController.onControllerChange()");
    DataAccessor dataAccessor = changeContext.getManager().getDataAccessor();

    // double check if this controller is the leader
    LiveInstance leader =
        dataAccessor.getProperty(LiveInstance.class, PropertyType.LEADER);
    if (leader == null)
    {
      logger.warn("No controller exists for cluster:"
          + changeContext.getManager().getClusterName());
      return;
    }
    else
    {
      String leaderName = leader.getInstanceName();

      String instanceName = changeContext.getManager().getInstanceName();
      if (leaderName == null || !leaderName.equals(instanceName))
      {
        logger.warn("leader name does NOT match, my name: " + instanceName + ", leader: "
            + leader);
        return;
      }
    }

    PauseSignal pauseSignal =
        dataAccessor.getProperty(PauseSignal.class, PropertyType.PAUSE);
    if (pauseSignal != null)
    {
      _paused = true;
      logger.info("controller is now paused");
    }
    else
    {
      if (_paused)
      {
        // it currently paused
        logger.info("controller is now resumed");
        _paused = false;
        ClusterEvent event = new ClusterEvent("resume");
        event.addAttribute("changeContext", changeContext);
        event.addAttribute("helixmanager", changeContext.getManager());
        event.addAttribute("eventData", pauseSignal);
        handleEvent(event);
      }
      else
      {
        _paused = false;
      }
    }
    logger.info("END: GenericClusterController.onControllerChange()");
  }

  /**
   * Go through the list of liveinstances in the cluster, and add currentstateChange
   * listener and Message listeners to them if they are newly added. For current state
   * change, the observation is tied to the session id of each live instance.
   *
   */
  protected void checkLiveInstancesObservation(List<LiveInstance> liveInstances,
                                               NotificationContext changeContext)
  {
    for (LiveInstance instance : liveInstances)
    {
      String instanceName = instance.getId();
      String clientSessionId = instance.getSessionId();

      if (!_instanceCurrentStateChangeSubscriptionSessionIds.contains(clientSessionId))
      {
        try
        {
          changeContext.getManager().addCurrentStateChangeListener(this,
                                                                   instanceName,
                                                                   clientSessionId);
        }
        catch (Exception e)
        {
          logger.error("Exception adding current state and message listener for instance:"
                           + instanceName,
                       e);
        }
        logger.info("Observing client session id: " + clientSessionId + "@"
            + new Date().getTime());
        _instanceCurrentStateChangeSubscriptionSessionIds.add(clientSessionId);
      }
      // TODO shi should call removeListener on the previous session id;
      // but the removeListener with that functionality is not implemented yet
    }
  }

}
