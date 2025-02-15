package com.eko;

import android.annotation.SuppressLint;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import android.app.DownloadManager;
import android.app.DownloadManager.Request;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import javax.annotation.Nullable;

import java.util.Set;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.LongSparseArray;

import com.facebook.react.bridge.Callback;

import java.util.ArrayList;
import java.util.Arrays;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.database.Cursor;
import android.os.Build;
import android.os.Environment;

public class RNBackgroundDownloaderModule extends ReactContextBaseJavaModule {

  private static final int TASK_RUNNING = 0;
  private static final int TASK_SUSPENDED = 1;
  private static final int TASK_CANCELING = 2;
  private static final int TASK_COMPLETED = 3;

  private static final int ERR_STORAGE_FULL = 0;
  private static final int ERR_NO_INTERNET = 1;
  private static final int ERR_NO_WRITE_PERMISSION = 2;
  private static final int ERR_FILE_NOT_FOUND = 3;
  private static final int ERR_OTHERS = 100;

  private static Map<Integer, Integer> stateMap = new HashMap<Integer, Integer>() {
    {
      put(DownloadManager.STATUS_FAILED, TASK_CANCELING);
      put(DownloadManager.STATUS_PAUSED, TASK_SUSPENDED);
      put(DownloadManager.STATUS_PENDING, TASK_RUNNING);
      put(DownloadManager.STATUS_RUNNING, TASK_RUNNING);
      put(DownloadManager.STATUS_SUCCESSFUL, TASK_COMPLETED);
    }
  };

  private Downloader downloader;

  private Map<String, Long> configIdToDownloadId = new HashMap<>();
  private Map<Long, RNBGDTaskConfig> downloadIdToConfig = new HashMap<>();
  private Map<String, Double> configIdToPercent = new HashMap<>();
  private Map<String, WritableMap> progressReports = new HashMap<>();
  private Date lastProgressReportedAt = new Date();
  private int progressInterval;
  private DeviceEventManagerModule.RCTDeviceEventEmitter ee;
  private Map<String, OnProgress> onProgressThreads = new HashMap<>();

  private static Object sharedLock = new Object();

  BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      try {
        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);

        RNBGDTaskConfig config = downloadIdToConfig.get(downloadId);

