/****************************************************************************
 * Copyright 2010 kraigs.android@gmail.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ****************************************************************************/

package io.github.carlorodriguez.alarmon;

import java.util.HashMap;
import java.util.Map;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.widget.Toast;

import org.apache.commons.lang3.text.StrSubstitutor;

public final class AlarmClockService extends Service {
  public final static String COMMAND_EXTRA = "command";
  public final static int COMMAND_UNKNOWN = 1;
  public final static int COMMAND_NOTIFICATION_REFRESH = 2;
  public final static int COMMAND_DEVICE_BOOT = 3;
  public final static int COMMAND_TIMEZONE_CHANGE = 4;

  public final static int NOTIFICATION_BAR_ID = 69;

  private DbAccessor db;
  private PendingAlarmList pendingAlarms;

  @Override
  public void onCreate() {
    super.onCreate();
    // Registers an exception handler of capable of writing the stack trace
    // to the device's SD card.  This is only possible if the proper
    // permissions are available.
    if (getPackageManager().checkPermission(
        "android.permission.WRITE_EXTERNAL_STORAGE", getPackageName()) ==
          PackageManager.PERMISSION_GRANTED) {
      Thread.setDefaultUncaughtExceptionHandler(
          new LoggingUncaughtExceptionHandler(
                  Environment.getExternalStorageDirectory().getPath()));
    }

    // Access to in-memory and persistent data structures.
    db = new DbAccessor(getApplicationContext());
    pendingAlarms = new PendingAlarmList(getApplicationContext());

    // Schedule enabled alarms during initial startup.
    for (Long alarmId : db.getEnabledAlarms()) {
      if (pendingAlarms.pendingTime(alarmId) != null) {
        continue;
      }
      if (AppSettings.isDebugMode(getApplicationContext())) {
        Toast.makeText(getApplicationContext(), "RENABLE " + alarmId, Toast.LENGTH_SHORT).show();
      }

        AlarmTime alarmTime = null;

        AlarmInfo info = db.readAlarmInfo(alarmId);

        if (info != null) {
            alarmTime = info.getTime();
        }

      pendingAlarms.put(alarmId, alarmTime);
    }

    ReceiverNotificationRefresh.startRefreshing(getApplicationContext());
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    handleStart(intent);
    return START_STICKY;
  }

  private void handleStart(Intent intent) {
    if (intent != null && intent.hasExtra(COMMAND_EXTRA)) {
      Bundle extras = intent.getExtras();
      int command = extras.getInt(COMMAND_EXTRA, COMMAND_UNKNOWN);

      final Handler handler = new Handler();
      final Runnable maybeShutdown = new Runnable() {
        @Override
        public void run() {
          if (pendingAlarms.size() == 0) {
            stopSelf();
          }
        }
      };

      switch (command) {
        case COMMAND_NOTIFICATION_REFRESH:
          refreshNotification();
          handler.post(maybeShutdown);
          break;
        case COMMAND_DEVICE_BOOT:
          fixPersistentSettings();
          handler.post(maybeShutdown);
          break;
        case COMMAND_TIMEZONE_CHANGE:
          if (AppSettings.isDebugMode(getApplicationContext())) {
            Toast.makeText(getApplicationContext(), "TIMEZONE CHANGE, RESCHEDULING...", Toast.LENGTH_SHORT).show();
          }
          for (long alarmId : pendingAlarms.pendingAlarms()) {
            scheduleAlarm(alarmId);
            if (AppSettings.isDebugMode(getApplicationContext())) {
              Toast.makeText(getApplicationContext(), "ALARM " + alarmId, Toast.LENGTH_SHORT).show();
            }
          }
          handler.post(maybeShutdown);
          break;
        default:
          throw new IllegalArgumentException("Unknown service command.");
      }
    }
  }

