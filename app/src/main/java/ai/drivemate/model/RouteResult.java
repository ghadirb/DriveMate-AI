package ai.drivemate.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RouteResult {
    public final String providerName;
    public final int distanceMeters;
    public final int durationSeconds;
    public final String rawSummary;
    public final List<RouteStep> steps;
    public final List<RoutePoint> geometry;
    /** Optional explicit numeric values returned by the routing provider itself. */
    public final List<SpeedLimitPoint> providerSpeedLimits;

    public RouteResult(String providerName, int distanceMeters, int durationSeconds, String rawSummary) {
        this.providerName = providerName;
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.rawSummary = rawSummary;
        this.steps = Collections.emptyList();
        this.geometry = Collections.emptyList();
        this.providerSpeedLimits = Collections.emptyList();
    }

    public RouteResult(String providerName, int distanceMeters, int durationSeconds, String rawSummary, List<RouteStep> steps) {
        this.providerName = providerName;
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.rawSummary = rawSummary;
        this.steps = steps == null ? Collections.emptyList() : steps;
        this.geometry = Collections.emptyList();
        this.providerSpeedLimits = Collections.emptyList();
    }

    public RouteResult(String providerName, int distanceMeters, int durationSeconds, String rawSummary,
                       List<RouteStep> steps, List<RoutePoint> geometry) {
        this(providerName, distanceMeters, durationSeconds, rawSummary, steps, geometry, Collections.emptyList());
    }

    public RouteResult(String providerName, int distanceMeters, int durationSeconds, String rawSummary,
                       List<RouteStep> steps, List<RoutePoint> geometry, List<SpeedLimitPoint> providerSpeedLimits) {
        this.providerName = providerName;
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.rawSummary = rawSummary;
        this.steps = steps == null ? Collections.emptyList() : steps;
        this.geometry = geometry == null ? Collections.emptyList() : geometry;
        this.providerSpeedLimits = providerSpeedLimits == null ? Collections.emptyList() : providerSpeedLimits;
    }

    /** Serializes the fields NavigationEngine/voice guidance actually consume - enough to fully
     *  restore an in-progress trip (see NavigationSessionStore). Never called on the hot GPS path;
     *  only at route-selection time and when the active session is checkpointed. */
    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("providerName", providerName);
        object.put("distanceMeters", distanceMeters);
        object.put("durationSeconds", durationSeconds);
        object.put("rawSummary", rawSummary);
        JSONArray stepsArray = new JSONArray();
        for (RouteStep step : steps) stepsArray.put(step.toJson());
        object.put("steps", stepsArray);
        JSONArray geometryArray = new JSONArray();
        for (RoutePoint point : geometry) geometryArray.put(point.toJson());
        object.put("geometry", geometryArray);
        JSONArray speedLimitsArray = new JSONArray();
        for (SpeedLimitPoint point : providerSpeedLimits) speedLimitsArray.put(point.toJson());
        object.put("providerSpeedLimits", speedLimitsArray);
        return object;
    }

    public static RouteResult fromJson(JSONObject object) throws JSONException {
        List<RouteStep> steps = new ArrayList<>();
        JSONArray stepsArray = object.optJSONArray("steps");
        if (stepsArray != null) {
            for (int i = 0; i < stepsArray.length(); i++) steps.add(RouteStep.fromJson(stepsArray.getJSONObject(i)));
        }
        List<RoutePoint> geometry = new ArrayList<>();
        JSONArray geometryArray = object.optJSONArray("geometry");
        if (geometryArray != null) {
            for (int i = 0; i < geometryArray.length(); i++) geometry.add(RoutePoint.fromJson(geometryArray.getJSONObject(i)));
        }
        List<SpeedLimitPoint> speedLimits = new ArrayList<>();
        JSONArray speedLimitsArray = object.optJSONArray("providerSpeedLimits");
        if (speedLimitsArray != null) {
            for (int i = 0; i < speedLimitsArray.length(); i++) speedLimits.add(SpeedLimitPoint.fromJson(speedLimitsArray.getJSONObject(i)));
        }
        return new RouteResult(
                object.optString("providerName"),
                object.optInt("distanceMeters"),
                object.optInt("durationSeconds"),
                object.optString("rawSummary"),
                steps, geometry, speedLimits
        );
    }
}
