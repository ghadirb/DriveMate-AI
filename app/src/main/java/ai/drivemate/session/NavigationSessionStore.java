package ai.drivemate.session;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.SavedPlace;

/**
 * Durable (SharedPreferences-backed) checkpoint of the single active navigation session, written
 * by whoever owns {@code NavigationEngine} (see NavigationForegroundService) so that after a
 * process death Android can restart the service ({@code START_STICKY}) and resume the trip -
 * route, destination, waypoints, mode, trip start time, travelled distance and the current
 * step/waypoint index - without needing MainActivity to still be alive.
 *
 * Deliberately plain SharedPreferences rather than a DB: a single active session is the only
 * thing ever stored here, writes are infrequent (checkpointed on step/waypoint advance, not on
 * every GPS tick), and this must survive process death exactly like any other SharedPreferences
 * write (commit()/apply() are durable to disk before the process is free to die again).
 */
public final class NavigationSessionStore {
    private static final String PREFS_NAME = "navigation_session_v1";
    private static final String KEY_HAS_SESSION = "has_session";
    private static final String KEY_ROUTE_JSON = "route_json";
    private static final String KEY_DESTINATION_JSON = "destination_json";
    private static final String KEY_WAYPOINTS_JSON = "waypoints_json";
    private static final String KEY_MODE = "mode";
    private static final String KEY_TRIP_START_AT = "trip_start_at";
    private static final String KEY_TRAVELLED_METERS = "travelled_meters";
    private static final String KEY_CURRENT_STEP_INDEX = "current_step_index";
    private static final String KEY_CURRENT_WAYPOINT_ORDINAL = "current_waypoint_ordinal";
    private static final String KEY_TRIP_PATH_JSON = "trip_path_json";

    /** Snapshot of everything needed to call NavigationEngine.start(route, listener, location,
     *  finalDestination, initialStepIndex) again and keep reporting distance/duration/trip time
     *  consistently with the trip the driver was actually on before the process died. */
    public static final class Session {
        public final RouteResult route;
        public final SavedPlace destination;
        public final List<RoutePoint> waypoints;
        public final String mode;
        public final long tripStartAtMillis;
        public final float travelledMeters;
        public final int currentStepIndex;
        public final int currentWaypointOrdinal;
        public final List<RoutePoint> tripPath;

        public Session(RouteResult route, SavedPlace destination, List<RoutePoint> waypoints, String mode,
                       long tripStartAtMillis, float travelledMeters, int currentStepIndex,
                       int currentWaypointOrdinal, List<RoutePoint> tripPath) {
            this.route = route;
            this.destination = destination;
            this.waypoints = waypoints;
            this.mode = mode;
            this.tripStartAtMillis = tripStartAtMillis;
            this.travelledMeters = travelledMeters;
            this.currentStepIndex = currentStepIndex;
            this.currentWaypointOrdinal = currentWaypointOrdinal;
            this.tripPath = tripPath == null ? new ArrayList<>() : new ArrayList<>(tripPath);
        }
    }

    private final SharedPreferences prefs;

    public NavigationSessionStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Checkpoints the full session state. Safe to call often (e.g. on every step/waypoint
     *  advance and arrival-progress update) - it is not called from the raw per-GPS-tick path, so
     *  this does not add disk I/O to every location update, only to genuine progress events. */
    public void save(RouteResult route, SavedPlace destination, List<RoutePoint> waypoints, String mode,
                     long tripStartAtMillis, float travelledMeters, int currentStepIndex,
                     int currentWaypointOrdinal, List<RoutePoint> tripPath) {
        try {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(KEY_HAS_SESSION, true);
            editor.putString(KEY_ROUTE_JSON, route == null ? null : route.toJson().toString());
            editor.putString(KEY_DESTINATION_JSON, destination == null ? null : destination.toJson().toString());
            editor.putString(KEY_WAYPOINTS_JSON, waypointsToJson(waypoints).toString());
            editor.putString(KEY_MODE, mode == null ? "" : mode);
            editor.putLong(KEY_TRIP_START_AT, tripStartAtMillis);
            editor.putFloat(KEY_TRAVELLED_METERS, travelledMeters);
            editor.putInt(KEY_CURRENT_STEP_INDEX, currentStepIndex);
            editor.putInt(KEY_CURRENT_WAYPOINT_ORDINAL, currentWaypointOrdinal);
            editor.putString(KEY_TRIP_PATH_JSON, waypointsToJson(tripPath).toString());
            editor.apply();
        } catch (JSONException ignored) {
            // A checkpoint failure must never crash an in-progress trip; the previous checkpoint
            // (or none, if this is the first) simply remains on disk.
        }
    }

