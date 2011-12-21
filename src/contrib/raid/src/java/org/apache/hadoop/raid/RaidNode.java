/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.raid;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.http.HttpServer;
import org.apache.hadoop.ipc.ProtocolSignature;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.raid.DistBlockIntegrityMonitor.Worker;
import org.apache.hadoop.raid.protocol.PolicyInfo;
import org.apache.hadoop.raid.protocol.RaidProtocol;
import org.apache.hadoop.tools.HadoopArchives;
import org.apache.hadoop.util.Daemon;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.ToolRunner;
import org.xml.sax.SAXException;

/**
 * A base class that implements {@link RaidProtocol}.
 *
 * use raid.classname to specify which implementation to use
 */
public abstract class RaidNode implements RaidProtocol {

  static{
    Configuration.addDefaultResource("hdfs-default.xml");
    Configuration.addDefaultResource("hdfs-site.xml");
    Configuration.addDefaultResource("mapred-default.xml");
    Configuration.addDefaultResource("mapred-site.xml");
  }
  public static final Log LOG = LogFactory.getLog(RaidNode.class);
  public static final long SLEEP_TIME = 10000L; // 10 seconds
  public static final int DEFAULT_PORT = 60000;

  public static final String RAID_RECOVERY_LOCATION_KEY =
    "hdfs.raid.local.recovery.location";
  public static final String DEFAULT_RECOVERY_LOCATION = "/tmp/raidrecovery";

  public static final String RAID_PARITY_HAR_THRESHOLD_DAYS_KEY =
    "raid.parity.har.threshold.days";
  public static final int DEFAULT_RAID_PARITY_HAR_THRESHOLD_DAYS = 3;

  public static final String RAID_DIRECTORYTRAVERSAL_SHUFFLE = "raid.directorytraversal.shuffle";
  public static final String RAID_DIRECTORYTRAVERSAL_THREADS = "raid.directorytraversal.threads";
  
  public static final String RAID_DISABLE_CORRUPT_BLOCK_FIXER_KEY = 
    "raid.blockreconstruction.corrupt.disable";
  public static final String RAID_DISABLE_DECOMMISSIONING_BLOCK_COPIER_KEY = 
    "raid.blockreconstruction.decommissioning.disable";

  public static final String JOBUSER = "raid";

  public static final String HAR_SUFFIX = "_raid.har";
  public static final Pattern PARITY_HAR_PARTFILE_PATTERN =

    Pattern.compile(".*" + HAR_SUFFIX + "/part-.*");

  public static final String RAIDNODE_CLASSNAME_KEY = "raid.classname";  

  /** RPC server */
  private Server server;
  /** RPC server address */
  private InetSocketAddress serverAddress = null;
  /** only used for testing purposes  */
  protected boolean stopRequested = false;

  /** Configuration Manager */
  private ConfigManager configMgr;

  private HttpServer infoServer;
  private String infoBindAddress;
  private long startTime;

  /** hadoop configuration */
  protected Configuration conf;

  protected boolean initialized = false;  // Are we initialized?
  protected volatile boolean running = true; // Are we running?

  /** Deamon thread to trigger policies */
  Daemon triggerThread = null;

  /** Deamon thread to delete obsolete parity files */
  PurgeMonitor purgeMonitor = null;
  Daemon purgeThread = null;
  
  /** Deamon thread to har raid directories */
  Daemon harThread = null;

  /** Daemon thread to fix corrupt files */
  BlockIntegrityMonitor blockIntegrityMonitor = null;
  Daemon blockFixerThread = null;
  Daemon blockCopierThread = null;

  /** Daemon thread to collecting statistics */
  StatisticsCollector statsCollector = null;
  Daemon statsCollectorThread = null;

  PlacementMonitor placementMonitor = null;
  Daemon placementMonitorThread = null;

  private int directoryTraversalThreads;
  private boolean directoryTraversalShuffle;
  
  // statistics about RAW hdfs blocks. This counts all replicas of a block.
  public static class Statistics {
    long numProcessedBlocks; // total blocks encountered in namespace
    long processedSize;   // disk space occupied by all blocks
    long remainingSize;      // total disk space post RAID
    
    long numMetaBlocks;      // total blocks in metafile
    long metaSize;           // total disk space for meta files

    public void clear() {
      numProcessedBlocks = 0;
      processedSize = 0;
      remainingSize = 0;
      numMetaBlocks = 0;
      metaSize = 0;
    }
    public String toString() {
      long save = processedSize - (remainingSize + metaSize);
      long savep = 0;
      if (processedSize > 0) {
        savep = (save * 100)/processedSize;
      }
      String msg = " numProcessedBlocks = " + numProcessedBlocks +
                   " processedSize = " + processedSize +
                   " postRaidSize = " + remainingSize +
                   " numMetaBlocks = " + numMetaBlocks +
                   " metaSize = " + metaSize +
                   " %save in raw disk space = " + savep;
      return msg;
    }
  }

  // Startup options
  static public enum StartupOption{
    TEST ("-test"),
    REGULAR ("-regular");

