package ai.drivemate.model;

import org.json.JSONException;
import org.json.JSONObject;

/** A numeric road-speed value tied to a map point and its supplying data source. */
public final class SpeedLimitPoint {
    public final double latitude;
    public final double longitude;
    public final int kilometersPerHour;
    public final String source;

    public SpeedLimitPoint(double latitude, double longitude, int kilometersPerHour, String source) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.kilometersPerHour = kilometersPerHour;
        this.source = source == null ? "" : source;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("latitude", latitude).put("longitude", longitude)
                .put("kilometersPerHour", kilometersPerHour).put("source", source);
    }

    public static SpeedLimitPoint fromJson(JSONObject object) {
        return new SpeedLimitPoint(object.optDouble("latitude"), object.optDouble("longitude"),
                object.optInt("kilometersPerHour"), object.optString("source"));
    }
}
