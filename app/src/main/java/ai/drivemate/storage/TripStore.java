package ai.drivemate.storage;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.TripRecord;

/** Stores a compact on-device trip history used only for driving context. */
public class TripStore {
    private static final String PREFS = "drivemate_trips";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_LAST_SHOWN_REPORT = "last_shown_report_started_at";
    private final SharedPreferences preferences;

    public TripStore(Context context) { preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public void add(TripRecord record) {
        List<TripRecord> current = recent(60);
        current.add(0, record);
        while (current.size() > 60) current.remove(current.size() - 1);
        JSONArray values = new JSONArray();
        for (TripRecord item : current) {
            try { values.put(item.toJson()); }
            catch (org.json.JSONException ignored) { }
        }
        preferences.edit().putString(KEY_HISTORY, values.toString()).apply();
    }

    /** The most recently completed trip whose report has not yet been shown to the driver, or null
     *  if none is pending. Neither MainActivity nor MapActivity can guarantee showing the
     *  completion dialog at the exact moment arrival is detected (the activity may be paused,
     *  backgrounded, or mid-destruction right then) - this lets whichever screen the driver next
     *  opens pick it up and show it there instead, rather than the report silently never appearing. */
    public TripRecord pendingReport() {
        long lastShown = preferences.getLong(KEY_LAST_SHOWN_REPORT, 0L);
        for (TripRecord record : recent(5)) {
            if (record.completed && record.startedAt > lastShown) return record;
        }
        return null;
    }

    public void markReportShown(long startedAt) {
        preferences.edit().putLong(KEY_LAST_SHOWN_REPORT, startedAt).apply();
    }

    public void remove(long startedAt) {
        List<TripRecord> current = recent(60);
        for (int i = current.size() - 1; i >= 0; i--) {
            if (current.get(i).startedAt == startedAt) current.remove(i);
        }
        restore(current);
    }

    public void clear() {
        preferences.edit().remove(KEY_HISTORY).apply();
    }

    public List<TripRecord> recent(int limit) {
        ArrayList<TripRecord> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(preferences.getString(KEY_HISTORY, "[]"));
            for (int i = 0; i < values.length() && result.size() < limit; i++) result.add(TripRecord.fromJson(values.getJSONObject(i)));
        } catch (Exception ignored) { }
        return result;
    }

    /** Replaces history from a validated backup and keeps the same on-device retention limit. */
    public void restore(List<TripRecord> records) {
        JSONArray values = new JSONArray();
        if (records != null) {
            for (int i = 0; i < records.size() && i < 60; i++) {
                try { values.put(records.get(i).toJson()); }
                catch (org.json.JSONException ignored) { }
            }
        }
        preferences.edit().putString(KEY_HISTORY, values.toString()).apply();
    }
}
