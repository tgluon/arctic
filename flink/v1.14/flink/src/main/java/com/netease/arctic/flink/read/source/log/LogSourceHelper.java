/*
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

package com.netease.arctic.flink.read.source.log;

import org.apache.flink.connector.kafka.source.split.KafkaPartitionSplit;
import org.apache.flink.table.data.RowData;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import static com.netease.arctic.log.LogData.MAGIC_NUMBER;
import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * According to upstreamId and partition topic dealing with the flip message, when should begin to retract message and
 * when to end it.
 */
public class LogSourceHelper implements Serializable {
  private static final Logger LOG = LoggerFactory.getLogger(LogSourceHelper.class);
  private static final long serialVersionUID = 1L;

  /**
   * Record the topic partitions that are in retracting state.
   */
  private Map<TopicPartition, EpicRetractingInfo> retractingInfo;
  /**
   * Key: topic partition + "_" + upstream job id + "_" + epicNo, generated by
   * {@link #combineTopicPartitionAndUpstreamIdAndEpicNo)} method.
   * Value: epic start offset
   */
  private NavigableMap<String, Long> upstreamEpicStartOffsets;

  public LogSourceHelper() {
    retractingInfo = new HashMap<>();
    upstreamEpicStartOffsets = new TreeMap<>();
  }

  public void initializedState(KafkaPartitionSplit s) {
    if (!(s instanceof LogKafkaPartitionSplit)) {
      return;
    }
    LogKafkaPartitionSplit split = (LogKafkaPartitionSplit) s;
    if (split.isRetracting()) {
      retractingInfo.put(split.getTopicPartition(),
          EpicRetractingInfo.of(
              split.getRetractingEpicNo(), split.getRetractingUpstreamId(), 
              split.getRetractStopOffset(), split.getRevertStartOffset()));
    }
    Map<String, Long> upStreamEpicStartOffsets = split.getUpStreamEpicStartOffsets();

    upStreamEpicStartOffsets.forEach((upstreamEpic, offset) -> {
      String key = combineTopicPartitionAndUpstreamIdAndEpicNo(split.getTopicPartition(), upstreamEpic);
      upstreamEpicStartOffsets.putIfAbsent(key, offset);
    });
  }

  /**
   * turn row kind of a row.
   * +I -> -D
   * -D -> +I
   * -U -> +U
   * +U -> -U
   *
   * @param rowData before reset row
   * @return after reset row kind.
   */
  public RowData turnRowKind(RowData rowData) {
    switch (rowData.getRowKind()) {
      case INSERT:
        rowData.setRowKind(RowKind.DELETE);
        break;
      case DELETE:
        rowData.setRowKind(RowKind.INSERT);
        break;
      case UPDATE_AFTER:
        rowData.setRowKind(RowKind.UPDATE_BEFORE);
        break;
      case UPDATE_BEFORE:
        rowData.setRowKind(RowKind.UPDATE_AFTER);
        break;
      default:
        throw new FlinkRuntimeException("unKnown ChangeAction=" + rowData.getRowKind());
    }
    LOG.debug("after retract a row, ChangeAction={}", rowData.getRowKind());
    return rowData;
  }

  public Set<TopicPartition> getRetractTopicPartitions() {
    return retractingInfo.keySet();
  }

  public EpicRetractingInfo getRetractInfo(TopicPartition topicPartition) {
    EpicRetractingInfo info = retractingInfo.get(topicPartition);
    if (info == null) {
      throw new IllegalStateException(String.format("the topic partition: %s, %d is not in retracting state",
          topicPartition.topic(), topicPartition.partition()));
    }
    return info;
  }

  public void suspendRetracting(TopicPartition tp) {
    EpicRetractingInfo info = retractingInfo.remove(tp);
    clearEpicStartOffsetsBeforeOrEqual(tp, info.upstreamId, info.epicNo);
  }

  public void suspendRetracting(Collection<TopicPartition> tps) {
    tps.forEach(this::suspendRetracting);
  }

