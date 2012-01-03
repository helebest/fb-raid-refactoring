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

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;

/**
 * A place for some static methods used by the Raid unit test
 */
public class Utils {

  public static final Log LOG = LogFactory.getLog(Utils.class);

  /**
   * Load typical codecs for unit test use
   */
  public static void loadTestCodecs(Configuration conf) throws IOException {
    Utils.loadTestCodecs(conf, 5, 1, 3, "/raid", "/raidrs");
  }

  /**
   * Load RS and XOR codecs with given parameters
   */
  public static void loadTestCodecs(Configuration conf,
      int stripeLength, int xorParityLength, int rsParityLength,
      String xorParityDirectory, String rsParityDirectory) throws IOException {
    String codecsJSON = "[ " +
    " { " +
      "\"id\":\"xor\"," +
      "\"parity_dir\":\"" + xorParityDirectory + "\"," +
      "\"tmp_parity_dir\":\"" + "/tmp/" + xorParityDirectory + "\"," +
      "\"tmp_har_dir\":\"" + "/tmp/" + xorParityDirectory + "_har" + "\"," +
      "\"stripe_length\":" + stripeLength + "," +
      "\"parity_length\":" + xorParityLength + "," +
      "\"priority\":" + 100 + "," +
      "\"erasure_code\":\"org.apache.hadoop.raid.XorCode\"," +
      "\"description\":\"XOR Code\"," +
    " }, " +
    " { " +
      "\"id\":\"rs\"," +
      "\"parity_dir\":\"" + rsParityDirectory + "\"," +
      "\"tmp_parity_dir\":\"" + "/tmp/" + rsParityDirectory + "\"," +
      "\"tmp_har_dir\":\"" + "/tmp/" + rsParityDirectory + "_har" + "\"," +
      "\"stripe_length\":" + stripeLength + "," +
      "\"parity_length\":" + rsParityLength + "," +
      "\"priority\":" + 300 + "," +
      "\"erasure_code\":\"org.apache.hadoop.raid.ReedSolomonCode\"," +
      "\"description\":\"Reed Solomon Code\"," +
    " }, " +
    " ] ";
    LOG.info("raid.codecs.json=" + codecsJSON);
    conf.set("raid.codecs.json", codecsJSON);
    Codec.initializeCodecs(conf);
    LOG.info("Test codec loaded");
    for (Codec c : Codec.getCodecs()) {
      LOG.info("Loaded raid code:" + c.id);
    }
  }

}
