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
package com.linkedin.helix.messaging.handling;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.log4j.Logger;

import com.linkedin.helix.HelixDataAccessor;
import com.linkedin.helix.HelixException;
import com.linkedin.helix.HelixManager;
import com.linkedin.helix.NotificationContext;
import com.linkedin.helix.PropertyKey.Builder;
import com.linkedin.helix.ZNRecordBucketizer;
import com.linkedin.helix.ZNRecordDelta;
import com.linkedin.helix.ZNRecordDelta.MERGEOPERATION;
import com.linkedin.helix.model.CurrentState;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.participant.statemachine.StateModel;
import com.linkedin.helix.participant.statemachine.StateModelParser;
import com.linkedin.helix.participant.statemachine.StateTransitionError;
import com.linkedin.helix.util.StatusUpdateUtil;

public class HelixStateTransitionHandler extends MessageHandler
{
  private static Logger          logger =
                                            Logger.getLogger(HelixStateTransitionHandler.class);
  private final StateModel       _stateModel;
  StatusUpdateUtil               _statusUpdateUtil;
  private final StateModelParser _transitionMethodFinder;
  private final CurrentState     _currentStateDelta;

  public HelixStateTransitionHandler(StateModel stateModel,
                                     Message message,
                                     NotificationContext context,
                                     CurrentState currentStateDelta)
  {
    super(message, context);
    _stateModel = stateModel;
    _statusUpdateUtil = new StatusUpdateUtil();
    _transitionMethodFinder = new StateModelParser();
    _currentStateDelta = currentStateDelta;
  }

  private void prepareMessageExecution(HelixManager manager, Message message) throws HelixException
  {
    if (!message.isValid())
    {
      String errorMessage =
          "Invalid Message, ensure that message: " + message
              + " has all the required fields: "
              + Arrays.toString(Message.Attributes.values());

      _statusUpdateUtil.logError(message,
                                 HelixStateTransitionHandler.class,
                                 errorMessage,
                                 manager.getHelixDataAccessor());
      logger.error(errorMessage);
      throw new HelixException(errorMessage);
    }
    // DataAccessor accessor = manager.getDataAccessor();
    HelixDataAccessor accessor = manager.getHelixDataAccessor();

    String partitionName = message.getPartitionName();
    String fromState = message.getFromState();

    // Verify the fromState and current state of the stateModel
    String state = _currentStateDelta.getState(partitionName);

    if (fromState != null && !fromState.equals("*") && !fromState.equalsIgnoreCase(state))
    {
      String errorMessage =
          "Current state of stateModel does not match the fromState in Message"
              + ", Current State:" + state + ", message expected:" + fromState
              + ", partition: " + partitionName + ", from: " + message.getMsgSrc()
              + ", to: " + message.getTgtName();

      _statusUpdateUtil.logError(message,
                                 HelixStateTransitionHandler.class,
                                 errorMessage,
                                 accessor);
      logger.error(errorMessage);
      throw new HelixException(errorMessage);
    }
  }

  void postExecutionMessage(HelixManager manager,
                            Message message,
                            NotificationContext context,
                            HelixTaskResult taskResult,
                            Exception exception) throws InterruptedException
  {
    // DataAccessor accessor = manager.getDataAccessor();
    HelixDataAccessor accessor = manager.getHelixDataAccessor();
    Builder keyBuilder = accessor.keyBuilder();
    try
    {
      String partitionKey = message.getPartitionName();
      String resource = message.getResourceName();
      String instanceName = manager.getInstanceName();

      int bucketSize = message.getBucketSize();

      CurrentState currentState = null;
      if (bucketSize <= 0)
      {
        accessor.getProperty(keyBuilder.currentState(instanceName,
                                                     manager.getSessionId(),
                                                     resource));
      }
      else
      {
        ZNRecordBucketizer bucketizer = new ZNRecordBucketizer(bucketSize);
        accessor.getProperty(keyBuilder.currentState(instanceName,
                                                     manager.getSessionId(),
                                                     resource,
                                                     bucketizer.getBucketName(partitionKey)));

      }

      if (currentState == null)
      {
        logger.warn("currentState is null. Storage node should be working with static file based cluster manager.");
      }

      // TODO verify that fromState is same as currentState this task
      // was
      // called at.
      // Verify that no one has edited this field

      if (taskResult.isSucess())
      {
        // String fromState = message.getFromState();
        String toState = message.getToState();
        _currentStateDelta.setState(partitionKey, toState);

        if (toState.equalsIgnoreCase("DROPPED"))
        {
          // for "OnOfflineToDROPPED" message, we need to remove the resource
          // key
          // record from
          // the current state of the instance because the resource key is
          // dropped.
          ZNRecordDelta delta =
              new ZNRecordDelta(_currentStateDelta.getRecord(), MERGEOPERATION.SUBTRACT);
          // don't subtract simple fields since they contain stateModelDefRef
          delta._record.getSimpleFields().clear();

          List<ZNRecordDelta> deltaList = new ArrayList<ZNRecordDelta>();
          deltaList.add(delta);
          _currentStateDelta.setDeltaList(deltaList);
        }
        else
        {
          // If a resource key is dropped, it is ok to leave it "offline"
          _stateModel.updateState(toState);
        }
      }
      else
      {
        StateTransitionError error =
            new StateTransitionError(ErrorType.INTERNAL, ErrorCode.ERROR, exception);

        _stateModel.rollbackOnError(message, context, error);
        _currentStateDelta.setState(partitionKey, "ERROR");
        _stateModel.updateState("ERROR");

      }

      // based on task result update the current state of the node
      if (bucketSize <= 0)
      {
        accessor.updateProperty(keyBuilder.currentState(instanceName,
                                                        manager.getSessionId(),
                                                        resource),
                                _currentStateDelta);

      }
      else
      {
        ZNRecordBucketizer bucketizer = new ZNRecordBucketizer(bucketSize);
        accessor.updateProperty(keyBuilder.currentState(instanceName,
                                                        manager.getSessionId(),
                                                        resource,
                                                        bucketizer.getBucketName(partitionKey)),
                                _currentStateDelta);
      }
    }
    catch (Exception e)
    {
      logger.error("Error when updating the state ", e);
      StateTransitionError error =
          new StateTransitionError(ErrorType.FRAMEWORK, ErrorCode.ERROR, e);
      _stateModel.rollbackOnError(message, context, error);
      _statusUpdateUtil.logError(message,
                                 HelixStateTransitionHandler.class,
                                 e,
                                 "Error when update the state ",
                                 accessor);
    }
  }

