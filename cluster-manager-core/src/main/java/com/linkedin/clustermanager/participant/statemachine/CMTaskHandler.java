package com.linkedin.clustermanager.participant.statemachine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.log4j.Logger;

import com.linkedin.clustermanager.ClusterDataAccessor;
import com.linkedin.clustermanager.ClusterManager;
import com.linkedin.clustermanager.ClusterManagerException;
import com.linkedin.clustermanager.NotificationContext;
import com.linkedin.clustermanager.ZNRecord;
import com.linkedin.clustermanager.CMConstants.ZNAttribute;
import com.linkedin.clustermanager.ClusterDataAccessor.ClusterPropertyType;
import com.linkedin.clustermanager.ClusterDataAccessor.InstancePropertyType;
import com.linkedin.clustermanager.model.Message;
import com.linkedin.clustermanager.monitoring.StateTransitionContext;
import com.linkedin.clustermanager.monitoring.StateTransitionDataPoint;
import com.linkedin.clustermanager.util.StatusUpdateUtil;

public class CMTaskHandler implements Callable<CMTaskResult>
{
  private static Logger logger = Logger.getLogger(CMTaskHandler.class);
  private final Message _message;
  private final StateModel _stateModel;
  private final NotificationContext _notificationContext;
  private final ClusterManager _manager;
  StatusUpdateUtil _statusUpdateUtil;
  private TransitionMethodFinder _transitionMethodFinder;
  CMTaskExecutor _executor;

  public CMTaskHandler(NotificationContext notificationContext,
      Message message, StateModel stateModel, CMTaskExecutor executor) throws Exception
  {
    this._notificationContext = notificationContext;
    this._message = message;
    this._stateModel = stateModel;
    this._manager = notificationContext.getManager();
    _statusUpdateUtil = new StatusUpdateUtil();
    _transitionMethodFinder = new TransitionMethodFinder();
    _executor = executor;
    if (!validateTask())
    {
      String errorMessage = "Invalid Message, ensure that message: " + message
          + " has all the required fields: "
          + Arrays.toString(Message.Attributes.values());

      _statusUpdateUtil.logError(_message, CMTaskHandler.class, errorMessage,
          _manager.getDataAccessor());
      throw new ClusterManagerException(errorMessage);
    }
  }

  // TODO replace with util from espresso or linkedin
  private boolean isNullorEmpty(String data)
  {
    return data == null || data.length() == 0 || data.trim().length() == 0;
  }

  private boolean validateTask()
  {
    boolean isValid = isNullorEmpty(_message.getFromState())
        || isNullorEmpty(_message.getToState())
        || isNullorEmpty(_message.getToState())
        || isNullorEmpty(_message.getStateUnitKey())
        || isNullorEmpty(_message.getToState());
    return !isValid;
  }

