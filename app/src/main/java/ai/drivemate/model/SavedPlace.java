package ai.drivemate.model;

import org.json.JSONException;
import org.json.JSONObject;

public class SavedPlace {
    public final String name;
    public final String kind;
    public final double latitude;
    public final double longitude;
    public final String address;
    public final long updatedAt;
    public final boolean favorite;

    public SavedPlace(String name, String kind, double latitude, double longitude, String address, long updatedAt, boolean favorite) {
        this.name = name;
        this.kind = kind;
        this.latitude = latitude;
        this.longitude = longitude;
        this.address = address;
        this.updatedAt = updatedAt;
        this.favorite = favorite;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("name", name);
        object.put("kind", kind);
        object.put("latitude", latitude);
        object.put("longitude", longitude);
        object.put("address", address);
        object.put("updatedAt", updatedAt);
        object.put("favorite", favorite);
        return object;
    }

    public static SavedPlace fromJson(JSONObject object) {
        return new SavedPlace(
                object.optString("name"),
                object.optString("kind", "custom"),
                object.optDouble("latitude"),
                object.optDouble("longitude"),
                object.optString("address"),
                object.optLong("updatedAt"),
                object.optBoolean("favorite", false)
        );
    }
}
