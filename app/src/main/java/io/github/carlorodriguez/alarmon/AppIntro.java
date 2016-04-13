package io.github.carlorodriguez.alarmon;

import android.app.Activity;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.View;

import co.mobiwise.materialintro.shape.Focus;
import co.mobiwise.materialintro.shape.FocusGravity;
import co.mobiwise.materialintro.view.MaterialIntroView;

public class AppIntro {

    public static final String ALARM_DELETION_SHOWCASE = "ALARM_DELETION_SHOWCASE";
    public static final String SHOWN = "SHOWN";
    public static final String NOT_SHOWN = "NOT_SHOWN";

    public static boolean isAlarmDeletionShowcased(Activity activity) {
        SharedPreferences sharedPref = PreferenceManager.
                getDefaultSharedPreferences(activity);

        return sharedPref.getString(ALARM_DELETION_SHOWCASE, NOT_SHOWN).
                equals(SHOWN);
    }

    public static void showcaseAlarmDeletion(final Activity activity,
            final View view) {
        SharedPreferences sharedPref = PreferenceManager.
                getDefaultSharedPreferences(activity);

        SharedPreferences.Editor editor = sharedPref.edit();

        editor.putString(ALARM_DELETION_SHOWCASE, SHOWN);

        editor.apply();

        new MaterialIntroView.Builder(activity)
                .enableDotAnimation(false)
                .enableIcon(true)
                .setFocusGravity(FocusGravity.CENTER)
                .setFocusType(Focus.NORMAL)
                .setDelayMillis(200)
                .enableFadeAnimation(true)
                .setInfoText(activity.
                        getString(R.string.swipe_right_to_delete))
                .setTarget(view)
                .setUsageId(activity.
                        getString(R.string.material_intro_id_alarm_deletion))
                .show();
    }

}
