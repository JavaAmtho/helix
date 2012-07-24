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
package com.linkedin.helix.participant;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;

import com.linkedin.helix.HelixConstants;
import com.linkedin.helix.HelixDataAccessor;
import com.linkedin.helix.HelixException;
import com.linkedin.helix.HelixManager;
import com.linkedin.helix.InstanceType;
import com.linkedin.helix.NotificationContext;
import com.linkedin.helix.PropertyKey.Builder;
import com.linkedin.helix.messaging.handling.HelixStateTransitionHandler;
import com.linkedin.helix.messaging.handling.MessageHandler;
import com.linkedin.helix.model.CurrentState;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.model.Message.MessageType;
import com.linkedin.helix.model.StateModelDefinition;
import com.linkedin.helix.participant.statemachine.StateModel;
import com.linkedin.helix.participant.statemachine.StateModelFactory;
import com.linkedin.helix.participant.statemachine.StateModelParser;

public class HelixStateMachineEngine implements StateMachineEngine
{
  private static Logger                                                           logger                =
                                                                                                            Logger.getLogger(HelixStateMachineEngine.class);

  // StateModelName->FactoryName->StateModelFactory
  private final Map<String, Map<String, StateModelFactory<? extends StateModel>>> _stateModelFactoryMap =
                                                                                                            new ConcurrentHashMap<String, Map<String, StateModelFactory<? extends StateModel>>>();
  StateModelParser                                                                _stateModelParser;

  private final HelixManager                                                      _manager;

  public StateModelFactory<? extends StateModel> getStateModelFactory(String stateModelName)
  {
    return getStateModelFactory(stateModelName,
                                HelixConstants.DEFAULT_STATE_MODEL_FACTORY);
  }

  public StateModelFactory<? extends StateModel> getStateModelFactory(String stateModelName,
                                                                      String factoryName)
  {
    if (!_stateModelFactoryMap.containsKey(stateModelName))
    {
      return null;
    }
    return _stateModelFactoryMap.get(stateModelName).get(factoryName);
  }

  public HelixStateMachineEngine(HelixManager manager)
  {
    _stateModelParser = new StateModelParser();
    _manager = manager;
  }

  @Override
  public boolean registerStateModelFactory(String stateModelDef,
                                           StateModelFactory<? extends StateModel> factory)
  {
    return registerStateModelFactory(stateModelDef,
                                     factory,
                                     HelixConstants.DEFAULT_STATE_MODEL_FACTORY);
  }

  @Override
  public boolean registerStateModelFactory(String stateModelDef,
                                           StateModelFactory<? extends StateModel> factory,
                                           String factoryName)
  {
    if (stateModelDef == null || factory == null || factoryName == null)
    {
      throw new HelixException("stateModelDef|stateModelFactory|factoryName cannot be null");
    }

    logger.info("Register state model factory for state model " + stateModelDef
        + " using factory name " + factoryName + " with " + factory);

    if (!_stateModelFactoryMap.containsKey(stateModelDef))
    {
      _stateModelFactoryMap.put(stateModelDef,
                                new ConcurrentHashMap<String, StateModelFactory<? extends StateModel>>());
    }

    if (_stateModelFactoryMap.get(stateModelDef).containsKey(factoryName))
    {
      logger.warn("stateModelFactory for " + stateModelDef + " using factoryName "
          + factoryName + " has already been registered.");
      return false;
    }

    _stateModelFactoryMap.get(stateModelDef).put(factoryName, factory);
    sendNopMessage();
    return true;
  }

  // TODO: duplicated code in DefaultMessagingService
  private void sendNopMessage()
  {
    if (_manager.isConnected())
    {
      try
      {
        Message nopMsg = new Message(MessageType.NO_OP, UUID.randomUUID().toString());
        nopMsg.setSrcName(_manager.getInstanceName());

        HelixDataAccessor accessor = _manager.getHelixDataAccessor();
        Builder keyBuilder = accessor.keyBuilder();

        if (_manager.getInstanceType() == InstanceType.CONTROLLER
            || _manager.getInstanceType() == InstanceType.CONTROLLER_PARTICIPANT)
        {
          nopMsg.setTgtName("Controller");
          accessor.setProperty(keyBuilder.controllerMessage(nopMsg.getId()), nopMsg);
        }

        if (_manager.getInstanceType() == InstanceType.PARTICIPANT
            || _manager.getInstanceType() == InstanceType.CONTROLLER_PARTICIPANT)
        {
          nopMsg.setTgtName(_manager.getInstanceName());
          accessor.setProperty(keyBuilder.message(nopMsg.getTgtName(), nopMsg.getId()),
                               nopMsg);
        }

      }
      catch (Exception e)
      {
        logger.error(e);
      }
    }
  }

