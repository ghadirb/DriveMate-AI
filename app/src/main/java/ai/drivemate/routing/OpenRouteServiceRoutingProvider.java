package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteStep;

/** Third routing fallback. ORS supplies GeoJSON, so its geometry can be drawn directly on the map. */
public class OpenRouteServiceRoutingProvider implements RoutingProvider {
    private String apiKey = "";
    private boolean enabled = true;

    public OpenRouteServiceRoutingProvider(String apiKey) {
        setApiKey(apiKey);
    }

    public void setApiKey(String value) {
        if (value != null && !value.trim().isEmpty()) apiKey = value.trim();
    }

    public boolean isConfigured() {
        return enabled && apiKey.length() >= 20;
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override public String name() {
        return "OpenRouteService";
    }

    @Override public RouteResult route(double originLat, double originLng, double destinationLat, double destinationLng)
            throws Exception {
        return routeWithWaypoints(originLat, originLng, null, destinationLat, destinationLng);
    }

    /** ORS accepts any number of coordinates in order; the response then has one "segment" per
     *  consecutive pair (origin->wp1, wp1->wp2, ..., wpN->destination), mirroring Neshan's/map.ir's
     *  per-leg structure. A null/empty waypoints list reduces this to exactly the previous request. */
    @Override public RouteResult routeWithWaypoints(double originLat, double originLng, List<RoutePoint> waypoints,
                                                    double destinationLat, double destinationLng) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("OpenRouteService API key is not configured.");
        JSONObject request = new JSONObject();
        JSONArray coordinates = new JSONArray();
        coordinates.put(new JSONArray().put(originLng).put(originLat));
        if (waypoints != null) for (RoutePoint stop : waypoints) {
            coordinates.put(new JSONArray().put(stop.longitude).put(stop.latitude));
        }
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
        ArrayList<RouteStep> steps = parseSteps(properties, geometry, waypoints, destinationLat, destinationLng);
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

    /** Parses every ORS segment (one per consecutive coordinate pair) in order into a single flat
     *  step list. A synthetic zero-distance RouteStep (waypointOrdinal = segment index) marks
     *  arrival at each intermediate stop, matching Neshan's/map.ir's per-leg parsing. */
    private ArrayList<RouteStep> parseSteps(JSONObject properties, ArrayList<RoutePoint> geometry, List<RoutePoint> waypoints,
                                            double destinationLat, double destinationLng) {
        ArrayList<RouteStep> steps = new ArrayList<>();
        JSONArray segments = properties == null ? null : properties.optJSONArray("segments");
        if (segments != null) for (int segmentIndex = 0; segmentIndex < segments.length(); segmentIndex++) {
            JSONObject segment = segments.optJSONObject(segmentIndex);
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
            boolean isLastSegment = segmentIndex == segments.length() - 1;
            if (!isLastSegment && waypoints != null && segmentIndex < waypoints.size()) {
                RoutePoint stop = waypoints.get(segmentIndex);
                steps.add(new RouteStep(stop.latitude, stop.longitude, "Arrive at stop", 0, null, segmentIndex));
            }
        }
        steps.add(new RouteStep(destinationLat, destinationLng, "Arrive at destination", 0));
        return steps;
    }
}