    /** Only updates the lightweight, frequently-changing fields (current step/waypoint index and
     *  travelled distance) without re-serializing the whole route - cheap enough to call on every
     *  confirmed step advance. */
    public void updateProgress(int currentStepIndex, int currentWaypointOrdinal, float travelledMeters) {
        if (!hasActiveSession()) return;
        prefs.edit()
                .putInt(KEY_CURRENT_STEP_INDEX, currentStepIndex)
                .putInt(KEY_CURRENT_WAYPOINT_ORDINAL, currentWaypointOrdinal)
                .putFloat(KEY_TRAVELLED_METERS, travelledMeters)
                .apply();
    }

    public boolean hasActiveSession() {
        return prefs.getBoolean(KEY_HAS_SESSION, false);
    }

    /** Returns null when there is no session, or when the persisted route JSON is corrupt (never
     *  throws - a restore failure must fall back to "no session" so the driver can simply start a
     *  fresh trip instead of crashing the service on the next process start). */
    public Session load() {
        if (!hasActiveSession()) return null;
        try {
            String routeJson = prefs.getString(KEY_ROUTE_JSON, null);
            if (routeJson == null) return null;
            RouteResult route = RouteResult.fromJson(new JSONObject(routeJson));
            String destinationJson = prefs.getString(KEY_DESTINATION_JSON, null);
            SavedPlace destination = destinationJson == null ? null : SavedPlace.fromJson(new JSONObject(destinationJson));
            List<RoutePoint> waypoints = waypointsFromJson(prefs.getString(KEY_WAYPOINTS_JSON, null));
            String mode = prefs.getString(KEY_MODE, "");
            long tripStartAtMillis = prefs.getLong(KEY_TRIP_START_AT, 0L);
            float travelledMeters = prefs.getFloat(KEY_TRAVELLED_METERS, 0f);
            int currentStepIndex = prefs.getInt(KEY_CURRENT_STEP_INDEX, 0);
            int currentWaypointOrdinal = prefs.getInt(KEY_CURRENT_WAYPOINT_ORDINAL, -1);
            List<RoutePoint> tripPath = waypointsFromJson(prefs.getString(KEY_TRIP_PATH_JSON, null));
            return new Session(route, destination, waypoints, mode, tripStartAtMillis, travelledMeters,
                    currentStepIndex, currentWaypointOrdinal, tripPath);
        } catch (JSONException e) {
            clear();
            return null;
        }
    }

    /** Must be called on trip end (arrival, explicit stop, or a fresh navigationEngine.stop())
     *  or a future restart will incorrectly try to resume a trip that already finished. */
    public void clear() {
        prefs.edit().clear().apply();
    }

    private JSONArray waypointsToJson(List<RoutePoint> waypoints) throws JSONException {
        JSONArray array = new JSONArray();
        if (waypoints != null) for (RoutePoint point : waypoints) array.put(point.toJson());
        return array;
    }

    private List<RoutePoint> waypointsFromJson(String json) {
        List<RoutePoint> waypoints = new ArrayList<>();
        if (json == null) return waypoints;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) waypoints.add(RoutePoint.fromJson(array.getJSONObject(i)));
        } catch (JSONException ignored) {
            // Corrupt waypoints alone must not fail the whole session restore.
        }
        return waypoints;
    }
}