    private String name = null;
    private StartupOption(String arg) {this.name = arg;}
    public String getName() {return name;}
  }
  
  // For unit test
  RaidNode() {}

  /**
   * Start RaidNode.
   * <p>
   * The raid-node can be started with one of the following startup options:
   * <ul> 
   * <li>{@link StartupOption#REGULAR REGULAR} - normal raid node startup</li>
   * </ul>
   * The option is passed via configuration field: 
   * <tt>fs.raidnode.startup</tt>
   * 
   * The conf will be modified to reflect the actual ports on which 
   * the RaidNode is up and running if the user passes the port as
   * <code>zero</code> in the conf.
   * 
   * @param conf  confirguration
   * @throws IOException
   */
  RaidNode(Configuration conf) throws IOException {
    try {
      initialize(conf);
    } catch (IOException e) {
      LOG.error(StringUtils.stringifyException(e));
      this.stop();
      throw e;
    } catch (Exception e) {
      this.stop();
      throw new IOException(e);
    }
  }

  public long getProtocolVersion(String protocol,
                                 long clientVersion) throws IOException {
    if (protocol.equals(RaidProtocol.class.getName())) {
      return RaidProtocol.versionID;
    } else {
      throw new IOException("Unknown protocol to name node: " + protocol);
    }
  }

  @Override
  public ProtocolSignature getProtocolSignature(String protocol,
      long clientVersion, int clientMethodsHash) throws IOException {
    return ProtocolSignature.getProtocolSignature(
        this, protocol, clientVersion, clientMethodsHash);
  }
  
  /**
   * Wait for service to finish.
   * (Normally, it runs forever.)
   */
  public void join() {
    try {
      if (server != null) server.join();
      if (triggerThread != null) triggerThread.join();
      if (blockFixerThread != null) blockFixerThread.join();
      if (blockCopierThread != null) blockCopierThread.join();
      if (purgeThread != null) purgeThread.join();
      if (statsCollectorThread != null) statsCollectorThread.join();
    } catch (InterruptedException ie) {
      // do nothing
    }
  }
  
  /**
   * Stop all RaidNode threads and wait for all to finish.
   */
  public void stop() {
    if (stopRequested) {
      return;
    }
    stopRequested = true;
    running = false;
    if (server != null) server.stop();
    if (triggerThread != null) triggerThread.interrupt();
    if (blockIntegrityMonitor != null) blockIntegrityMonitor.running = false;
    if (blockFixerThread != null) blockFixerThread.interrupt();
    if (blockCopierThread != null) blockCopierThread.interrupt();
    if (purgeMonitor != null) purgeMonitor.running = false;
    if (purgeThread != null) purgeThread.interrupt();
    if (placementMonitor != null) placementMonitor.stop();
    if (statsCollector != null) statsCollector.stop();
    if (statsCollectorThread != null) statsCollectorThread.interrupt();
    if (infoServer != null) {
        try {
          infoServer.stop();
        } catch (Exception e) {
          LOG.warn("Exception shutting down " + RaidNode.class, e);
        }
    }
  }

  private static InetSocketAddress getAddress(String address) {
    return NetUtils.createSocketAddr(address);
  }

  public static InetSocketAddress getAddress(Configuration conf) {
    String nodeport = conf.get("raid.server.address");
    if (nodeport == null) {
      nodeport = "localhost:" + DEFAULT_PORT;
    }
    return getAddress(nodeport);
  }

  public InetSocketAddress getListenerAddress() {
    return server.getListenerAddress();
  }
  