        if (config != null) {
          WritableMap downloadStatus = downloader.checkDownloadStatus(downloadId);
          int status = downloadStatus.getInt("status");


          stopTrackingProgress(config.id);

          synchronized (sharedLock) {
            switch (status) {
              case DownloadManager.STATUS_SUCCESSFUL: {
                // MOVES FILE TO DESTINATION
                String localUri = downloadStatus.getString("localUri");
                File file = new File(localUri);
                File dest = new File(config.destination);
                Files.move(file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);

                WritableMap params = Arguments.createMap();
                params.putString("id", config.id);
                params.putString("location", config.destination);
                params.putInt("bytesDownloaded", downloadStatus.getInt("bytesDownloaded"));
                params.putInt("bytesTotal", downloadStatus.getInt("bytesTotal"));

                ee.emit("downloadComplete", params);
                break;
              }
              case DownloadManager.STATUS_FAILED: {
                Log.e(getName(), "Error in enqueue: " + downloadStatus.getInt("status") + ":"
                    + downloadStatus.getInt("reason") + ":" + downloadStatus.getString("reasonText"));

                WritableMap params = Arguments.createMap();
                params.putString("id", config.id);
                params.putInt("errorCode", downloadStatus.getInt("reason"));
                params.putString("error", downloadStatus.getString("reasonText"));
                ee.emit("downloadFailed", params);
                break;
              }
            }
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
        Log.e(getName(), "downloadReceiver: onReceive. " + Log.getStackTraceString(e));
      }
    }
  };

  public RNBackgroundDownloaderModule(ReactApplicationContext reactContext) {
    super(reactContext);

    loadDownloadIdToConfigMap();
    loadConfigMap();

    downloader = new Downloader(reactContext);

    IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
    compatRegisterReceiver(reactContext, downloadReceiver, filter, true);

    // iterate over downloadIdToConfig
    for(Map.Entry<Long, RNBGDTaskConfig> entry : downloadIdToConfig.entrySet()) {
      Long downloadId = entry.getKey();
      RNBGDTaskConfig config = entry.getValue();

      startReportingTasks(downloadId, config);
    }
  }

  // TAKEN FROM
  // https://github.com/facebook/react-native/pull/38256/files#diff-d5e21477eeadeb0c536d5870f487a8528f9a16ae928c397fec7b255805cc8ad3
  private void compatRegisterReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter,
      boolean exported) {
    if (Build.VERSION.SDK_INT >= 34 && context.getApplicationInfo().targetSdkVersion >= 34) {
      context.registerReceiver(
          receiver, filter, exported ? Context.RECEIVER_EXPORTED : Context.RECEIVER_NOT_EXPORTED);
    } else {
      context.registerReceiver(receiver, filter);
    }
  }

  private void stopTrackingProgress(String configId) {
    OnProgress onProgressTh = onProgressThreads.get(configId);
    if (onProgressTh != null) {
      onProgressTh.interrupt();
      onProgressThreads.remove(configId);
      configIdToPercent.remove(configId);
    }
  }

  @Override
  public void onCatalystInstanceDestroy() {
  }

  @Override
  public String getName() {
    return "RNBackgroundDownloader";
  }

  @Override
  public void initialize() {
    ee = getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);
  }

  @ReactMethod
  public void addListener(String eventName) {
    // Keep: Required for RN built in Event Emitter Calls.
  }

  @ReactMethod
  public void removeListeners(Integer count) {
    // Keep: Required for RN built in Event Emitter Calls.
  }

  @Nullable
  @Override
  public Map<String, Object> getConstants() {
    Map<String, Object> constants = new HashMap<>();
    File externalDirectory = this.getReactApplicationContext().getExternalFilesDir(null);
    if (externalDirectory != null) {
      constants.put("documents", externalDirectory.getAbsolutePath());
    } else {
      constants.put("documents", this.getReactApplicationContext().getFilesDir().getAbsolutePath());
    }

    constants.put("TaskRunning", TASK_RUNNING);
    constants.put("TaskSuspended", TASK_SUSPENDED);
    constants.put("TaskCanceling", TASK_CANCELING);
    constants.put("TaskCompleted", TASK_COMPLETED);

    return constants;
  }

  private void removeFromMaps(long downloadId) {
    synchronized (sharedLock) {
      RNBGDTaskConfig config = downloadIdToConfig.get(downloadId);
      if (config != null) {
        configIdToDownloadId.remove(config.id);
        downloadIdToConfig.remove(downloadId);
        configIdToPercent.remove(config.id);

        saveDownloadIdToConfigMap();
      }
    }
  }

  private void saveDownloadIdToConfigMap() {
    synchronized (sharedLock) {
      File file = new File(this.getReactApplicationContext().getFilesDir(), getName() + "_downloadIdToConfig");
      try {
        ObjectOutputStream outputStream = new ObjectOutputStream(new FileOutputStream(file));
        outputStream.writeObject(downloadIdToConfig);
        outputStream.flush();
        outputStream.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private void loadDownloadIdToConfigMap() {
    File file = new File(this.getReactApplicationContext().getFilesDir(), getName() + "_downloadIdToConfig");
    if (file.exists()) {
      try {
        ObjectInputStream inputStream = new ObjectInputStream(new FileInputStream(file));
        downloadIdToConfig = (Map<Long, RNBGDTaskConfig>) inputStream.readObject();
      } catch (IOException | ClassNotFoundException e) {
        e.printStackTrace();
      }
    }
  }

  private void saveConfigMap () {
    synchronized (sharedLock) {
      File file = new File(this.getReactApplicationContext().getFilesDir(), getName() + "_config");
      try {
        ObjectOutputStream outputStream = new ObjectOutputStream(new FileOutputStream(file));

        Map<String, String> config = new HashMap<>();
        config.put("progressInterval", Integer.toString(progressInterval));
        outputStream.writeObject(config);
        outputStream.flush();
        outputStream.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private void loadConfigMap () {
    File file = new File(this.getReactApplicationContext().getFilesDir(), getName() + "_config");
    if (file.exists()) {
      try {
        ObjectInputStream inputStream = new ObjectInputStream(new FileInputStream(file));
        Map<String, Object> config = (HashMap<String, Object>) inputStream.readObject();
        // iterate over config
        for(Map.Entry<String, Object> entry : config.entrySet()) {
          String key = entry.getKey();

          Object valueObj = entry.getValue();
          String value = null;
          if (valueObj instanceof Long) {
            value = Long.toString((Long) valueObj);
          } else if (valueObj instanceof String) {
            value = (String) valueObj;
          }

          if (key.equals("progressInterval") && value != null) {
            try {
              int _progressInterval = Integer.parseInt(value);
              if (_progressInterval > 0) {
                progressInterval = _progressInterval;
              }
            } catch (NumberFormatException e) {
              e.printStackTrace();
            }
          }
        }
      } catch (IOException | ClassNotFoundException e) {
        e.printStackTrace();
      }
    }
  }

  // JS Methods
  @ReactMethod
  public void download(ReadableMap options) {
    final String id = options.getString("id");
    String url = options.getString("url");
    String destination = options.getString("destination");
    ReadableMap headers = options.getMap("headers");
    String metadata = options.getString("metadata");
    int _progressInterval = options.getInt("progressInterval");
    if (_progressInterval > 0) {
      progressInterval = _progressInterval;
      saveConfigMap();
    }

    boolean isAllowedOverRoaming = options.getBoolean("isAllowedOverRoaming");
    boolean isAllowedOverMetered = options.getBoolean("isAllowedOverMetered");

    if (id == null || url == null || destination == null) {
      Log.e(getName(), "id, url and destination must be set");
      return;
    }

    RNBGDTaskConfig config = new RNBGDTaskConfig(id, url, destination, metadata);
    final Request request = new Request(Uri.parse(url));
    request.setAllowedOverRoaming(isAllowedOverRoaming);
    request.setAllowedOverMetered(isAllowedOverMetered);
    request.setNotificationVisibility(Request.VISIBILITY_HIDDEN);
    request.setRequiresCharging(false);

    int uuid = (int) (System.currentTimeMillis() & 0xfffffff);
    // GETS THE FILE EXTENSION FROM PATH
    String extension = MimeTypeMap.getFileExtensionFromUrl(destination);

    String fileName = uuid + "." + extension;
    request.setDestinationInExternalFilesDir(this.getReactApplicationContext(), null, fileName);

    // TOREMOVE
    // request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS.toString(), fileName);
    // request.setDestinationUri(Uri.parse(destination));

    if (headers != null) {
      ReadableMapKeySetIterator it = headers.keySetIterator();
      while (it.hasNextKey()) {
        String headerKey = it.nextKey();
        request.addRequestHeader(headerKey, headers.getString(headerKey));
      }
    }

    long downloadId = downloader.queueDownload(request);

    synchronized (sharedLock) {
      configIdToDownloadId.put(id, downloadId);
      downloadIdToConfig.put(downloadId, config);
      configIdToPercent.put(id, 0.0);
      saveDownloadIdToConfigMap();

      WritableMap downloadStatus = downloader.checkDownloadStatus(downloadId);
      int status = downloadStatus.getInt("status");

      if (config.reportedBegin) {
        return;
      }

      startReportingTasks(downloadId, config);
    }
  }

  private void startReportingTasks(Long downloadId, RNBGDTaskConfig config) {
    config.reportedBegin = true;
    downloadIdToConfig.put(downloadId, config);
    saveDownloadIdToConfigMap();

    // report begin & progress
    //
    // overlaped with thread to not block main thread
    new Thread(new Runnable() {
      public void run() {
        try {
          while (true) {
            WritableMap downloadStatus = downloader.checkDownloadStatus(downloadId);
            int status = downloadStatus.getInt("status");

            if (status == DownloadManager.STATUS_RUNNING) {
              break;
            }
            if (status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_SUCCESSFUL) {
              Thread.currentThread().interrupt();
            }

            Thread.sleep(500);
          }

          // EMIT BEGIN
          OnBegin onBeginTh = new OnBegin(config, ee);
          onBeginTh.start();
          // wait for onBeginTh to finish
          onBeginTh.join();

          OnProgress onProgressTh = new OnProgress(
            config,
            downloadId,
            downloader,
            new ProgressCallback() {
              @Override
              public void onProgress(String configId, int bytesDownloaded, int bytesTotal) {
                double prevPercent = configIdToPercent.get(configId);
                double percent = (double) bytesDownloaded / bytesTotal;
                if (percent - prevPercent > 0.01) {
                  WritableMap params = Arguments.createMap();
                  params.putString("id", configId);
                  params.putInt("bytesDownloaded", bytesDownloaded);
                  params.putInt("bytesTotal", bytesTotal);

                  progressReports.put(configId, params);
                  configIdToPercent.put(configId, percent);
                }

                Date now = new Date();
                if (
                  now.getTime() - lastProgressReportedAt.getTime() > progressInterval &&
                  progressReports.size() > 0
                ) {
                  WritableArray reportsArray = Arguments.createArray();
                  for (Object report : progressReports.values()) {
                    reportsArray.pushMap((WritableMap) report);
                  }
                  ee.emit("downloadProgress", reportsArray);
                  lastProgressReportedAt = now;
                  progressReports.clear();
                }
              }
            }
           );
          onProgressThreads.put(config.id, onProgressTh);
          onProgressTh.start();
        } catch (Exception e) {
          e.printStackTrace();
          Log.e(getName(), "RNBD: Runnable e: " + Log.getStackTraceString(e));
        }
      }
    }).start();
  }

  // TODO: NOT WORKING WITH DownloadManager FOR NOW
  @ReactMethod
  public void pauseTask(String configId) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        downloader.pauseDownload(downloadId);
      }
    }
  }

  // TODO: NOT WORKING WITH DownloadManager FOR NOW
  @ReactMethod
  public void resumeTask(String configId) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        downloader.resumeDownload(downloadId);
      }
    }
  }

  @ReactMethod
  public void stopTask(String configId) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        // DELETES CONFIG HERE SO receiver WILL NOT THROW ERROR DOWNLOAD_FAILED TO THE
        // USER
        removeFromMaps(downloadId);
        stopTrackingProgress(configId);

        downloader.cancelDownload(downloadId);
      }
    }
  }

  @ReactMethod
  public void completeHandler(String configId, final Promise promise) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        removeFromMaps(downloadId);
        // REMOVES DOWNLOAD FROM DownloadManager SO IT WOULD NOT BE RETURNED IN checkForExistingDownloads
        downloader.cancelDownload(downloadId);
      }
    }
  }

  @ReactMethod
  public void checkForExistingDownloads(final Promise promise) {
    WritableArray foundIds = Arguments.createArray();

    synchronized (sharedLock) {
      try {
        DownloadManager.Query downloadQuery = new DownloadManager.Query();
        Cursor cursor = downloader.downloadManager.query(downloadQuery);

        if (cursor.moveToFirst()) {
          do {
            WritableMap downloadStatus = downloader.getDownloadStatus(cursor);
            Long downloadId = Long.parseLong(downloadStatus.getString("downloadId"));

            if (downloadIdToConfig.containsKey(downloadId)) {
              RNBGDTaskConfig config = downloadIdToConfig.get(downloadId);
              WritableMap params = Arguments.createMap();
              params.putString("id", config.id);
              params.putString("metadata", config.metadata);
              params.putInt("state", stateMap.get(downloadStatus.getInt("status")));

              int bytesDownloaded = downloadStatus.getInt("bytesDownloaded");
              params.putInt("bytesDownloaded", bytesDownloaded);

              int bytesTotal = downloadStatus.getInt("bytesTotal");
              params.putInt("bytesTotal", bytesTotal);

              foundIds.pushMap(params);

              // TODO: MAYBE ADD headers

              configIdToDownloadId.put(config.id, downloadId);
              configIdToPercent.put(config.id, (double) bytesDownloaded / bytesTotal);

              // TOREMOVE
              // config.reportedBegin = true;
            } else {
              downloader.cancelDownload(downloadId);
            }
          } while (cursor.moveToNext());
        }

        cursor.close();
      } catch (Exception e) {
        e.printStackTrace();
        Log.e(getName(), "CheckForExistingDownloads e: " + Log.getStackTraceString(e));
      }
    }

    promise.resolve(foundIds);
  }
}
