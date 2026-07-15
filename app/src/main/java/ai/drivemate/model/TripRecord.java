package ai.drivemate.model;

import org.json.JSONObject;

public class TripRecord {
    public final String destinationName;
    public final double originLatitude;
    public final double originLongitude;
    public final double destinationLatitude;
    public final double destinationLongitude;
    public final int distanceMeters;
    public final int durationSeconds;
    public final long startedAt;

    public TripRecord(String destinationName, double originLatitude, double originLongitude, double destinationLatitude,
                      double destinationLongitude, int distanceMeters, int durationSeconds, long startedAt) {
        this.destinationName = destinationName;
        this.originLatitude = originLatitude;
        this.originLongitude = originLongitude;
        this.destinationLatitude = destinationLatitude;
        this.destinationLongitude = destinationLongitude;
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.startedAt = startedAt;
    }

    public JSONObject toJson() throws org.json.JSONException {
        return new JSONObject().put("destinationName", destinationName).put("originLatitude", originLatitude)
                .put("originLongitude", originLongitude).put("destinationLatitude", destinationLatitude)
                .put("destinationLongitude", destinationLongitude).put("distanceMeters", distanceMeters)
                .put("durationSeconds", durationSeconds).put("startedAt", startedAt);
    }

    public static TripRecord fromJson(JSONObject item) {
        return new TripRecord(item.optString("destinationName"), item.optDouble("originLatitude"), item.optDouble("originLongitude"),
                item.optDouble("destinationLatitude"), item.optDouble("destinationLongitude"), item.optInt("distanceMeters"),
                item.optInt("durationSeconds"), item.optLong("startedAt"));
    }
}