  private void initialize(Configuration conf) 
    throws IOException, SAXException, InterruptedException, RaidConfigurationException,
           ClassNotFoundException, ParserConfigurationException {
    this.startTime = RaidNode.now();
    this.conf = conf;
    InetSocketAddress socAddr = RaidNode.getAddress(conf);
    int handlerCount = conf.getInt("fs.raidnode.handler.count", 10);

    // read in the configuration
    configMgr = new ConfigManager(conf);

    // create rpc server 
    this.server = RPC.getServer(this, socAddr.getHostName(), socAddr.getPort(),
                                handlerCount, false, conf);

    // The rpc-server port can be ephemeral... ensure we have the correct info
    this.serverAddress = this.server.getListenerAddress();
    LOG.info("RaidNode up at: " + this.serverAddress);

    this.server.start(); // start RPC server

    // Create a block integrity monitor and start its thread(s)
    this.blockIntegrityMonitor = BlockIntegrityMonitor.createBlockIntegrityMonitor(conf);
    
    boolean useBlockFixer = 
      !conf.getBoolean(RAID_DISABLE_CORRUPT_BLOCK_FIXER_KEY, false);
    boolean useBlockCopier = 
      !conf.getBoolean(RAID_DISABLE_DECOMMISSIONING_BLOCK_COPIER_KEY, false);
    
    Runnable fixer = blockIntegrityMonitor.getCorruptionMonitor();
    if (useBlockFixer && (fixer != null)) {
      this.blockFixerThread = new Daemon(fixer);
      this.blockFixerThread.setName("Block Fixer");
      this.blockFixerThread.start();
    }
    
    Runnable copier = blockIntegrityMonitor.getDecommissioningMonitor();
    if (useBlockCopier && (copier != null)) {
      this.blockCopierThread = new Daemon(copier);
      this.blockCopierThread.setName("Block Copier");
      this.blockCopierThread.start();
    }

    // start the deamon thread to fire polcies appropriately
    this.triggerThread = new Daemon(new TriggerMonitor());
    this.triggerThread.setName("Trigger Thread");
    this.triggerThread.start();

    // start the thread that monitor and moves blocks
    this.placementMonitor = new PlacementMonitor(conf);
    this.placementMonitor.start();

    // start the thread that deletes obsolete parity files
    this.purgeMonitor = new PurgeMonitor(conf, placementMonitor);
    this.purgeThread = new Daemon(purgeMonitor);
    this.purgeThread.setName("Purge Thread");
    this.purgeThread.start();

    // start the thread that creates HAR files
    this.harThread = new Daemon(new HarMonitor());
    this.harThread.setName("HAR Thread");
    this.harThread.start();

    // start the thread that collects statistics
    this.statsCollector = new StatisticsCollector(this, configMgr, conf);
    this.statsCollectorThread = new Daemon(statsCollector);
    this.statsCollectorThread.setName("Stats Collector");
    this.statsCollectorThread.start();

    this.directoryTraversalShuffle =
        conf.getBoolean(RAID_DIRECTORYTRAVERSAL_SHUFFLE, true);
    this.directoryTraversalThreads =
        conf.getInt(RAID_DIRECTORYTRAVERSAL_THREADS, 4);
    // Instantiate the metrics singleton.
    RaidNodeMetrics.getInstance(RaidNodeMetrics.DEFAULT_NAMESPACE_ID);

    startHttpServer();

    initialized = true;
  }

  private void startHttpServer() throws IOException {
    String infoAddr = conf.get("mapred.raid.http.address", "localhost:50091");
    InetSocketAddress infoSocAddr = NetUtils.createSocketAddr(infoAddr);
    this.infoBindAddress = infoSocAddr.getHostName();
    int tmpInfoPort = infoSocAddr.getPort();
    this.infoServer = new HttpServer("raid", this.infoBindAddress, tmpInfoPort,
        tmpInfoPort == 0, conf);
    this.infoServer.setAttribute("raidnode", this);
    this.infoServer.start();
    LOG.info("Web server started at port " + this.infoServer.getPort());
  }

  public StatisticsCollector getStatsCollector() {
    return this.statsCollector;
  }

  public PlacementMonitor getPlacementMonitor() {
    return this.placementMonitor;
  }

  public PurgeMonitor getPurgeMonitor() {
    return this.purgeMonitor;
  }

  public BlockIntegrityMonitor.Status getBlockIntegrityMonitorStatus() {
    return blockIntegrityMonitor.getAggregateStatus();
  }
  
  public BlockIntegrityMonitor.Status getBlockFixerStatus() {
    return ((Worker)blockIntegrityMonitor.getCorruptionMonitor()).getStatus();
  }

  public BlockIntegrityMonitor.Status getBlockCopierStatus() {
    return ((Worker)blockIntegrityMonitor.getDecommissioningMonitor()).getStatus();
  }

  public String getHostName() {
    return this.infoBindAddress;
  }

  public long getStartTime() {
    return this.startTime;
  }

  public Thread.State getStatsCollectorState() {
    return this.statsCollectorThread.getState();
  }

  public Configuration getConf() {
    return this.conf;
  }

  /**
   * Implement RaidProtocol methods
   */

  /** {@inheritDoc} */
  public PolicyInfo[] getAllPolicies() throws IOException {
    Collection<PolicyInfo> list = configMgr.getAllPolicies();
    return list.toArray(new PolicyInfo[list.size()]);
  }

  /** {@inheritDoc} */
  public String recoverFile(String inStr, long corruptOffset) throws IOException {
    throw new IOException("Not supported");
  }

  /**
   * returns the number of raid jobs running for a particular policy
   */
  abstract int getRunningJobsForPolicy(String policyName);

  /**
   * Periodically checks to see which policies should be fired.
   */
  class TriggerMonitor implements Runnable {

    class PolicyState {
      long startTime = 0;
      // A policy may specify either a path for directory traversal
      // or a file with the list of files to raid.
      DirectoryTraversal pendingTraversal = null;
      BufferedReader fileListReader = null;

      PolicyState() {}

      boolean isFileListReadInProgress() {
        return fileListReader != null;
      }
      void resetFileListRead() throws IOException {
        if (fileListReader != null) {
          fileListReader.close();
          fileListReader = null;
        }
      }

      boolean isScanInProgress() {
        return pendingTraversal != null;
      }
      void resetTraversal() {
        pendingTraversal = null;
      }
      void setTraversal(DirectoryTraversal pendingTraversal) {
        this.pendingTraversal = pendingTraversal;
      }
    }

    private Map<String, PolicyState> policyStateMap =
      new HashMap<String, PolicyState>();

