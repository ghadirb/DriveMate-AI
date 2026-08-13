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
    private boolean enabled = true;

    public MapIrRoutingProvider(String apiKey) { this.apiKey = apiKey; }

    public void setApiKey(String apiKey) {
        if (apiKey != null && !apiKey.trim().isEmpty()) this.apiKey = apiKey.trim();
    }

    String apiKey() { return !enabled || apiKey == null || apiKey.trim().isEmpty() ? null : apiKey; }
    public boolean isConfigured() { return apiKey() != null; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

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
                    steps.add(new RouteStep(latitude, longitude, persianInstruction(step, maneuver), step.optInt("distance"),
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

    /** map.ir is OSRM-style and commonly exposes maneuver type/modifier rather than a complete
     * instruction string. Build the spoken Persian instruction from those stable fields so voice
     * guidance never degenerates into a repeated generic "continue" message. */
    private String persianInstruction(JSONObject step, JSONObject maneuver) {
        String type = maneuver.optString("type", "").toLowerCase(java.util.Locale.ROOT);
        String modifier = maneuver.optString("modifier", "").toLowerCase(java.util.Locale.ROOT);
        String road = step.optString("name", maneuver.optString("name", "")).trim();
        String explicit = maneuver.optString("instruction", step.optString("instruction", "")).trim();
        // Diagnostic only: lets a logcat capture from a real drive show exactly what map.ir sent
        // for every maneuver (type/modifier/exit), so a roundabout that got voiced as a plain
        // "turn left" instead of "میدان ... خروجی" can be confirmed as either (a) map.ir's own
        // road data not tagging that junction as a roundabout at all - nothing to fix on our end -
        // or (b) a type/field value this method isn't recognizing, which would be. Cheap at debug
        // level; safe to leave in.
        android.util.Log.d("DriveMateManeuver", "type=" + type + " modifier=" + modifier
                + " exit=" + maneuver.optInt("exit", maneuver.optInt("roundaboutExitNumber", -1))
                + " road=" + road);
        if (type.isEmpty()) return explicit.isEmpty() ? "در مسیر ادامه دهید" : explicit;
        String instruction;
        if ("arrive".equals(type)) {
            return "به مقصد می‌رسید";
        } else if ("depart".equals(type)) {
            instruction = "به سمت مقصد حرکت کنید";
        } else if (type.contains("roundabout") || type.contains("rotary")) {
            int exit = maneuver.optInt("exit", maneuver.optInt("roundaboutExitNumber", 0));
            instruction = exit > 0 ? "وارد میدان شوید و از خروجی " + persianDigits(exit) + " خارج شوید"
                    : "وارد میدان شوید";
        } else if (type.contains("u-turn") || type.contains("uturn") || modifier.contains("uturn")) {
            instruction = "دور بزنید";
        } else if ("turn".equals(type) || "end of road".equals(type) || "fork".equals(type)) {
            instruction = turnInstruction(modifier, "end of road".equals(type));
        } else if (type.contains("exit") || type.contains("off ramp")) {
            instruction = "از خروجی خارج شوید";
        } else if (type.contains("merge") || type.contains("on ramp")) {
            instruction = "وارد مسیر شوید";
        } else if ("new name".equals(type) || "continue".equals(type) || "notification".equals(type)) {
            instruction = "در مسیر ادامه دهید";
        } else {
            instruction = turnInstruction(modifier, false);
        }
        // Iranian junctions that are actually roundabouts are very often carried in OSM only as a
        // named place ("میدان آزادی", "میدان ولیعصر") rather than a junction=roundabout tag map.ir's
        // engine recognizes, so this maneuver can arrive as a plain "turn" even though the road name
        // itself says it's a میدان. Keep the direction (still correct) but frame it as the roundabout
        // it actually is instead of a generic street-corner turn.
        if (road.contains("میدان") && ("turn".equals(type) || "end of road".equals(type) || "fork".equals(type))) {
            instruction = "در " + road + (modifier.contains("left") ? " از سمت چپ" : modifier.contains("right") ? " از سمت راست" : "") + " خارج شوید";
            return instruction;
        }
        return road.isEmpty() || "به مقصد می‌رسید".equals(instruction)
                ? instruction : instruction + " به سمت " + road;
    }

    private String turnInstruction(String modifier, boolean endOfRoad) {
        if (modifier.contains("sharp left")) return endOfRoad ? "در انتهای مسیر تند به چپ بپیچید" : "تند به چپ بپیچید";
        if (modifier.contains("slight left")) return "کمی به چپ بپیچید";
        if (modifier.contains("left")) return endOfRoad ? "در انتهای مسیر به چپ بپیچید" : "به چپ بپیچید";
        if (modifier.contains("sharp right")) return endOfRoad ? "در انتهای مسیر تند به راست بپیچید" : "تند به راست بپیچید";
        if (modifier.contains("slight right")) return "کمی به راست بپیچید";
        if (modifier.contains("right")) return endOfRoad ? "در انتهای مسیر به راست بپیچید" : "به راست بپیچید";
        if (modifier.contains("straight")) return "مستقیم ادامه دهید";
        return "در مسیر ادامه دهید";
    }

    private static String persianDigits(int value) {
        String latin = String.valueOf(value);
        StringBuilder result = new StringBuilder(latin.length());
        for (int index = 0; index < latin.length(); index++) {
            char character = latin.charAt(index);
            result.append(character >= '0' && character <= '9'
                    ? (char) ('۰' + character - '0') : character);
        }
        return result.toString();
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
