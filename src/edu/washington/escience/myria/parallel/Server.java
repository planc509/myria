package edu.washington.escience.myria.parallel;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import edu.washington.escience.myria.DbException;
import edu.washington.escience.myria.MyriaConstants;
import edu.washington.escience.myria.MyriaConstants.FTMODE;
import edu.washington.escience.myria.MyriaSystemConfigKeys;
import edu.washington.escience.myria.RelationKey;
import edu.washington.escience.myria.Schema;
import edu.washington.escience.myria.TupleWriter;
import edu.washington.escience.myria.Type;
import edu.washington.escience.myria.accessmethod.AccessMethod.IndexRef;
import edu.washington.escience.myria.api.encoding.DatasetStatus;
import edu.washington.escience.myria.api.encoding.QueryEncoding;
import edu.washington.escience.myria.api.encoding.QueryStatusEncoding;
import edu.washington.escience.myria.api.encoding.QueryStatusEncoding.Status;
import edu.washington.escience.myria.coordinator.catalog.CatalogException;
import edu.washington.escience.myria.coordinator.catalog.CatalogMaker;
import edu.washington.escience.myria.coordinator.catalog.MasterCatalog;
import edu.washington.escience.myria.expression.ConditionalExpression;
import edu.washington.escience.myria.expression.ConstantExpression;
import edu.washington.escience.myria.expression.EqualsExpression;
import edu.washington.escience.myria.expression.Expression;
import edu.washington.escience.myria.expression.MinusExpression;
import edu.washington.escience.myria.expression.PlusExpression;
import edu.washington.escience.myria.expression.StateExpression;
import edu.washington.escience.myria.expression.VariableExpression;
import edu.washington.escience.myria.expression.WorkerIdExpression;
import edu.washington.escience.myria.operator.Apply;
import edu.washington.escience.myria.operator.DataOutput;
import edu.washington.escience.myria.operator.DbInsert;
import edu.washington.escience.myria.operator.DbQueryScan;
import edu.washington.escience.myria.operator.EOSSource;
import edu.washington.escience.myria.operator.InMemoryOrderBy;
import edu.washington.escience.myria.operator.Operator;
import edu.washington.escience.myria.operator.RootOperator;
import edu.washington.escience.myria.operator.SinkRoot;
import edu.washington.escience.myria.operator.StatefulApply;
import edu.washington.escience.myria.operator.network.CollectConsumer;
import edu.washington.escience.myria.operator.network.CollectProducer;
import edu.washington.escience.myria.operator.network.Consumer;
import edu.washington.escience.myria.operator.network.GenericShuffleConsumer;
import edu.washington.escience.myria.operator.network.GenericShuffleProducer;
import edu.washington.escience.myria.operator.network.partition.RoundRobinPartitionFunction;
import edu.washington.escience.myria.parallel.ipc.FlowControlBagInputBuffer;
import edu.washington.escience.myria.parallel.ipc.IPCConnectionPool;
import edu.washington.escience.myria.parallel.ipc.IPCMessage;
import edu.washington.escience.myria.parallel.ipc.InJVMLoopbackChannelSink;
import edu.washington.escience.myria.parallel.ipc.QueueBasedShortMessageProcessor;
import edu.washington.escience.myria.proto.ControlProto.ControlMessage;
import edu.washington.escience.myria.proto.QueryProto.QueryMessage;
import edu.washington.escience.myria.proto.QueryProto.QueryReport;
import edu.washington.escience.myria.proto.TransportProto.TransportMessage;
import edu.washington.escience.myria.tool.MyriaConfigurationReader;
import edu.washington.escience.myria.util.DateTimeUtils;
import edu.washington.escience.myria.util.DeploymentUtils;
import edu.washington.escience.myria.util.IPCUtils;
import edu.washington.escience.myria.util.MyriaUtils;
import edu.washington.escience.myria.util.concurrent.ErrorLoggingTimerTask;
import edu.washington.escience.myria.util.concurrent.RenamingThreadFactory;

/**
 * The master entrance.
 */
public final class Server {

  /**
   * Master message processor.
   */
  private final class MessageProcessor implements Runnable {

    /** Constructor, set the thread name. */
    public MessageProcessor() {
      super();
    }

    @Override
    public void run() {
      TERMINATE_MESSAGE_PROCESSING : while (true) {
        IPCMessage.Data<TransportMessage> mw = null;
        try {
          mw = messageQueue.take();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          break TERMINATE_MESSAGE_PROCESSING;
        }

        final TransportMessage m = mw.getPayload();
        final int senderID = mw.getRemoteID();

        switch (m.getType()) {
          case CONTROL:
            final ControlMessage controlM = m.getControlMessage();
            switch (controlM.getType()) {
              case WORKER_HEARTBEAT:
                if (LOGGER.isTraceEnabled()) {
                  LOGGER.trace("getting heartbeat from worker " + senderID);
                }
                updateHeartbeat(senderID);
                break;
              case REMOVE_WORKER_ACK:
                int workerID = controlM.getWorkerId();
                removeWorkerAckReceived.get(workerID).add(senderID);
                break;
              case ADD_WORKER_ACK:
                workerID = controlM.getWorkerId();
                addWorkerAckReceived.get(workerID).add(senderID);
                for (Long id : activeQueries.keySet()) {
                  MasterQueryPartition mqp = activeQueries.get(id);
                  if (mqp.getFTMode().equals(FTMODE.rejoin) && mqp.getMissingWorkers().contains(workerID)
                      && addWorkerAckReceived.get(workerID).containsAll(mqp.getWorkerAssigned())) {
                    /* so a following ADD_WORKER_ACK won't cause queryMessage to be sent again */
                    mqp.getMissingWorkers().remove(workerID);
                    try {
                      connectionPool.sendShortMessage(workerID, IPCUtils.queryMessage(mqp.getQueryID(), mqp
                          .getWorkerPlans().get(workerID)));
                    } catch (final IOException e) {
                      throw new RuntimeException(e);
                    }
                  }
                }
                break;
              case STOP_WORKER_ACK:
                workerID = controlM.getWorkerId();
                if (LOGGER.isInfoEnabled()) {
                  LOGGER.info("Received STOP_WORKER_ACK from worker #" + workerID);
                }
                stoppingWorkers.remove(workerID);
                connectionPool.sendShortMessage(workerID, IPCUtils.CONTROL_SHUTDOWN);
                break;
              default:
                if (LOGGER.isErrorEnabled()) {
                  LOGGER.error("Unexpected control message received at master: " + controlM);
                }
            }
            break;
          case QUERY:
            final QueryMessage qm = m.getQueryMessage();
            final long queryId = qm.getQueryId();
            switch (qm.getType()) {
              case QUERY_READY_TO_EXECUTE:
                MasterQueryPartition mqp = activeQueries.get(queryId);
                mqp.queryReceivedByWorker(senderID);
                break;
              case QUERY_COMPLETE:
                mqp = activeQueries.get(queryId);
                QueryReport qr = qm.getQueryReport();
                if (qr.getSuccess()) {
                  mqp.workerComplete(senderID);
                } else {
                  ObjectInputStream osis = null;
                  Throwable cause = null;
                  try {
                    osis = new ObjectInputStream(new ByteArrayInputStream(qr.getCause().toByteArray()));
                    cause = (Throwable) (osis.readObject());
                  } catch (IOException | ClassNotFoundException e) {
                    if (LOGGER.isErrorEnabled()) {
                      LOGGER.error("Error decoding failure cause", e);
                    }
                  }
                  mqp.workerFail(senderID, cause);
                  if (LOGGER.isErrorEnabled()) {
                    LOGGER.error("Worker #{} failed in executing query #{}.", senderID, queryId, cause);
                  }
                }
                break;
              default:
                if (LOGGER.isErrorEnabled()) {
                  LOGGER.error("Unexpected query message received at master: " + qm);
                }
                break;
            }
            break;
          default:
            if (LOGGER.isErrorEnabled()) {
              LOGGER.error("Unknown short message received at master: " + m.getType());
            }
            break;
        }
      }
    }
  }

  /** The usage message for this server. */
  static final String USAGE = "Usage: Server catalogFile [-explain] [-f queryFile]";

  /** The logger for this class. */
  private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(Server.class);

  /**
   * Initial worker list.
   */
  private final ConcurrentHashMap<Integer, SocketInfo> workers;

  /**
   * Queries currently in execution.
   */
  private final ConcurrentHashMap<Long, MasterQueryPartition> activeQueries;

  /**
   * Results of succeeded queries, currently the number of tuples received by the SinkRoot.
   */
  private final ConcurrentHashMap<Long, Long> succeededQueryResults;

  /**
   * Current alive worker set.
   */
  private final ConcurrentHashMap<Integer, Long> aliveWorkers;

  /**
   * Current stopping worker set. No intersection with the alive worker set is possible. When a worker sends a
   * STOP_WORKER_ACK message, it will be removed from this set.
   * 
   */
  private final ConcurrentHashMap<Integer, Long> stoppingWorkers;

