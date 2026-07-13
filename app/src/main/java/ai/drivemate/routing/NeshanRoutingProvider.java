package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteStep;

public class NeshanRoutingProvider implements RoutingProvider {
    private String apiKey;

    public NeshanRoutingProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setApiKey(String apiKey) {
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            this.apiKey = apiKey.trim();
        }
    }

    String apiKey() { return apiKey == null || apiKey.trim().isEmpty() ? null : apiKey; }
    public boolean isConfigured() { return apiKey() != null; }

    @Override
    public String name() {
        return "نشان";
    }

    @Override
    public RouteResult route(double originLat, double originLng, double destinationLat, double destinationLng) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("کلید API نشان تنظیم نشده است.");
        }
        String url = "https://api.neshan.org/v4/direction?type=car"
                + "&origin=" + originLat + "," + originLng
                + "&destination=" + destinationLat + "," + destinationLng;
        JSONObject object = RoutingHttp.getJson(url, "Api-Key", apiKey);
        JSONArray routes = object.optJSONArray("routes");
        if (routes == null || routes.length() == 0) {
            throw new IllegalStateException("مسیری از نشان دریافت نشد.");
        }
        JSONObject leg = routes.getJSONObject(0).getJSONArray("legs").getJSONObject(0);
        int distance = leg.optJSONObject("distance") != null ? leg.getJSONObject("distance").optInt("value") : 0;
        int duration = leg.optJSONObject("duration") != null ? leg.getJSONObject("duration").optInt("value") : 0;
        List<RouteStep> steps = new ArrayList<>();
        JSONArray rawSteps = leg.optJSONArray("steps");
        if (rawSteps != null) for (int i = 0; i < rawSteps.length(); i++) {
            JSONObject step = rawSteps.optJSONObject(i);
            JSONObject end = step == null ? null : step.optJSONObject("end_location");
            if (end != null) {
                JSONObject stepDistance = step.optJSONObject("distance");
                steps.add(new RouteStep(end.optDouble("lat"), end.optDouble("lng"), step.optString("instruction"), stepDistance == null ? 0 : stepDistance.optInt("value")));
            }
        }
        if (steps.isEmpty()) steps.add(new RouteStep(destinationLat, destinationLng, "رسیدن به مقصد", 0));
        return new RouteResult(name(), distance, duration, leg.optString("summary"), steps);
    }
}