  public HelixTaskResult handleMessageInternal(Message message,
                                               NotificationContext context)
  {
    synchronized (_stateModel)
    {
      HelixTaskResult taskResult = new HelixTaskResult();
      HelixManager manager = context.getManager();
      // DataAccessor accessor = manager.getDataAccessor();
      HelixDataAccessor accessor = manager.getHelixDataAccessor();
      Builder keyBuilder = accessor.keyBuilder();

      try
      {
        _statusUpdateUtil.logInfo(message,
                                  HelixStateTransitionHandler.class,
                                  "Message handling task begin execute",
                                  accessor);
        message.setExecuteStartTimeStamp(new Date().getTime());

        Exception exception = null;
        try
        {
          prepareMessageExecution(manager, message);
          invoke(accessor, context, taskResult, message);
        }
        catch (InterruptedException e)
        {
          throw e;
        }
        catch (Exception e)
        {
          String errorMessage = "Exception while executing a state transition task. ";
          logger.error(errorMessage + ". " + e.getMessage(), e);
          _statusUpdateUtil.logError(message,
                                     HelixStateTransitionHandler.class,
                                     e,
                                     errorMessage,
                                     accessor);
          taskResult.setSuccess(false);
          taskResult.setMessage(e.toString());
          taskResult.setException(e);

          exception = e;
        }
        postExecutionMessage(manager, message, context, taskResult, exception);
        return taskResult;
      }
      catch (InterruptedException e)
      {
        _statusUpdateUtil.logError(message,
                                   HelixStateTransitionHandler.class,
                                   e,
                                   "State transition interrupted",
                                   accessor);
        logger.info("Message " + message.getMsgId() + " is interrupted");

        StateTransitionError error =
            new StateTransitionError(ErrorType.FRAMEWORK, ErrorCode.CANCEL, e);

        _stateModel.rollbackOnError(message, context, error);
        // We have handled the cancel case here, so no need to let outside know
        // taskResult.setInterrupted(true);
        // taskResult.setException(e);
        taskResult.setSuccess(false);
        return taskResult;
      }
    }
  }

  private void invoke(HelixDataAccessor accessor,
                      NotificationContext context,
                      HelixTaskResult taskResult,
                      Message message) throws IllegalAccessException,
      InvocationTargetException,
      InterruptedException
  {
    _statusUpdateUtil.logInfo(message,
                              HelixStateTransitionHandler.class,
                              "Message handling invoking",
                              accessor);

    // by default, we invoke state transition function in state model
    Method methodToInvoke = null;
    String fromState = message.getFromState();
    String toState = message.getToState();
    methodToInvoke =
        _transitionMethodFinder.getMethodForTransition(_stateModel.getClass(),
                                                       fromState,
                                                       toState,
                                                       new Class[] { Message.class,
                                                           NotificationContext.class });
    if (methodToInvoke != null)
    {
      methodToInvoke.invoke(_stateModel, new Object[] { message, context });
      taskResult.setSuccess(true);
    }
    else
    {
      String errorMessage =
          "Unable to find method for transition from " + fromState + " to " + toState
              + "in " + _stateModel.getClass();
      logger.error(errorMessage);
      taskResult.setSuccess(false);

      System.out.println(errorMessage);
      _statusUpdateUtil.logError(message,
                                 HelixStateTransitionHandler.class,
                                 errorMessage,
                                 accessor);
    }
  }

  @Override
  public HelixTaskResult handleMessage() throws InterruptedException
  {
    return handleMessageInternal(_message, _notificationContext);
  }

  @Override
  public void onError(Exception e, ErrorCode code, ErrorType type)
  {
    HelixManager manager = _notificationContext.getManager();
    HelixDataAccessor accessor = manager.getHelixDataAccessor();
    Builder keyBuilder = accessor.keyBuilder();

    String instanceName = manager.getInstanceName();
    String partition = _message.getPartitionName();
    String resourceName = _message.getResourceName();
    CurrentState currentStateDelta = new CurrentState(resourceName);

    StateTransitionError error = new StateTransitionError(type, code, e);
    _stateModel.rollbackOnError(_message, _notificationContext, error);
    // if the transition is not canceled, it should go into error state
    if (code == ErrorCode.ERROR)
    {
      currentStateDelta.setState(partition, "ERROR");
      _stateModel.updateState("ERROR");

      accessor.updateProperty(keyBuilder.currentState(instanceName,
                                                      manager.getSessionId(),
                                                      resourceName),
                              currentStateDelta);
    }
  }
};
