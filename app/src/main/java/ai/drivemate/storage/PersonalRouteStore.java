package ai.drivemate.storage;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.PersonalRoute;

/** Local storage for user-created routes. Kept separate from trip history so deleting a trip never deletes a saved route. */
public final class PersonalRouteStore {
    private static final String PREFS = "drivemate_personal_routes";
    private static final String KEY_ROUTES = "routes";
    private static final int MAX_ROUTES = 30;
    private final SharedPreferences preferences;

    public PersonalRouteStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<PersonalRoute> all() {
        ArrayList<PersonalRoute> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(preferences.getString(KEY_ROUTES, "[]"));
            for (int i = 0; i < values.length() && result.size() < MAX_ROUTES; i++) {
                PersonalRoute route = PersonalRoute.fromJson(values.getJSONObject(i));
                if (!route.points.isEmpty()) result.add(route);
            }
        } catch (Exception ignored) { }
        return result;
    }

    public synchronized void upsert(PersonalRoute route) {
        if (route == null || route.points.size() < 2) return;
        List<PersonalRoute> routes = all();
        for (int i = routes.size() - 1; i >= 0; i--) {
            if (route.id.equals(routes.get(i).id)) routes.remove(i);
        }
        routes.add(0, route);
        while (routes.size() > MAX_ROUTES) routes.remove(routes.size() - 1);
        write(routes);
    }

    public synchronized void remove(String id) {
        List<PersonalRoute> routes = all();
        for (int i = routes.size() - 1; i >= 0; i--) {
            if (id != null && id.equals(routes.get(i).id)) routes.remove(i);
        }
        write(routes);
    }

    public synchronized PersonalRoute find(String id) {
        if (id == null) return null;
        for (PersonalRoute route : all()) if (id.equals(route.id)) return route;
        return null;
    }

    private void write(List<PersonalRoute> routes) {
        JSONArray values = new JSONArray();
        if (routes != null) {
            for (PersonalRoute route : routes) {
                try { values.put(route.toJson()); } catch (org.json.JSONException ignored) { }
            }
        }
        preferences.edit().putString(KEY_ROUTES, values.toString()).apply();
    }
}
