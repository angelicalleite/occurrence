/*
 * Copyright 2012 Global Biodiversity Information Facility (GBIF)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gbif.occurrence.download.service;

import org.gbif.api.exception.ServiceUnavailableException;
import org.gbif.api.model.occurrence.Download;
import org.gbif.api.model.occurrence.DownloadRequest;
import org.gbif.api.service.occurrence.DownloadRequestService;
import org.gbif.api.service.registry.OccurrenceDownloadService;
import org.gbif.occurrence.common.download.DownloadUtils;
import org.gbif.occurrence.download.service.workflow.DownloadWorkflowParametersBuilder;
import org.gbif.ws.response.GbifResponseStatus;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.Map;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Enums;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.sun.jersey.api.NotFoundException;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import org.apache.oozie.client.Job;
import org.apache.oozie.client.OozieClient;
import org.apache.oozie.client.OozieClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.gbif.occurrence.common.download.DownloadUtils.downloadLink;
import static org.gbif.occurrence.download.service.Constants.NOTIFY_ADMIN;

@Singleton
public class DownloadRequestServiceImpl implements DownloadRequestService, CallbackService {

  private static final Logger LOG = LoggerFactory.getLogger(DownloadRequestServiceImpl.class);
  // magic prefix for download keys to indicate these aren't real download files
  private static final String NON_DOWNLOAD_PREFIX = "dwca-";

  public static final EnumSet<Download.Status> RUNNING_STATUSES = EnumSet.of(Download.Status.PREPARING,
                                                                             Download.Status.RUNNING,
                                                                             Download.Status.SUSPENDED);

  //Next variables are used for the tryFileExist function
  private static int FILE_EXISTS_RETRIES = 3;
  private static long FILE_EXISTS_WAITING = 10000;

  /**
   * Map to provide conversions from oozie.Job.Status to Download.Status.
   */
  @VisibleForTesting
  protected static final ImmutableMap<Job.Status, Download.Status> STATUSES_MAP =
    new ImmutableMap.Builder<Job.Status, Download.Status>()
      .put(Job.Status.PREP, Download.Status.PREPARING)
      .put(Job.Status.PREPPAUSED, Download.Status.PREPARING)
      .put(Job.Status.PREMATER, Download.Status.PREPARING)
      .put(Job.Status.PREPSUSPENDED, Download.Status.SUSPENDED)
      .put(Job.Status.RUNNING, Download.Status.RUNNING)
      .put(Job.Status.KILLED, Download.Status.KILLED)
      .put(Job.Status.RUNNINGWITHERROR, Download.Status.RUNNING)
      .put(Job.Status.DONEWITHERROR, Download.Status.FAILED)
      .put(Job.Status.FAILED, Download.Status.FAILED)
      .put(Job.Status.PAUSED, Download.Status.RUNNING)
      .put(Job.Status.PAUSEDWITHERROR, Download.Status.RUNNING)
      .put(Job.Status.SUCCEEDED, Download.Status.SUCCEEDED)
      .put(Job.Status.SUSPENDED, Download.Status.SUSPENDED)
      .put(Job.Status.SUSPENDEDWITHERROR, Download.Status.SUSPENDED)
      .put(Job.Status.IGNORED, Download.Status.FAILED).build();

  private static final Counter SUCCESSFUL_DOWNLOADS = Metrics.newCounter(CallbackService.class, "successful_downloads");
  private static final Counter FAILED_DOWNLOADS = Metrics.newCounter(CallbackService.class, "failed_downloads");
  private static final Counter CANCELLED_DOWNLOADS = Metrics.newCounter(CallbackService.class, "cancelled_downloads");


  private final OozieClient client;
  private final String wsUrl;
  private final File downloadMount;
  private final OccurrenceDownloadService occurrenceDownloadService;
  private final DownloadEmailUtils downloadEmailUtils;
  private final DownloadWorkflowParametersBuilder parametersBuilder;

  private final DownloadLimitsService downloadLimitsService;


  @Inject
  public DownloadRequestServiceImpl(OozieClient client,
                                    @Named("oozie.default_properties") Map<String, String> defaultProperties,
                                    @Named("ws.url") String wsUrl,
                                    @Named("ws.mount") String wsMountDir,
                                    OccurrenceDownloadService occurrenceDownloadService,
                                    DownloadEmailUtils downloadEmailUtils,
                                    DownloadLimitsService downloadLimitsService) {

    this.client = client;
    this.wsUrl = wsUrl;
    downloadMount = new File(wsMountDir);
    this.occurrenceDownloadService = occurrenceDownloadService;
    this.downloadEmailUtils = downloadEmailUtils;
    parametersBuilder = new DownloadWorkflowParametersBuilder(defaultProperties);
    this.downloadLimitsService = downloadLimitsService;
  }

  @Override
  public void cancel(String downloadKey) {
    try {
      Download download = occurrenceDownloadService.get(downloadKey);
      if (download != null) {
        if (RUNNING_STATUSES.contains(download.getStatus())) {
          updateDownloadStatus(download, Download.Status.CANCELLED);
          client.kill(DownloadUtils.downloadToWorkflowId(downloadKey));
          LOG.info("Download {} canceled", downloadKey);
        }
      } else {
        throw new NotFoundException(String.format("Download %s not found", downloadKey));
      }
    } catch (OozieClientException e) {
      throw new ServiceUnavailableException("Failed to cancel download " + downloadKey, e);
    }
  }

  @Override
  public String create(DownloadRequest request) {
    LOG.debug("Trying to create download from request [{}]", request);
    Preconditions.checkNotNull(request);
    try {
      if (!downloadLimitsService.isInDownloadLimits(request.getCreator())) {
        throw new WebApplicationException(Response.status(GbifResponseStatus.ENHANCE_YOUR_CALM.getStatus()).build());
      }
      String jobId = client.run(parametersBuilder.buildWorkflowParameters(request));
      LOG.debug("oozie job id is: [{}]", jobId);
      String downloadId = DownloadUtils.workflowToDownloadId(jobId);
      persistDownload(request, downloadId);
      return downloadId;
    } catch (OozieClientException e) {
      throw new ServiceUnavailableException("Failed to create download job", e);
    }

  }


  @Override
  public InputStream getResult(String downloadKey) {
    // avoid check for download in the registry if we have secret non download files with a magic prefix!
    if (downloadKey == null || !downloadKey.toLowerCase().startsWith(NON_DOWNLOAD_PREFIX)) {
      Download d = occurrenceDownloadService.get(downloadKey);

      if (d == null) {
        throw new NotFoundException("Download " + downloadKey + " doesn't exist");
      }

      if (!d.isAvailable()) {
        throw new NotFoundException("Download " + downloadKey + " is not ready yet");
      }
    }

    File localFile = new File(downloadMount, downloadKey + ".zip");
    try {
      return new FileInputStream(localFile);

    } catch (IOException e) {
      throw new IllegalStateException(
        "Failed to read download " + downloadKey + " from " + localFile.getAbsolutePath(), e);
    }
  }

  /**
   * Processes a callback from Oozie which update the download status.
   */
  @Override
  public void processCallback(String jobId, String status) {
    Preconditions.checkNotNull(Strings.isNullOrEmpty(jobId), "<jobId> may not be null or empty");
    Preconditions.checkNotNull(Strings.isNullOrEmpty(status), "<status> may not be null or empty");
    Optional<Job.Status> opStatus = Enums.getIfPresent(Job.Status.class, status.toUpperCase());
    Preconditions.checkArgument(opStatus.isPresent(), "<status> the requested status is not valid");
    String downloadId = DownloadUtils.workflowToDownloadId(jobId);

    LOG.debug("Processing callback for jobId [{}] with status [{}]", jobId, status);

    Download download = occurrenceDownloadService.get(downloadId);
    if (download == null) {
      // Download can be null if the oozie reports status before the download is persisted
      LOG.info(String.format("Download [%s] not found [Oozie may be issuing callback before download persisted]", downloadId));
      return;
    }

    Download.Status newStatus = STATUSES_MAP.get(opStatus.get());
    switch (newStatus) {
      case KILLED:
        // Keep a manually cancelled download status as opposed to a killed one
        if (download.getStatus() == Download.Status.CANCELLED) {
          CANCELLED_DOWNLOADS.inc();
          return;
        }

      case FAILED:
        LOG.error(NOTIFY_ADMIN, "Got callback for failed query. JobId [{}], Status [{}]", jobId, status);
        updateDownloadStatus(download, newStatus);
        downloadEmailUtils.sendErrorNotificationMail(download);
        FAILED_DOWNLOADS.inc();
        break;

      case SUCCEEDED:
        SUCCESSFUL_DOWNLOADS.inc();
        updateDownloadStatus(download, newStatus);
        // notify about download
        if (download.getRequest().getSendNotification()) {
          downloadEmailUtils.sendSuccessNotificationMail(download);
        }
        break;

      default:
        updateDownloadStatus(download, newStatus);
        break;
    }
  }

  /**
   * Returns the download size in bytes.
   */
  private Long getDownloadSize(String downloadKey) {
    File downloadFile = new File(downloadMount, downloadKey + ".zip");
    if(downloadFile.exists()) {
      return downloadFile.length();
    }
    LOG.warn("Download file not found {}", downloadFile.getName());
    return 0L;
  }

  /**
   * Persists the download information.
   */
  private void persistDownload(DownloadRequest request, String downloadId) {
    Download download = new Download();
    download.setKey(downloadId);
    download.setRequest(request);
    download.setStatus(Download.Status.PREPARING);
    download.setDownloadLink(downloadLink(wsUrl, downloadId));
    occurrenceDownloadService.create(download);
  }


  /**
   * Updates the download status and file size.
   */
  private void updateDownloadStatus(Download download, Download.Status newStatus) {
    download.setStatus(newStatus);
    download.setSize(getDownloadSize(download.getKey()));
    occurrenceDownloadService.update(download);
  }
}
