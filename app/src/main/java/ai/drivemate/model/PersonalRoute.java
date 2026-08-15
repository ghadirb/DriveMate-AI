package ai.drivemate.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A user-created route. Every saved point is a routing waypoint; intermediate points are mandatory stops. */
public final class PersonalRoute {
    public final String id;
    public final String name;
    public final long createdAt;
    public final List<RoutePoint> points;

    public PersonalRoute(String id, String name, long createdAt, List<RoutePoint> points) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "مسیر شخصی" : name;
        this.createdAt = createdAt;
        ArrayList<RoutePoint> copy = new ArrayList<>();
        if (points != null) {
            for (RoutePoint point : points) if (point != null) copy.add(point);
        }
        this.points = Collections.unmodifiableList(copy);
    }

    public JSONObject toJson() throws org.json.JSONException {
        JSONArray values = new JSONArray();
        for (RoutePoint point : points) {
            values.put(new JSONObject().put("lat", point.latitude).put("lng", point.longitude));
        }
        return new JSONObject().put("id", id).put("name", name).put("createdAt", createdAt).put("points", values);
    }

    public static PersonalRoute fromJson(JSONObject object) {
        ArrayList<RoutePoint> points = new ArrayList<>();
        JSONArray values = object.optJSONArray("points");
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                JSONObject point = values.optJSONObject(i);
                if (point != null && point.has("lat") && point.has("lng")) {
                    points.add(new RoutePoint(point.optDouble("lat"), point.optDouble("lng")));
                }
            }
        }
        return new PersonalRoute(object.optString("id"), object.optString("name", "مسیر شخصی"),
                object.optLong("createdAt"), points);
    }
}
