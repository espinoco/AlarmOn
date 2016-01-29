package io.github.carlorodriguez.alarmon;

interface NotificationServiceInterface {
  long currentAlarmId();
  int firingAlarmCount();
  float volume();
  void acknowledgeCurrentNotification(int snoozeMinutes);
}