  /**
   * Scheduled new workers, when a scheduled worker sends the first heartbeat, it'll be removed from this set.
   */
  private final ConcurrentHashMap<Integer, SocketInfo> scheduledWorkers;

  /**
   * The time when new workers were scheduled.
   */
  private final ConcurrentHashMap<Integer, Long> scheduledWorkersTime;

  /**
   * Execution environment variables for operators.
   */
  private final ConcurrentHashMap<String, Object> execEnvVars;

  /**
   * All message queue.
   * 
   * @TODO remove this queue as in {@link Worker}s.
   */
  private final LinkedBlockingQueue<IPCMessage.Data<TransportMessage>> messageQueue;

  /**
   * The IPC Connection Pool.
   */
  private final IPCConnectionPool connectionPool;

  /**
   * {@link ExecutorService} for message processing.
   */
  private volatile ExecutorService messageProcessingExecutor;

  /** The Catalog stores the metadata about the Myria instance. */
  private final MasterCatalog catalog;

  /**
   * Default input buffer capacity for {@link Consumer} input buffers.
   */
  private final int inputBufferCapacity;

  /**
   * @return the system wide default inuput buffer recover event trigger.
   * @see FlowControlBagInputBuffer#INPUT_BUFFER_RECOVER
   */
  private final int inputBufferRecoverTrigger;

  /**
   * The {@link OrderedMemoryAwareThreadPoolExecutor} who gets messages from {@link workerExecutor} and further process
   * them using application specific message handlers, e.g. {@link MasterShortMessageProcessor}.
   */
  private volatile OrderedMemoryAwareThreadPoolExecutor ipcPipelineExecutor;

  /**
   * The {@link ExecutorService} who executes the master-side query partitions.
   */
  private volatile ExecutorService serverQueryExecutor;

  /**
   * @return the query executor used in this worker.
   */
  ExecutorService getQueryExecutor() {
    return serverQueryExecutor;
  }

  /**
   * max number of seconds for elegant cleanup.
   */
  public static final int NUM_SECONDS_FOR_ELEGANT_CLEANUP = 10;

  /** for each worker id, record the set of workers which REMOVE_WORKER_ACK have been received. */
  private final Map<Integer, Set<Integer>> removeWorkerAckReceived;
  /** for each worker id, record the set of workers which ADD_WORKER_ACK have been received. */
  private final Map<Integer, Set<Integer>> addWorkerAckReceived;