    public void run() {
      while (running) {
        try {
          doProcess();
        } catch (Exception e) {
          LOG.error(StringUtils.stringifyException(e));
        } finally {
          LOG.info("Trigger thread continuing to run...");
        }
      }
    }

    private boolean shouldReadFileList(PolicyInfo info) {
      if (info.getFileListPath() == null || !info.getShouldRaid()) {
        return false;
      }
      String policyName = info.getName();
      PolicyState scanState = policyStateMap.get(policyName);
      if (scanState.isFileListReadInProgress()) {
        int maxJobsPerPolicy = configMgr.getMaxJobsPerPolicy();
        int runningJobsCount = getRunningJobsForPolicy(policyName);

        // If there is a scan in progress for this policy, we can have
        // upto maxJobsPerPolicy running jobs.
        return (runningJobsCount < maxJobsPerPolicy);
      } else {
        long lastReadStart = scanState.startTime;
        return (now() > lastReadStart + configMgr.getPeriodicity());
      }
    }

    /**
     * Should we select more files for a policy.
     */
    private boolean shouldSelectFiles(PolicyInfo info) {
      if (!info.getShouldRaid()) {
        return false;
      }
      String policyName = info.getName();
      int runningJobsCount = getRunningJobsForPolicy(policyName);
      PolicyState scanState = policyStateMap.get(policyName);
      if (scanState.isScanInProgress()) {
        int maxJobsPerPolicy = configMgr.getMaxJobsPerPolicy();

        // If there is a scan in progress for this policy, we can have
        // upto maxJobsPerPolicy running jobs.
        return (runningJobsCount < maxJobsPerPolicy);
      } else {

        // Check the time of the last full traversal before starting a fresh
        // traversal.
        long lastScan = scanState.startTime;
        return (now() > lastScan + configMgr.getPeriodicity());
      }
    }

   /**
    * Returns a list of pathnames that needs raiding.
    * The list of paths could be obtained by resuming a previously suspended
    * traversal.
    * The number of paths returned is limited by raid.distraid.max.jobs.
    */
    private List<FileStatus> selectFiles(
      PolicyInfo info, ArrayList<PolicyInfo> allPolicies) throws IOException {
      String policyName = info.getName();

      // Max number of files returned.
      int selectLimit = configMgr.getMaxFilesPerJob();

      PolicyState scanState = policyStateMap.get(policyName);

      List<FileStatus> returnSet = new ArrayList<FileStatus>(selectLimit);
      DirectoryTraversal traversal;
      if (scanState.isScanInProgress()) {
        LOG.info("Resuming traversal for policy " + policyName);
        traversal = scanState.pendingTraversal;
      } else {
        LOG.info("Start new traversal for policy " + policyName);
        scanState.startTime = now();
        traversal = DirectoryTraversal.raidFileRetriever(
            info, info.getSrcPathExpanded(), allPolicies, conf,
            directoryTraversalThreads, directoryTraversalShuffle,
            true);
        scanState.setTraversal(traversal);
      }

      FileStatus f;
      while ((f = traversal.next()) != DirectoryTraversal.FINISH_TOKEN) {
        returnSet.add(f);
        if (returnSet.size() == selectLimit) {
          return returnSet;
        }
      }
      scanState.resetTraversal();
      return returnSet;
    }

    private List<FileStatus> readFileList(PolicyInfo info) throws IOException {
      Path fileListPath = info.getFileListPath();
      List<FileStatus> list = new ArrayList<FileStatus>();
      if (fileListPath == null) {
        return list;
      }

      // Max number of files returned.
      int selectLimit = configMgr.getMaxFilesPerJob();

      String policyName = info.getName();
      PolicyState scanState = policyStateMap.get(policyName);
      if (!scanState.isFileListReadInProgress()) {
        scanState.startTime = now();
        try {
          InputStream in = fileListPath.getFileSystem(conf).open(fileListPath);
          scanState.fileListReader = new BufferedReader(new InputStreamReader(in));
        } catch (IOException e) {
          LOG.warn("Could not create reader for " + fileListPath, e);
          return list;
        }
      }

      String l = null;
      try {
        while ((l = scanState.fileListReader.readLine()) != null) {
          Path p = new Path(l);
          FileSystem fs = p.getFileSystem(conf);
          list.add(fs.getFileStatus(p));
          if (list.size() >= selectLimit) {
            break;
          }
        }
        if (l == null) {
          scanState.resetFileListRead();
        }
      } catch (IOException e) {
        LOG.error("Encountered error in file list read ", e);
        scanState.resetFileListRead();
      }
      return list;
    }