  @Override
  public void reset()
  {
    for (Map<String, StateModelFactory<? extends StateModel>> ftyMap : _stateModelFactoryMap.values())
    {
      for (StateModelFactory<? extends StateModel> stateModelFactory : ftyMap.values())
      {
        Map<String, ? extends StateModel> modelMap = stateModelFactory.getStateModelMap();
        if (modelMap == null || modelMap.isEmpty())
        {
          continue;
        }

        for (String resourceKey : modelMap.keySet())
        {
          StateModel stateModel = modelMap.get(resourceKey);
          stateModel.reset();
          String initialState = _stateModelParser.getInitialState(stateModel.getClass());
          stateModel.updateState(initialState);
          // TODO probably should update the state on ZK. Shi confirm what needs
          // to be done here.
        }

      }
    }
  }

  @Override
  public MessageHandler createHandler(Message message, NotificationContext context)
  {
    String type = message.getMsgType();

    if (!type.equals(MessageType.STATE_TRANSITION.toString()))
    {
      throw new HelixException("Unexpected msg type for message " + message.getMsgId()
          + " type:" + message.getMsgType());
    }

    String partitionKey = message.getPartitionName();
    String stateModelName = message.getStateModelDef();
    String resourceName = message.getResourceName();
    String sessionId = message.getTgtSessionId();
    
    int bucketSize = message.getBucketSize();
    if (stateModelName == null)
    {
      logger.error("message does not contain stateModelDef");
      return null;
    }

    String factoryName = message.getStateModelFactoryName();
    if (factoryName == null)
    {
      factoryName = HelixConstants.DEFAULT_STATE_MODEL_FACTORY;
    }

    StateModelFactory stateModelFactory =
        getStateModelFactory(stateModelName, factoryName);
    if (stateModelFactory == null)
    {
      logger.warn("Cannot find stateModelFactory for model:" + stateModelName
          + " using factoryName:" + factoryName + " for resourceGroup:" + resourceName);
      return null;
    }

    // create currentStateDelta for this partition
    HelixManager manager = context.getManager();

    // stateModelDefs are in dataAccessor's cache
    HelixDataAccessor accessor = manager.getHelixDataAccessor();
    Builder keyBuilder = accessor.keyBuilder();
    
    Map<String, StateModelDefinition> stateModelDefs =
        accessor.getChildValuesMap(keyBuilder.stateModelDefs());

    if (!stateModelDefs.containsKey(stateModelName))
    {
      throw new HelixException("No State Model Defined for " + stateModelName);
    }
    String initState = stateModelDefs.get(stateModelName).getInitialState();

    StateModel stateModel = stateModelFactory.getStateModel(partitionKey);
    if (stateModel == null)
    {
      stateModelFactory.createAndAddStateModel(partitionKey);
      stateModel = stateModelFactory.getStateModel(partitionKey);
      stateModel.updateState(initState);
    }

    CurrentState currentStateDelta = new CurrentState(resourceName);
    currentStateDelta.setSessionId(sessionId);
    currentStateDelta.setStateModelDefRef(stateModelName);
    currentStateDelta.setStateModelFactoryName(factoryName);
    currentStateDelta.setBucketSize(bucketSize);

    currentStateDelta.setState(partitionKey, (stateModel.getCurrentState() == null)
                               ? initState : stateModel.getCurrentState());
    
    return new HelixStateTransitionHandler(stateModel,
                                           message,
                                           context,
                                           currentStateDelta);
  }

  @Override
  public String getMessageType()
  {
    return MessageType.STATE_TRANSITION.toString();
  }

  @Override
  public boolean removeStateModelFactory(String stateModelDef,
                                         StateModelFactory<? extends StateModel> factory)
  {
    throw new UnsupportedOperationException("Remove not yet supported");
  }

  @Override
  public boolean removeStateModelFactory(String stateModelDef,
                                         StateModelFactory<? extends StateModel> factory,
                                         String factoryName)
  {
    throw new UnsupportedOperationException("Remove not yet supported");
  }
}
