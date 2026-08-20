package ai.drivemate.model;

import org.json.JSONException;
import org.json.JSONObject;

/** A numeric road-speed value tied to a map point and its supplying data source. */
public final class SpeedLimitPoint {
    public final double latitude;
    public final double longitude;
    public final int kilometersPerHour;
    public final String source;
    /** True when this value was not read from an explicit OSM {@code maxspeed} tag but inferred
     *  from Iran's traffic-law default for the road's {@code highway} classification (see
     *  {@link ai.drivemate.routing.OverpassPoiProvider}). Callers must treat this less confidently
     *  than a tagged value: show it distinctly on the map and gate any spoken warning behind its
     *  own opt-in setting, since it is a legal default rather than a value observed on that road. */
    public final boolean estimated;

    public SpeedLimitPoint(double latitude, double longitude, int kilometersPerHour, String source) {
        this(latitude, longitude, kilometersPerHour, source, false);
    }

    public SpeedLimitPoint(double latitude, double longitude, int kilometersPerHour, String source, boolean estimated) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.kilometersPerHour = kilometersPerHour;
        this.source = source == null ? "" : source;
        this.estimated = estimated;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("latitude", latitude).put("longitude", longitude)
                .put("kilometersPerHour", kilometersPerHour).put("source", source).put("estimated", estimated);
    }

    public static SpeedLimitPoint fromJson(JSONObject object) {
        return new SpeedLimitPoint(object.optDouble("latitude"), object.optDouble("longitude"),
                object.optInt("kilometersPerHour"), object.optString("source"), object.optBoolean("estimated", false));
    }
}