    /**
     * Keep processing policies.
     * If the config file has changed, then reload config file and start afresh.
     */
    private void doProcess() throws IOException, InterruptedException {
      ArrayList<PolicyInfo> allPolicies = new ArrayList<PolicyInfo>();
      for (PolicyInfo info : configMgr.getAllPolicies()) {
        allPolicies.add(info);
      }
      while (running) {
        Thread.sleep(SLEEP_TIME);

        boolean reloaded = configMgr.reloadConfigsIfNecessary();
        if (reloaded) {
          allPolicies.clear();
          for (PolicyInfo info : configMgr.getAllPolicies()) {
              allPolicies.add(info);
          }
        }
        LOG.info("TriggerMonitor.doProcess " + allPolicies.size());

        for (PolicyInfo info: allPolicies) {
          if (!policyStateMap.containsKey(info.getName())) {
            policyStateMap.put(info.getName(), new PolicyState());
          }

          List<FileStatus> filteredPaths = null;
          if (shouldReadFileList(info)) {
            filteredPaths = readFileList(info);
          } else if (shouldSelectFiles(info)) {
            LOG.info("Triggering Policy Filter " + info.getName() +
                   " " + info.getSrcPath());
            try {
              filteredPaths = selectFiles(info, allPolicies);
            } catch (Exception e) {
              LOG.info("Exception while invoking filter on policy " + info.getName() +
                       " srcPath " + info.getSrcPath() + 
                       " exception " + StringUtils.stringifyException(e));
              continue;
            }
          } else {
            continue;
          }

          if (filteredPaths == null || filteredPaths.size() == 0) {
            LOG.info("No filtered paths for policy " + info.getName());
             continue;
          }

          // Apply the action on accepted paths
          LOG.info("Triggering Policy Action " + info.getName() +
            " " + filteredPaths.size() + " files");
          try {
            raidFiles(info, filteredPaths);
          } catch (Exception e) {
            LOG.info("Exception while invoking action on policy " + info.getName() +
                     " srcPath " + info.getSrcPath() + 
                     " exception " + StringUtils.stringifyException(e));
            continue;
          }
        }
      }
    }
  }

  /**
   * raid a list of files, this will be overridden by subclasses of RaidNode
   */
  abstract void raidFiles(PolicyInfo info, List<FileStatus> paths) throws IOException;

  public abstract String raidJobsHtmlTable(boolean running);

  static Path getOriginalParityFile(Path destPathPrefix, Path srcPath) {
    return new Path(destPathPrefix, makeRelative(srcPath));
  }

  static long numBlocks(FileStatus stat) {
    return (long) Math.ceil(stat.getLen() * 1.0 / stat.getBlockSize());
  }

  static long numStripes(long numBlocks, int stripeSize) {
    return (long) Math.ceil(numBlocks * 1.0 / stripeSize);
  }

  static long savingFromRaidingFile(
      FileStatus stat, int stripeSize, int paritySize,
      int targetReplication, int parityReplication) {
    long currentReplication = stat.getReplication();
    if (currentReplication > targetReplication) {
      long numBlocks = numBlocks(stat);
      long numStripes = numStripes(numBlocks, stripeSize);
      long sourceSaving =
        stat.getLen() * (currentReplication - targetReplication);
      long parityBlocks = numStripes * paritySize;
      return sourceSaving - parityBlocks * parityReplication;
    }
    return 0;
  }

  /**
   * RAID a list of files.
   */
  void doRaid(Configuration conf, PolicyInfo info, List<FileStatus> paths)
      throws IOException {
    int targetRepl = Integer.parseInt(info.getProperty("targetReplication"));
    int metaRepl = Integer.parseInt(info.getProperty("metaReplication"));
    Codec codec = Codec.getCodec(info.getCodecId());
    Path destPref = new Path(codec.parityDirectory);
    String simulate = info.getProperty("simulate");
    boolean doSimulate = simulate == null ? false : Boolean
        .parseBoolean(simulate);

    Statistics statistics = new Statistics();
    int count = 0;

    for (FileStatus s : paths) {
      doRaid(conf, s, destPref, codec, statistics,
                 RaidUtils.NULL_PROGRESSABLE,
                 doSimulate, targetRepl, metaRepl);
      if (count % 1000 == 0) {
        LOG.info("RAID statistics " + statistics.toString());
      }
      count++;
    }
    LOG.info("RAID statistics " + statistics.toString());
  }

  
  /**
   * RAID an individual file
   */
  static public boolean doRaid(Configuration conf, PolicyInfo info,
                               FileStatus src, Statistics statistics, 
                               Progressable reporter) 
    throws IOException {
    int targetRepl = Integer.parseInt(info.getProperty("targetReplication"));
    int metaRepl = Integer.parseInt(info.getProperty("metaReplication"));
    
    Codec codec = Codec.getCodec(info.getCodecId());
    Path destPref = new Path(codec.parityDirectory);
    String simulate = info.getProperty("simulate");
    boolean doSimulate = simulate == null ? false : Boolean
        .parseBoolean(simulate);

    return doRaid(conf, src, destPref, codec, statistics,
                  reporter, doSimulate, targetRepl, metaRepl);
  }