  @Override
  public CMTaskResult call() throws Exception
  {
    synchronized (_stateModel)
    {
      ClusterDataAccessor accessor = _manager.getDataAccessor();

      _statusUpdateUtil.logInfo(_message, CMTaskHandler.class,
          "Message handling task begin execute", accessor);

      String stateUnitKey = _message.getStateUnitKey();
      String stateUnitGroup = _message.getStateUnitGroup();
      String instanceName = _manager.getInstanceName();
      CMTaskResult taskResult = new CMTaskResult();
      String fromState = _message.getFromState();
      String toState = _message.getToState();
      _message.setExecuteStartTimeStamp(new Date().getTime());
      if (fromState == null
          || !fromState.equalsIgnoreCase(_stateModel.getCurrentState()))
      {
        String errorMessage = "Current state of stateModel does not match the fromState in Message "
            + " Current State:"
            + _stateModel.getCurrentState()
            + ", message expected:" + fromState;
        logger.error(errorMessage);
        taskResult.setSuccess(false);
        taskResult.setMessage(errorMessage);
        accessor.removeInstanceProperty(instanceName,
            InstancePropertyType.MESSAGES, _message.getId());

        _statusUpdateUtil.logError(_message, CMTaskHandler.class, errorMessage,
            accessor);
        return taskResult;
      }
      Exception exception = null;
      try
      {
        invoke(accessor, taskResult, _message);
      } catch (Exception e)
      {
        exception = e;
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);

        String errorMessage = "Exception while executing a state transition task"
            + pw.toString();
        _statusUpdateUtil.logError(_message, CMTaskHandler.class, errorMessage,
            accessor);
        logger.error(errorMessage);
        taskResult.setSuccess(false);
      }

      try
      {
        ZNRecord currentState = accessor.getInstanceProperty(instanceName,
            InstancePropertyType.CURRENTSTATES, stateUnitGroup);
        if (currentState == null)
        {
          currentState = new ZNRecord();
        }
        
        Map<String, String> map = currentState.getMapField(stateUnitKey);
        if (map == null)
        {
          map = new HashMap<String, String>();
          currentState.setMapField(stateUnitKey, map);
        }
        // TODO verify that fromState is same as currentState this task
        // was
        // called at.
        // Verify that no one has edited this field
        if (taskResult.isSucess())
        {
          map.put(ZNAttribute.CURRENT_STATE.toString(), toState);

          _stateModel.updateState(toState);
          _statusUpdateUtil.logInfo(_message, CMTaskHandler.class,
              "Message handling task completed successfully", accessor);
        } else
        {
          StateTransitionError error = new StateTransitionError(
              StateTransitionError.ErrorCode.FRAMEWORK, exception);
          _stateModel.rollbackOnError(_message, _notificationContext, error);
          map.put(ZNAttribute.CURRENT_STATE.toString(), "ERROR");
          _stateModel.updateState("ERROR");
        }
        map.put(Message.Attributes.STATE_UNIT_GROUP.toString(),
            _message.getStateUnitGroup());
        accessor.setInstanceProperty(instanceName,
            InstancePropertyType.CURRENTSTATES, stateUnitGroup, currentState);
        accessor.removeInstanceProperty(instanceName,
            InstancePropertyType.MESSAGES, _message.getId());
        // based on task result update the current state of the node.
        reportMessgeStat(taskResult);

      } catch (Exception e)
      {
        logger.error("Error when updating the state ", e);
        StateTransitionError error = new StateTransitionError(
            StateTransitionError.ErrorCode.FRAMEWORK, e);
        _stateModel.rollbackOnError(_message, _notificationContext, error);
      }
      return taskResult;
    }
  }

  private void reportMessgeStat(CMTaskResult taskResult) throws Exception
  {
    // report stat
    long now = new Date().getTime();
    long msgReadTime = _message.getReadTimeStamp();
    long msgExecutionStartTime = _message.getReadTimeStamp();
    if(msgReadTime != 0 && msgExecutionStartTime != 0)
    {
      long totalDelay = now - msgReadTime;
      long executionDelay = now - msgExecutionStartTime;
      if(totalDelay > 0 && executionDelay > 0)
      {
        String fromState = _message.getFromState();
        String toState = _message.getToState();
        String transition = fromState + "--" + toState;
        
        StateTransitionContext cxt = new StateTransitionContext(
          _manager.getClusterName(),
          _manager.getInstanceName(),
          _message.getStateUnitGroup(),
          transition
          );
        
        StateTransitionDataPoint data = new StateTransitionDataPoint(totalDelay, executionDelay, taskResult.isSucess());
        if(_executor != null)
        {
          _executor.getStatMonitor().reportTransitionStat(cxt, data);
        }
      }
    }
    else
    {
      logger.warn("message read time and start execution time not recorded.");
      throw new Exception();
    }
  }
  
  private void invoke(ClusterDataAccessor accessor, CMTaskResult taskResult,
      Message message) throws IllegalAccessException, InvocationTargetException
  {
    Method methodToInvoke = null;
    String fromState = _message.getFromState();
    String toState = _message.getToState();
    methodToInvoke = _transitionMethodFinder.getMethodForTransition(
        _stateModel.getClass(), fromState, toState, new Class[]
        { Message.class, NotificationContext.class });
    if (methodToInvoke != null)
    {
      methodToInvoke.invoke(_stateModel, new Object[]
      { _message, _notificationContext });
      taskResult.setSuccess(true);
    } else
    {
      String errorMessage = "Unable to find method for transition from "
          + fromState + " to " + toState + "in " + _stateModel.getClass();
      logger.error(errorMessage);
      taskResult.setSuccess(false);

      _statusUpdateUtil.logError(_message, CMTaskHandler.class, errorMessage,
          accessor);
    }
  }
};
