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
import com.linkedin.helix.PropertyKey;
import com.linkedin.helix.PropertyKey.Builder;
import com.linkedin.helix.ZNRecordBucketizer;
import com.linkedin.helix.ZNRecordDelta;
import com.linkedin.helix.ZNRecordDelta.MergeOperation;
import com.linkedin.helix.model.CurrentState;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.participant.statemachine.StateModel;
import com.linkedin.helix.participant.statemachine.StateModelParser;
import com.linkedin.helix.participant.statemachine.StateTransitionError;
import com.linkedin.helix.util.StatusUpdateUtil;

public class HelixStateTransitionHandler extends MessageHandler {
    public static class HelixStateMismatchException extends Exception {
	public HelixStateMismatchException(String info) {
	    super(info);
	}
    }

    private static Logger logger = Logger.getLogger(HelixStateTransitionHandler.class);
    private final List<StateModel> _stateModels;
    StatusUpdateUtil _statusUpdateUtil;
    private final StateModelParser _transitionMethodFinder;
    private final CurrentState _currentStateDelta;
    volatile boolean _isTimeout = false;
    private final HelixTaskExecutor _executor;

    public HelixStateTransitionHandler(StateModel stateModel, Message message,
	    NotificationContext context, CurrentState currentStateDelta, HelixTaskExecutor executor) {
	super(message, context);
	_stateModels = new ArrayList<StateModel>();
	_stateModels.add(stateModel);
	_statusUpdateUtil = new StatusUpdateUtil();
	_transitionMethodFinder = new StateModelParser();
	_currentStateDelta = currentStateDelta;
	_executor = executor;
    }

    public HelixStateTransitionHandler(List<StateModel> stateModels, Message message,
	    NotificationContext context, CurrentState currentStateDelta, HelixTaskExecutor executor) {
	super(message, context);
	_stateModels = stateModels;
	_statusUpdateUtil = new StatusUpdateUtil();
	_transitionMethodFinder = new StateModelParser();
	_currentStateDelta = currentStateDelta;
	_executor = executor;
    }