  /**
   * clear the epic start offsets before or equal the epicNo in the topicPartition.
   *
   * @param tp
   * @param upstreamId
   * @param epicNo
   */
  public void clearEpicStartOffsetsBeforeOrEqual(TopicPartition tp, String upstreamId, long epicNo) {
    String key = combineTopicPartitionAndUpstreamIdAndEpicNo(tp, upstreamId, epicNo);
    NavigableMap<String, Long> beforeOrEqual = upstreamEpicStartOffsets.headMap(key, true);

    String prefix = combineTopicPartitionAndUpstreamId(tp, upstreamId);
    for (String s : beforeOrEqual.keySet()) {
      if (!s.contains(prefix)) {
        continue;
      }
      upstreamEpicStartOffsets.remove(s);
    }
  }

  /**
   * @param revertStartingOffset the offset where job revert to normal read starts from. It should skip the flip which
   *                             has been read.
   */
  public void startRetracting(TopicPartition tp, String upstreamId, long epicNo, long revertStartingOffset) {
    String key = combineTopicPartitionAndUpstreamIdAndEpicNo(tp, upstreamId, epicNo);
    if (!upstreamEpicStartOffsets.containsKey(key)) {
      // data have not been read, so that it's unnecessary to retract
      return;
    }
    long retractStoppingOffset = upstreamEpicStartOffsets.get(key);

    retractingInfo.put(tp, new EpicRetractingInfo(epicNo, upstreamId, retractStoppingOffset, revertStartingOffset));
  }

  public void initialEpicStartOffsetIfEmpty(TopicPartition tp, String upstreamId, long epicNo, long startOffset) {
    String key = combineTopicPartitionAndUpstreamIdAndEpicNo(tp, upstreamId, epicNo);
    upstreamEpicStartOffsets.putIfAbsent(key, startOffset);
  }
  
  private String combineTopicPartitionAndUpstreamIdAndEpicNo(TopicPartition tp, String upstreamId, long epicNo) {
    return combineTopicPartitionAndUpstreamId(tp, upstreamId) + "_" + epicNo;
  }

  private String combineTopicPartitionAndUpstreamIdAndEpicNo(TopicPartition tp, String upstreamIdAndEpicNo) {
    return combineTopicPartition(tp) + "_" + upstreamIdAndEpicNo;
  }

  private String combineTopicPartitionAndUpstreamId(TopicPartition tp, String upstreamId) {
    return combineTopicPartition(tp) + "_" + upstreamId;
  }

  private String combineTopicPartition(TopicPartition tp) {
    return tp.topic() + "_" + tp.partition();
  }

  public static boolean checkMagicNum(byte[] value) {
    checkNotNull(value);
    checkArgument(value.length >= 3);
    return value[0] == MAGIC_NUMBER[0] && value[1] == MAGIC_NUMBER[1] && value[2] == MAGIC_NUMBER[2];
  }

  public static class EpicRetractingInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private final long epicNo;
    private final String upstreamId;
    private final long retractStoppingOffset;
    private final long revertStartingOffset;

    public EpicRetractingInfo(long epicNo, String upstreamId, long retractStoppingOffset, long revertStartingOffset) {
      this.epicNo = epicNo;
      this.upstreamId = upstreamId;
      this.retractStoppingOffset = retractStoppingOffset;
      this.revertStartingOffset = revertStartingOffset;
    }

    private static EpicRetractingInfo of(long epicNo, String upstreamId, long retractStopOffset,
                                         long revertStartOffset) {
      return new EpicRetractingInfo(epicNo, upstreamId, retractStopOffset, revertStartOffset);
    }

    public long getEpicNo() {
      return epicNo;
    }

    public String getUpstreamId() {
      return upstreamId;
    }

    public long getRetractStoppingOffset() {
      return retractStoppingOffset;
    }

    public long getRevertStartingOffset() {
      return revertStartingOffset;
    }
  }
}
