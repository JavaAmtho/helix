package com.linkedin.clustermanager.agent.file;

import static com.linkedin.clustermanager.CMConstants.ChangeType.CURRENT_STATE;
import static com.linkedin.clustermanager.CMConstants.ChangeType.IDEAL_STATE;
import static com.linkedin.clustermanager.CMConstants.ChangeType.LIVE_INSTANCE;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.zookeeper.Watcher.Event.EventType;

import com.linkedin.clustermanager.CMConstants;
import com.linkedin.clustermanager.CMConstants.ChangeType;
import com.linkedin.clustermanager.ClusterDataAccessor;
import com.linkedin.clustermanager.ClusterManagementService;
import com.linkedin.clustermanager.ClusterManager;
import com.linkedin.clustermanager.ClusterManagerException;
import com.linkedin.clustermanager.ClusterMessagingService;
import com.linkedin.clustermanager.ConfigChangeListener;
import com.linkedin.clustermanager.ControllerChangeListener;
import com.linkedin.clustermanager.CurrentStateChangeListener;
import com.linkedin.clustermanager.ExternalViewChangeListener;
import com.linkedin.clustermanager.IdealStateChangeListener;
import com.linkedin.clustermanager.InstanceType;
import com.linkedin.clustermanager.LiveInstanceChangeListener;
import com.linkedin.clustermanager.MessageListener;
import com.linkedin.clustermanager.PropertyType;
import com.linkedin.clustermanager.ZNRecord;
import com.linkedin.clustermanager.healthcheck.ParticipantHealthReportCollector;
import com.linkedin.clustermanager.messaging.DefaultMessagingService;
import com.linkedin.clustermanager.store.PropertyStore;
import com.linkedin.clustermanager.store.file.FilePropertyStore;
import com.linkedin.clustermanager.util.CMUtil;

public class DynamicFileClusterManager implements ClusterManager
{
  private static final Logger LOG = Logger
      .getLogger(FileBasedClusterManager.class.getName());
  private final ClusterDataAccessor _fileDataAccessor;

  private final String _clusterName;
  private final InstanceType _instanceType;
  private final String _instanceName;
  private boolean _isConnected;
  private final List<CallbackHandlerForFile> _handlers;
  private final FileClusterManagementTool _mgmtTool;

  public static final String _sessionId = "12345";
  public static final String configFile = "configFile";
  private final DefaultMessagingService _messagingService;
  private final FilePropertyStore<ZNRecord> _store;

  public DynamicFileClusterManager(String clusterName, String instanceName,
      InstanceType instanceType, ClusterDataAccessor accessor)
  {
    this._clusterName = clusterName;
    this._instanceName = instanceName;
    this._instanceType = instanceType;

    _handlers = new ArrayList<CallbackHandlerForFile>();
    _fileDataAccessor = accessor;

    if (_instanceType == InstanceType.PARTICIPANT)
    {
      addLiveInstance();
    }

    _store = (FilePropertyStore<ZNRecord>) _fileDataAccessor.getStore();
    _mgmtTool = new FileClusterManagementTool(_store);

    _messagingService = new DefaultMessagingService(this);
    if (instanceType == InstanceType.PARTICIPANT)
    {
      addMessageListener(_messagingService.getExecutor(), _instanceName);
    }

    _store.start();

  }

  @Override
  public void disconnect()
  {
    _store.stop();
    _messagingService.getExecutor().shutDown();
    _isConnected = false;
  }

  @Override
  public void addIdealStateChangeListener(IdealStateChangeListener listener)
  {
    /*
     * NotificationContext context = new NotificationContext(this);
     * context.setType(NotificationContext.Type.INIT);
     * listener.onIdealStateChange(this._clusterView
     * .getPropertyList(PropertyType.IDEALSTATES), context);
     */
    final String path = CMUtil.getIdealStatePath(_clusterName);

    CallbackHandlerForFile callbackHandler = createCallBackHandler(path,
        listener, new EventType[]
        { EventType.NodeDataChanged, EventType.NodeDeleted,
            EventType.NodeCreated }, IDEAL_STATE);
    _handlers.add(callbackHandler);

  }

  @Override
  public void addLiveInstanceChangeListener(LiveInstanceChangeListener listener)
  {
    final String path = CMUtil.getLiveInstancesPath(_clusterName);
    CallbackHandlerForFile callbackHandler = createCallBackHandler(path,
        listener, new EventType[]
        { EventType.NodeChildrenChanged, EventType.NodeDeleted,
            EventType.NodeCreated }, LIVE_INSTANCE);
    _handlers.add(callbackHandler);
  }

