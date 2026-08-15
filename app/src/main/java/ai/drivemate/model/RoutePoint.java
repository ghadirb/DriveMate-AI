package ai.drivemate.model;

import org.json.JSONException;
import org.json.JSONObject;

/** A point on the actual road geometry returned by a routing provider. */
public class RoutePoint {
    public final double latitude;
    public final double longitude;

    public RoutePoint(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("latitude", latitude).put("longitude", longitude);
    }

    public static RoutePoint fromJson(JSONObject object) {
        return new RoutePoint(object.optDouble("latitude"), object.optDouble("longitude"));
    }
}
