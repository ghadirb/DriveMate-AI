package ai.drivemate.storage;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import ai.drivemate.model.SavedPlace;

public class PlaceStore {
    private static final String PREFS = "drivemate_places";
    private static final String KEY_PLACES = "places";
    private static final String KEY_RECENT = "recent";

    private final SharedPreferences preferences;

    public PlaceStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void upsert(SavedPlace place) {
        List<SavedPlace> places = allPlaces();
        List<SavedPlace> updated = new ArrayList<>();
        for (SavedPlace existing : places) {
            if (!existing.kind.equals(place.kind)) {
                updated.add(existing);
            }
        }
        updated.add(place);
        saveList(KEY_PLACES, updated);
    }

    public SavedPlace findByKind(String kind) {
        for (SavedPlace place : allPlaces()) {
            if (place.kind.equals(kind)) {
                return place;
            }
        }
        return null;
    }

    public List<SavedPlace> allPlaces() {
        List<SavedPlace> places = readList(KEY_PLACES);
        places.sort(Comparator.comparingLong(place -> -place.updatedAt));
        return places;
    }

    public void addRecent(SavedPlace place) {
        List<SavedPlace> recent = readList(KEY_RECENT);
        List<SavedPlace> updated = new ArrayList<>();
        updated.add(place);
        for (SavedPlace item : recent) {
            if (!item.kind.equals(place.kind)) {
                updated.add(item);
            }
            if (updated.size() >= 8) {
                break;
            }
        }
        saveList(KEY_RECENT, updated);
    }

    public List<SavedPlace> recentPlaces() {
        return readList(KEY_RECENT);
    }

    private List<SavedPlace> readList(String key) {
        ArrayList<SavedPlace> result = new ArrayList<>();
        String raw = preferences.getString(key, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                result.add(SavedPlace.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
            preferences.edit().remove(key).apply();
        }
        return result;
    }

    private void saveList(String key, List<SavedPlace> places) {
        JSONArray array = new JSONArray();
        for (SavedPlace place : places) {
            try {
                array.put(place.toJson());
            } catch (JSONException ignored) {
            }
        }
        preferences.edit().putString(key, array.toString()).apply();
    }
}
