package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteStep;

/** TomTom route adapter. It keeps the provider response independent from the map renderer. */
public final class TomTomRoutingProvider implements RoutingProvider {
    private String apiKey = "";
    private boolean enabled = true;

    public TomTomRoutingProvider(String apiKey) {
        setApiKey(apiKey);
    }

    public void setApiKey(String value) {
        if (value != null && !value.trim().isEmpty()) apiKey = value.trim();
    }

    public boolean isConfigured() {
        return enabled && apiKey != null && apiKey.length() >= 20;
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override public String name() {
        return "TomTom";
    }

    @Override public RouteResult route(double originLat, double originLng, double destinationLat, double destinationLng)
            throws Exception {
        return routes(originLat, originLng, destinationLat, destinationLng).get(0);
    }

    @Override public List<RouteResult> routes(double originLat, double originLng, double destinationLat, double destinationLng)
            throws Exception {
        return routesWithWaypoints(originLat, originLng, null, destinationLat, destinationLng);
    }

    @Override public RouteResult routeWithWaypoints(double originLat, double originLng, List<RoutePoint> waypoints,
                                                    double destinationLat, double destinationLng) throws Exception {
        return routesWithWaypoints(originLat, originLng, waypoints, destinationLat, destinationLng).get(0);
    }

    @Override public List<RouteResult> routesWithWaypoints(double originLat, double originLng, List<RoutePoint> waypoints,
                                                           double destinationLat, double destinationLng) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("TomTom API key is not configured.");
        StringBuilder locations = new StringBuilder(point(originLat, originLng));
        if (waypoints != null) for (RoutePoint waypoint : waypoints) {
            locations.append(':').append(point(waypoint.latitude, waypoint.longitude));
        }
        locations.append(':').append(point(destinationLat, destinationLng));
        String url = "https://api.tomtom.com/routing/1/calculateRoute/" + locations + "/json"
                + "?key=" + URLEncoder.encode(apiKey, "UTF-8")
                + "&traffic=true&routeType=fastest&travelMode=car&instructionsType=text"
                + "&computeAlternativeRoutes=true&maxAlternatives=2&language=en-US";
        JSONObject body = RoutingHttp.getJson(url);
        JSONArray routes = body.optJSONArray("routes");
        if (routes == null || routes.length() == 0) throw new IllegalStateException("TomTom returned no route.");
        ArrayList<RouteResult> results = new ArrayList<>();
        for (int index = 0; index < routes.length() && index < 3; index++) {
            JSONObject route = routes.optJSONObject(index);
            if (route != null) results.add(parseRoute(route, waypoints, destinationLat, destinationLng));
        }
        if (results.isEmpty()) throw new IllegalStateException("TomTom returned an invalid route.");
        return results;
    }

    private RouteResult parseRoute(JSONObject route, List<RoutePoint> waypoints,
                                   double destinationLat, double destinationLng) {
        JSONObject summary = route.optJSONObject("summary");
        int distance = summary == null ? 0 : summary.optInt("lengthInMeters");
        int duration = summary == null ? 0 : summary.optInt("travelTimeInSeconds");
        ArrayList<RoutePoint> geometry = new ArrayList<>();
        ArrayList<StepAnchor> anchors = new ArrayList<>();
        JSONArray legs = route.optJSONArray("legs");
        int waypointOrdinal = 0;
        if (legs != null) for (int legIndex = 0; legIndex < legs.length(); legIndex++) {
            JSONObject leg = legs.optJSONObject(legIndex);
            JSONArray points = leg == null ? null : leg.optJSONArray("points");
            if (points != null) for (int pointIndex = 0; pointIndex < points.length(); pointIndex++) {
                JSONObject point = points.optJSONObject(pointIndex);
                if (point != null) geometry.add(new RoutePoint(point.optDouble("latitude"), point.optDouble("longitude")));
            }
        }
        JSONObject guidance = route.optJSONObject("guidance");
        JSONArray instructions = guidance == null ? null : guidance.optJSONArray("instructions");
        if (instructions != null) for (int index = 0; index < instructions.length(); index++) {
            JSONObject instruction = instructions.optJSONObject(index);
            JSONObject point = instruction == null ? null : instruction.optJSONObject("point");
            if (point == null) continue;
            String text = instruction.optString("message");
            if (text.isEmpty()) text = instruction.optString("instructionType");
            double latitude = point.optDouble("latitude");
            double longitude = point.optDouble("longitude");
            anchors.add(new StepAnchor(nearestGeometryIndex(geometry, latitude, longitude),
                    new RouteStep(latitude, longitude, text, 0)));
        }
        if (waypoints != null) for (RoutePoint waypoint : waypoints) {
            anchors.add(new StepAnchor(nearestGeometryIndex(geometry, waypoint.latitude, waypoint.longitude),
                    new RouteStep(waypoint.latitude, waypoint.longitude, "Arrive at stop", 0, null, waypointOrdinal++)));
        }
        java.util.Collections.sort(anchors, (left, right) -> Integer.compare(left.geometryIndex, right.geometryIndex));
        ArrayList<RouteStep> steps = new ArrayList<>();
        for (StepAnchor anchor : anchors) steps.add(anchor.step);
        steps.add(new RouteStep(destinationLat, destinationLng, "Arrive at destination", 0));
        String detail = summary == null ? "" : "traffic delay " + summary.optInt("trafficDelayInSeconds");
        return new RouteResult(name(), distance, duration, detail, steps, geometry);
    }

    private static String point(double latitude, double longitude) {
        return latitude + "," + longitude;
    }

    private static int nearestGeometryIndex(List<RoutePoint> geometry, double latitude, double longitude) {
        int closest = 0;
        double closestDistance = Double.MAX_VALUE;
        for (int index = 0; index < geometry.size(); index++) {
            RoutePoint point = geometry.get(index);
            double deltaLat = point.latitude - latitude;
            double deltaLon = point.longitude - longitude;
            double squaredDistance = deltaLat * deltaLat + deltaLon * deltaLon;
            if (squaredDistance < closestDistance) {
                closestDistance = squaredDistance;
                closest = index;
            }
        }
        return closest;
    }

    private static final class StepAnchor {
        final int geometryIndex;
        final RouteStep step;

        StepAnchor(int geometryIndex, RouteStep step) {
            this.geometryIndex = geometryIndex;
            this.step = step;
        }
    }
}