  /**
   * RAID an individual file
   */
  public static boolean doRaid(Configuration conf, FileStatus stat,
      Path destPath, Codec codec, Statistics statistics,
      Progressable reporter, boolean doSimulate, int targetRepl, int metaRepl)
        throws IOException {
    Path p = stat.getPath();
    FileSystem srcFs = p.getFileSystem(conf);

    // extract block locations from File system
    BlockLocation[] locations = srcFs.getFileBlockLocations(stat, 0, stat.getLen());
    
    // if the file has fewer than 2 blocks, then nothing to do
    if (locations.length <= 2) {
      return false;
    }

    // add up the raw disk space occupied by this file
    long diskSpace = 0;
    for (BlockLocation l: locations) {
      diskSpace += (l.getLength() * stat.getReplication());
    }
    statistics.numProcessedBlocks += locations.length;
    statistics.processedSize += diskSpace;

    // generate parity file
    generateParityFile(conf, stat, targetRepl, reporter, srcFs, destPath, codec, locations,
                       metaRepl);
    if (!doSimulate) {
      if (srcFs.setReplication(p, (short)targetRepl) == false) {
        LOG.info("Error in reducing replication of " + p + " to " + targetRepl);
        statistics.remainingSize += diskSpace;
        return false;
      };
    }

    diskSpace = 0;
    for (BlockLocation l: locations) {
      diskSpace += (l.getLength() * targetRepl);
    }
    statistics.remainingSize += diskSpace;

    // the metafile will have this many number of blocks
    int numMeta = locations.length / codec.stripeLength;
    if (locations.length % codec.stripeLength != 0) {
      numMeta++;
    }

    // we create numMeta for every file. This metablock has metaRepl # replicas.
    // the last block of the metafile might not be completely filled up, but we
    // ignore that for now.
    statistics.numMetaBlocks += (numMeta * metaRepl);
    statistics.metaSize += (numMeta * metaRepl * stat.getBlockSize());
    return true;
  }

  /**
   * Create the parity file.
   */
  static private void generateParityFile(Configuration conf, FileStatus stat,
                                  int targetRepl,
                                  Progressable reporter,
                                  FileSystem inFs,
                                  Path destPathPrefix,
                                  Codec codec,
                                  BlockLocation[] locations,
                                  int metaRepl) throws IOException {

    Path inpath = stat.getPath();
    Path outpath =  getOriginalParityFile(destPathPrefix, inpath);
    FileSystem outFs = outpath.getFileSystem(conf);

    // If the parity file is already upto-date and source replication is set
    // then nothing to do.
    try {
      FileStatus stmp = outFs.getFileStatus(outpath);
      if (stmp.getModificationTime() == stat.getModificationTime() &&
          stat.getReplication() == targetRepl) {
        LOG.info("Parity file for " + inpath + "(" + locations.length +
              ") is " + outpath + " already upto-date and " +
              "file is at target replication . Nothing more to do.");
        return;
      }
    } catch (IOException e) {
      // ignore errors because the raid file might not exist yet.
    }

    Encoder encoder = encoderForCodec(codec);
    encoder.encodeFile(inFs, inpath, outFs, outpath, (short)metaRepl, reporter);

    // set the modification time of the RAID file. This is done so that the modTime of the
    // RAID file reflects that contents of the source file that it has RAIDed. This should
    // also work for files that are being appended to. This is necessary because the time on
    // on the destination namenode may not be synchronised with the timestamp of the 
    // source namenode.
    outFs.setTimes(outpath, stat.getModificationTime(), -1);

    FileStatus outstat = outFs.getFileStatus(outpath);
    FileStatus inStat = inFs.getFileStatus(inpath);
    if (stat.getModificationTime() != inStat.getModificationTime()) {
      String msg = "Source file changed mtime during raiding from " +
        stat.getModificationTime() + " to " + inStat.getModificationTime();
      throw new IOException(msg);
    }
    if (outstat.getModificationTime() != inStat.getModificationTime()) {
      String msg = "Parity file mtime " + outstat.getModificationTime() +
        " does not match source mtime " + inStat.getModificationTime();
      throw  new IOException(msg);
    }
    LOG.info("Source file " + inpath + " of size " + inStat.getLen() +
             " Parity file " + outpath + " of size " + outstat.getLen() +
             " src mtime " + stat.getModificationTime()  +
             " parity mtime " + outstat.getModificationTime());
  }

