package ai.drivemate.routing;

import android.util.Log;

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
    private static final String TAG = "DriveMateTomTom";
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
                + "&traffic=true&routeType=fastest&travelMode=car&routeRepresentation=polyline"
                + "&instructionsType=text&instructionAnnouncementPoints=all"
                + "&maxAlternatives=2&alternativeType=anyRoute"
                + "&sectionType=importantRoadStretch&language=en-US";
        JSONObject body = RoutingHttp.getJson(url);
        logRawResponse(body);
        JSONArray routes = body.optJSONArray("routes");
        if (routes == null || routes.length() == 0) throw new IllegalStateException("TomTom returned no route.");
        ArrayList<RouteResult> results = new ArrayList<>();
        for (int index = 0; index < routes.length() && index < 3; index++) {
            JSONObject route = routes.optJSONObject(index);
            if (route == null) continue;
            try {
<<<<<<< Updated upstream
                results.add(parseRoute(route, waypoints, destinationLat, destinationLng));
            } catch (RuntimeException parseError) {
                // One malformed alternate (an unexpected field shape, a missing section) must not
                // cost every other route in the response - this used to throw out of the whole
                // loop, discarding a perfectly good primary route (and any other alternatives)
                // just because route index 1 or 2 didn't parse, which looked like "TomTom only
                // ever shows one route" even when the API had actually returned several.
                android.util.Log.w("TomTomRoutingProvider", "Skipping unparsable route at index " + index, parseError);
=======
                results.add(parseRoute(route, originLat, originLng, waypoints, destinationLat, destinationLng));
            } catch (RuntimeException parseError) {
                Log.w(TAG, "Skipping unparsable route at index " + index, parseError);
>>>>>>> Stashed changes
            }
        }
        if (results.isEmpty()) throw new IllegalStateException("TomTom returned an invalid route.");
        return results;
    }

    private RouteResult parseRoute(JSONObject route, double originLat, double originLng, List<RoutePoint> waypoints,
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
                if (point == null || !point.has("latitude") || !point.has("longitude")) continue;
                double latitude = point.optDouble("latitude", Double.NaN);
                double longitude = point.optDouble("longitude", Double.NaN);
                if (!isValidCoordinate(latitude, longitude)) {
                    Log.w(TAG, "Skipping invalid routes[].legs[].points coordinate lat=" + latitude + " lon=" + longitude);
                    continue;
                }
                geometry.add(new RoutePoint(latitude, longitude));
            }
        }
        logGeometry(originLat, originLng, destinationLat, destinationLng, geometry);
        JSONObject guidance = route.optJSONObject("guidance");
        JSONArray instructions = guidance == null ? null : guidance.optJSONArray("instructions");
        int previousRouteOffset = 0;
        if (instructions != null) for (int index = 0; index < instructions.length(); index++) {
            JSONObject instruction = instructions.optJSONObject(index);
            JSONObject point = instruction == null ? null : instruction.optJSONObject("point");
            if (point == null) continue;
            int routeOffset = Math.max(previousRouteOffset, instruction.optInt("routeOffsetInMeters", previousRouteOffset));
            int segmentDistance = Math.max(0, routeOffset - previousRouteOffset);
            JSONObject mainAnnouncement = instruction.optJSONObject("mainAnnouncement");
            int announcementDistance = mainAnnouncement == null ? 0
                    : Math.max(0, mainAnnouncement.optInt("distanceInMeters", 0));
            double latitude = point.optDouble("latitude");
            double longitude = point.optDouble("longitude");
            String text = persianInstruction(instruction, announcementDistance,
                    roadNameAt(route, geometry, latitude, longitude));
            anchors.add(new StepAnchor(nearestGeometryIndex(geometry, latitude, longitude),
                    new RouteStep(latitude, longitude, text,
                            segmentDistance > 0 ? segmentDistance : announcementDistance)));
            previousRouteOffset = routeOffset;
        }
        if (waypoints != null) for (RoutePoint waypoint : waypoints) {
            anchors.add(new StepAnchor(nearestGeometryIndex(geometry, waypoint.latitude, waypoint.longitude),
                    new RouteStep(waypoint.latitude, waypoint.longitude, "به توقف میانی می‌رسید", 0, null, waypointOrdinal++)));
        }
        java.util.Collections.sort(anchors, (left, right) -> Integer.compare(left.geometryIndex, right.geometryIndex));
        ArrayList<RouteStep> steps = new ArrayList<>();
        for (StepAnchor anchor : anchors) steps.add(anchor.step);
        steps.add(new RouteStep(destinationLat, destinationLng, "به مقصد می‌رسید", 0));
        String detail = summary == null ? "" : "traffic delay " + summary.optInt("trafficDelayInSeconds");
        return new RouteResult(name(), distance, duration, detail, steps, geometry);
    }

    private static String point(double latitude, double longitude) {
        return latitude + "," + longitude;
    }

    private static void logRawResponse(JSONObject body) {
        String raw = body == null ? "" : body.toString();
        int maximumLogLength = 12_000;
        Log.d(TAG, "raw Calculate Route response chars=" + raw.length() + " payload="
                + (raw.length() <= maximumLogLength ? raw : raw.substring(0, maximumLogLength) + "...[truncated]"));
    }

    private static void logGeometry(double originLat, double originLng, double destinationLat, double destinationLng,
                                    List<RoutePoint> geometry) {
        StringBuilder samples = new StringBuilder();
        int[] candidates = {0, geometry.size() / 4, geometry.size() / 2, (geometry.size() * 3) / 4, geometry.size() - 1};
        int previous = -1;
        for (int index : candidates) {
            if (index < 0 || index >= geometry.size() || index == previous) continue;
            RoutePoint point = geometry.get(index);
            if (samples.length() > 0) samples.append(" | ");
            samples.append(index).append('=').append(point.latitude).append(',').append(point.longitude);
            previous = index;
        }
        Log.i(TAG, "geometry source=routes[].legs[].points encoding=coordinate-array"
                + " coordinateOrder=latitude,longitude parsedPoints=" + geometry.size()
                + " origin=" + originLat + ',' + originLng
                + " destination=" + destinationLat + ',' + destinationLng
                + " samples=[" + samples + ']');
    }

    private static boolean isValidCoordinate(double latitude, double longitude) {
        return !Double.isNaN(latitude) && !Double.isNaN(longitude)
                && !Double.isInfinite(latitude) && !Double.isInfinite(longitude)
                && latitude >= -90d && latitude <= 90d && longitude >= -180d && longitude <= 180d;
    }

    private static String persianInstruction(JSONObject instruction, int announcementDistanceMeters, String routeRoadName) {
        String maneuver = instruction.optString("maneuver",
                instruction.optString("instructionType", "")).toUpperCase(java.util.Locale.US);
        String road = routeRoadName == null || routeRoadName.trim().isEmpty()
                ? directRoadName(instruction) : routeRoadName.trim();
        String prefix = announcementDistanceMeters > 0
                ? distanceLabel(announcementDistanceMeters) + " جلوتر " : "";
        String text;
        if (maneuver.contains("ARRIVE")) return "به مقصد می‌رسید";
        if (maneuver.contains("DEPART")) return "به سمت مقصد حرکت کنید";
        if (maneuver.contains("UTURN")) text = "دور بزنید";
        else if (maneuver.contains("ROUNDABOUT")) {
            int exit = instruction.optInt("roundaboutExitNumber", 0);
            text = exit > 0 ? "وارد میدان شوید و از خروجی " + persianDigits(exit) + " خارج شوید" : "وارد میدان شوید";
        } else if (maneuver.contains("LEFT")) text = maneuver.contains("KEEP") ? "در سمت چپ بمانید" : "به چپ بپیچید";
        else if (maneuver.contains("RIGHT")) text = maneuver.contains("KEEP") ? "در سمت راست بمانید" : "به راست بپیچید";
        else if (maneuver.contains("EXIT")) text = "از خروجی خارج شوید";
        else if (maneuver.contains("ENTER")) text = "وارد مسیر شوید";
        else if (maneuver.contains("KEEP")) text = "در مسیر ادامه دهید";
        else if (maneuver.contains("TURN")) text = turnFromAngle(instruction);
        else text = "مستقیم ادامه دهید";
        return prefix + text + roadSuffix(road);
    }

    private static String roadNameAt(JSONObject route, List<RoutePoint> geometry, double latitude, double longitude) {
        if (geometry == null || geometry.isEmpty()) return "";
        int pointIndex = nearestGeometryIndex(geometry, latitude, longitude);
        JSONArray sections = route.optJSONArray("sections");
        if (sections == null) return "";
        for (int index = 0; index < sections.length(); index++) {
            JSONObject section = sections.optJSONObject(index);
            if (section == null || !"IMPORTANT_ROAD_STRETCH".equals(section.optString("sectionType"))) continue;
            int start = section.optInt("startPointIndex", -1);
            int end = section.optInt("endPointIndex", -1);
            if (start <= pointIndex && pointIndex <= end) {
                String road = textValue(section.opt("streetName"));
                if (!road.isEmpty()) return road;
                JSONArray roadNumbers = section.optJSONArray("roadNumbers");
                if (roadNumbers != null && roadNumbers.length() > 0) return textValue(roadNumbers.opt(0));
            }
        }
        return "";
    }

    private static String directRoadName(JSONObject instruction) {
        String road = instruction.optString("street", "").trim();
        if (road.isEmpty()) road = textValue(instruction.opt("streetName"));
        if (road.isEmpty()) road = instruction.optString("signpostText", "").trim();
        if (road.isEmpty()) {
            JSONArray roadNumbers = instruction.optJSONArray("roadNumbers");
            if (roadNumbers != null && roadNumbers.length() > 0) road = textValue(roadNumbers.opt(0));
        }
        return road;
    }

    private static String textValue(Object value) {
        if (value instanceof JSONObject) return ((JSONObject) value).optString("text", "").trim();
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value).trim();
    }

    private static String turnFromAngle(JSONObject instruction) {
        double angle = instruction.optDouble("turnAngleInDecimalDegrees", 0d);
        if (angle >= 20d) return "به راست بپیچید";
        if (angle <= -20d) return "به چپ بپیچید";
        return "در مسیر ادامه دهید";
    }

    private static String roadSuffix(String road) {
        return road == null || road.trim().isEmpty() ? "" : " به سمت " + road.trim();
    }

    private static String distanceLabel(int meters) {
        if (meters < 1000) return persianDigits(Math.max(10, Math.round(meters / 10f) * 10)) + " متر";
        String kilometers = String.format(java.util.Locale.US, "%.1f", meters / 1000d);
        return persianDigits(kilometers) + " کیلومتر";
    }

    private static String persianDigits(Object value) {
        String latin = String.valueOf(value);
        StringBuilder result = new StringBuilder(latin.length());
        for (int index = 0; index < latin.length(); index++) {
            char character = latin.charAt(index);
            result.append(character >= '0' && character <= '9' ? (char) ('۰' + character - '0') : character);
        }
        return result.toString();
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
