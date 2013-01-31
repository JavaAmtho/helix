package com.linkedin.helix.mock.controller;

import java.util.concurrent.CountDownLatch;

import org.apache.log4j.Logger;

import com.linkedin.helix.HelixManager;
import com.linkedin.helix.HelixManagerFactory;
import com.linkedin.helix.InstanceType;
import com.linkedin.helix.ZkHelixTestManager;
import com.linkedin.helix.controller.HelixControllerMain;
import com.linkedin.helix.manager.zk.ZKHelixManager;
import com.linkedin.helix.participant.DistClusterControllerStateModelFactory;
import com.linkedin.helix.participant.StateMachineEngine;

public class ClusterController extends Thread
{
  private static Logger        LOG                      =
                                                            Logger.getLogger(ClusterController.class);

  private final CountDownLatch _startCountDown          = new CountDownLatch(1);
  private final CountDownLatch _stopCountDown           = new CountDownLatch(1);
  private final CountDownLatch _waitStopFinishCountDown = new CountDownLatch(1);
  private final String         _controllerMode;
  private final String         _zkAddr;

  private ZkHelixTestManager   _manager;

  public ClusterController(String clusterName, String controllerName, String zkAddr) throws Exception
  {
    this(clusterName, controllerName, zkAddr, HelixControllerMain.STANDALONE.toString());
  }

  public ClusterController(String clusterName,
                           String controllerName,
                           String zkAddr,
                           String controllerMode) throws Exception
  {
    _controllerMode = controllerMode;
    _zkAddr = zkAddr;

    if (_controllerMode.equals(HelixControllerMain.STANDALONE.toString()))
    {
      _manager =  new ZkHelixTestManager(clusterName, controllerName, InstanceType.CONTROLLER, zkAddr);

//          HelixManagerFactory.getZKHelixManager(clusterName,
//                                                controllerName,
//                                                InstanceType.CONTROLLER,
//                                                zkAddr);
    }
    else if (_controllerMode.equals(HelixControllerMain.DISTRIBUTED.toString()))
    {
      _manager = new ZkHelixTestManager(clusterName, controllerName, InstanceType.CONTROLLER_PARTICIPANT, zkAddr);
//          HelixManagerFactory.getZKHelixManager(clusterName,
//                                                controllerName,
//                                                InstanceType.CONTROLLER_PARTICIPANT,
//                                                zkAddr);

    }
    else
    {
      throw new IllegalArgumentException("Controller mode: " + controllerMode
          + " NOT recoginized");
    }
  }

  public ZkHelixTestManager getManager()
  {
    return _manager;
  }

  public void syncStop()
  {
    if (_manager == null)
    {
      LOG.warn("manager already stopped");
      return;
    }

    _stopCountDown.countDown();
    try
    {
      _waitStopFinishCountDown.await();
    }
    catch (InterruptedException e)
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  public void syncStart()
  {
    // TODO: prevent start multiple times
    
    super.start();
    try
    {
      _startCountDown.await();
    }
    catch (InterruptedException e)
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  public void run()
  {
    try
    {
      try
      {
        if (_controllerMode.equals(HelixControllerMain.STANDALONE.toString()))
        {
          _manager.connect();
        }
        else if (_controllerMode.equals(HelixControllerMain.DISTRIBUTED.toString()))
        {
          DistClusterControllerStateModelFactory stateModelFactory =
              new DistClusterControllerStateModelFactory(_zkAddr);

          StateMachineEngine stateMach = _manager.getStateMachineEngine();
          stateMach.registerStateModelFactory("LeaderStandby", stateModelFactory);
          _manager.connect();
        }
      }
      catch (Exception e)
      {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
      finally
      {
        _startCountDown.countDown();
        _stopCountDown.await();
      }
    }
    catch (Exception e)
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    finally
    {
      synchronized (_manager)
      {
        _manager.disconnect();
        _manager = null;
      }
      _waitStopFinishCountDown.countDown();
    }
  }
}
