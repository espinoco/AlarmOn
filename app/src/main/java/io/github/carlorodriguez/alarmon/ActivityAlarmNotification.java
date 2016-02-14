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

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

/**
 * This is the activity responsible for alerting the user when an alarm goes
 * off.  It is the activity triggered by the NotificationService.  It assumes
 * that the intent sender has acquired a screen wake lock.
 * NOTE: This class assumes that it will never be instantiated nor active
 * more than once at the same time. (ie, it assumes
 * android:launchMode="singleInstance" is set in the manifest file).
 */
public final class ActivityAlarmNotification extends AppCompatActivity {

    public static final String TIMEOUT_COMMAND = "timeout";

    public static final int TIMEOUT = 0;

    private NotificationServiceBinder notifyService;
    private DbAccessor db;
    private Handler handler;
    private Runnable timeTick;

    // Dialog state
    int snoozeMinutes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences sharedPref = PreferenceManager.
                getDefaultSharedPreferences(getBaseContext());

        String theme = sharedPref.getString(AppSettings.APP_THEME_KEY, "0");

        switch (theme) {
            case "1":
                setTheme(R.style.AppThemeLightNoActionBar);
                break;
            case "2":
                setTheme(R.style.AppThemeLightNoActionBar);
                break;
        }

        super.onCreate(savedInstanceState);

        setContentView(R.layout.notification);

        // Make sure this window always shows over the lock screen.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        db = new DbAccessor(getApplicationContext());

        // Start the notification service and bind to it.
        notifyService = new NotificationServiceBinder(getApplicationContext());

        notifyService.bind();

        // Setup a self-scheduling event loops.
        handler = new Handler();

        timeTick = new Runnable() {
            @Override
            public void run() {
                notifyService.call(new NotificationServiceBinder.
                        ServiceCallback() {
                    @Override
                    public void run(NotificationServiceInterface service) {
                        try {
                            TextView volume = (TextView)
                                    findViewById(R.id.volume);

                            String volumeText = "Volume: " + service.volume();

                            volume.setText(volumeText);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }

                        long next = AlarmUtil.millisTillNextInterval(
                                AlarmUtil.Interval.SECOND);

                        handler.postDelayed(timeTick, next);
                    }
                });
            }
        };

        // Setup individual UI elements.
        final Button snoozeButton = (Button) findViewById(R.id.notify_snooze);

        snoozeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                notifyService.acknowledgeCurrentNotification(snoozeMinutes);

                finish();
            }
        });

        final Button decreaseSnoozeButton = (Button) findViewById(
                R.id.notify_snooze_minus_five);

        decreaseSnoozeButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                int snooze = snoozeMinutes - 5;

                if (snooze < 5) {
                    snooze = 5;
                }

                snoozeMinutes = snooze;

                redraw();
            }
        });

        final Button increaseSnoozeButton = (Button) findViewById(
                R.id.notify_snooze_plus_five);

        increaseSnoozeButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                int snooze = snoozeMinutes + 5;

                if (snooze > 60) {
                    snooze = 60;
                }

                snoozeMinutes = snooze;

                redraw();
            }
        });

        final Slider dismiss = (Slider) findViewById(R.id.dismiss_slider);

        dismiss.setOnCompleteListener(new Slider.OnCompleteListener() {
            @Override
            public void complete() {
                notifyService.acknowledgeCurrentNotification(0);

                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        handler.post(timeTick);

        redraw();
    }

    @Override
    protected void onPause() {
        super.onPause();

        handler.removeCallbacks(timeTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        db.closeConnections();

        notifyService.unbind();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        Bundle extras = intent.getExtras();

        if (extras == null || !extras.getBoolean(TIMEOUT_COMMAND, false)) {
            return;
        }

        // The notification service has signaled this activity for a second time.
        // This represents a acknowledgment timeout.  Display the appropriate error.
        // (which also finish()es this activity.
        showDialogFragment(TIMEOUT);
    }

    private void redraw() {
        notifyService.call(new NotificationServiceBinder.ServiceCallback() {
            @Override
            public void run(NotificationServiceInterface service) {
                long alarmId;

                try {
                    alarmId = service.currentAlarmId();
                } catch (RemoteException e) {
                    return;
                }

                AlarmInfo alarmInfo = db.readAlarmInfo(alarmId);

                if (snoozeMinutes == 0) {
                    snoozeMinutes = db.readAlarmSettings(alarmId).
                            getSnoozeMinutes();
                }

                String infoTime = "";

                String infoName = "";

                if (alarmInfo != null) {
                    infoTime = alarmInfo.getTime().toString();

                    infoName = alarmInfo.getName();
                }

                String info = infoTime + "\n" + infoName;

                if (AppSettings.isDebugMode(getApplicationContext())) {
                    info += " [" + alarmId + "]";

                    findViewById(R.id.volume).setVisibility(View.VISIBLE);
                } else {
                    findViewById(R.id.volume).setVisibility(View.GONE);
                }
                TextView infoText = (TextView) findViewById(R.id.alarm_info);

                infoText.setText(info);

                TextView snoozeInfo = (TextView) findViewById(
                        R.id.notify_snooze_time);

                String snoozeInfoText = getString(R.string.snooze) + "\n"
                        + getString(R.string.minutes, snoozeMinutes);

                snoozeInfo.setText(snoozeInfoText);
            }
        });
    }

    private void showDialogFragment(int id) {
        DialogFragment dialog = new ActivityDialogFragment().newInstance(
                id);

        dialog.show(getFragmentManager(), "ActivityDialogFragment");
    }

    public static class ActivityDialogFragment extends DialogFragment {

        public ActivityDialogFragment newInstance(int id) {
            ActivityDialogFragment fragment = new ActivityDialogFragment();

            Bundle args = new Bundle();

            args.putInt("id", id);

            fragment.setArguments(args);

            return fragment;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            switch (getArguments().getInt("id")) {
                case TIMEOUT:
                    final AlertDialog.Builder timeoutBuilder =
                            new AlertDialog.Builder(getActivity());

                    timeoutBuilder.setIcon(android.R.drawable.ic_dialog_alert);

                    timeoutBuilder.setTitle(R.string.time_out_title);

                    timeoutBuilder.setMessage(R.string.time_out_error);

                    timeoutBuilder.setPositiveButton(R.string.ok,
                            new DialogInterface.OnClickListener(){
                        @Override
                        public void onClick(DialogInterface dialog, int which) {}
                    });

                    AlertDialog dialog = timeoutBuilder.create();

                    dialog.setOnDismissListener(new DialogInterface.
                            OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            getActivity().finish();
                        }});

                    return dialog;
                default:
                    return super.onCreateDialog(savedInstanceState);
            }
        }

    }

}
