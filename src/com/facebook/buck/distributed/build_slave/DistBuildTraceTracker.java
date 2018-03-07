/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.build_slave.DistBuildTrace.RuleTrace;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class tracks start and stop timestamp for build rules processed by stamepede minions.
 *
 * <p>Not thread safe.
 */
public class DistBuildTraceTracker {

  private Map<String, ArrayList<RuleTrace>> rulesByMinionId = new HashMap<>();
  private Map<String, Long> jobStartedEpochMillisByJobId = new HashMap<>();
  private Map<String, String> nextBuildRuleInWorkUnitByRule = new HashMap<>();

  private final Clock clock;

  private final StampedeId stampedeId;

  public DistBuildTraceTracker(StampedeId stampedeId) {
    this(stampedeId, new DefaultClock());
  }

  public DistBuildTraceTracker(StampedeId stampedeId, Clock clock) {
    this.stampedeId = stampedeId;
    this.clock = clock;
  }

  private void minionGotWork(List<WorkUnit> workUnits, long now) {
    for (WorkUnit workUnit : workUnits) {
      for (String buildTarget : workUnit.buildTargets) {
        Long prev = jobStartedEpochMillisByJobId.put(buildTarget, now);
        Preconditions.checkState(prev == null, "must not override previous entries");
      }

      for (int i = 1; i < workUnit.buildTargets.size(); i++) {
        String buildTarget = workUnit.buildTargets.get(i);
        String prevBuildTarget = workUnit.buildTargets.get(i - 1);
        String prev = nextBuildRuleInWorkUnitByRule.put(prevBuildTarget, buildTarget);
        Preconditions.checkState(prev == null, "must not override previous entries");
      }
    }
  }

  private void minionFinishedWork(String minionId, List<String> ruleNames, long now) {
    for (String ruleName : ruleNames) {
      Long startEpochMillisOrNull = jobStartedEpochMillisByJobId.remove(ruleName);
      long startEpochMillis =
          Preconditions.checkNotNull(startEpochMillisOrNull, "job was not started: %s", ruleName);

      ArrayList<RuleTrace> historyEntries =
          rulesByMinionId.computeIfAbsent(minionId, k -> new ArrayList<>());
      historyEntries.add(new RuleTrace(ruleName, startEpochMillis, now));

      String nextRule = nextBuildRuleInWorkUnitByRule.remove(ruleName);
      if (nextRule != null) {
        jobStartedEpochMillisByJobId.put(nextRule, now);
      }
    }
  }

  /** Update tracker state with just finished targets, and targets to be executed. */
  public void updateWork(String minionId, List<String> finishedRules, List<WorkUnit> newWorkUnits) {
    long now = clock.currentTimeMillis();
    minionFinishedWork(minionId, finishedRules, now);
    minionGotWork(newWorkUnits, now);
  }

  public DistBuildTrace generateTrace() {
    return new DistBuildTrace(stampedeId, new HashMap<>(rulesByMinionId));
  }
}
