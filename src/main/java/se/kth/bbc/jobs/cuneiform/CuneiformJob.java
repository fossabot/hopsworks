package se.kth.bbc.jobs.cuneiform;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import se.kth.bbc.fileoperations.FileOperations;
import se.kth.bbc.jobs.HopsJob;
import se.kth.bbc.jobs.jobhistory.JobHistory;
import se.kth.bbc.jobs.jobhistory.JobHistoryFacade;
import se.kth.bbc.jobs.jobhistory.JobOutputFile;
import se.kth.bbc.jobs.jobhistory.JobState;
import se.kth.bbc.jobs.yarn.YarnRunner;
import se.kth.bbc.lims.Utils;

/**
 *
 * @author stig
 */
public final class CuneiformJob extends HopsJob {

  private static final Logger logger = Logger.getLogger(CuneiformJob.class.
          getName());
  private static final int DEFAULT_MAX_STATE_POLL_RETRIES = 10;
  private static final int DEFAULT_POLL_TIMEOUT_INTERVAL = 1; //in seconds

  private final YarnRunner runner;
  private final FileOperations fops;
  private String stdOutFinalDestination, stdErrFinalDestination, summaryPath;

  public CuneiformJob(JobHistoryFacade facade, FileOperations fops,
          YarnRunner runner) {
    super(facade);
    this.runner = runner;
    this.fops = fops;
  }

  public void setStdOutFinalDestination(String stdOutFinalDestination) {
    this.stdOutFinalDestination = stdOutFinalDestination;
  }

  public void setStdErrFinalDestination(String stdErrFinalDestination) {
    this.stdErrFinalDestination = stdErrFinalDestination;
  }

  public void setSummaryPath(String summaryPath) {
    this.summaryPath = summaryPath;
  }

  @Override
  public HopsJob getInstance(JobHistory jh) throws IllegalArgumentException {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  protected void runJobInternal() {
    //Update job history object first
    getJobHistoryFacade().updateArgs(getJobId(), runner.getAmArgs());

    //Can only be called if this job has a valid id.
    long startTime = System.currentTimeMillis();
    //Try to start the application master.
    boolean proceed = startJob();
    // If success: monitor running job
    if (!proceed) {
      return;
    }
    proceed = monitor();
    //If ok: copy logs
    if (!proceed) {
      return;
    }
    copyLogs();
    //If the application finished normally: process its output
    try {
      if (runner.getApplicationState() == YarnApplicationState.FINISHED) {
        processOutput();
      }
      //Update execution time and final state
      long endTime = System.currentTimeMillis();
      long duration = endTime - startTime;
      getJobHistoryFacade().update(getJobId(),
              JobState.getJobState(runner.getApplicationState()),
              duration);
    } catch (YarnException | IOException ex) {
      logger.log(Level.SEVERE, "Error while getting final state for job "
              + getJobId() + ". Assuming failed", ex);
      getJobHistoryFacade().update(getJobId(),
              JobState.FRAMEWORK_FAILURE);
    }
  }

  private boolean startJob() {
    try {
      updateState(JobState.STARTING_APP_MASTER);
      runner.startAppMaster();
      return true;
    } catch (YarnException | IOException e) {
      logger.log(Level.SEVERE, "Failed to start application master for job {0}. Aborting execution", getJobId());
      updateState(JobState.APP_MASTER_START_FAILED);
      return false;
    }
  }

  private boolean monitor() {
    YarnApplicationState appState;
    int failures;
    try {
      appState = runner.getApplicationState();
      updateState(JobState.getJobState(appState));
      //count how many consecutive times the state could not be polled. Cancel if too much.
      failures = 0;
    } catch (YarnException | IOException ex) {
      logger.log(Level.WARNING, "Failed to get application state for job id "
              + getJobId(), ex);
      appState = null;
      failures = 1;
    }

    //Loop as long as the application is in a running/runnable state
    while (appState != YarnApplicationState.FAILED && appState
            != YarnApplicationState.FINISHED && appState
            != YarnApplicationState.KILLED && failures
            <= DEFAULT_MAX_STATE_POLL_RETRIES) {
      //wait to poll another time
      long startTime = System.currentTimeMillis();
      while ((System.currentTimeMillis() - startTime)
              < DEFAULT_POLL_TIMEOUT_INTERVAL * 1000) {
        try {
          Thread.sleep(200);
        } catch (InterruptedException e) {
          //not much...
        }
      }

      try {
        appState = runner.getApplicationState();
        updateState(JobState.getJobState(appState));
        failures = 0;
      } catch (YarnException | IOException ex) {
        failures++;
        logger.log(Level.WARNING, "Failed to get application state for job id "
                + getJobId() + ". Tried " + failures + " time(s).", ex);
      }
    }

    if (failures > DEFAULT_MAX_STATE_POLL_RETRIES) {
      try {
        logger.log(Level.SEVERE,
                "Killing application, jobId {0}, because unable to poll for status.",
                getJobId());
        runner.cancelJob();
        updateState(JobState.KILLED);
      } catch (YarnException | IOException ex) {
        logger.log(Level.SEVERE,
                "Failed to cancel job, jobId " + getJobId()
                + " after failing to poll for status.", ex);
        updateState(JobState.FRAMEWORK_FAILURE);
      }
      return false;
    }
    return true;
  }

  private void updateState(JobState newState) {
    getJobHistoryFacade().update(getJobId(), newState);
  }

  private void copyLogs() {
    //TODO: does not make too much sense...
    try {
      if (stdOutFinalDestination != null && !stdOutFinalDestination.isEmpty()) {
        if (!runner.areLogPathsHdfs()) {
          fops.copyToHDFSFromPath(runner.getStdOutPath(),
                  stdOutFinalDestination,
                  null);
          getJobHistoryFacade().updateStdOutPath(getJobId(),
                  stdOutFinalDestination);
        } else {
          getJobHistoryFacade().updateStdOutPath(getJobId(),
                  runner.getStdOutPath());
        }

      }
      if (stdErrFinalDestination != null && !stdErrFinalDestination.isEmpty()) {
        if (!runner.areLogPathsHdfs()) {
          fops.copyToHDFSFromPath(runner.getStdErrPath(),
                  stdErrFinalDestination,
                  null);
          getJobHistoryFacade().updateStdErrPath(getJobId(),
                  stdErrFinalDestination);
        } else {
          getJobHistoryFacade().updateStdErrPath(getJobId(),
                  runner.getStdErrPath());
        }
      }
    } catch (IOException e) {
      //TODO: figure out how to handle this
      logger.log(Level.SEVERE, "Exception while trying to write logs for job "
              + getJobId() + " to HDFS.", e);
    }
  }

  private void processOutput() {
    try {
      String resultsPath = runner.getLocalResourcesBasePath() + File.separator
              + summaryPath;
      String json = fops.cat(resultsPath);
      JSONObject jobj = new JSONObject(json);
      JSONArray outputpaths = jobj.getJSONArray("output");
      for (int i = 0; i < outputpaths.length(); i++) {
        String outfile = outputpaths.getString(i);
        JobOutputFile file = new JobOutputFile(getJobId(), Utils.getFileName(
                outfile));
        file.setPath(outfile);
        getJobHistoryFacade().persist(file);
      }
    } catch (IOException | JSONException e) {
      logger.log(Level.SEVERE,
              "Failed to copy output files after running Cuneiform job "
              + getJobId(), e);
    }
  }
}