  public static Path unRaidCorruptBlock(Configuration conf, Path srcPath,
    Codec codec, long corruptOffset, FileSystem recoveryFs) throws IOException {
    // Test if parity file exists
    ParityFilePair ppair = ParityFilePair.getParityFile(codec, srcPath, conf);
    if (ppair == null) {
      LOG.warn("Could not find " + codec.id + " parity file for " + srcPath);
      return null;
    }

    final Path recoveryDestination = new Path(conf.get(
      RAID_RECOVERY_LOCATION_KEY, DEFAULT_RECOVERY_LOCATION));
    final Path recoveredPrefix =
      recoveryFs.makeQualified(new Path(recoveryDestination, makeRelative(srcPath)));
    final Path recoveredBlock =
      new Path(recoveredPrefix + "." + new Random().nextLong() + ".recovered");
    LOG.info("Creating recovered Block " + recoveredBlock);

    FileSystem srcFs = srcPath.getFileSystem(conf);
    FileStatus stat = srcFs.getFileStatus(srcPath);
    long limit = Math.min(stat.getBlockSize(), stat.getLen() - corruptOffset);
    java.io.OutputStream out = recoveryFs.create(recoveredBlock);
    Decoder decoder = decoderForCodec(codec);
    try {
      decoder.fixErasedBlock(srcFs, srcPath,
          ppair.getFileSystem(), ppair.getPath(),
          stat.getBlockSize(), corruptOffset, limit, out,
          RaidUtils.NULL_PROGRESSABLE);
      out.close();
      out = null;
    } catch (IOException e) {
      if (out != null) {
        out.close();
      }
      try {
        recoveryFs.delete(recoveredBlock);
      } catch (IOException e2) {
      }
      throw e;
    }
    return recoveredBlock;
  }

  
  private void doHar() throws IOException, InterruptedException {
    long prevExec = 0;
    while (running) {

      // The config may be reloaded by the TriggerMonitor. 
      // This thread uses whatever config is currently active.
      while(now() < prevExec + configMgr.getPeriodicity()){
        Thread.sleep(SLEEP_TIME);
      }

      LOG.info("Started archive scan");
      prevExec = now();
      
      // fetch all categories
      for (Codec codec : Codec.getCodecs()) {
        try {
          String tmpHarPath = codec.tmpHarDirectory;
          int harThresold = conf.getInt(RAID_PARITY_HAR_THRESHOLD_DAYS_KEY,
            DEFAULT_RAID_PARITY_HAR_THRESHOLD_DAYS);
          long cutoff = now() - ( harThresold * 24L * 3600000L );

          Path destPref = new Path(codec.parityDirectory);
          FileSystem destFs = destPref.getFileSystem(conf);
          FileStatus destStat = null;
          try {
            destStat = destFs.getFileStatus(destPref);
          } catch (FileNotFoundException e) {
            continue;
          }

          LOG.info("Haring parity files in " + destPref);
          recurseHar(codec, destFs, destStat, destPref.toUri().getPath(),
            destFs, cutoff, tmpHarPath);
        } catch (Exception e) {
          LOG.warn("Ignoring Exception while haring ", e);
        }
      }
    }
    return;
  }
  
  void recurseHar(Codec codec, FileSystem destFs, FileStatus dest,
      String destPrefix, FileSystem srcFs, long cutoff, String tmpHarPath)
    throws IOException {

    if (!dest.isDir()) {
      return;
    }
    
    Path destPath = dest.getPath(); // pathname, no host:port
    String destStr = destPath.toUri().getPath();

    // If the source directory is a HAR, do nothing.
    if (destStr.endsWith(".har")) {
      return;
    }

    // Verify if it already contains a HAR directory
    if ( destFs.exists(new Path(destPath, destPath.getName()+HAR_SUFFIX)) ) {
      return;
    }

    boolean shouldHar = false;
    FileStatus[] files = destFs.listStatus(destPath);
    long harBlockSize = -1;
    short harReplication = -1;
    if (files != null) {
      shouldHar = files.length > 0;
      for (FileStatus one: files) {
        if (one.isDir()){
          recurseHar(codec, destFs, one, destPrefix, srcFs, cutoff, tmpHarPath);
          shouldHar = false;
        } else if (one.getModificationTime() > cutoff ) {
          if (shouldHar) {
            LOG.debug("Cannot archive " + destPath + 
                   " because " + one.getPath() + " was modified after cutoff");
            shouldHar = false;
          }
        } else {
          if (harBlockSize == -1) {
            harBlockSize = one.getBlockSize();
          } else if (harBlockSize != one.getBlockSize()) {
            LOG.info("Block size of " + one.getPath() + " is " +
              one.getBlockSize() + " which is different from " + harBlockSize);
            shouldHar = false;
          }
          if (harReplication == -1) {
            harReplication = one.getReplication();
          } else if (harReplication != one.getReplication()) {
            LOG.info("Replication of " + one.getPath() + " is " +
              one.getReplication() + " which is different from " + harReplication);
            shouldHar = false;
          }
        }
      }

      if (shouldHar) {
        String src = destStr.replaceFirst(destPrefix, "");
        Path srcPath = new Path(src);
        FileStatus[] statuses = srcFs.listStatus(srcPath);
        Path destPathPrefix = new Path(destPrefix).makeQualified(destFs);
        if (statuses != null) {
          for (FileStatus status : statuses) {
            if (ParityFilePair.getParityFile(codec,
                status.getPath().makeQualified(srcFs), conf) == null ) {
              LOG.debug("Cannot archive " + destPath + 
                  " because it doesn't contain parity file for " +
                  status.getPath().makeQualified(srcFs) + " on destination " +
                  destPathPrefix);
              shouldHar = false;
              break;
            }
          }
        }
      }
      
    }

    if ( shouldHar ) {
      LOG.info("Archiving " + dest.getPath() + " to " + tmpHarPath );
      singleHar(codec, destFs, dest, tmpHarPath, harBlockSize, harReplication);
    }
  } 

  
  private void singleHar(Codec codec, FileSystem destFs,
       FileStatus dest, String tmpHarPath, long harBlockSize,
       short harReplication) throws IOException {
    
    Random rand = new Random();
    Path root = new Path("/");
    Path qualifiedPath = dest.getPath().makeQualified(destFs);
    String harFileDst = qualifiedPath.getName() + HAR_SUFFIX;
    String harFileSrc = qualifiedPath.getName() + "-" + 
                                rand.nextLong() + "-" + HAR_SUFFIX;

    // HadoopArchives.HAR_PARTFILE_LABEL is private, so hard-coding the label.
    conf.setLong("har.partfile.size", configMgr.getHarPartfileSize());
    conf.setLong("har.block.size", harBlockSize);
    HadoopArchives har = new HadoopArchives(conf);
    String[] args = new String[7];
    args[0] = "-Ddfs.replication=" + harReplication;
    args[1] = "-archiveName";
    args[2] = harFileSrc;
    args[3] = "-p"; 
    args[4] = root.makeQualified(destFs).toString();
    args[5] = qualifiedPath.toUri().getPath().substring(1);
    args[6] = tmpHarPath.toString();
    int ret = 0;
    Path tmpHar = new Path(tmpHarPath + "/" + harFileSrc);
    try {
      ret = ToolRunner.run(har, args);
      if (ret == 0 && !destFs.rename(tmpHar,
                                     new Path(qualifiedPath, harFileDst))) {
        LOG.info("HAR rename didn't succeed from " + tmpHarPath+"/"+harFileSrc +
            " to " + qualifiedPath + "/" + harFileDst);
        ret = -2;
      }
    } catch (Exception exc) {
      throw new IOException("Error while creating archive " + ret, exc);
    } finally {
      destFs.delete(tmpHar, true);
    }
    
    if (ret != 0){
      throw new IOException("Error while creating archive " + ret);
    }
    return;
  }
  
