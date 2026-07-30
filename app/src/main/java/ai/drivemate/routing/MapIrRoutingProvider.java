package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteStep;
import ai.drivemate.model.SpeedLimitPoint;

public class MapIrRoutingProvider implements RoutingProvider {
    private String apiKey;

    public MapIrRoutingProvider(String apiKey) { this.apiKey = apiKey; }

    public void setApiKey(String apiKey) {
        if (apiKey != null && !apiKey.trim().isEmpty()) this.apiKey = apiKey.trim();
    }

    String apiKey() { return apiKey == null || apiKey.trim().isEmpty() ? null : apiKey; }
    public boolean isConfigured() { return apiKey() != null; }

    @Override public String name() { return "map.ir"; }

    @Override public RouteResult route(double originLat, double originLng, double destinationLat, double destinationLng) throws Exception {
        return routes(originLat, originLng, destinationLat, destinationLng).get(0);
    }

    @Override public List<RouteResult> routes(double originLat, double originLng, double destinationLat, double destinationLng) throws Exception {
        return routesWithWaypoints(originLat, originLng, null, destinationLat, destinationLng);
    }

    @Override public RouteResult routeWithWaypoints(double originLat, double originLng, List<RoutePoint> waypoints,
                                                    double destinationLat, double destinationLng) throws Exception {
        return routesWithWaypoints(originLat, originLng, waypoints, destinationLat, destinationLng).get(0);
    }

    /** map.ir's routing endpoint is OSRM-style: extra stops are just extra coordinates chained
     *  with ";" in the path itself (no separate waypoints parameter), and the response then
     *  contains one leg per consecutive coordinate pair - origin->wp1, wp1->wp2, ..., wpN->destination.
     *  A null/empty waypoints list reduces this to exactly the previous single-leg request. */
    @Override public List<RouteResult> routesWithWaypoints(double originLat, double originLng, List<RoutePoint> waypoints,
                                                           double destinationLat, double destinationLng) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("map.ir API key is not configured.");
        StringBuilder coordinates = new StringBuilder();
        coordinates.append(originLng).append(',').append(originLat);
        if (waypoints != null) for (RoutePoint stop : waypoints) {
            coordinates.append(';').append(stop.longitude).append(',').append(stop.latitude);
        }
        coordinates.append(';').append(destinationLng).append(',').append(destinationLat);
        String url = "https://map.ir/routes/route/v1/driving/" + coordinates
                + "?alternatives=true&steps=true&overview=full&geometries=polyline";
        JSONObject object = RoutingHttp.getJson(url, "x-api-key", apiKey);
        JSONArray rawRoutes = object.optJSONArray("routes");
        if (rawRoutes == null || rawRoutes.length() == 0) throw new IllegalStateException("map.ir returned no route.");
        ArrayList<RouteResult> results = new ArrayList<>();
        for (int i = 0; i < rawRoutes.length() && i < 3; i++) {
            results.add(parseRoute(rawRoutes.getJSONObject(i), originLat, originLng, waypoints, destinationLat, destinationLng));
        }
        return results;
    }

    /** Parses every OSRM leg in order into one flat step list, summing distance/duration across
     *  legs. A synthetic zero-distance RouteStep (waypointOrdinal = leg index) marks arrival at
     *  each intermediate stop, exactly matching NeshanRoutingProvider's parseRouteWithLegs. */
    private RouteResult parseRoute(JSONObject route, double originLat, double originLng, List<RoutePoint> waypoints,
                                   double destinationLat, double destinationLng) throws Exception {
        ArrayList<RouteStep> steps = new ArrayList<>();
        ArrayList<SpeedLimitPoint> speedLimits = new ArrayList<>();
        JSONArray legs = route.optJSONArray("legs");
        if (legs != null) for (int legIndex = 0; legIndex < legs.length(); legIndex++) {
            JSONArray rawSteps = legs.getJSONObject(legIndex).optJSONArray("steps");
            if (rawSteps != null) for (int i = 0; i < rawSteps.length(); i++) {
                JSONObject step = rawSteps.optJSONObject(i);
                JSONObject maneuver = step == null ? null : step.optJSONObject("maneuver");
                if (maneuver != null) {
                    JSONArray point = maneuver.optJSONArray("location");
                    double longitude = point == null ? destinationLng : point.optDouble(0, destinationLng);
                    double latitude = point == null ? destinationLat : point.optDouble(1, destinationLat);
                    steps.add(new RouteStep(latitude, longitude, maneuver.optString("instruction"), step.optInt("distance"),
                            parseLaneGuidance(step)));
                    int speedLimit = explicitSpeedLimit(step);
                    if (speedLimit > 0) speedLimits.add(new SpeedLimitPoint(latitude, longitude, speedLimit, name()));
                }
            }
            boolean isLastLeg = legIndex == legs.length() - 1;
            if (!isLastLeg && waypoints != null && legIndex < waypoints.size()) {
                RoutePoint stop = waypoints.get(legIndex);
                steps.add(new RouteStep(stop.latitude, stop.longitude, "Arrive at stop", 0, null, legIndex));
            }
        }
        if (steps.isEmpty()) steps.add(new RouteStep(destinationLat, destinationLng, "Arrive at destination", 0));
        return new RouteResult(name(), route.optInt("distance"), route.optInt("duration"), route.optString("weight_name"), steps,
                RouteGeometry.fromRoute(route, steps, originLat, originLng, destinationLat, destinationLng), speedLimits);
    }

    /** Parses map.ir's OSRM-style intersections[0].lanes when the response actually includes
     * it. intersections[0] is the intersection at the start of this step, i.e. the one where
     * the maneuver takes place - this is never inferred from the instruction text or road class,
     * only read verbatim from an explicit "lanes" array if present. */
    private ai.drivemate.model.LaneGuidance parseLaneGuidance(JSONObject step) {
        JSONArray intersections = step.optJSONArray("intersections");
        JSONObject firstIntersection = intersections == null || intersections.length() == 0
                ? null : intersections.optJSONObject(0);
        JSONArray lanesArray = firstIntersection == null ? null : firstIntersection.optJSONArray("lanes");
        if (lanesArray == null || lanesArray.length() < 2) return null;
        ArrayList<String> indications = new ArrayList<>();
        ArrayList<Boolean> validForManeuver = new ArrayList<>();
        for (int i = 0; i < lanesArray.length(); i++) {
            JSONObject lane = lanesArray.optJSONObject(i);
            if (lane == null) continue;
            JSONArray laneIndications = lane.optJSONArray("indications");
            String primary = laneIndications == null || laneIndications.length() == 0
                    ? "" : laneIndications.optString(0, "");
            indications.add(primary);
            validForManeuver.add(lane.optBoolean("valid", false));
        }
        return indications.isEmpty() ? null : new ai.drivemate.model.LaneGuidance(indications, validForManeuver);
    }

    /** map.ir's documented route schema currently has no maxspeed field. Keep this strict adapter
     * so an explicit future numeric field is usable without inventing a speed from road category. */
    private int explicitSpeedLimit(JSONObject step) {
        String[] keys = {"maxspeed", "maxSpeed", "speed_limit", "speedLimit"};
        for (String key : keys) {
            if (!step.has(key)) continue;
            String value = String.valueOf(step.opt(key)).trim().toLowerCase();
            if (!value.matches("\\d{1,3}(\\s*(km/h|kph))?")) continue;
            try {
                int parsed = Integer.parseInt(value.replaceAll("[^0-9]", ""));
                if (parsed >= 10 && parsed <= 160) return parsed;
            } catch (NumberFormatException ignored) { }
        }
        return -1;
    }
}
