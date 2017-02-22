package net.sf.fakenames.fddemo;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.widget.Toast;

import net.sf.xfd.provider.RootSingleton;

import java.io.IOException;

public class SettingsActivity extends PreferenceActivity implements Preference.OnPreferenceChangeListener {
    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.prefs);

        final Preference pref = findPreference(getString(R.string.pref_use_root));

        pref.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        try {
            if (Boolean.FALSE.equals(newValue)) {
                RootSingleton.clear();
            } else {
                RootSingleton.get(this);
            }

            return true;
        } catch (IOException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        return false;
    }
}
