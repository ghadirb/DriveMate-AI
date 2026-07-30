package ai.drivemate.settings;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.WindowManager;

import java.util.Calendar;

/** Keeps DriveMate readable at night without changing the device-wide brightness setting. */
public final class NightModeManager {
    private static final String PREFS = "drivemate_display";
    private static final String KEY_MODE = "night_mode";

    public enum Mode { AUTO, LIGHT, DARK }

    private NightModeManager() { }

    public static Context wrap(Context base) {
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (isNight(base) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(configuration);
    }

    public static Mode readMode(Context context) {
        String value = preferences(context).getString(KEY_MODE, Mode.AUTO.name());
        try { return Mode.valueOf(value); }
        catch (IllegalArgumentException ignored) { return Mode.AUTO; }
    }

    public static void saveMode(Context context, Mode mode) {
        preferences(context).edit().putString(KEY_MODE, mode.name()).apply();
    }

    public static boolean isNight(Context context) {
        Mode mode = readMode(context);
        if (mode == Mode.DARK) return true;
        if (mode == Mode.LIGHT) return false;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 19 || hour < 6;
    }

    public static String label(Context context) {
        switch (readMode(context)) {
            case LIGHT: return "روشن";
            case DARK: return "تیره";
            default: return "خودکار";
        }
    }

    /** Returns true when the activity is being recreated for a time-based theme change. */
    public static boolean refreshIfChanged(Activity activity) {
        int current = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int expected = isNight(activity) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        if (current != expected) {
            activity.recreate();
            return true;
        }
        return false;
    }

    public static void applyWindowBrightness(Activity activity) {
        WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        attributes.screenBrightness = isNight(activity) ? 0.72f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        activity.getWindow().setAttributes(attributes);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