    private void prepareMessageExecution(HelixManager manager, Message message)
	    throws HelixException, HelixStateMismatchException {
	if (!message.isValid()) {
	    String errorMessage = "Invalid Message, ensure that message: " + message
		    + " has all the required fields: "
		    + Arrays.toString(Message.Attributes.values());

	    _statusUpdateUtil.logError(message, HelixStateTransitionHandler.class, errorMessage,
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

	if (fromState != null && !fromState.equals("*") && !fromState.equalsIgnoreCase(state)) {
	    String errorMessage = "Current state of stateModel does not match the fromState in Message"
		    + ", Current State:"
		    + state
		    + ", message expected:"
		    + fromState
		    + ", partition: "
		    + partitionName
		    + ", from: "
		    + message.getMsgSrc()
		    + ", to: "
		    + message.getTgtName();

	    _statusUpdateUtil.logError(message, HelixStateTransitionHandler.class, errorMessage,
		    accessor);
	    logger.error(errorMessage);
	    throw new HelixStateMismatchException(errorMessage);
	}
    }

    void postExecutionMessage(HelixManager manager, Message message, NotificationContext context,
	    HelixTaskResult taskResult, Exception exception) {
	// String partitionKey = message.getPartitionName();
	List<String> partitionKeys = message.getExePartitionNames();

	String resource = message.getResourceName();
	String sessionId = message.getTgtSessionId();
	String instanceName = manager.getInstanceName();

	HelixDataAccessor accessor = manager.getHelixDataAccessor();
	Builder keyBuilder = accessor.keyBuilder();

	int bucketSize = message.getBucketSize();
	ZNRecordBucketizer bucketizer = new ZNRecordBucketizer(bucketSize);

	// Lock the helix manager so that the session id will not change when we
	// update
	// the state model state. for zk current state it is OK as we have the
	// per-session
	// current state node
	// synchronized (manager)
	{
	    if (!message.getTgtSessionId().equals(manager.getSessionId())) {
		logger.warn("Session id has changed. Skip postExecutionMessage. Old session "
		        + message.getExecutionSessionId() + " , new session : "
		        + manager.getSessionId());
		return;
	    }

	    if (taskResult.isSucess()) {
		// String fromState = message.getFromState();
		String toState = message.getToState();
		for (String partitionKey : partitionKeys) {
		    _currentStateDelta.setState(partitionKey, toState);
		}

		if (toState.equalsIgnoreCase("DROPPED")) {
		    // for "OnOfflineToDROPPED" message, we need to remove the
		    // resource key record
		    // from the current state of the instance because the
		    // resource key is dropped.
		    // In the state model it will be stayed as "OFFLINE", which
		    // is OK.
		    ZNRecordDelta delta = new ZNRecordDelta(_currentStateDelta.getRecord(),
			    MergeOperation.SUBTRACT);
		    // Don't subtract simple fields since they contain
		    // stateModelDefRef
		    delta._record.getSimpleFields().clear();

		    List<ZNRecordDelta> deltaList = new ArrayList<ZNRecordDelta>();
		    deltaList.add(delta);
		    _currentStateDelta.setDeltaList(deltaList);
		} else {
		    // if the partition is not to be dropped, update _stateModel
		    // to the TO_STATE
		    for (StateModel stateModel : _stateModels) {
			stateModel.updateState(toState);
		    }
		}
	    } else {
		if (exception instanceof HelixStateMismatchException) {
		    // if fromState mismatch, set current state on zk to
		    // stateModel's current state
		    logger.warn("Force CurrentState on Zk to be stateModel's CurrentState. partitionKey: "
			    + partitionKeys
			    + ", currentState: "
			    + _stateModels.get(0).getCurrentState() + ", message: " + message);
		    for (int i = 0; i < partitionKeys.size(); i++) {
			String partitionKey = partitionKeys.get(i);
			_currentStateDelta.setState(partitionKey, _stateModels.get(i)
			        .getCurrentState());
		    }
		} else {
		    StateTransitionError error = new StateTransitionError(ErrorType.INTERNAL,
			    ErrorCode.ERROR, exception);
		    if (exception instanceof InterruptedException) {
			if (_isTimeout) {
			    error = new StateTransitionError(ErrorType.INTERNAL, ErrorCode.TIMEOUT,
				    exception);
			} else {
			    // State transition interrupted but not caused by
			    // timeout. Keep the current
			    // state in this case
			    logger.error("State transition interrupted but not timeout. Not updating state. Partition : "
				    + message.getPartitionName() + " MsgId : " + message.getMsgId());
			    return;
			}
		    }
		    for (int i = 0; i < partitionKeys.size(); i++) {
			_stateModels.get(i).rollbackOnError(message, context, error);
			_currentStateDelta.setState(partitionKeys.get(i), "ERROR");
			_stateModels.get(i).updateState("ERROR");
		    }
		}
	    }
	}
	try {
	    // Update the ZK current state of the node
	    if (!_message.getGroupMessageMode()) {
		PropertyKey key = keyBuilder.currentState(instanceName, sessionId, resource,
		        bucketizer.getBucketName(partitionKeys.get(0)));

		accessor.updateProperty(key, _currentStateDelta);
	    } else {
		int exeBatchSize = message.getExeBatchSize();
		if (exeBatchSize == 1) {
		    PropertyKey key = keyBuilder.currentState(instanceName, sessionId, resource,
			    bucketizer.getBucketName(partitionKeys.get(0)));

		    _executor._groupMsgHandler.addCurStateUpdate(_message, key, _currentStateDelta);
		} else {
		    PropertyKey key = keyBuilder.currentState(instanceName, sessionId, resource); // not
												  // supporting
												  // bucketize

		    _executor._groupMsgHandler.addCurStateUpdate(_message, key, _currentStateDelta);
		}

		// ZkItem<ZNRecord> item =
		// (ZkItem<ZNRecord>)context.get(NotificationContext.ZK_WRITE_KEY);
		// Queue<ZkItem<ZNRecord>> list = context.getZkItemList();
		// if (list != null)
		// {
		// _executor._groupMsgHandler.addZkItems(list);
		// }
	    }
	} catch (Exception e) {
	    logger.error("Error when updating the state ", e);
	    StateTransitionError error = new StateTransitionError(ErrorType.FRAMEWORK,
		    ErrorCode.ERROR, e);
	    for (StateModel stateModel : _stateModels) {
		stateModel.rollbackOnError(message, context, error);
	    }
	    _statusUpdateUtil.logError(message, HelixStateTransitionHandler.class, e,
		    "Error when update the state ", accessor);
	}
    }

    public HelixTaskResult handleMessageInternal(Message message, NotificationContext context) {
	// synchronized (_stateModel)
	{
	    HelixTaskResult taskResult = new HelixTaskResult();
	    HelixManager manager = context.getManager();
	    HelixDataAccessor accessor = manager.getHelixDataAccessor();

	    _statusUpdateUtil.logInfo(message, HelixStateTransitionHandler.class,
		    "Message handling task begin execute", accessor);
	    message.setExecuteStartTimeStamp(new Date().getTime());

	    Exception exception = null;
	    try {
		prepareMessageExecution(manager, message);
		invoke(accessor, context, taskResult, message);
	    } catch (HelixStateMismatchException e) {
		// Simply log error and return from here if State mismatch.
		// The current state of the state model is intact.
		taskResult.setSuccess(false);
		taskResult.setMessage(e.toString());
		taskResult.setException(e);
		exception = e;
		// return taskResult;
	    } catch (Exception e) {
		String errorMessage = "Exception while executing a state transition task "
		        + message.getPartitionName();
		logger.error(errorMessage, e);
		if (e.getCause() != null && e.getCause() instanceof InterruptedException) {
		    e = (InterruptedException) e.getCause();
		}
		_statusUpdateUtil.logError(message, HelixStateTransitionHandler.class, e,
		        errorMessage, accessor);
		taskResult.setSuccess(false);
		taskResult.setMessage(e.toString());
		taskResult.setException(e);
		taskResult.setInterrupted(e instanceof InterruptedException);
		exception = e;
	    }
	    postExecutionMessage(manager, message, context, taskResult, exception);

	    return taskResult;
	}
    }

    private void invoke(HelixDataAccessor accessor, NotificationContext context,
	    HelixTaskResult taskResult, Message message) throws IllegalAccessException,
	    InvocationTargetException, InterruptedException {
	_statusUpdateUtil.logInfo(message, HelixStateTransitionHandler.class,
	        "Message handling invoking", accessor);

	// by default, we invoke state transition function in state model
	Method methodToInvoke = null;
	String fromState = message.getFromState();
	String toState = message.getToState();
	methodToInvoke = _transitionMethodFinder.getMethodForTransition(_stateModels.get(0)
	        .getClass(), fromState, toState, new Class[] { Message.class,
	        NotificationContext.class });
	if (methodToInvoke != null) {
	    methodToInvoke.invoke(_stateModels.get(0), new Object[] { message, context });
	    taskResult.setSuccess(true);
	} else {
	    String errorMessage = "Unable to find method for transition from " + fromState + " to "
		    + toState + "in " + _stateModels.get(0).getClass();
	    logger.error(errorMessage);
	    taskResult.setSuccess(false);

	    _statusUpdateUtil.logError(message, HelixStateTransitionHandler.class, errorMessage,
		    accessor);
	}
    }

    @Override
    public HelixTaskResult handleMessage() {
	return handleMessageInternal(_message, _notificationContext);
    }

    @Override
    public void onError(Exception e, ErrorCode code, ErrorType type) {
	// All internal error has been processed already, so we can skip them
	if (type == ErrorType.INTERNAL) {
	    logger.error("Skip internal error " + e.getMessage() + " " + code);
	    return;
	}
	HelixManager manager = _notificationContext.getManager();
	HelixDataAccessor accessor = manager.getHelixDataAccessor();
	Builder keyBuilder = accessor.keyBuilder();

	String instanceName = manager.getInstanceName();
	String partition = _message.getPartitionName();
	String resourceName = _message.getResourceName();
	CurrentState currentStateDelta = new CurrentState(resourceName);

	StateTransitionError error = new StateTransitionError(type, code, e);
	for (StateModel stateModel : _stateModels) {
	    stateModel.rollbackOnError(_message, _notificationContext, error);
	    // if the transition is not canceled, it should go into error state
	    if (code == ErrorCode.ERROR) {
		currentStateDelta.setState(partition, "ERROR");
		stateModel.updateState("ERROR");

		accessor.updateProperty(keyBuilder.currentState(instanceName,
		        _message.getTgtSessionId(), resourceName), currentStateDelta);
	    }
	}
    }

    @Override
    public void onTimeout() {
	_isTimeout = true;
    }
};
