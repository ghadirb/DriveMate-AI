package ai.drivemate.model;

import org.json.JSONObject;

/** A completed or interrupted navigation session kept locally for trip reports and route learning. */
public class TripRecord {
    public final String destinationName;
    public final double originLatitude;
    public final double originLongitude;
    public final double destinationLatitude;
    public final double destinationLongitude;
    /** Planned route distance supplied by the routing provider. */
    public final int distanceMeters;
    /** Planned route duration supplied by the routing provider. */
    public final int durationSeconds;
    public final long startedAt;
    public final long endedAt;
    /** GPS distance sampled during this session; zero for legacy planned-only entries. */
    public final int traveledDistanceMeters;
    public final String routeProvider;
    public final int waypointCount;
    public final boolean completed;

    /** Compatibility constructor for records made by older versions. */
    public TripRecord(String destinationName, double originLatitude, double originLongitude, double destinationLatitude,
                      double destinationLongitude, int distanceMeters, int durationSeconds, long startedAt) {
        this(destinationName, originLatitude, originLongitude, destinationLatitude, destinationLongitude,
                distanceMeters, durationSeconds, startedAt, 0L, 0, "", 0, false);
    }

    public TripRecord(String destinationName, double originLatitude, double originLongitude, double destinationLatitude,
                      double destinationLongitude, int distanceMeters, int durationSeconds, long startedAt, long endedAt,
                      int traveledDistanceMeters, String routeProvider, int waypointCount, boolean completed) {
        this.destinationName = destinationName;
        this.originLatitude = originLatitude;
        this.originLongitude = originLongitude;
        this.destinationLatitude = destinationLatitude;
        this.destinationLongitude = destinationLongitude;
        this.distanceMeters = Math.max(0, distanceMeters);
        this.durationSeconds = Math.max(0, durationSeconds);
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.traveledDistanceMeters = Math.max(0, traveledDistanceMeters);
        this.routeProvider = routeProvider == null ? "" : routeProvider;
        this.waypointCount = Math.max(0, waypointCount);
        this.completed = completed;
    }

    public JSONObject toJson() throws org.json.JSONException {
        return new JSONObject().put("destinationName", destinationName).put("originLatitude", originLatitude)
                .put("originLongitude", originLongitude).put("destinationLatitude", destinationLatitude)
                .put("destinationLongitude", destinationLongitude).put("distanceMeters", distanceMeters)
                .put("durationSeconds", durationSeconds).put("startedAt", startedAt).put("endedAt", endedAt)
                .put("traveledDistanceMeters", traveledDistanceMeters).put("routeProvider", routeProvider)
                .put("waypointCount", waypointCount).put("completed", completed);
    }

    public static TripRecord fromJson(JSONObject item) {
        return new TripRecord(item.optString("destinationName"), item.optDouble("originLatitude"), item.optDouble("originLongitude"),
                item.optDouble("destinationLatitude"), item.optDouble("destinationLongitude"), item.optInt("distanceMeters"),
                item.optInt("durationSeconds"), item.optLong("startedAt"), item.optLong("endedAt"),
                item.optInt("traveledDistanceMeters"), item.optString("routeProvider"), item.optInt("waypointCount"),
                item.optBoolean("completed", false));
    }
}
