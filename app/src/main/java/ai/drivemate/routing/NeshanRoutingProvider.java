package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.LaneGuidance;
import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteStep;
import ai.drivemate.model.SpeedLimitPoint;

public class NeshanRoutingProvider implements RoutingProvider {
    private String apiKey;

    public NeshanRoutingProvider(String apiKey) { this.apiKey = apiKey; }

    public void setApiKey(String apiKey) {
        if (apiKey != null && !apiKey.trim().isEmpty()) this.apiKey = apiKey.trim();
    }

    String apiKey() { return apiKey == null || apiKey.trim().isEmpty() ? null : apiKey; }
    public boolean isConfigured() { return apiKey() != null; }

    @Override public String name() { return "Neshan"; }

    @Override public RouteResult route(double originLat, double originLng, double destinationLat, double destinationLng) throws Exception {
        return routes(originLat, originLng, destinationLat, destinationLng).get(0);
    }

    @Override public List<RouteResult> routes(double originLat, double originLng, double destinationLat, double destinationLng) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("Neshan API key is not configured.");
        String url = "https://api.neshan.org/v4/direction?type=car"
                + "&origin=" + originLat + "," + originLng
                + "&destination=" + destinationLat + "," + destinationLng
                + "&alternative=true";
        JSONObject object = RoutingHttp.getJson(url, "Api-Key", apiKey);
        JSONArray rawRoutes = object.optJSONArray("routes");
        if (rawRoutes == null || rawRoutes.length() == 0) throw new IllegalStateException("Neshan returned no route.");
        ArrayList<RouteResult> results = new ArrayList<>();
        for (int i = 0; i < rawRoutes.length() && i < 3; i++) {
            results.add(parseRoute(rawRoutes.getJSONObject(i), originLat, originLng, destinationLat, destinationLng));
        }
        return results;
    }

    private RouteResult parseRoute(JSONObject route, double originLat, double originLng,
                                   double destinationLat, double destinationLng) throws Exception {
        return parseRouteWithLegs(route, originLat, originLng, null, destinationLat, destinationLng);
    }

    @Override public RouteResult routeWithWaypoints(double originLat, double originLng, List<RoutePoint> waypoints,
                                                    double destinationLat, double destinationLng) throws Exception {
        return routesWithWaypoints(originLat, originLng, waypoints, destinationLat, destinationLng).get(0);
    }

    /** Neshan v4 direction accepts extra stops as "waypoints=lat,lng|lat,lng" (order preserved),
     *  returning one leg per consecutive pair (origin->wp1, wp1->wp2, ..., wpN->destination) - see
     *  the routing-category docs. Falls back to the plain two-point call when there are no stops. */
    @Override public List<RouteResult> routesWithWaypoints(double originLat, double originLng, List<RoutePoint> waypoints,
                                                           double destinationLat, double destinationLng) throws Exception {
        if (waypoints == null || waypoints.isEmpty()) return routes(originLat, originLng, destinationLat, destinationLng);
        if (!isConfigured()) throw new IllegalStateException("Neshan API key is not configured.");
        StringBuilder waypointsParam = new StringBuilder();
        for (RoutePoint point : waypoints) {
            if (waypointsParam.length() > 0) waypointsParam.append('|');
            waypointsParam.append(point.latitude).append(',').append(point.longitude);
        }
        String url = "https://api.neshan.org/v4/direction?type=car"
                + "&origin=" + originLat + "," + originLng
                + "&destination=" + destinationLat + "," + destinationLng
                + "&waypoints=" + encode(waypointsParam.toString())
                + "&alternative=true";
        JSONObject object = RoutingHttp.getJson(url, "Api-Key", apiKey);
        JSONArray rawRoutes = object.optJSONArray("routes");
        if (rawRoutes == null || rawRoutes.length() == 0) throw new IllegalStateException("Neshan returned no route.");
        ArrayList<RouteResult> results = new ArrayList<>();
        for (int i = 0; i < rawRoutes.length() && i < 3; i++) {
            results.add(parseRouteWithLegs(rawRoutes.getJSONObject(i), originLat, originLng, waypoints, destinationLat, destinationLng));
        }
        return results;
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            return value;
        }
    }

    /** Parses every leg in order (one leg per origin/waypoint/destination pair) into a single flat
     *  step list, summing distance and duration across all legs. A synthetic zero-distance
     *  RouteStep (waypointOrdinal = leg index) marks arrival at each intermediate stop so
     *  NavigationEngine can fire onWaypointReached there instead of treating it as a normal turn.
     *  With waypoints == null/empty this reduces to exactly the previous single-leg behavior. */
    private RouteResult parseRouteWithLegs(JSONObject route, double originLat, double originLng, List<RoutePoint> waypoints,
                                           double destinationLat, double destinationLng) throws Exception {
        JSONArray legs = route.getJSONArray("legs");
        ArrayList<RouteStep> steps = new ArrayList<>();
        ArrayList<SpeedLimitPoint> speedLimits = new ArrayList<>();
        int totalDistance = 0;
        int totalDuration = 0;
        String summary = null;
        for (int legIndex = 0; legIndex < legs.length(); legIndex++) {
            JSONObject leg = legs.getJSONObject(legIndex);
            totalDistance += leg.optJSONObject("distance") == null ? 0 : leg.getJSONObject("distance").optInt("value");
            JSONObject trafficDuration = leg.optJSONObject("duration_in_traffic");
            if (trafficDuration == null) trafficDuration = leg.optJSONObject("durationInTraffic");
            totalDuration += trafficDuration != null ? trafficDuration.optInt("value")
                    : (leg.optJSONObject("duration") == null ? 0 : leg.getJSONObject("duration").optInt("value"));
            if (summary == null) summary = leg.optString("summary");
            JSONArray rawSteps = leg.optJSONArray("steps");
            if (rawSteps != null) for (int i = 0; i < rawSteps.length(); i++) {
                JSONObject step = rawSteps.optJSONObject(i);
                if (step == null || "arrive".equalsIgnoreCase(step.optString("type"))) continue;
                JSONObject stepDistance = step.optJSONObject("distance");
                JSONObject end = step.optJSONObject("end_location");
                JSONArray start = step.optJSONArray("start_location");
                double latitude = end != null ? end.optDouble("lat", Double.NaN)
                        : (start == null ? Double.NaN : start.optDouble(1, Double.NaN));
                double longitude = end != null ? end.optDouble("lng", Double.NaN)
                        : (start == null ? Double.NaN : start.optDouble(0, Double.NaN));
                if (!Double.isNaN(latitude) && !Double.isNaN(longitude)) {
                    steps.add(new RouteStep(latitude, longitude, step.optString("instruction"),
                            stepDistance == null ? 0 : stepDistance.optInt("value"), parseLaneGuidance(step)));
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
        // Neshan step coordinates mark the start of each maneuver. Keep arrival at the real
        // destination instead of the start of the provider's final arrive step.
        steps.add(new RouteStep(destinationLat, destinationLng, "Arrive at destination", 0));
        return new RouteResult(name(), totalDistance, totalDuration, summary == null ? "" : summary, steps,
                RouteGeometry.fromRoute(route, steps, originLat, originLng, destinationLat, destinationLng), speedLimits);
    }

    /** The documented Neshan v4 response has no confirmed per-lane field. This defensively checks
     * for an OSRM-style intersections[0].lanes array (Neshan's engine, like map.ir's, is commonly
     * OSRM-derived) purely in case a future response includes it - identical parsing to
     * MapIrRoutingProvider.parseLaneGuidance. Returns null (never inferred/guessed) when absent,
     * so today's behavior is completely unchanged until such a field actually appears. */
    private LaneGuidance parseLaneGuidance(JSONObject step) {
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
        return indications.isEmpty() ? null : new LaneGuidance(indications, validForManeuver);
    }

    /** The documented Neshan response does not currently expose maxspeed. This parses only an
     * explicit numeric field if a future response adds one; no value is derived from ETA or road type. */
    private int explicitSpeedLimit(JSONObject step) {
        return numericValue(step, "maxspeed", "maxSpeed", "speed_limit", "speedLimit");
    }

    private int numericValue(JSONObject item, String... keys) {
        for (String key : keys) {
            if (!item.has(key)) continue;
            Object raw = item.opt(key);
            String value = String.valueOf(raw).trim().toLowerCase();
            if (!value.matches("\\d{1,3}(\\s*(km/h|kph))?")) continue;
            try {
                int parsed = Integer.parseInt(value.replaceAll("[^0-9]", ""));
                if (parsed >= 10 && parsed <= 160) return parsed;
            } catch (NumberFormatException ignored) { }
        }
        return -1;
    }
}
