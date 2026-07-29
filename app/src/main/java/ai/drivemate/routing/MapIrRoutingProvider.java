package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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
        if (!isConfigured()) throw new IllegalStateException("map.ir API key is not configured.");
        String url = "https://map.ir/routes/route/v1/driving/" + originLng + "," + originLat + ";"
                + destinationLng + "," + destinationLat + "?alternatives=true&steps=true&overview=full&geometries=polyline";
        JSONObject object = RoutingHttp.getJson(url, "x-api-key", apiKey);
        JSONArray rawRoutes = object.optJSONArray("routes");
        if (rawRoutes == null || rawRoutes.length() == 0) throw new IllegalStateException("map.ir returned no route.");
        ArrayList<RouteResult> results = new ArrayList<>();
        for (int i = 0; i < rawRoutes.length() && i < 3; i++) {
            results.add(parseRoute(rawRoutes.getJSONObject(i), originLat, originLng, destinationLat, destinationLng));
        }
        return results;
    }

    private RouteResult parseRoute(JSONObject route, double originLat, double originLng,
                                   double destinationLat, double destinationLng) throws Exception {
        ArrayList<RouteStep> steps = new ArrayList<>();
        ArrayList<SpeedLimitPoint> speedLimits = new ArrayList<>();
        JSONArray legs = route.optJSONArray("legs");
        if (legs != null && legs.length() > 0) {
            JSONArray rawSteps = legs.getJSONObject(0).optJSONArray("steps");
            if (rawSteps != null) for (int i = 0; i < rawSteps.length(); i++) {
                JSONObject step = rawSteps.optJSONObject(i);
                JSONObject maneuver = step == null ? null : step.optJSONObject("maneuver");
                if (maneuver != null) {
                    JSONArray point = maneuver.optJSONArray("location");
                    double longitude = point == null ? destinationLng : point.optDouble(0, destinationLng);
                    double latitude = point == null ? destinationLat : point.optDouble(1, destinationLat);
                    steps.add(new RouteStep(latitude, longitude, maneuver.optString("instruction"), step.optInt("distance")));
                    int speedLimit = explicitSpeedLimit(step);
                    if (speedLimit > 0) speedLimits.add(new SpeedLimitPoint(latitude, longitude, speedLimit, name()));
                }
            }
        }
        if (steps.isEmpty()) steps.add(new RouteStep(destinationLat, destinationLng, "Arrive at destination", 0));
        return new RouteResult(name(), route.optInt("distance"), route.optInt("duration"), route.optString("weight_name"), steps,
                RouteGeometry.fromRoute(route, steps, originLat, originLng, destinationLat, destinationLng), speedLimits);
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