  private void refreshNotification() {
      String resolvedString = getString(R.string.no_pending_alarms);

      AlarmTime nextTime = pendingAlarms.nextAlarmTime();

      if (nextTime != null) {
          Map<String, String> values = new HashMap<>();

          values.put("t", nextTime.localizedString(getApplicationContext()));

          values.put("c", nextTime.timeUntilString(getApplicationContext()));

          String templateString = AppSettings.getNotificationTemplate(
                  getApplicationContext());

          StrSubstitutor sub = new StrSubstitutor(values);

          resolvedString = sub.replace(templateString);
      }

    // Make the notification launch the UI Activity when clicked.
    final Intent notificationIntent = new Intent(this, ActivityAlarmClock.class);
    final PendingIntent launch = PendingIntent.getActivity(this, 0,
        notificationIntent, 0);

    Context c = getApplicationContext();

      NotificationCompat.Builder builder = new NotificationCompat.Builder(
              getApplicationContext());

      String notificationTitle = getString(R.string.app_name);

      if (pendingAlarms.nextAlarmId() != AlarmClockServiceBinder.NO_ALARM_ID) {
          DbAccessor db = new DbAccessor(getApplicationContext());

          AlarmInfo alarmInfo = db.readAlarmInfo(pendingAlarms.nextAlarmId());

          if (alarmInfo != null) {
              notificationTitle = alarmInfo.getName() != null && !alarmInfo.getName().isEmpty()
                      ? alarmInfo.getName()
                      : getString(R.string.app_name);
          }

          db.closeConnections();
      }

      Notification notification = builder
              .setContentIntent(launch)
              .setSmallIcon(R.drawable.ic_stat_notify_alarm)
              .setContentTitle(notificationTitle)
              .setContentText(resolvedString)
              .setColor(ContextCompat.getColor(getApplicationContext(),
                      R.color.notification_color))
              .build();
      notification.flags |= Notification.FLAG_ONGOING_EVENT;

    final NotificationManager manager =
      (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (pendingAlarms.size() > 0 && AppSettings.displayNotificationIcon(c)) {
      manager.notify(NOTIFICATION_BAR_ID, notification);
    } else {
      manager.cancel(NOTIFICATION_BAR_ID);
    }

    setSystemAlarmStringOnLockScreen(getApplicationContext(), nextTime);
  }

    @SuppressWarnings("deprecation")
    public static void setSystemAlarmStringOnLockScreen(Context context,
            AlarmTime alarmTime) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            String lockScreenText = AppSettings.lockScreenString(
                    context, alarmTime);

            if (lockScreenText != null) {
                Settings.System.putString(context.getContentResolver(),
                        Settings.System.NEXT_ALARM_FORMATTED, lockScreenText);
            }
        }
    }

  // This hack is necessary b/c I released a version of the code with a bunch
  // of errors in the settings strings.  This should correct them.
  public void fixPersistentSettings() {
    final String badDebugName = "DEBUG_MODE\"";
    final String badNotificationName = "NOTFICATION_ICON";
    final String badLockScreenName = "LOCK_SCREEN\"";
    final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    Map<String, ?> prefNames = prefs.getAll();
    // Don't do anything if the bad preferences have already been fixed.
    if (!prefNames.containsKey(badDebugName) &&
        !prefNames.containsKey(badNotificationName) &&
        !prefNames.containsKey(badLockScreenName)) {
      return;
    }
    Editor editor = prefs.edit();
    if (prefNames.containsKey(badDebugName)) {
      editor.putString(AppSettings.DEBUG_MODE, prefs.getString(badDebugName, null));
      editor.remove(badDebugName);
    }
    if (prefNames.containsKey(badNotificationName)){
      editor.putBoolean(AppSettings.NOTIFICATION_ICON, prefs.getBoolean(badNotificationName, true));
      editor.remove(badNotificationName);
    }
    if (prefNames.containsKey(badLockScreenName)) {
      editor.putString(AppSettings.LOCK_SCREEN, prefs.getString(badLockScreenName, null));
      editor.remove(badLockScreenName);
    }
    editor.apply();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    db.closeConnections();

    ReceiverNotificationRefresh.stopRefreshing(getApplicationContext());

    final NotificationManager manager =
      (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    manager.cancel(NOTIFICATION_BAR_ID);

      setSystemAlarmStringOnLockScreen(getApplicationContext(), null);
  }

  @Override
  public IBinder onBind(Intent intent) {
    return new AlarmClockInterfaceStub(getApplicationContext(), this);
  }

  @Override
  public boolean onUnbind(Intent intent) {
    // Decide if we need to explicitly shut down this service. Normally,
    // the service would shutdown after the last un-bind, but it was explicitly
    // started in onBind(). If there are no pending alarms, explicitly stop
    // the service.
    if (pendingAlarms.size() == 0) {
      stopSelf();
      return false;
    }
    // Returning true causes the IBinder object to be re-used until the
    // service is actually shutdown.
    return true;
  }

  public AlarmTime pendingAlarm(long alarmId) {
    return pendingAlarms.pendingTime(alarmId);
  }

  public AlarmTime[] pendingAlarmTimes() {
    return pendingAlarms.pendingTimes();
  }

    public long resurrectAlarm(AlarmTime time, String alarmName, boolean enabled) {
        long alarmId =  db.newAlarm(time, enabled, alarmName);

        if (enabled) {
            scheduleAlarm(alarmId);
        }

        return alarmId;
    }

  public void createAlarm(AlarmTime time) {
    // Store the alarm in the persistent database.
    long alarmId = db.newAlarm(time, true, "");
    scheduleAlarm(alarmId);
  }

    public void deleteAlarm(long alarmId) {
        pendingAlarms.remove(alarmId);

        db.deleteAlarm(alarmId);

        refreshNotification();
    }

  public void deleteAllAlarms() {
    for (Long alarmId : db.getAllAlarms()) {
      deleteAlarm(alarmId);
    }
  }

  public void scheduleAlarm(long alarmId) {
    AlarmInfo info = db.readAlarmInfo(alarmId);
    if (info == null) {
      return;
    }
    // Schedule the next alarm.
    pendingAlarms.put(alarmId, info.getTime());

    // Mark the alarm as enabled in the database.
    db.enableAlarm(alarmId, true);

    // Now that there is more than one pending alarm, explicitly start the
    // service so that it continues to run after binding.
    final Intent self = new Intent(getApplicationContext(), AlarmClockService.class);
    startService(self);

    refreshNotification();
  }

  public void acknowledgeAlarm(long alarmId) {
    AlarmInfo info = db.readAlarmInfo(alarmId);
    if (info == null) {
      return;
    }

    pendingAlarms.remove(alarmId);

    AlarmTime time = info.getTime();
    if (time.repeats()) {
      pendingAlarms.put(alarmId, time);
    } else {
      db.enableAlarm(alarmId, false);
    }
    refreshNotification();
  }

  public void dismissAlarm(long alarmId) {
    AlarmInfo info = db.readAlarmInfo(alarmId);
    if (info == null) {
      return;
    }

    pendingAlarms.remove(alarmId);
    db.enableAlarm(alarmId, false);

    refreshNotification();
  }

  public void snoozeAlarm(long alarmId) {
    snoozeAlarmFor(alarmId, db.readAlarmSettings(alarmId).getSnoozeMinutes());
  }

  public void snoozeAlarmFor(long alarmId, int minutes) {
    // Clear the snoozed alarm.
    pendingAlarms.remove(alarmId);

    // Calculate the time for the next alarm.
    AlarmTime time = AlarmTime.snoozeInMillisUTC(minutes);

    // Schedule it.
    pendingAlarms.put(alarmId, time);
    refreshNotification();
  }
}