WHAT IS HELIX
--------------
Helix is a generic cluster management framework used for automatic management of partitioned, replicated and distributed resources hosted on a group of nodes( cluster). Helix provides the following features 

1. Resource/partition assignment to nodes
2. Node Failure detection and recovery
3. Dynamic addition of Resources 
4. Dynamic Addition of nodes to the cluster
5. Pluggable distributed state machine to manage the state of a resource via state transitions
6. Automatic Load balancing and throttling of transitions 

-----

A resource (for eg a database, lucene index or any task) in general is partitioned, replicated and distributed among the nodes in the cluster. 

Each partition of a resource can have a state associated with it. Here are some common state models used
1. Master, Slave
2. Online, Offline
3. Leader Standby.

Helix manages the state of a resource by supporting a pluggable distributed state machine. One can define the state machine table along with the constraints for each state. For example in case of a MasterSlave state model one can specify the state machine as

<code>
          OFFLINE  | SLAVE  |  MASTER  
         _____________________________
        |          |        |         |
OFFLINE |   N/A    | SLAVE  | SLAVE   |
        |__________|________|_________|
        |          |        |         |
SLAVE   |  OFFLINE |   N/A  | MASTER  |
        |__________|________|_________|
        |          |        |         |
MASTER  | SLAVE    | SLAVE  |   N/A   |
        |__________|________|_________|

</code>
Helix also supports the ability to provide constraints on each state. For example in a MasterSlave state model with a replication factor of 3 one can say MASTER:1 SLAVE:2

Helix will automatically try to maintain 1 Master and 2 Slaves by initiating appropriate state transitions in the cluster. 

Each transition results in a Partition moving from its CURRENT state state to an NEW state. These transitions are triggered on changes in the cluster state like 
* Node start up
* Node soft and hard failures 
* Addition of resources
* Addition of nodes

---------


With these features Helix framework can be used to build distributed, scalable, elastic and fault tolerant systems by configuring application state machine. Application has to provide the implementation for handling state transitions appropriately. Example 
<pre><code>
MasterSlaveStateModel extends HelixStateModel {

  void onOfflineToSlave(Message m, NotificationContext context){
    print("Transitioning from Offline to Slave for database:"+ m.resourceGroup + " and partition:"+ m.resourceKey);
  }
  void onSlaveToMaster(Message m, NotificationContext context){
    print("Transitioning from Slave to Master for database:"+ m.resourceGroup + " and partition:"+ m.resourceKey);
  }
  void onMasterToSlave(Message m, NotificationContext context){
    print("Transitioning from Master to Slave for database:"+ m.resourceGroup + " and partition:"+ m.resourceKey);
  }
  void onSlaveToOffline(Message m, NotificationContext context){
    print("Transitioning from Slave to Offline for database:"+ m.resourceGroup + " and partition:"+ m.resourceKey);
  }

}
</code></pre>

Helix uses Zookeeper for maintaining the cluster state and change notification.

----------------

TRY IT
-----------

Install/Start zookeeper
-----------------------
http://zookeeper.apache.org/doc/r3.3.3/zookeeperStarted.html
Zookeeper can be started in standalone mode or replicated node more info at http://zookeeper.apache.org/doc/trunk/zookeeperAdmin.html#sc_zkMulitServerSetup
<code>
bin/zkServer.sh start or
java -cp zookeeper-3.3.3.jar:lib/log4j-1.2.15.jar:conf org.apache.zookeeper.server.quorum.QuorumPeerMain conf/zoo_multi.cfg
</code>

BUILD Helix
-----------
<code>
git clone git@github.com:linkedin/helix.git
cd helix-core
mvn install package appassemble:assemble -Dmaven.test.skip=true 
cd target/helix-core-pkg/bin
</code>

Cluster setup
-------------
<code>
cluster-admin -zkSvr <zookeeper_address> -addCluster <mycluster>

 #Create a database
 cluster-admin -zkSvr <zookeeper_address> -addResourceGroup <mycluster> <myDB> <numpartitions> <statemodel>
 #Add nodes to the cluster, in this case we add three nodes, hostname:port is host and port on which the service will start
 cluster-admin -zkSvr <zookeeper_address> -addNode <mycluster> <hostname:port1>
 cluster-admin -zkSvr <zookeeper_address> -addNode <mycluster> <hostname:port2>
 cluster-admin -zkSvr <zookeeper_address> -addNode <mycluster> <hostname:port3>

 #After adding nodes assign partitions to nodes. By default there will be one MASTER per partition, use replication_factor to specif number of SLAVES for each partition
 cluster-manager-admin --rebalance <mycluster> <myDB> <replication_factor>
</code>

Start Cluster Manager
---------------------
<code>

#This will start the cluster manager which will manage <mycluster>
run-cluster-manager --zkSvr <zookeeper_address> --cluster <mycluster>

</code>

Start Example Process
---------------------
<code>

cd target/cluster-manager-core-pkg/bin
chmod \+x *
./start-example-process --help
#start process 1 process corresponding to every host port added during cluster setup
./start-example-process --cluster <mycluster> --host <hostname1> --port <port1> --stateModelType MasterSlave
./start-example-process --cluster <mycluster> --host <hostname2> --port <port2> --stateModelType MasterSlave
./start-example-process --cluster <mycluster> --host <hostname3> --port <port3> --stateModelType MasterSlave

</code>

Inspect Cluster Data
--------------------

* Use ZooInspector that comes with zookeeper to inspect the data
* To start zooinspector
   java -cp zookeeper-3.3.3-ZooInspector.jar:lib/jtoaster-1.0.4.jar:../../lib/log4j-1.2.15.jar:../../zookeeper-3.3.3.jar org.apache.zookeeper.inspector.ZooInspector
   Click and connect and provide the zookeeper address to inspect. If zookeeper is running locally use localhost:2181








