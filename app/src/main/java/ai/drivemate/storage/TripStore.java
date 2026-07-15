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
    private final SharedPreferences preferences;

    public TripStore(Context context) { preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public void add(TripRecord record) {
        List<TripRecord> current = recent(24);
        current.add(0, record);
        while (current.size() > 60) current.remove(current.size() - 1);
        JSONArray values = new JSONArray();
        for (TripRecord item : current) {
            try { values.put(item.toJson()); }
            catch (org.json.JSONException ignored) { }
        }
        preferences.edit().putString(KEY_HISTORY, values.toString()).apply();
    }

    public List<TripRecord> recent(int limit) {
        ArrayList<TripRecord> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(preferences.getString(KEY_HISTORY, "[]"));
            for (int i = 0; i < values.length() && result.size() < limit; i++) result.add(TripRecord.fromJson(values.getJSONObject(i)));
        } catch (Exception ignored) { }
        return result;
    }
}
