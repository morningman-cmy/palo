// Copyright (c) 2017, Baidu.com, Inc. All Rights Reserved

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.baidu.palo.load;

import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.common.Config;
import com.baidu.palo.common.util.Daemon;
import com.baidu.palo.load.ExportJob.JobState;
import com.baidu.palo.task.ExportExportingTask;
import com.baidu.palo.task.ExportPendingTask;
import com.baidu.palo.task.MasterTask;
import com.baidu.palo.task.MasterTaskExecutor;

import com.google.common.collect.Maps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

public final class ExportChecker extends Daemon {
    private static final Logger LOG = LogManager.getLogger(ExportChecker.class);

    // checkers for running job state
    private static Map<JobState, ExportChecker> checkers = Maps.newHashMap();
    // executors for pending tasks
    private static Map<JobState, MasterTaskExecutor> executors = Maps.newHashMap();
    private JobState jobState;

    private ExportChecker(JobState jobState, long intervalMs) {
        super("export checker " + jobState.name().toLowerCase(), intervalMs);
        this.jobState = jobState;
    }

    public static void init(long intervalMs) {
        checkers.put(JobState.PENDING, new ExportChecker(JobState.PENDING, intervalMs));
        checkers.put(JobState.EXPORTING, new ExportChecker(JobState.EXPORTING, intervalMs));

        MasterTaskExecutor pendingTaskExecutor = new MasterTaskExecutor(Config.export_pending_thread_num);
        executors.put(JobState.PENDING, pendingTaskExecutor);

        MasterTaskExecutor exporingTaskExecutor = new MasterTaskExecutor(Config.export_exporting_thread_num);
        executors.put(JobState.EXPORTING, exporingTaskExecutor);
    }

    public static void startAll() {
        for (ExportChecker exportChecker : checkers.values()) {
            exportChecker.start();
        }
    }

    @Override
    protected void runOneCycle() {
        LOG.debug("start check export jobs. job state: {}", jobState.name());
        switch (jobState) {
            case PENDING:
                runPendingJobs();
                break;
            case EXPORTING:
                runExportingJobs();
                break;
            default:
                LOG.warn("wrong export job state: {}", jobState.name());
                break;
        }
    }

    private void runPendingJobs() {
        ExportMgr exportMgr = Catalog.getInstance().getExportMgr();
        List<ExportJob> pendingJobs = exportMgr.getExportJobs(JobState.PENDING);

        // check to limit running etl job num
        int runningJobNumLimit = Config.export_running_job_num_limit;
        if (runningJobNumLimit > 0 && !pendingJobs.isEmpty()) {
            // pending executor running + exporting state
            int runningJobNum = executors.get(JobState.PENDING).getTaskNum()
                    + executors.get(JobState.EXPORTING).getTaskNum();
            if (runningJobNum >= runningJobNumLimit) {
                LOG.debug("running export job num {} exceeds system limit {}", runningJobNum, runningJobNumLimit);
                return;
            }

            int remain = runningJobNumLimit - runningJobNum;
            if (pendingJobs.size() > remain) {
                pendingJobs = pendingJobs.subList(0, remain);
            }
        }

        LOG.debug("pending export job num: {}", pendingJobs.size());

        for (ExportJob job : pendingJobs) {
            try {
                MasterTask task = new ExportPendingTask(job);
                if (executors.get(JobState.PENDING).submit(task)) {
                    LOG.info("run pending export job. job: {}", job);
                }
            } catch (Exception e) {
                LOG.warn("run pending export job error", e);
            }
        }
    }

    private void runExportingJobs() {
        List<ExportJob> jobs = Catalog.getInstance().getExportMgr().getExportJobs(JobState.EXPORTING);
        LOG.debug("exporting export job num: {}", jobs.size());
        for (ExportJob job : jobs) {
            try {
                MasterTask task = new ExportExportingTask(job);
                if (executors.get(JobState.EXPORTING).submit(task)) {
                    LOG.info("run exporting export job. job: {}", job);
                } else {
                    LOG.info("fail to submit exporting job to executor. job: {}", job);

                }
            } catch (Exception e) {
                LOG.warn("run export exporing job error", e);
            }
        }
    }
}