  /**
   * Periodically generates HAR files
   */
  class HarMonitor implements Runnable {

    public void run() {
      while (running) {
        try {
          doHar();
        } catch (Exception e) {
          LOG.error(StringUtils.stringifyException(e));
        } finally {
          LOG.info("Har parity files thread continuing to run...");
        }
      }
      LOG.info("Leaving Har thread.");
    }
  }

  static Encoder encoderForCodec(Codec codec) {
    // TODO: implement this
    return null;
  }

  static Decoder decoderForCodec(Codec codec) {
    // TODO: implemnet this
    return null;
  }

  static boolean isParityHarPartFile(Path p) {
    Matcher m = PARITY_HAR_PARTFILE_PATTERN.matcher(p.toUri().getPath());
    return m.matches();
  }

  /**
   * Returns current time.
   */
  static long now() {
    return System.currentTimeMillis();
  }

  /**                       
   * Make an absolute path relative by stripping the leading /
   */   
  static Path makeRelative(Path path) {
    if (!path.isAbsolute()) {
      return path;
    }          
    String p = path.toUri().getPath();
    String relative = p.substring(1, p.length());
    return new Path(relative);
  } 

  private static void printUsage() {
    System.err.println("Usage: java RaidNode ");
  }

  private static StartupOption parseArguments(String args[]) {
    StartupOption startOpt = StartupOption.REGULAR;
    return startOpt;
  }

  /**
   * Convert command line options to configuration parameters
   */
  private static void setStartupOption(Configuration conf, StartupOption opt) {
    conf.set("fs.raidnode.startup", opt.toString());
  }

  /**
   * Create an instance of the appropriate subclass of RaidNode 
   */
  public static RaidNode createRaidNode(Configuration conf)
    throws ClassNotFoundException {
    try {
      // default to distributed raid node
      Class<?> raidNodeClass =
        conf.getClass(RAIDNODE_CLASSNAME_KEY, DistRaidNode.class);
      if (!RaidNode.class.isAssignableFrom(raidNodeClass)) {
        throw new ClassNotFoundException("not an implementation of RaidNode");
      }
      Constructor<?> constructor =
        raidNodeClass.getConstructor(new Class[] {Configuration.class} );
      return (RaidNode) constructor.newInstance(conf);
    } catch (NoSuchMethodException e) {
      throw new ClassNotFoundException("cannot construct raidnode", e);
    } catch (InstantiationException e) {
      throw new ClassNotFoundException("cannot construct raidnode", e);
    } catch (IllegalAccessException e) {
      throw new ClassNotFoundException("cannot construct raidnode", e);
    } catch (InvocationTargetException e) {
      throw new ClassNotFoundException("cannot construct raidnode", e);
    }
  }

  /**
   * Create an instance of the RaidNode 
   */
  public static RaidNode createRaidNode(String argv[], Configuration conf)
    throws IOException, ClassNotFoundException {
    if (conf == null) {
      conf = new Configuration();
    }
    StartupOption startOpt = parseArguments(argv);
    if (startOpt == null) {
      printUsage();
      return null;
    }
    setStartupOption(conf, startOpt);
    RaidNode node = createRaidNode(conf);
    return node;
  }

  public static void main(String argv[]) throws Exception {
    try {
      StringUtils.startupShutdownMessage(RaidNode.class, argv, LOG);
      RaidNode raid = createRaidNode(argv, null);
      if (raid != null) {
        raid.join();
      }
    } catch (Throwable e) {
      LOG.error(StringUtils.stringifyException(e));
      System.exit(-1);
    }
  }
}
