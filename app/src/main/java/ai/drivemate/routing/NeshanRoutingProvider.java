package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteStep;

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
        JSONObject leg = route.getJSONArray("legs").getJSONObject(0);
        int distance = leg.optJSONObject("distance") == null ? 0 : leg.getJSONObject("distance").optInt("value");
        JSONObject trafficDuration = leg.optJSONObject("duration_in_traffic");
        if (trafficDuration == null) trafficDuration = leg.optJSONObject("durationInTraffic");
        int duration = trafficDuration != null ? trafficDuration.optInt("value")
                : (leg.optJSONObject("duration") == null ? 0 : leg.getJSONObject("duration").optInt("value"));
        ArrayList<RouteStep> steps = new ArrayList<>();
        JSONArray rawSteps = leg.optJSONArray("steps");
        if (rawSteps != null) for (int i = 0; i < rawSteps.length(); i++) {
            JSONObject step = rawSteps.optJSONObject(i);
            JSONObject end = step == null ? null : step.optJSONObject("end_location");
            if (end != null) {
                JSONObject stepDistance = step.optJSONObject("distance");
                steps.add(new RouteStep(end.optDouble("lat"), end.optDouble("lng"), step.optString("instruction"),
                        stepDistance == null ? 0 : stepDistance.optInt("value")));
            }
        }
        if (steps.isEmpty()) steps.add(new RouteStep(destinationLat, destinationLng, "Arrive at destination", 0));
        return new RouteResult(name(), distance, duration, leg.optString("summary"), steps,
                RouteGeometry.fromRoute(route, steps, originLat, originLng, destinationLat, destinationLng));
    }
}
