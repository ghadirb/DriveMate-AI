package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteStep;

public class MapIrRoutingProvider implements RoutingProvider {
    private String apiKey;

    public MapIrRoutingProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setApiKey(String apiKey) {
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            this.apiKey = apiKey.trim();
        }
    }

    String apiKey() { return apiKey == null || apiKey.trim().isEmpty() ? null : apiKey; }

    @Override
    public String name() {
        return "map.ir";
    }

    @Override
    public RouteResult route(double originLat, double originLng, double destinationLat, double destinationLng) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("کلید API map.ir تنظیم نشده است.");
        }
        String url = "https://map.ir/routes/route/v1/driving/"
                + originLng + "," + originLat + ";" + destinationLng + "," + destinationLat
                + "?alternatives=false&steps=true&overview=false";
        JSONObject object = RoutingHttp.getJson(url, "x-api-key", apiKey);
        JSONArray routes = object.optJSONArray("routes");
        if (routes == null || routes.length() == 0) {
            throw new IllegalStateException("مسیری از map.ir دریافت نشد.");
        }
        JSONObject route = routes.getJSONObject(0);
        List<RouteStep> steps = new ArrayList<>();
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
                }
            }
        }
        if (steps.isEmpty()) steps.add(new RouteStep(destinationLat, destinationLng, "رسیدن به مقصد", 0));
        return new RouteResult(name(), route.optInt("distance"), route.optInt("duration"), route.optString("weight_name"), steps);
    }
}
