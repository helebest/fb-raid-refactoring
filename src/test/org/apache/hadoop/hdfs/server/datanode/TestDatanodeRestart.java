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
package org.apache.hadoop.hdfs.server.datanode;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.datanode.FSDataset.FSVolume;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.io.IOUtils;

import junit.framework.TestCase;

/** Test if a datanode can correctly upgrade itself */
public class TestDatanodeRestart extends TestCase {
  // test finalized replicas persist across DataNode restarts
  public void testFinalizedReplicas() throws Exception {
    // bring up a cluster of 3
    Configuration conf = new Configuration();
    conf.setLong("dfs.block.size", 1024L);
    conf.setInt("dfs.write.packet.size", 512);
    MiniDFSCluster cluster = new MiniDFSCluster(conf, 3, true, null);
    cluster.waitActive();
    FileSystem fs = cluster.getFileSystem();
    try {
      // test finalized replicas
      final String TopDir = "/test";
      DFSTestUtil util = new DFSTestUtil("TestCrcCorruption", 2, 3, 8 * 1024);
      util.createFiles(fs, TopDir, (short) 3);
      util.waitReplication(fs, TopDir, (short) 3);
      util.checkFiles(fs, TopDir);
      cluster.restartDataNodes();
      cluster.waitActive();
      util.checkFiles(fs, TopDir);
    } finally {
      cluster.shutdown();
    }
  }

  // test rbw replicas persist across DataNode restarts
  public void testRbwReplicas() throws IOException {
    Configuration conf = new Configuration();
    conf.setLong("dfs.block.size", 1024L);
    conf.setInt("dfs.write.packet.size", 512);
    conf.setBoolean("dfs.support.append", true);
    MiniDFSCluster cluster = new MiniDFSCluster(conf, 1, true, null);
    cluster.waitActive();
    try {
      testRbwReplicas(cluster, false);
      testRbwReplicas(cluster, true);
    } finally {
      cluster.shutdown();
    }
  }

  private void testRbwReplicas(MiniDFSCluster cluster, boolean isCorrupt)
      throws IOException {
    FSDataOutputStream out = null;
    try {
      FileSystem fs = cluster.getFileSystem();
      NamespaceInfo nsInfo = cluster.getNameNode().versionRequest();
      final int fileLen = 515;
      // create some rbw replicas on disk
      byte[] writeBuf = new byte[fileLen];
      new Random().nextBytes(writeBuf);
      final Path src = new Path("/test.txt");
      out = fs.create(src);
      out.write(writeBuf);
      out.sync();
      DataNode dn = cluster.getDataNodes().get(0);
      // corrupt rbw replicas
      for (FSVolume volume : ((FSDataset) dn.data).volumes.volumes) {
        File rbwDir = volume.getRbwDir(nsInfo.getNamespaceID());
        for (File file : rbwDir.listFiles()) {
          if (isCorrupt && Block.isBlockFilename(file)) {
            new RandomAccessFile(file, "rw").setLength(fileLen - 1); // corrupt
          }
        }
      }
      cluster.restartDataNodes();
      cluster.waitActive();
      dn = cluster.getDataNodes().get(0);

      // check volumeMap: one rbw replica
      Map<Block, DatanodeBlockInfo> volumeMap =
        ((FSDataset) (dn.data)).volumeMap.getNamespaceMap(nsInfo.getNamespaceID());
      assertEquals(1, volumeMap.size());
      Block replica = volumeMap.keySet().iterator().next();
      if (isCorrupt) {
        assertEquals((fileLen - 1), replica.getNumBytes());
      } else {
        assertEquals(fileLen, replica.getNumBytes());
      }
      dn.data.invalidate(nsInfo.getNamespaceID(), new Block[] { replica });
      fs.delete(src, false);
    } finally {
      IOUtils.closeStream(out);
    }
  }
}
