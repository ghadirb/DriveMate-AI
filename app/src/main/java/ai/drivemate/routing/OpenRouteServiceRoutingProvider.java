package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteStep;

/** Third routing fallback. ORS supplies GeoJSON, so its geometry can be drawn directly on the map. */
public class OpenRouteServiceRoutingProvider implements RoutingProvider {
    private final String apiKey;

    public OpenRouteServiceRoutingProvider(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean isConfigured() {
        return apiKey.length() >= 20;
    }

    @Override public String name() {
        return "OpenRouteService";
    }

    @Override public RouteResult route(double originLat, double originLng, double destinationLat, double destinationLng)
            throws Exception {
        if (!isConfigured()) throw new IllegalStateException("OpenRouteService API key is not configured.");
        JSONObject request = new JSONObject();
        JSONArray coordinates = new JSONArray();
        coordinates.put(new JSONArray().put(originLng).put(originLat));
        coordinates.put(new JSONArray().put(destinationLng).put(destinationLat));
        request.put("coordinates", coordinates);
        request.put("instructions", true);
        request.put("language", "en");
        JSONObject body = RoutingHttp.postJson(
                "https://api.openrouteservice.org/v2/directions/driving-car/geojson",
                "Authorization", apiKey, request);
        JSONArray features = body.optJSONArray("features");
        JSONObject feature = features == null ? null : features.optJSONObject(0);
        if (feature == null) throw new IllegalStateException("OpenRouteService returned no route.");
        JSONObject properties = feature.optJSONObject("properties");
        JSONObject summary = properties == null ? null : properties.optJSONObject("summary");
        int distance = summary == null ? 0 : (int) Math.round(summary.optDouble("distance"));
        int duration = summary == null ? 0 : (int) Math.round(summary.optDouble("duration"));
        ArrayList<RoutePoint> geometry = parseGeometry(feature.optJSONObject("geometry"));
        ArrayList<RouteStep> steps = parseSteps(properties, geometry, destinationLat, destinationLng);
        return new RouteResult(name(), distance, duration, "OpenRouteService fallback", steps, geometry);
    }

    private ArrayList<RoutePoint> parseGeometry(JSONObject geometry) {
        ArrayList<RoutePoint> points = new ArrayList<>();
        JSONArray rawPoints = geometry == null ? null : geometry.optJSONArray("coordinates");
        if (rawPoints == null) return points;
        for (int index = 0; index < rawPoints.length(); index++) {
            JSONArray point = rawPoints.optJSONArray(index);
            if (point != null && point.length() >= 2) {
                points.add(new RoutePoint(point.optDouble(1), point.optDouble(0)));
            }
        }
        return points;
    }

    private ArrayList<RouteStep> parseSteps(JSONObject properties, ArrayList<RoutePoint> geometry,
                                            double destinationLat, double destinationLng) {
        ArrayList<RouteStep> steps = new ArrayList<>();
        JSONArray segments = properties == null ? null : properties.optJSONArray("segments");
        JSONObject segment = segments == null ? null : segments.optJSONObject(0);
        JSONArray rawSteps = segment == null ? null : segment.optJSONArray("steps");
        if (rawSteps != null) for (int index = 0; index < rawSteps.length(); index++) {
            JSONObject step = rawSteps.optJSONObject(index);
            JSONArray wayPoints = step == null ? null : step.optJSONArray("way_points");
            int geometryIndex = wayPoints == null ? -1 : wayPoints.optInt(0, -1);
            if (geometryIndex < 0 || geometryIndex >= geometry.size()) continue;
            RoutePoint point = geometry.get(geometryIndex);
            steps.add(new RouteStep(point.latitude, point.longitude, step.optString("instruction"),
                    (int) Math.round(step.optDouble("distance"))));
        }
        steps.add(new RouteStep(destinationLat, destinationLng, "Arrive at destination", 0));
        return steps;
    }
}
