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

import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.text.format.DateFormat;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.wdullaer.materialdatetimepicker.time.*;
import com.wdullaer.materialdatetimepicker.time.TimePickerDialog;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * This is the main Activity for the application.  It contains a ListView
 * for displaying all alarms, a simple clock, and a button for adding new
 * alarms.  The context menu allows the user to edit default settings.  Long-
 * clicking on the clock will trigger a dialog for enabling/disabling 'debug
 * mode.'
 */
public final class ActivityAlarmClock extends AppCompatActivity implements
        TimePickerDialog.OnTimeSetListener,
        TimePickerDialog.OnTimeChangedListener {

    public static final int DELETE_CONFIRM = 1;
    public static final int DELETE_ALARM_CONFIRM = 2;

    public static final int ACTION_TEST_ALARM = 0;
    public static final int ACTION_PENDING_ALARMS = 1;

    private TimePickerDialog picker;
    public static ActivityAlarmClock activityAlarmClock;

    private static AlarmClockServiceBinder service;
    private static NotificationServiceBinder notifyService;
    private DbAccessor db;
    private static AlarmAdapter adapter;
    private Cursor cursor;
    private Handler handler;
    private Runnable tickCallback;
    private static RecyclerView alarmList;
    private int mLastFirstVisiblePosition;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppSettings.setMainActivityTheme(getBaseContext(),
                ActivityAlarmClock.this);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.alarm_list);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);

        setSupportActionBar(toolbar);

        activityAlarmClock = this;

        // Access to in-memory and persistent data structures.
        service = new AlarmClockServiceBinder(getApplicationContext());

        db = new DbAccessor(getApplicationContext());

        handler = new Handler();

        // Setup the alarm list and the underlying adapter. Clicking an individual
        // item will start the settings activity.
        alarmList = (RecyclerView) findViewById(R.id.alarm_list);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);

        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);

        alarmList.setLayoutManager(layoutManager);

        ItemTouchHelper.SimpleCallback simpleCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                final AlarmInfo alarmInfo = adapter.getAlarmInfos().
                        get(viewHolder.getAdapterPosition());

                final long alarmId = alarmInfo.getAlarmId();

                removeItemFromList(ActivityAlarmClock.this, alarmId,
                        viewHolder.getAdapterPosition());

                Snackbar.make(findViewById(R.id.coordinator_layout),
                        getString(R.string.alarm_deleted), Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.undo), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        undoAlarmDeletion(alarmInfo.getTime(),
                                db.readAlarmSettings(alarmId),
                                alarmInfo.getName(), alarmInfo.enabled());
                    }
                })
                .show();
            }
        };

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(simpleCallback);

        itemTouchHelper.attachToRecyclerView(alarmList);

        final FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.add_fab);

        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Calendar now = Calendar.getInstance();

                picker = TimePickerDialog.newInstance(
                        ActivityAlarmClock.this,
                        ActivityAlarmClock.this,
                        now.get(Calendar.HOUR_OF_DAY),
                        now.get(Calendar.MINUTE),
                        DateFormat.is24HourFormat(ActivityAlarmClock.this)
                );

                if (AppSettings.isThemeDark(ActivityAlarmClock.this)) {
                    picker.setThemeDark(true);
                }

                picker.setAccentColor(AppSettings.getTimePickerColor(
                        ActivityAlarmClock.this));

                picker.vibrate(true);

                if (AppSettings.isDebugMode(ActivityAlarmClock.this)) {
                    picker.enableSeconds(true);
                } else {
                    picker.enableSeconds(false);
                }

                AlarmTime time = new AlarmTime(now.get(Calendar.HOUR_OF_DAY),
                        now.get(Calendar.MINUTE), 0);

                picker.setTitle(time.timeUntilString(ActivityAlarmClock.this));

                picker.show(getFragmentManager(), "TimePickerDialog");
            }
        });

        // This is a self-scheduling callback that is responsible for refreshing
        // the screen.  It is started in onResume() and stopped in onPause().
        tickCallback = new Runnable() {
            @Override
            public void run() {
                // Redraw the screen.
                redraw();

                // Schedule the next update on the next interval boundary.
                AlarmUtil.Interval interval = AlarmUtil.Interval.MINUTE;

                if (AppSettings.isDebugMode(getApplicationContext())) {
                    interval = AlarmUtil.Interval.SECOND;
                }

                long next = AlarmUtil.millisTillNextInterval(interval);

                handler.postDelayed(tickCallback, next);
            }
        };

        if (!AppIntro.isAlarmDeletionShowcased(this)) {
            requery();

            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (adapter.getItemCount() >= 1 && alarmList.getChildAt(0) != null) {
                        AppIntro.showcaseAlarmDeletion(ActivityAlarmClock.this,
                                alarmList.getChildAt(0));
                    }
                }
            }, 500);
        }
    }

    private void undoAlarmDeletion(AlarmTime alarmTime,
            AlarmSettings alarmSettings, String alarmName, boolean enabled) {
        long newAlarmId = service.resurrectAlarm(alarmTime, alarmName, enabled);

        if (newAlarmId != AlarmClockServiceBinder.NO_ALARM_ID) {
            db.writeAlarmSettings(newAlarmId, alarmSettings);

            requery();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        invalidateOptionsMenu();

        service.bind();

        handler.post(tickCallback);

        requery();

        alarmList.getLayoutManager().scrollToPosition(mLastFirstVisiblePosition);

        notifyService = new NotificationServiceBinder(getApplicationContext());

        notifyService.bind();

        notifyService.call(new NotificationServiceBinder.ServiceCallback() {
            @Override
            public void run(NotificationServiceInterface service) {
                int count;

                try {
                    count = service.firingAlarmCount();
                } catch (RemoteException e) {
                    return;
                }

                if (count > 0) {
                    Intent notifyActivity = new Intent(getApplicationContext(),
                            ActivityAlarmNotification.class);

                    notifyActivity.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    startActivity(notifyActivity);
                }
            }
        });

        TimePickerDialog tpd = (TimePickerDialog) getFragmentManager().
                findFragmentByTag("TimePickerDialog");

        if (tpd != null) {
            picker = tpd;

            tpd.setOnTimeSetListener(this);

            tpd.setOnTimeChangedListener(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        handler.removeCallbacks(tickCallback);

        service.unbind();

        if (notifyService != null) {
            notifyService.unbind();
        }

        mLastFirstVisiblePosition = ((LinearLayoutManager)
                alarmList.getLayoutManager()).
                findFirstCompletelyVisibleItemPosition();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        db.closeConnections();

        activityAlarmClock = null;

        notifyService = null;

        cursor.close();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (AppSettings.isDebugMode(getApplicationContext())) {
            menu.add(Menu.NONE, ACTION_TEST_ALARM, 5, R.string.test_alarm);

            menu.add(Menu.NONE, ACTION_PENDING_ALARMS, 6, R.string.pending_alarms);
        }

        getMenuInflater().inflate(R.menu.menu_main, menu);

        return true;
    }

    @Override
    public void onTimeSet(RadialPickerLayout view, int hourOfDay, int minute, int second) {
        AlarmTime time = new AlarmTime(hourOfDay, minute, second);

        service.createAlarm(time);

        requery();
    }

    @Override
    public void onTimeChanged(RadialPickerLayout view, int hourOfDay, int minute, int second) {
        AlarmTime time = new AlarmTime(hourOfDay, minute, second);

        picker.setTitle(time.timeUntilString(this));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_delete_all:
                showDialogFragment(DELETE_CONFIRM);
                break;
            case R.id.action_default_settings:
                Intent alarm_settings = new Intent(getApplicationContext(),
                        ActivityAlarmSettings.class);

                alarm_settings.putExtra(ActivityAlarmSettings.EXTRAS_ALARM_ID,
                        AlarmSettings.DEFAULT_SETTINGS_ID);

                startActivity(alarm_settings);
                break;
            case R.id.action_app_settings:
                Intent app_settings = new Intent(getApplicationContext(),
                        ActivityAppSettings.class);

                startActivity(app_settings);
                break;
            case ACTION_TEST_ALARM:
                // Used in debug mode.  Schedules an alarm for 5 seconds in the future
                // when clicked.
                final Calendar testTime = Calendar.getInstance();

                testTime.add(Calendar.SECOND, 5);

                AlarmTime time = new AlarmTime(
                        testTime.get(Calendar.HOUR_OF_DAY),
                        testTime.get(Calendar.MINUTE),
                        testTime.get(Calendar.SECOND));

                service.createAlarm(time);

                requery();
                break;
            case ACTION_PENDING_ALARMS:
                // Displays a list of pending alarms (only visible in debug mode).
                startActivity(new Intent(getApplicationContext(),
                        ActivityPendingAlarms.class));
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showDialogFragment(int id) {
        DialogFragment dialog = new ActivityDialogFragment().newInstance(
                id);

        dialog.show(getFragmentManager(), "ActivityDialogFragment");
    }

    private void redraw() {
        // Recompute expiration times in the list view
        adapter.notifyDataSetChanged();

        Calendar now = Calendar.getInstance();

        AlarmTime time = new AlarmTime(now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE), 0);

        ((CollapsingToolbarLayout) findViewById(R.id.toolbar_layout)).setTitle(
                time.localizedString(this));
    }

    private void requery() {
        cursor = db.readAlarmInfo();

        ArrayList<AlarmInfo> infos = new ArrayList<>();

        while (cursor.moveToNext()) {
            infos.add(new AlarmInfo(cursor));
        }

        adapter = new AlarmAdapter(infos, service, this);

        alarmList.setAdapter(adapter);

        setEmptyViewIfEmpty(this);
    }

    public static void setEmptyViewIfEmpty(Activity activity) {
        if (adapter.getItemCount() == 0) {
            activity.findViewById(R.id.empty_view).setVisibility(View.VISIBLE);

            alarmList.setVisibility(View.GONE);
        } else {
            activity.findViewById(R.id.empty_view).setVisibility(View.GONE);

            alarmList.setVisibility(View.VISIBLE);
        }
    }

    public static void removeItemFromList(Activity activity, long alarmId, int position) {
        if (adapter.getItemCount() == 1) {
            ((AppBarLayout) activity.findViewById(R.id.app_bar)).
                    setExpanded(true);
        }

        service.deleteAlarm(alarmId);

        adapter.removeAt(position);

        setEmptyViewIfEmpty(activity);
    }

    public static class ActivityDialogFragment extends DialogFragment {

        public ActivityDialogFragment newInstance(int id) {
            ActivityDialogFragment fragment = new ActivityDialogFragment();

            Bundle args = new Bundle();

            args.putInt("id", id);

            fragment.setArguments(args);

            return fragment;
        }

        public ActivityDialogFragment newInstance(int id, AlarmInfo info,
                int position) {
            ActivityDialogFragment fragment = new ActivityDialogFragment();

            Bundle args = new Bundle();

            args.putInt("id", id);

            args.putLong("alarmId", info.getAlarmId());

            args.putInt("position", position);

            fragment.setArguments(args);

            return fragment;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            switch (getArguments().getInt("id")) {
                case ActivityAlarmClock.DELETE_CONFIRM:
                    final AlertDialog.Builder deleteConfirmBuilder =
                            new AlertDialog.Builder(getActivity());

                    deleteConfirmBuilder.setTitle(R.string.delete_all);

                    deleteConfirmBuilder.setMessage(R.string.confirm_delete);

                    deleteConfirmBuilder.setPositiveButton(R.string.ok,
                            new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            service.deleteAllAlarms();

                            adapter.removeAll();

                            setEmptyViewIfEmpty(getActivity());

                            dismiss();
                        }
                    });

                    deleteConfirmBuilder.setNegativeButton(R.string.cancel,
                            new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dismiss();
                        }
                    });
                    return deleteConfirmBuilder.create();
                case ActivityAlarmClock.DELETE_ALARM_CONFIRM:
                    final AlertDialog.Builder deleteAlarmConfirmBuilder =
                            new AlertDialog.Builder(getActivity());

                    deleteAlarmConfirmBuilder.setTitle(R.string.delete);

                    deleteAlarmConfirmBuilder.setMessage(
                            R.string.confirm_delete);

                    deleteAlarmConfirmBuilder.setPositiveButton(R.string.ok,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    removeItemFromList(getActivity(),
                                            getArguments().getLong("alarmId"),
                                            getArguments().getInt("position"));

                                    dismiss();
                                }
                            });

                    deleteAlarmConfirmBuilder.setNegativeButton(R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    dismiss();
                                }
                            });
                    return deleteAlarmConfirmBuilder.create();
                default:
                    return super.onCreateDialog(savedInstanceState);
            }
        }

    }

}