  /**
   * Entry point for the Master.
   * 
   * @param args the command line arguments.
   * @throws IOException if there's any error in reading catalog file.
   */
  public static void main(final String[] args) throws IOException {
    try {

      Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.SEVERE);
      Logger.getLogger("com.almworks.sqlite4java.Internal").setLevel(Level.SEVERE);

      if (args.length < 1) {
        if (LOGGER.isErrorEnabled()) {
          LOGGER.error(USAGE);
        }
        System.exit(-1);
      }

      final String catalogName = args[0];

      final Server server = new Server(catalogName);

      if (LOGGER.isInfoEnabled()) {
        LOGGER.info("Workers are: ");
        for (final Entry<Integer, SocketInfo> w : server.workers.entrySet()) {
          LOGGER.info(w.getKey() + ":  " + w.getValue().getHost() + ":" + w.getValue().getPort());
        }
      }

      Runtime.getRuntime().addShutdownHook(new Thread("Master shutdown hook cleaner") {
        @Override
        public void run() {
          final Thread cleaner = new Thread("Shutdown hook cleaner") {
            @Override
            public void run() {
              server.cleanup();
            }
          };

          final Thread countDown = new Thread("Shutdown hook countdown") {
            @Override
            public void run() {
              if (LOGGER.isInfoEnabled()) {
                LOGGER
                    .info("Wait for " + Server.NUM_SECONDS_FOR_ELEGANT_CLEANUP + " seconds for graceful cleaning-up.");
              }
              int i;
              for (i = Server.NUM_SECONDS_FOR_ELEGANT_CLEANUP; i > 0; i--) {
                try {
                  if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(i + "");
                  }
                  Thread.sleep(MyriaConstants.WAITING_INTERVAL_1_SECOND_IN_MS);
                  if (!cleaner.isAlive()) {
                    break;
                  }
                } catch (InterruptedException e) {
                  break;
                }
              }
              if (i <= 0) {
                if (LOGGER.isInfoEnabled()) {
                  LOGGER.info("Graceful cleaning-up timeout. Going to shutdown abruptly.");
                }
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                  if (t != Thread.currentThread()) {
                    t.interrupt();
                  }
                }
              }
            }
          };
          cleaner.start();
          countDown.start();
          try {
            countDown.join();
          } catch (InterruptedException e) {
            // should not happen
            return;
          }
        }
      });
      server.start();
    } catch (Exception e) {
      if (LOGGER.isErrorEnabled()) {
        LOGGER.error("Unknown error occurs at Master. Quit directly.", e);
      }
    }
  }

  /**
   * @return my connection pool for IPC.
   */
  IPCConnectionPool getIPCConnectionPool() {
    return connectionPool;
  }

  /**
   * @return my pipeline executor.
   */
  OrderedMemoryAwareThreadPoolExecutor getPipelineExecutor() {
    return ipcPipelineExecutor;
  }

  /** The socket info for the master. */
  private final SocketInfo masterSocketInfo;

  /**
   * @return my execution environment variables for init of operators.
   */
  ConcurrentHashMap<String, Object> getExecEnvVars() {
    return execEnvVars;
  }

  /**
   * @return execution mode.
   */
  QueryExecutionMode getExecutionMode() {
    return QueryExecutionMode.NON_BLOCKING;
  }

  /**
   * Construct a server object, with configuration stored in the specified catalog file.
   * 
   * @param catalogFileName the name of the file containing the catalog.
   * @throws FileNotFoundException the specified file not found.
   * @throws CatalogException if there is an error reading from the Catalog.
   */
  public Server(final String catalogFileName) throws FileNotFoundException, CatalogException {
    catalog = MasterCatalog.open(catalogFileName);

    /* Get the master socket info */
    List<SocketInfo> masters = catalog.getMasters();
    if (masters.size() != 1) {
      throw new RuntimeException("Unexpected number of masters: expected 1, got " + masters.size());
    }
    masterSocketInfo = masters.get(MyriaConstants.MASTER_ID);

    workers = new ConcurrentHashMap<Integer, SocketInfo>(catalog.getWorkers());
    final ImmutableMap<String, String> allConfigurations = catalog.getAllConfigurations();

    inputBufferCapacity =
        Integer.valueOf(catalog.getConfigurationValue(MyriaSystemConfigKeys.OPERATOR_INPUT_BUFFER_CAPACITY));

    inputBufferRecoverTrigger =
        Integer.valueOf(catalog.getConfigurationValue(MyriaSystemConfigKeys.OPERATOR_INPUT_BUFFER_RECOVER_TRIGGER));

    execEnvVars = new ConcurrentHashMap<String, Object>();
    for (Entry<String, String> cE : allConfigurations.entrySet()) {
      execEnvVars.put(cE.getKey(), cE.getValue());
    }
    execEnvVars.put(MyriaConstants.EXEC_ENV_VAR_NODE_ID, MyriaConstants.MASTER_ID);
    execEnvVars.put(MyriaConstants.EXEC_ENV_VAR_EXECUTION_MODE, getExecutionMode());

    aliveWorkers = new ConcurrentHashMap<Integer, Long>();
    stoppingWorkers = new ConcurrentHashMap<Integer, Long>();
    scheduledWorkers = new ConcurrentHashMap<Integer, SocketInfo>();
    scheduledWorkersTime = new ConcurrentHashMap<Integer, Long>();

    removeWorkerAckReceived = new ConcurrentHashMap<Integer, Set<Integer>>();
    addWorkerAckReceived = new ConcurrentHashMap<Integer, Set<Integer>>();

    activeQueries = new ConcurrentHashMap<Long, MasterQueryPartition>();
    succeededQueryResults = new ConcurrentHashMap<Long, Long>();

    messageQueue = new LinkedBlockingQueue<IPCMessage.Data<TransportMessage>>();

    final Map<Integer, SocketInfo> computingUnits = new HashMap<Integer, SocketInfo>(workers);
    computingUnits.put(MyriaConstants.MASTER_ID, masterSocketInfo);

    connectionPool =
        new IPCConnectionPool(MyriaConstants.MASTER_ID, computingUnits, IPCConfigurations
            .createMasterIPCServerBootstrap(this), IPCConfigurations.createMasterIPCClientBootstrap(this),
            new TransportMessageSerializer(), new QueueBasedShortMessageProcessor<TransportMessage>(messageQueue));

    scheduledTaskExecutor =
        Executors.newSingleThreadScheduledExecutor(new RenamingThreadFactory("Master global timer"));

    final String databaseSystem = catalog.getConfigurationValue(MyriaSystemConfigKeys.WORKER_STORAGE_DATABASE_SYSTEM);
    execEnvVars.put(MyriaConstants.EXEC_ENV_VAR_DATABASE_SYSTEM, databaseSystem);
  }

  /**
   * timer task executor.
   */
  private final ScheduledExecutorService scheduledTaskExecutor;

  /**
   * This class presents only for the purpose of debugging. No other usage.
   */
  private class DebugHelper extends ErrorLoggingTimerTask {

    /**
     * Interval of execution.
     */
    public static final int INTERVAL = MyriaConstants.WAITING_INTERVAL_1_SECOND_IN_MS;

    @Override
    public final synchronized void runInner() {
      System.currentTimeMillis();
    }
  }

  /**
   * The thread to check received REMOVE_WORKER_ACK and send ADD_WORKER to each worker. It's a temporary solution to
   * guarantee message synchronization. Once we have a generalized design in the IPC layer in the future, it can be
   * removed.
   */
  private Thread sendAddWorker = null;

  /**
   * The thread to check received REMOVE_WORKER_ACK and send ADD_WORKER to each worker.
   */
  private class SendAddWorker implements Runnable {

    /** the worker id that was removed. */
    private final int workerID;
    /** the expected number of REMOVE_WORKER_ACK messages to receive. */
    private int numOfAck;
    /** the socket info of the new worker. */
    private final SocketInfo socketInfo;

    /**
     * constructor.
     * 
     * @param workerID the removed worker id.
     * @param socketInfo the new worker's socket info.
     * @param numOfAck the number of REMOVE_WORKER_ACK to receive.
     */
    SendAddWorker(final int workerID, final SocketInfo socketInfo, final int numOfAck) {
      this.workerID = workerID;
      this.socketInfo = socketInfo;
      this.numOfAck = numOfAck;
    }

    @Override
    public void run() {
      while (numOfAck > 0) {
        for (int aliveWorkerId : aliveWorkers.keySet()) {
          if (removeWorkerAckReceived.get(workerID).remove(aliveWorkerId)) {
            numOfAck--;
            connectionPool.sendShortMessage(aliveWorkerId, IPCUtils.addWorkerTM(workerID, socketInfo));
          }
        }
        try {
          Thread.sleep(MyriaConstants.SHORT_WAITING_INTERVAL_100_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  /**
   * @param workerID the worker to get updated
   */
  private void updateHeartbeat(final int workerID) {
    if (scheduledWorkers.containsKey(workerID)) {
      SocketInfo newWorker = scheduledWorkers.remove(workerID);
      scheduledWorkersTime.remove(workerID);
      if (newWorker != null) {
        sendAddWorker.start();
      }
    }
    aliveWorkers.put(workerID, System.currentTimeMillis());
  }

  /**
   * Check worker livenesses periodically. If a worker is detected as dead, its queries will be notified, it will be
   * removed from connection pools, and a new worker will be scheduled.
   */
  private class WorkerLivenessChecker extends ErrorLoggingTimerTask {

    @Override
    public final synchronized void runInner() {
      for (Integer workerId : aliveWorkers.keySet()) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - aliveWorkers.get(workerId) >= MyriaConstants.WORKER_IS_DEAD_INTERVAL) {
          /* scheduleAtFixedRate() is not accurate at all, use isRemoteAlive() to make sure the connection is lost. */
          if (connectionPool.isRemoteAlive(workerId)) {
            updateHeartbeat(workerId);
            continue;
          }

          if (LOGGER.isInfoEnabled()) {
            LOGGER.info("worker " + workerId + " doesn't have heartbeats, treat it as dead.");
          }
          aliveWorkers.remove(workerId);

          for (long queryId : activeQueries.keySet()) {
            MasterQueryPartition mqp = activeQueries.get(queryId);
            /* for each alive query that the failed worker is assigned to, tell the query that the worker failed. */
            if (mqp.getWorkerAssigned().contains(workerId)) {
              mqp.workerFail(workerId, new LostHeartbeatException());
            }
            if (mqp.getFTMode().equals(FTMODE.abandon)) {
              mqp.getMissingWorkers().add(workerId);
              mqp.updateProducerChannels(workerId, false);
              mqp.triggerTasks();
            } else if (mqp.getFTMode().equals(FTMODE.rejoin)) {
              mqp.getMissingWorkers().add(workerId);
              mqp.updateProducerChannels(workerId, false);
            }
          }

          removeWorkerAckReceived.put(workerId, Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>()));
          addWorkerAckReceived.put(workerId, Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>()));
          /* for using containsAll() later */
          addWorkerAckReceived.get(workerId).add(workerId);
          try {
            /* remove the failed worker from the connectionPool. */
            connectionPool.removeRemote(workerId).await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          /* tell other workers to remove it too. */
          for (int aliveWorkerId : aliveWorkers.keySet()) {
            connectionPool.sendShortMessage(aliveWorkerId, IPCUtils.removeWorkerTM(workerId));
          }

          /* Temporary solution: using exactly the same hostname:port. One good thing is the data is still there. */
          /* Temporary solution: using exactly the same worker id. */
          String newAddress = workers.get(workerId).getHost();
          int newPort = workers.get(workerId).getPort();
          int newWorkerId = workerId;

          /* a new worker will be launched, put its information in scheduledWorkers. */
          scheduledWorkers.put(newWorkerId, new SocketInfo(newAddress, newPort));
          scheduledWorkersTime.put(newWorkerId, currentTime);

          sendAddWorker =
              new Thread(new SendAddWorker(newWorkerId, new SocketInfo(newAddress, newPort), aliveWorkers.size()));
          connectionPool.putRemote(newWorkerId, new SocketInfo(newAddress, newPort));

          /* start a thread to launch the new worker. */
          new Thread(new NewWorkerScheduler(newWorkerId, newAddress, newPort)).start();
        }
      }
      for (Integer workerId : scheduledWorkers.keySet()) {
        long currentTime = System.currentTimeMillis();
        long time = scheduledWorkersTime.get(workerId);
        /* Had several trials and may need to change hostname:port of the scheduled new worker. */
        if (currentTime - time >= MyriaConstants.SCHEDULED_WORKER_UNABLE_TO_START) {
          SocketInfo si = scheduledWorkers.remove(workerId);
          scheduledWorkersTime.remove(workerId);
          connectionPool.removeRemote(workerId);
          if (LOGGER.isErrorEnabled()) {
            LOGGER.error("Worker #" + workerId + "(" + si + ") failed to start. Give up.");
          }
          continue;
          // Temporary solution: simply giving up launching this new worker
          // TODO: find a new set of hostname:port for this scheduled worker
        } else
        /* Haven't heard heartbeats from the scheduled worker, try to launch it again. */
        if (currentTime - time >= MyriaConstants.SCHEDULED_WORKER_FAILED_TO_START) {
          SocketInfo info = scheduledWorkers.get(workerId);
          new Thread(new NewWorkerScheduler(workerId, info.getHost(), info.getPort())).start();
        }
      }
    }
  }

  /** The reader. */
  private static final MyriaConfigurationReader READER = new MyriaConfigurationReader();

  /**
   * The class to launch a new worker during recovery.
   */
  private class NewWorkerScheduler implements Runnable {
    /** the new worker's worker id. */
    private final int workerId;
    /** the new worker's port number id. */
    private final int port;
    /** the new worker's hostname. */
    private final String address;

    /**
     * constructor.
     * 
     * @param workerId worker id.
     * @param address hostname.
     * @param port port number.
     */
    NewWorkerScheduler(final int workerId, final String address, final int port) {
      this.workerId = workerId;
      this.address = address;
      this.port = port;
    }

    @Override
    public void run() {
      try {
        final String temp = Files.createTempDirectory(null).toAbsolutePath().toString();
        Map<String, String> tmpMap = Collections.emptyMap();
        String configFileName = catalog.getConfigurationValue(MyriaSystemConfigKeys.DEPLOYMENT_FILE);
        Map<String, Map<String, String>> config = READER.load(configFileName);
        CatalogMaker.makeOneWorkerCatalog(workerId + "", temp, config, tmpMap, true);

        final String workingDir = config.get("paths").get(workerId + "");
        final String description = catalog.getConfigurationValue(MyriaSystemConfigKeys.DESCRIPTION);
        String remotePath = workingDir;
        if (description != null) {
          remotePath += "/" + description + "-files" + "/" + description;
        }
        DeploymentUtils.mkdir(address, remotePath);
        String localPath = temp + "/" + "worker_" + workerId;
        DeploymentUtils.rsyncFileToRemote(localPath, address, remotePath);
        final String maxHeapSize = config.get("deployment").get("max_heap_size");
        if (LOGGER.isInfoEnabled()) {
          LOGGER.info("starting new worker " + address + ":" + port + ".");
        }
        boolean debug = config.get("deployment").get("debug_mode").equals("true");
        DeploymentUtils.startWorker(address, workingDir, description, maxHeapSize, workerId + "", port, debug);
      } catch (CatalogException e) {
        throw new RuntimeException(e);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Master cleanup.
   */
  private void cleanup() {
    if (LOGGER.isInfoEnabled()) {
      LOGGER.info(MyriaConstants.SYSTEM_NAME + " is going to shutdown");
    }

    if (scheduledWorkers.size() > 0) {
      if (LOGGER.isInfoEnabled()) {
        LOGGER.info("Waiting for scheduled recovery workers, please wait");
      }
      while (scheduledWorkers.size() > 0) {
        try {
          Thread.sleep(MyriaConstants.WAITING_INTERVAL_1_SECOND_IN_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    for (MasterQueryPartition p : activeQueries.values()) {
      p.kill();
    }

    messageProcessingExecutor.shutdownNow();
    scheduledTaskExecutor.shutdownNow();

    /*
     * Close the catalog before shutting down the IPC because there may be Catalog jobs pending that were triggered by
     * IPC events.
     */
    catalog.close();

    while (aliveWorkers.size() > 0) {
      // TODO add process kill
      if (LOGGER.isInfoEnabled()) {
        LOGGER.info("Send shutdown requests to the workers, please wait");
      }
      for (final Integer workerId : aliveWorkers.keySet()) {
        SocketInfo workerAddr = workers.get(workerId);
        if (LOGGER.isInfoEnabled()) {
          LOGGER.info("Shutting down #{} : {}", workerId, workerAddr);
        }

        connectionPool.sendShortMessage(workerId, IPCUtils.CONTROL_SHUTDOWN);
      }

      for (final int workerId : aliveWorkers.keySet()) {
        if (!connectionPool.isRemoteAlive(workerId)) {
          aliveWorkers.remove(workerId);
        }
      }
      if (aliveWorkers.size() > 0) {
        try {
          Thread.sleep(MyriaConstants.WAITING_INTERVAL_1_SECOND_IN_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    connectionPool.shutdown();
    connectionPool.releaseExternalResources();
    if (ipcPipelineExecutor != null && !ipcPipelineExecutor.isShutdown()) {
      ipcPipelineExecutor.shutdown();
    }

    if (LOGGER.isInfoEnabled()) {
      LOGGER.info("Master connection pool shutdown complete.");
    }

    if (LOGGER.isInfoEnabled()) {
      LOGGER.info("Master finishes cleanup.");
    }
  }

  /**
   * @param mqp the master query
   * @return the query dispatch {@link QueryFuture}.
   * @throws DbException if any error occurs.
   */
  private QueryFuture dispatchWorkerQueryPlans(final MasterQueryPartition mqp) throws DbException {
    // directly set the master part as already received.
    mqp.queryReceivedByWorker(MyriaConstants.MASTER_ID);
    for (final Map.Entry<Integer, SingleQueryPlanWithArgs> e : mqp.getWorkerPlans().entrySet()) {
      final Integer workerID = e.getKey();
      while (!aliveWorkers.containsKey(workerID)) {
        try {
          Thread.sleep(MyriaConstants.SHORT_WAITING_INTERVAL_MS);
        } catch (InterruptedException e1) {
          Thread.currentThread().interrupt();
        }
      }
      try {
        connectionPool.sendShortMessage(workerID, IPCUtils.queryMessage(mqp.getQueryID(), e.getValue()));
      } catch (final IOException ee) {
        throw new DbException(ee);
      }
    }
    return mqp.getWorkerReceiveFuture();
  }

  /**
   * @return if a query is running.
   * @param queryId queryID.
   */
  public boolean queryCompleted(final long queryId) {
    return !activeQueries.containsKey(queryId);
  }

  /**
   * @return if no query is running.
   */
  public boolean allQueriesCompleted() {
    return activeQueries.isEmpty();
  }

  /**
   * Shutdown the master.
   */
  public void shutdown() {
    cleanup();
  }

  /**
   * Start all the threads that do work for the server.
   * 
   * @throws Exception if any error occurs.
   */
  public void start() throws Exception {
    if (LOGGER.isInfoEnabled()) {
      LOGGER.info("Server starting on {}", masterSocketInfo.toString());
    }

    scheduledTaskExecutor.scheduleAtFixedRate(new DebugHelper(), DebugHelper.INTERVAL, DebugHelper.INTERVAL,
        TimeUnit.MILLISECONDS);
    scheduledTaskExecutor.scheduleAtFixedRate(new WorkerLivenessChecker(),
        MyriaConstants.WORKER_LIVENESS_CHECKER_INTERVAL, MyriaConstants.WORKER_LIVENESS_CHECKER_INTERVAL,
        TimeUnit.MILLISECONDS);

    messageProcessingExecutor = Executors.newCachedThreadPool(new RenamingThreadFactory("Master message processor"));
    serverQueryExecutor = Executors.newCachedThreadPool(new RenamingThreadFactory("Master query executor"));

    /**
     * The {@link Executor} who deals with IPC connection setup/cleanup.
     */
    ExecutorService ipcBossExecutor = Executors.newCachedThreadPool(new RenamingThreadFactory("Master IPC boss"));
    /**
     * The {@link Executor} who deals with IPC message delivering and transformation.
     */
    ExecutorService ipcWorkerExecutor = Executors.newCachedThreadPool(new RenamingThreadFactory("Master IPC worker"));

    ipcPipelineExecutor =
        new OrderedMemoryAwareThreadPoolExecutor(Runtime.getRuntime().availableProcessors() * 2 + 1,
            5 * MyriaConstants.MB, 0, MyriaConstants.THREAD_POOL_KEEP_ALIVE_TIME_IN_MS, TimeUnit.MILLISECONDS,
            new RenamingThreadFactory("Master Pipeline executor"));

    /**
     * The {@link ChannelFactory} for creating client side connections.
     */
    ChannelFactory clientChannelFactory =
        new NioClientSocketChannelFactory(ipcBossExecutor, ipcWorkerExecutor, Runtime.getRuntime()
            .availableProcessors() * 2 + 1);

    /**
     * The {@link ChannelFactory} for creating server side accepted connections.
     */
    ChannelFactory serverChannelFactory =
        new NioServerSocketChannelFactory(ipcBossExecutor, ipcWorkerExecutor, Runtime.getRuntime()
            .availableProcessors() * 2 + 1);
    // Start server with Nb of active threads = 2*NB CPU + 1 as maximum.

    ChannelPipelineFactory serverPipelineFactory =
        new IPCPipelineFactories.MasterServerPipelineFactory(connectionPool, getPipelineExecutor());
    ChannelPipelineFactory clientPipelineFactory =
        new IPCPipelineFactories.MasterClientPipelineFactory(connectionPool, getPipelineExecutor());
    ChannelPipelineFactory masterInJVMPipelineFactory =
        new IPCPipelineFactories.MasterInJVMPipelineFactory(connectionPool);

    connectionPool.start(serverChannelFactory, serverPipelineFactory, clientChannelFactory, clientPipelineFactory,
        masterInJVMPipelineFactory, new InJVMLoopbackChannelSink());

    messageProcessingExecutor.submit(new MessageProcessor());
    if (LOGGER.isInfoEnabled()) {
      LOGGER.info("Server started on {}", masterSocketInfo.toString());
    }

    if (getSchema(MyriaConstants.PROFILING_RELATION) == null && !getDBMS().equals(MyriaConstants.STORAGE_SYSTEM_SQLITE)) {
      importDataset(MyriaConstants.PROFILING_RELATION, MyriaConstants.PROFILING_SCHEMA, null);
      importDataset(MyriaConstants.SENT_RELATION, MyriaConstants.SENT_SCHEMA, null);
    }
  }

  /**
   * @return the dbms from {@link #execEnvVars}.
   */
  private String getDBMS() {
    return (String) execEnvVars.get(MyriaConstants.EXEC_ENV_VAR_DATABASE_SYSTEM);
  }

  /**
   * @return the input capacity.
   */
  int getInputBufferCapacity() {
    return inputBufferCapacity;
  }

  /**
   * @return the system wide default inuput buffer recover event trigger.
   * @see FlowControlBagInputBuffer#INPUT_BUFFER_RECOVER
   */
  int getInputBufferRecoverTrigger() {
    return inputBufferRecoverTrigger;
  }

  /**
   * Pause a query with queryID.
   * 
   * @param queryID the queryID.
   * @return the future instance of the pause action.
   */
  public QueryFuture pauseQuery(final long queryID) {
    return activeQueries.get(queryID).pause();
  }

  /**
   * Pause a query with queryID.
   * 
   * @param queryID the queryID.
   */
  public void killQuery(final long queryID) {
    activeQueries.get(queryID).kill();
  }

  /**
   * Pause a query with queryID.
   * 
   * @param queryID the queryID.
   * @return the future instance of the resume action.
   */
  public QueryFuture resumeQuery(final long queryID) {
    return activeQueries.get(queryID).resume();
  }

  /**
   * 
   * Can be only used in test.
   * 
   * @return true if the query plan is accepted and scheduled for execution.
   * @param masterRoot the root operator of the master plan
   * @param workerRoots the roots of the worker part of the plan, {workerID -> RootOperator[]}
   * @throws DbException if any error occurs.
   * @throws CatalogException catalog errors.
   */
  public QueryFuture submitQueryPlan(final RootOperator masterRoot, final Map<Integer, RootOperator[]> workerRoots)
      throws DbException, CatalogException {
    String catalogInfoPlaceHolder = "MasterPlan: " + masterRoot + "; WorkerPlan: " + workerRoots;
    Map<Integer, SingleQueryPlanWithArgs> workerPlans = new HashMap<Integer, SingleQueryPlanWithArgs>();
    for (Entry<Integer, RootOperator[]> entry : workerRoots.entrySet()) {
      workerPlans.put(entry.getKey(), new SingleQueryPlanWithArgs(entry.getValue()));
    }
    return submitQuery(catalogInfoPlaceHolder, catalogInfoPlaceHolder, catalogInfoPlaceHolder,
        new SingleQueryPlanWithArgs(masterRoot), workerPlans, false);
  }

  /**
   * Submit a query for execution. The workerPlans may be removed in the future if the query compiler and schedulers are
   * ready. Returns null if there are too many active queries.
   * 
   * @param rawQuery the raw user-defined query. E.g., the source Datalog program.
   * @param logicalRa the logical relational algebra of the compiled plan.
   * @param physicalPlan the Myria physical plan for the query.
   * @param workerPlans the physical parallel plan fragments for each worker.
   * @param masterPlan the physical parallel plan fragment for the master.
   * @param profilingMode is the profiling mode of the query on.
   * @throws DbException if any error in non-catalog data processing
   * @throws CatalogException if any error in processing catalog
   * @return the query future from which the query status can be looked up.
   */
  public QueryFuture submitQuery(final String rawQuery, final String logicalRa, final String physicalPlan,
      final SingleQueryPlanWithArgs masterPlan, final Map<Integer, SingleQueryPlanWithArgs> workerPlans,
      @Nullable final Boolean profilingMode) throws DbException, CatalogException {
    if (!canSubmitQuery()) {
      return null;
    }
    if (profilingMode && getDBMS().equals(MyriaConstants.STORAGE_SYSTEM_SQLITE)) {
      throw new DbException("Profiling mode is not supported when using SQLite as the storage system.");
    }
    final long queryID = catalog.newQuery(rawQuery, logicalRa, physicalPlan, profilingMode);
    return submitQuery(queryID, masterPlan, workerPlans);
  }

  /**
   * Submit a query for execution. The workerPlans may be removed in the future if the query compiler and schedulers are
   * ready. Returns null if there are too many active queries.
   * 
   * @param rawQuery the raw user-defined query. E.g., the source Datalog program.
   * @param logicalRa the logical relational algebra of the compiled plan.
   * @param physicalPlan the Myria physical plan for the query.
   * @param workerPlans the physical parallel plan fragments for each worker.
   * @param masterPlan the physical parallel plan fragment for the master.
   * @throws DbException if any error in non-catalog data processing
   * @throws CatalogException if any error in processing catalog
   * @return the query future from which the query status can be looked up.
   */
  public QueryFuture submitQuery(final String rawQuery, final String logicalRa, final QueryEncoding physicalPlan,
      final SingleQueryPlanWithArgs masterPlan, final Map<Integer, SingleQueryPlanWithArgs> workerPlans)
      throws DbException, CatalogException {
    if (!canSubmitQuery()) {
      return null;
    }
    if (physicalPlan.profilingMode && getDBMS().equals(MyriaConstants.STORAGE_SYSTEM_SQLITE)) {
      throw new DbException("Profiling mode is not supported when using SQLite as the storage system.");
    }
    final long queryID = catalog.newQuery(rawQuery, logicalRa, physicalPlan);
    return submitQuery(queryID, masterPlan, workerPlans);
  }

  /**
   * Submit a query for execution. The workerPlans may be removed in the future if the query compiler and schedulers are
   * ready. Returns null if there are too many active queries.
   * 
   * @param queryID the catalog's assigned ID for this query.
   * @param workerPlans the physical parallel plan fragments for each worker.
   * @param masterPlan the physical parallel plan fragment for the master.
   * @throws DbException if any error in non-catalog data processing
   * @throws CatalogException if any error in processing catalog
   * @return the query future from which the query status can be looked up.
   */
  private QueryFuture submitQuery(final long queryID, final SingleQueryPlanWithArgs masterPlan,
      final Map<Integer, SingleQueryPlanWithArgs> workerPlans) throws DbException, CatalogException {
    /* First check whether there are too many active queries. */
    if (!canSubmitQuery()) {
      return null;
    }
    workerPlans.remove(MyriaConstants.MASTER_ID);
    try {
      final MasterQueryPartition mqp = new MasterQueryPartition(masterPlan, workerPlans, queryID, this);
      activeQueries.put(queryID, mqp);

      final QueryFuture queryExecutionFuture = mqp.getExecutionFuture();

      /*
       * Add the DatasetMetadataUpdater, which will update the catalog with the set of workers created when the query
       * succeeds.
       */
      queryExecutionFuture.addPreListener(new DatasetMetadataUpdater(catalog, workerPlans, queryID));

      queryExecutionFuture.addListener(new QueryFutureListener() {
        @Override
        public void operationComplete(final QueryFuture future) throws Exception {

          /* Before removing this query from the list of active queries, update it in the Catalog. */
          final QueryExecutionStatistics stats = mqp.getExecutionStatistics();
          final String startTime = stats.getStartTime();
          final String endTime = stats.getEndTime();
          final long elapsedNanos = stats.getQueryExecutionElapse();
          final QueryStatusEncoding.Status status;
          String message = null;
          if (mqp.isKilled()) {
            /* This is a catch-all for both ERROR and KILLED, right? */
            message = mqp.getMessage();
            if (message == null) {
              status = Status.KILLED;
            } else {
              status = Status.ERROR;
            }
          } else {
            status = Status.SUCCESS;
          }

          if (future.isSuccess()) {
            if (LOGGER.isInfoEnabled()) {
              LOGGER.info("Query #{} succeeded. Time elapsed: {}.", queryID, DateTimeUtils
                  .nanoElapseToHumanReadable(elapsedNanos));
            }
            if (mqp.getRootOperator() instanceof SinkRoot) {
              succeededQueryResults.put(queryID, ((SinkRoot) mqp.getRootOperator()).getCount());
            }
            // TODO success management.
          } else {
            if (LOGGER.isInfoEnabled()) {
              LOGGER.info("Query #{} failed. Time elapsed: {}. Failure cause is {}.", queryID, DateTimeUtils
                  .nanoElapseToHumanReadable(elapsedNanos), future.getCause());
            }
            // TODO failure management.
          }

          try {
            catalog.queryFinished(queryID, startTime, endTime, elapsedNanos, status, message);
          } finally {
            /*
             * This should be the last line of code -- the query should not be removed from activeQueries until all the
             * metadata is up to date.
             * 
             * Workaround #509: remove even if the update fails, otherwise we get zombie queries.
             */
            activeQueries.remove(queryID);
          }
        }
      });

      dispatchWorkerQueryPlans(mqp).addListener(new QueryFutureListener() {
        @Override
        public void operationComplete(final QueryFuture future) throws Exception {
          mqp.init();
          mqp.startExecution();
          Server.this.startWorkerQuery(future.getQuery().getQueryID());
        }
      });

      return mqp.getExecutionFuture();
    } catch (DbException | CatalogException | RuntimeException e) {
      catalog.queryFinished(queryID, "error during submission", null, null, Status.ERROR, e.toString());
      activeQueries.remove(queryID);
      throw e;
    }
  }

  /**
   * Tells all the workers to start the given query.
   * 
   * @param queryId the id of the query to be started.
   */
  private void startWorkerQuery(final long queryId) {
    final MasterQueryPartition mqp = activeQueries.get(queryId);
    for (final Integer workerID : mqp.getWorkerAssigned()) {
      connectionPool.sendShortMessage(workerID, IPCUtils.startQueryTM(queryId));
    }
  }

  /**
   * @return the set of workers that are currently alive.
   */
  public Set<Integer> getAliveWorkers() {
    return ImmutableSet.copyOf(aliveWorkers.keySet());
  }

  /**
   * @return the organization of shards and replication, given the alive workers
   * 
   * @param numShards the number of partitions
   * @param repFactor the replication factor
   * 
   */
  public List<Set<Integer>> getSampleAliveWorkers(final Integer numShards, final Integer repFactor) {
    Preconditions.checkNotNull(numShards);
    Preconditions.checkNotNull(repFactor);
    Preconditions.checkArgument(repFactor >= 1);

    List<Set<Integer>> result = new ArrayList<Set<Integer>>();
    List<Integer> alive = Collections.list(aliveWorkers.keys());
    Set<Integer> mainShardWorkers = MyriaUtils.randomSample(alive, numShards);
    for (Integer workerId : mainShardWorkers) {
      alive.remove(workerId);
      Set<Integer> shards = MyriaUtils.randomSample(alive, repFactor - 1);
      shards.add(workerId);
      alive.add(workerId);
      result.add(shards);
    }
    return result;
  }

  /**
   * 
   * @param workerId the worker identification
   * @return whether a worker is alive or not
   */
  public boolean isWorkerAlive(final Integer workerId) {
    return aliveWorkers.containsKey(workerId);
  }

  /**
   * Retrieves the worker info given the worker identification. Returns null if the worker does not exist on the worker
   * list.
   * 
   * @param workerId the worker identification
   * @return the worker information (host:port)
   */
  public SocketInfo getWorkerInfo(final Integer workerId) {
    return workers.get(workerId);
  }

  /**
   * Adds a worker turning it alive.
   * 
   * @param workerId the worker identification
   */
  public void startWorker(final Integer workerId) {
    SocketInfo workerInfo = getWorkerInfo(workerId);
    if (workerInfo == null) {
      throw new RuntimeException("Worker id: " + workerId + " not found");
    }
    sendAddWorker = new Thread(new SendAddWorker(workerId, workerInfo, aliveWorkers.size()));
    new Thread(new NewWorkerScheduler(workerId, workerInfo.getHost(), workerInfo.getPort())).start();
  }

  /**
   * Stops a worker.
   * 
   * @param workerId the worker identification
   */
  public void stopWorker(final Integer workerId) {
    SocketInfo workerInfo = getWorkerInfo(workerId);
    if (workerInfo == null) {
      throw new RuntimeException("Worker id: " + workerId + " not found");
    }
    if (connectionPool.isRemoteAlive(workerId)) {
      // TODO add process kill
      if (LOGGER.isInfoEnabled()) {
        LOGGER.info("Stopping worker #{} : {}", workerId, workerInfo);
      }
      connectionPool.sendShortMessage(workerId, IPCUtils.stopWorkerTM(workerId));
    }
    aliveWorkers.remove(workerId);
    stoppingWorkers.put(workerId, System.currentTimeMillis());
  }

  /**
   * @return the set of known workers in this Master.
   */
  public Map<Integer, SocketInfo> getWorkers() {
    return ImmutableMap.copyOf(workers);
  }

  /**
   * Ingest the given dataset.
   * 
   * @param relationKey the name of the dataset.
   * @param workersToIngest restrict the workers to ingest data (null for all)
   * @param indexes the indexes created.
   * @param source the source of tuples to be ingested.
   * @return the status of the ingested dataset.
   * @throws InterruptedException interrupted
   * @throws DbException if there is an error
   */
  public DatasetStatus ingestDataset(final RelationKey relationKey, final Set<Integer> workersToIngest,
      final List<List<IndexRef>> indexes, final Operator source) throws InterruptedException, DbException {
    /* Figure out the workers we will use. If workersToIngest is null, use all active workers. */
    Set<Integer> actualWorkers = workersToIngest;
    if (workersToIngest == null) {
      actualWorkers = getAliveWorkers();
    }
    Preconditions.checkArgument(actualWorkers.size() > 0, "Must use > 0 workers");
    int[] workersArray = MyriaUtils.integerCollectionToIntArray(actualWorkers);

    /* The master plan: send the tuples out. */
    ExchangePairID scatterId = ExchangePairID.newID();
    GenericShuffleProducer scatter =
        new GenericShuffleProducer(source, scatterId, workersArray,
            new RoundRobinPartitionFunction(workersArray.length));

    /* The workers' plan */
    GenericShuffleConsumer gather =
        new GenericShuffleConsumer(source.getSchema(), scatterId, new int[] { MyriaConstants.MASTER_ID });
    DbInsert insert = new DbInsert(gather, relationKey, true, indexes);
    Map<Integer, SingleQueryPlanWithArgs> workerPlans = new HashMap<Integer, SingleQueryPlanWithArgs>();
    for (Integer workerId : workersArray) {
      workerPlans.put(workerId, new SingleQueryPlanWithArgs(insert));
    }

    try {
      /* Start the workers */
      QueryFuture qf =
          submitQuery("ingest " + relationKey.toString("sqlite"), "ingest " + relationKey.toString("sqlite"),
              "ingest " + relationKey.toString("sqlite"), new SingleQueryPlanWithArgs(scatter), workerPlans, false)
              .sync();
      if (qf == null) {
        return null;
      }
      /* TODO(dhalperi) -- figure out how to populate the numTuples column. */
      DatasetStatus status =
          new DatasetStatus(relationKey, source.getSchema(), -1, qf.getQuery().getQueryID(), qf.getQuery()
              .getExecutionStatistics().getEndTime());

      return status;
    } catch (CatalogException e) {
      throw new DbException(e);
    }
  }

  /**
   * Ingest the given dataset with replication.
   * 
   * @param relationKey the name of the dataset.
   * @param partitions restrict the workers to ingest data (null for all)
   * @param numPartitions the number of partitions.
   * @param repFactor the replication factor.
   * @param indexes the indexes created.
   * @param source the source of tuples to be ingested.
   * @return the status of the ingested dataset.
   * @throws InterruptedException interrupted
   * @throws DbException if there is an error
   */
  public DatasetStatus ingestDataset(final RelationKey relationKey, final List<Set<Integer>> partitions,
      final Integer numPartitions, final Integer repFactor, final List<List<IndexRef>> indexes, final Operator source)
      throws InterruptedException, DbException {

    Preconditions.checkNotNull(numPartitions);
    Preconditions.checkNotNull(repFactor);

    /* Build the worker IDs and the partition indices arrays. */
    int[][] intArray2dPartitions = new int[numPartitions][repFactor];
    HashMap<Integer, Integer> mapWorkers = new HashMap<Integer, Integer>();
    int i = 0;
    int k = 0;
    int p = 0;
    for (Set<Integer> partition : partitions) {
      int j = 0;
      for (Integer id : partition) {
        intArray2dPartitions[i][j] = k;
        if (!mapWorkers.containsKey(k)) {
          mapWorkers.put(k, id);
          ++k;
        }
        ++j;
      }
      ++i;
    }

    /* Building intArrayWorkers. */
    int[] intArrayWorkers = new int[numPartitions * repFactor];
    int[] intArrayPartitions = new int[numPartitions * repFactor];
    i = 0;
    int j = 0;
    for (int[] partitionIndex : intArray2dPartitions) {
      for (int pi : partitionIndex) {
        intArrayWorkers[i] = mapWorkers.get(pi);
        intArrayPartitions[i] = j;
        ++i;
      }
      ++j;
    }

    /* The master plan: send the tuples out. */
    LOGGER.info("Shuffle producer shards: " + Arrays.deepToString(intArray2dPartitions) + " workers: "
        + Arrays.toString(intArrayWorkers));
    ExchangePairID scatterId = ExchangePairID.newID();
    GenericShuffleProducer scatter =
        new GenericShuffleProducer(source, scatterId, intArray2dPartitions, intArrayWorkers,
            new RoundRobinPartitionFunction(numPartitions));

    /* The workers' plan */
    // TODO valmeida change the Consumer to add the partition number into the relationKey value
    GenericShuffleConsumer gather =
        new GenericShuffleConsumer(source.getSchema(), scatterId, new int[] { MyriaConstants.MASTER_ID });
    Map<Integer, SingleQueryPlanWithArgs> workerPlans = new HashMap<Integer, SingleQueryPlanWithArgs>();

    for (i = 0; i < intArrayWorkers.length; i++) {
      int workerId = intArrayWorkers[i];
      RelationKey relKey = RelationKey.of(relationKey);
      relKey.setPartitionId(intArrayPartitions[i]);
      DbInsert insert = new DbInsert(gather, relKey, true, indexes);
      workerPlans.put(workerId, new SingleQueryPlanWithArgs(insert));
    }

    try {
      /* Start the workers */
      QueryFuture qf =
          submitQuery("ingest " + relationKey.toString("sqlite"), "ingest " + relationKey.toString("sqlite"),
              "ingest " + relationKey.toString("sqlite"), new SingleQueryPlanWithArgs(scatter), workerPlans, false)
              .sync();
      if (qf == null) {
        return null;
      }
      /* TODO(dhalperi) -- figure out how to populate the numTuples column. */
      DatasetStatus status =
          new DatasetStatus(relationKey, source.getSchema(), -1, qf.getQuery().getQueryID(), qf.getQuery()
              .getExecutionStatistics().getEndTime());
      return status;
    } catch (CatalogException e) {
      throw new DbException(e);
    }
  }

  /**
   * @return whether this master can handle more queries or not.
   */
  public boolean canSubmitQuery() {
    return (activeQueries.size() < MyriaConstants.MAX_ACTIVE_QUERIES);
  }

  /**
   * @param relationKey the relationalKey of the dataset to import
   * @param schema the schema of the dataset to import
   * @param workersToImportFrom the set of workers
   * @throws DbException if there is an error
   * @throws InterruptedException interrupted
   */
  public void importDataset(final RelationKey relationKey, final Schema schema, final Set<Integer> workersToImportFrom)
      throws DbException, InterruptedException {

    // TODO valmeida allow database importing to deal with replication as ingest does

    /* Figure out the workers we will use. If workersToImportFrom is null, use all active workers. */
    Set<Integer> actualWorkers = workersToImportFrom;
    if (workersToImportFrom == null) {
      actualWorkers = getWorkers().keySet();
    }

    try {
      Map<Integer, SingleQueryPlanWithArgs> workerPlans = new HashMap<Integer, SingleQueryPlanWithArgs>();
      for (Integer workerId : actualWorkers) {
        workerPlans.put(workerId, new SingleQueryPlanWithArgs(new SinkRoot(new EOSSource())));
      }
      QueryFuture qf =
          submitQuery("import " + relationKey.toString("sqlite"), "import " + relationKey.toString("sqlite"),
              "import " + relationKey.toString("sqlite"), new SingleQueryPlanWithArgs(new SinkRoot(new EOSSource())),
              workerPlans, false).sync();

      if (qf == null) {
        throw new DbException("Cannot import dataset right now, server is overloaded.");
      }

      /* Now that the query has finished, add the metadata about this relation to the dataset. */
      /* TODO(dhalperi) -- figure out how to populate the numTuples column. */
      catalog.addRelationMetadata(relationKey, schema, -1, qf.getQuery().getQueryID());
      /* Add the round robin-partitioned shard. */
      catalog.addStoredRelation(relationKey, actualWorkers, "RoundRobin");
    } catch (CatalogException e) {
      throw new DbException(e);
    }

  }

  /**
   * @param relationKey the key of the desired relation.
   * @return the schema of the specified relation, or null if not found.
   * @throws CatalogException if there is an error getting the Schema out of the catalog.
   */
  public Schema getSchema(final RelationKey relationKey) throws CatalogException {
    return catalog.getSchema(relationKey);
  }

  /**
   * @param relationKey the key of the desired relation.
   * @param storedRelationId indicates which copy of the desired relation we want to scan.
   * @return the list of workers that store the specified relation.
   * @throws CatalogException if there is an error accessing the catalog.
   */
  public List<Integer> getWorkersForRelation(final RelationKey relationKey, final Integer storedRelationId)
      throws CatalogException {
    return catalog.getWorkersForRelation(relationKey, storedRelationId);
  }

  /**
   * @param configKey config key.
   * @return master configuration.
   */
  public String getConfiguration(final String configKey) {
    try {
      return catalog.getConfigurationValue(configKey);
    } catch (CatalogException e) {
      if (LOGGER.isWarnEnabled()) {
        LOGGER.warn("Configuration retrieval error", e);
      }
      return null;
    }
  }

  /**
   * @return the socket info for the master.
   */
  protected SocketInfo getSocketInfo() {
    return masterSocketInfo;
  }

  /**
   * @return the result of the query.
   * @param id the query id.
   */
  public Long getQueryResult(final long id) {
    return succeededQueryResults.get(id);
  }

  /**
   * Computes and returns the status of the requested query, or null if the query does not exist.
   * 
   * @param queryId the identifier of the query.
   * @throws CatalogException if there is an error in the catalog.
   * @return the status of this query.
   */
  public QueryStatusEncoding getQueryStatus(final long queryId) throws CatalogException {
    /* Get the stored data for this query, e.g., the submitted program. */
    QueryStatusEncoding queryStatus = catalog.getQuery(queryId);
    MasterQueryPartition mqp = activeQueries.get(queryId);
    if (mqp == null) {
      /* The query isn't active any more, so queryStatus already contains the final status. */
      return queryStatus;
    }

    queryStatus.startTime = mqp.getExecutionStatistics().getStartTime();
    queryStatus.finishTime = mqp.getExecutionStatistics().getEndTime();
    if (queryStatus.finishTime != null) {
      queryStatus.elapsedNanos = mqp.getExecutionStatistics().getQueryExecutionElapse();
    }
    /* TODO(dhalperi) get status in a better way. */
    if (mqp.isPaused()) {
      queryStatus.status = QueryStatusEncoding.Status.PAUSED;
    } else if (mqp.isKilled()) {
      queryStatus.status = QueryStatusEncoding.Status.KILLED;
    } else if (queryStatus.startTime != null) {
      queryStatus.status = QueryStatusEncoding.Status.RUNNING;
    } else {
      queryStatus.status = QueryStatusEncoding.Status.ACCEPTED;
    }
    return queryStatus;
  }

  /**
   * Computes and returns the status of queries that have been submitted to Myria.
   * 
   * @param limit the maximum number of results to return. Any value <= 0 is interpreted as all results.
   * @param maxId the largest query ID returned. If null or <= 0, all queries will be returned.
   * @throws CatalogException if there is an error in the catalog.
   * @return a list of the status of every query that has been submitted to Myria.
   */
  public List<QueryStatusEncoding> getQueries(final long limit, final long maxId) throws CatalogException {
    List<QueryStatusEncoding> ret = new LinkedList<QueryStatusEncoding>();

    /* Begin by adding the status for all the active queries. */
    NavigableSet<Long> activeQueryIds = new TreeSet<Long>(activeQueries.keySet());
    final Iterator<Long> iter = activeQueryIds.descendingIterator();
    while (iter.hasNext()) {
      final Long queryId = iter.next();
      final QueryStatusEncoding status = getQueryStatus(queryId);
      if (status == null) {
        LOGGER.warn("Weird: query status for active query {} is null.", queryId);
        continue;
      }
      ret.add(status);
    }

    /* Now add in the status for all the inactive (finished, killed, etc.) queries. */
    for (QueryStatusEncoding q : catalog.getQueries(limit, maxId)) {
      if (!activeQueryIds.contains(q.queryId)) {
        ret.add(q);
      }
    }

    return ret;
  }

  /**
   * @return A list of datasets in the system.
   * @throws DbException if there is an error accessing the desired Schema.
   */
  public List<DatasetStatus> getDatasets() throws DbException {
    try {
      return catalog.getDatasets();
    } catch (CatalogException e) {
      throw new DbException(e);
    }
  }

  /**
   * Get the metadata about a relation.
   * 
   * @param relationKey specified which relation to get the metadata about.
   * @return the metadata of the specified relation.
   * @throws DbException if there is an error getting the status.
   */
  public DatasetStatus getDatasetStatus(final RelationKey relationKey) throws DbException {
    try {
      return catalog.getDatasetStatus(relationKey);
    } catch (CatalogException e) {
      throw new DbException(e);
    }
  }

  /**
   * @param searchTerm the search term
   * @return the relations that match the search term
   * @throws DbException if there is an error getting the relation keys.
   */
  public List<RelationKey> getMatchingRelationKeys(final String searchTerm) throws DbException {
    try {
      return catalog.getMatchingRelationKeys(searchTerm);
    } catch (CatalogException e) {
      throw new DbException(e);
    }
  }

  /**
   * @param userName the user whose datasets we want to access.
   * @return a list of datasets belonging to the specified user.
   * @throws DbException if there is an error accessing the Catalog.
   */
  public List<DatasetStatus> getDatasetsForUser(final String userName) throws DbException {
    try {
      return catalog.getDatasetsForUser(userName);
    } catch (CatalogException e) {
      throw new DbException(e);
    }
  }

  /**
   * @param userName the user whose datasets we want to access.
   * @param programName the program by that user whose datasets we want to access.
   * @return a list of datasets belonging to the specified program.
   * @throws DbException if there is an error accessing the Catalog.
   */
  public List<DatasetStatus> getDatasetsForProgram(final String userName, final String programName) throws DbException {
    try {
      return catalog.getDatasetsForProgram(userName, programName);
    } catch (CatalogException e) {
      throw new DbException(e);
    }
  }

  /**
   * @return number of queries.
   * @throws CatalogException if an error occurs
   */
  public int getNumQueries() throws CatalogException {
    return catalog.getNumQueries();
  }

  /**
   * Start a query that streams tuples from the specified relation to the specified {@link TupleWriter}.
   * 
   * @param relationKey the relation to be downloaded.
   * @param writer the {@link TupleWriter} which will serialize the tuples.
   * @return the query future from which the query status can be looked up.
   * @throws DbException if there is an error in the system.
   */
  public QueryFuture startDataStream(final RelationKey relationKey, final TupleWriter writer) throws DbException {
    /* Get the relation's schema, to make sure it exists. */
    final Schema schema;
    try {
      schema = catalog.getSchema(relationKey);
    } catch (CatalogException e) {
      throw new DbException(e);
    }
    Preconditions.checkArgument(schema != null, "relation %s was not found", relationKey);

    /* Get the workers that store it. */
    List<Integer> scanWorkers;
    try {
      scanWorkers = getWorkersForRelation(relationKey, null);
    } catch (CatalogException e) {
      throw new DbException(e);
    }

    /* Construct the operators that go elsewhere. */
    DbQueryScan scan = new DbQueryScan(relationKey, schema);
    final ExchangePairID operatorId = ExchangePairID.newID();
    CollectProducer producer = new CollectProducer(scan, operatorId, MyriaConstants.MASTER_ID);

    /* Construct the workers' {@link SingleQueryPlanWithArgs}. */
    SingleQueryPlanWithArgs workerPlan = new SingleQueryPlanWithArgs(producer);
    Map<Integer, SingleQueryPlanWithArgs> workerPlans =
        new HashMap<Integer, SingleQueryPlanWithArgs>(scanWorkers.size());
    for (Integer worker : scanWorkers) {
      workerPlans.put(worker, workerPlan);
    }

    /* Construct the master plan. */
    final CollectConsumer consumer = new CollectConsumer(schema, operatorId, ImmutableSet.copyOf(scanWorkers));
    DataOutput output = new DataOutput(consumer, writer);
    final SingleQueryPlanWithArgs masterPlan = new SingleQueryPlanWithArgs(output);

    /* Submit the plan for the download. */
    String planString = "download " + relationKey.toString(MyriaConstants.STORAGE_SYSTEM_SQLITE);
    try {
      return submitQuery(planString, planString, planString, masterPlan, workerPlans, false);
    } catch (CatalogException e) {
      throw new DbException(e);
    }
  }

  /**
   * @param queryId query id.
   * @param fragmentId the fragment id to return data for. All fragments, if < 0.
   * @param writer writer to get data.
   * @param relationKey the relation to stream from
   * @param schema the schema of the relation to stream from
   * @return profiling logs for the query.
   * @throws DbException if there is an error when accessing profiling logs.
   */
  public QueryFuture startLogDataStream(final long queryId, final long fragmentId, final TupleWriter writer,
      final RelationKey relationKey, final Schema schema) throws DbException {
    /* Get the relation's schema, to make sure it exists. */
    final QueryStatusEncoding queryStatus;
    try {
      queryStatus = catalog.getQuery(queryId);
    } catch (CatalogException e) {
      throw new DbException(e);
    }
    Preconditions.checkArgument(queryStatus != null, "query %s not found", queryId);
    Preconditions.checkArgument(queryStatus.status == QueryStatusEncoding.Status.SUCCESS,
        "query %s did not succeed (%s)", queryId, queryStatus.status);
    Preconditions.checkArgument(queryStatus.profilingMode, "query %s was not run with profiling enabled");

    /* Get the workers. */
    Set<Integer> actualWorkers = ((QueryEncoding) queryStatus.physicalPlan).getWorkers();;

    String fragmentWhere = "";
    if (fragmentId >= 0) {
      fragmentWhere = " AND fragmentId=" + fragmentId;
    }

    /* Construct the operators that go elsewhere. */
    // TODO: replace this with some kind of query construction
    DbQueryScan scan =
        new DbQueryScan("SELECT * FROM " + relationKey.toString(getDBMS()) + " WHERE queryId=" + queryId
            + fragmentWhere + " ORDER BY fragmentid, nanotime", schema);
    final ExchangePairID operatorId = ExchangePairID.newID();

    ImmutableList.Builder<Expression> emitExpressions = ImmutableList.builder();

    emitExpressions.add(new Expression("workerId", new WorkerIdExpression()));

    for (int column = 0; column < schema.numColumns(); column++) {
      // we don't need the query id and sometimes the fragment id since they are in the query
      if (schema.getColumnName(column).toLowerCase().equals("queryid") || fragmentId >= 0
          && schema.getColumnName(column).toLowerCase().equals("fragmentid")) {
        continue;
      }
      VariableExpression copy = new VariableExpression(column);
      emitExpressions.add(new Expression(schema.getColumnName(column), copy));
    }

    Apply addWorkerId = new Apply(scan, emitExpressions.build());

    CollectProducer producer = new CollectProducer(addWorkerId, operatorId, MyriaConstants.MASTER_ID);

    /* Construct the workers' {@link SingleQueryPlanWithArgs}. */
    SingleQueryPlanWithArgs workerPlan = new SingleQueryPlanWithArgs(producer);
    Map<Integer, SingleQueryPlanWithArgs> workerPlans = new HashMap<>(actualWorkers.size());
    for (Integer worker : actualWorkers) {
      workerPlans.put(worker, workerPlan);
    }

    /* Construct the master plan. */
    final CollectConsumer consumer =
        new CollectConsumer(addWorkerId.getSchema(), operatorId, ImmutableSet.copyOf(actualWorkers));
    DataOutput output = new DataOutput(consumer, writer);
    final SingleQueryPlanWithArgs masterPlan = new SingleQueryPlanWithArgs(output);

    /* Submit the plan for the download. */
    String planString =
        Joiner.on("").join("download profiling log data for (query=", queryId, ", fragment=", fragmentId,
            ", log type=", relationKey.getRelationName(), ')');
    try {
      return submitQuery(planString, planString, planString, masterPlan, workerPlans, false);
    } catch (CatalogException e) {
      throw new DbException(e);
    }
  }

  /**
   * @param queryId query id.
   * @param fragmentId the fragment id to return data for. All fragments, if < 0.
   * @param writer writer to get data.
   * @return profiling logs for the query.
   * 
   * @throws DbException if there is an error when accessing profiling logs.
   */
  public QueryFuture startHistogramDataStream(final long queryId, final long fragmentId, final TupleWriter writer)
      throws DbException {
    /* Get the relation's schema, to make sure it exists. */
    final QueryStatusEncoding queryStatus;
    try {
      queryStatus = catalog.getQuery(queryId);
    } catch (CatalogException e) {
      throw new DbException(e);
    }
    Preconditions.checkArgument(queryStatus != null, "query %s not found", queryId);
    Preconditions.checkArgument(queryStatus.status == QueryStatusEncoding.Status.SUCCESS,
        "query %s did not succeed (%s)", queryId, queryStatus.status);
    Preconditions.checkArgument(queryStatus.profilingMode, "query %s was not run with profiling enabled");

    final Schema schema =
        new Schema(ImmutableList.of(Type.LONG_TYPE, Type.STRING_TYPE), ImmutableList.of("nanoTime", "eventType"));
    final RelationKey relationKey = MyriaConstants.PROFILING_RELATION;

    /* Get the workers. */
    Set<Integer> actualWorkers = ((QueryEncoding) queryStatus.physicalPlan).getWorkers();

    /* Construct the operators that go elsewhere. */
    // TODO: replace this with some kind of query construction
    String opnameQueryString =
        Joiner.on(' ').join("select opname from", relationKey.toString(getDBMS()), "where", fragmentId,
            "=fragmentId and ", queryId, "=queryId order by nanotime asc limit 1");
    String queryString =
        Joiner.on(' ').join("select nanotime, eventtype from", relationKey.toString(getDBMS()), "where opname = (",
            opnameQueryString, ") and queryid=", queryId, "and fragmentid=", fragmentId,
            "and eventtype in ('call', 'return') order by nanotime asc");
    DbQueryScan scan = new DbQueryScan(queryString, schema);
    final ExchangePairID operatorId = ExchangePairID.newID();

    ImmutableList.Builder<Expression> emitExpressions = ImmutableList.builder();

    emitExpressions.add(new Expression("workerId", new WorkerIdExpression()));

    for (int column = 0; column < schema.numColumns(); column++) {
      VariableExpression copy = new VariableExpression(column);
      emitExpressions.add(new Expression(schema.getColumnName(column), copy));
    }

    CollectProducer producer = new CollectProducer(scan, operatorId, MyriaConstants.MASTER_ID);

    /* Construct the workers' {@link SingleQueryPlanWithArgs}. */
    SingleQueryPlanWithArgs workerPlan = new SingleQueryPlanWithArgs(producer);
    Map<Integer, SingleQueryPlanWithArgs> workerPlans = new HashMap<>(actualWorkers.size());
    for (Integer worker : actualWorkers) {
      workerPlans.put(worker, workerPlan);
    }

    /* Construct the master plan. */
    final CollectConsumer consumer =
        new CollectConsumer(scan.getSchema(), operatorId, ImmutableSet.copyOf(actualWorkers));
    final InMemoryOrderBy order = new InMemoryOrderBy(consumer, new int[] { 0 }, new boolean[] { true });

    /* Use stateful apply to build histogram */

    // increment on call, decrement on return, else do nothing
    Expression conditionalIncrementDecrement =
        new Expression(new ConditionalExpression(new EqualsExpression(new VariableExpression(1),
            new ConstantExpression(Type.STRING_TYPE, "call")), new PlusExpression(new StateExpression(0),
            new ConstantExpression(1)), new MinusExpression(new StateExpression(0), new ConstantExpression(1))));

    ImmutableList.Builder<Expression> ib = ImmutableList.builder();
    ib.add(new Expression("numWorkers", new ConstantExpression(0)));

    ImmutableList.Builder<Expression> eb = ImmutableList.builder();
    eb.add(new Expression("time", new VariableExpression(0)));
    eb.add(new Expression("numWorkers", new StateExpression(0)));

    ImmutableList.Builder<Expression> ub = ImmutableList.builder();
    ub.add(conditionalIncrementDecrement);

    final StatefulApply hist = new StatefulApply(order, eb.build(), ib.build(), ub.build());
    DataOutput output = new DataOutput(hist, writer);
    final SingleQueryPlanWithArgs masterPlan = new SingleQueryPlanWithArgs(output);

    /* Submit the plan for the download. */
    String planString =
        Joiner.on("").join("download profiling histogram (query=", queryId, ", fragment=", fragmentId, ")");
    try {
      return submitQuery(planString, planString, planString, masterPlan, workerPlans, false);
    } catch (CatalogException e) {
      throw new DbException(e);
    }
  }
}