  @Override
  public void addConfigChangeListener(ConfigChangeListener listener)
  {
    throw new UnsupportedOperationException(
        "addConfigChangeListener() is NOT supported by File Based cluster manager");
  }

  @Override
  public void addMessageListener(MessageListener listener, String instanceName)
  {
    final String path = CMUtil.getMessagePath(_clusterName, instanceName);

    CallbackHandlerForFile callbackHandler = createCallBackHandler(path,
        listener, new EventType[]
        { EventType.NodeDataChanged, EventType.NodeDeleted,
            EventType.NodeCreated }, ChangeType.MESSAGE);
    _handlers.add(callbackHandler);

  }

  @Override
  public void addCurrentStateChangeListener(
      CurrentStateChangeListener listener, String instanceName, String sessionId)
  {
    final String path = CMUtil.getCurrentStateBasePath(_clusterName,
        instanceName) + "/" + sessionId;

    CallbackHandlerForFile callbackHandler = createCallBackHandler(path,
        listener, new EventType[]
        { EventType.NodeChildrenChanged, EventType.NodeDeleted,
            EventType.NodeCreated }, CURRENT_STATE);
    _handlers.add(callbackHandler);
  }

  @Override
  public void addExternalViewChangeListener(ExternalViewChangeListener listener)
  {
    throw new UnsupportedOperationException(
        "addExternalViewChangeListener() is NOT supported by File Based cluster manager");
  }

  @Override
  public ClusterDataAccessor getDataAccessor()
  {
    return _fileDataAccessor;
  }

  @Override
  public String getClusterName()
  {
    return _clusterName;
  }

  @Override
  public String getInstanceName()
  {
    return _instanceName;
  }

  @Override
  public void connect()
  {
    _isConnected = true;
  }

  @Override
  public String getSessionId()
  {
    return _sessionId;
  }

  /*
  private static Options constructCommandLineOptions()
  {
    Option fileOption = OptionBuilder.withLongOpt(configFile)
        .withDescription("Provide file to write states/messages").create();
    fileOption.setArgs(1);
    fileOption.setRequired(true);
    fileOption.setArgName("File to read states/messages (Required)");

    Options options = new Options();
    options.addOption(fileOption);
    return options;

  }

  public static CommandLine processCommandLineArgs(String[] cliArgs)
      throws Exception
  {
    CommandLineParser cliParser = new GnuParser();
    Options cliOptions = constructCommandLineOptions();
    // CommandLine cmd = null;

    try
    {
      return cliParser.parse(cliOptions, cliArgs);
    } catch (ParseException pe)
    {
      System.err
          .println("CommandLineClient: failed to parse command-line options: "
              + pe.toString());
      // printUsage(cliOptions);
      System.exit(1);
    }
    return null;
  }
  */

  @Override
  public boolean isConnected()
  {
    return _isConnected;
  }

  private void addLiveInstance()
  {
    // set it from the session
    ZNRecord metaData = new ZNRecord(_instanceName);
    metaData.setSimpleField(CMConstants.ZNAttribute.SESSION_ID.toString(),
        _sessionId);
    _fileDataAccessor.setProperty(PropertyType.LIVEINSTANCES, metaData,
        _instanceName);
  }

  @Override
  public long getLastNotificationTime()
  {
    return 0;
  }

  @Override
  public void addControllerListener(ControllerChangeListener listener)
  {
    throw new UnsupportedOperationException(
        "addControllerListener() is NOT supported by File Based cluster manager");
  }

  @Override
  public boolean removeListener(Object listener)
  {
    // TODO Auto-generated method stub
    return false;
  }

  private CallbackHandlerForFile createCallBackHandler(String path,
      Object listener, EventType[] eventTypes, ChangeType changeType)
  {
    if (listener == null)
    {
      throw new ClusterManagerException("Listener cannot be null");
    }
    return new CallbackHandlerForFile(this, path, listener, eventTypes,
        changeType);
  }

  @Override
  public ClusterManagementService getClusterManagmentTool()
  {
    return _mgmtTool;
  }

  @Override
  public PropertyStore<ZNRecord> getPropertyStore()
  {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ClusterMessagingService getMessagingService()
  {
    return _messagingService;
  }

  @Override
  public ParticipantHealthReportCollector getHealthReportCollector()
  {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public InstanceType getInstanceType()
  {
    return _instanceType;
  }

}
