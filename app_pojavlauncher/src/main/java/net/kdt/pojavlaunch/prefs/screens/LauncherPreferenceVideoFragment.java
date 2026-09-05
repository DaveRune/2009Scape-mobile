package net.kdt.pojavlaunch.prefs.screens;

import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_NOTCH_SIZE;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_SCALE_FACTOR;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.SwitchPreference;
import androidx.preference.SwitchPreferenceCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.CustomSeekBarPreference;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

/**
 * Fragment for any settings video related
 */
public class LauncherPreferenceVideoFragment extends LauncherPreferenceFragment {
    @Override
    public void onCreatePreferences(Bundle b, String str) {
        addPreferencesFromResource(R.xml.pref_video);

        //Disable notch checking behavior on android 8.1 and below.
        findPreference("ignoreNotch").setVisible(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P);

        CustomSeekBarPreference uiScaleSeek = findPreference("uiScale");
        uiScaleSeek.setRange(100, 400);
        uiScaleSeek.setValue((int)(LauncherPreferences.PREF_UI_SCALE * 100f));
        uiScaleSeek.setSuffix(" %");

        CustomSeekBarPreference seek5 = findPreference("resolutionRatio");
        seek5.setMin(25);
        seek5.setSuffix(" %");

        // #724 bug fix
        if (seek5.getValue() < 25) {
            seek5.setValue(100);
        }

        // Sustained performance is only available since Nougat
        SwitchPreference sustainedPerfSwitch = findPreference("sustainedPerformance");
        sustainedPerfSwitch.setVisible(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
        computeVisibility();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String s) {
        super.onSharedPreferenceChanged(p, s);
        computeVisibility();
        if("keepRunningBackground".equals(s) && LauncherPreferences.PREF_KEEP_RUNNING_BACKGROUND)
            askForNotificationPermission();
    }

    /** The service that keeps the game running shows a notification, and Android 13 hides it without this permission. */
    private void askForNotificationPermission() {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if(ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
    }

    private void computeVisibility(){
        SwitchPreferenceCompat preference = findPreference("force_vsync");
        preference.setVisible(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE);
    }
}
