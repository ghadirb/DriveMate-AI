package ai.drivemate.traffic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.TrafficIncident;
import ai.drivemate.routing.RoutingHttp;

/**
 * Reads live, point-based traffic incidents (accident, road closure, roadworks, other dangerous
 * conditions) near the active route from TomTom's Traffic Incident Details API (v5). This is a
 * third-party live feed, not an on-device sensor or an official police/road-authority feed: a
 * missing key, an outage, or an empty response simply disables this one warning type without
 * affecting anything else - matching WeatherHazardProvider's honesty rules. The general
 * "traffic is slow, ETA got worse" reroute check already covers aggregate slowdowns; this feed
 * is only for a specific, driver-facing "something is here" point warning.
 */
public final class TrafficIncidentProvider {
    private final String apiKey;

    public TrafficIncidentProvider(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean hasKey() {
        return apiKey.length() >= 20;
    }

    /** Fetches once for the given route geometry; the caller decides how often to poll (see
     *  MainActivity's traffic-incident check cadence) so this never runs on every GPS sample. */
    public List<TrafficIncident> incidentsNear(List<RoutePoint> geometry) throws Exception {
        ArrayList<TrafficIncident> results = new ArrayList<>();
        if (!hasKey() || geometry == null || geometry.isEmpty()) return results;
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE, minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
        for (RoutePoint point : geometry) {
            minLat = Math.min(minLat, point.latitude);
            maxLat = Math.max(maxLat, point.latitude);
            minLng = Math.min(minLng, point.longitude);
            maxLng = Math.max(maxLng, point.longitude);
        }
        // ~2km buffer so an incident just off the bounding box edge (e.g. near a bend) is not missed.
        double pad = 0.02d;
        String bbox = (minLng - pad) + "," + (minLat - pad) + "," + (maxLng + pad) + "," + (maxLat + pad);
        String fields = "{incidents{type,geometry{type,coordinates},properties{iconCategory,events{description,code},delay,id}}}";
        String url = "https://api.tomtom.com/traffic/services/5/incidentDetails"
                + "?key=" + apiKey
                + "&bbox=" + bbox
                + "&fields=" + java.net.URLEncoder.encode(fields, "UTF-8")
                + "&language=fa-IR&timeValidityFilter=present";
        JSONObject body = RoutingHttp.getJson(url);
        JSONArray incidents = body.optJSONArray("incidents");
        if (incidents == null) return results;
        for (int index = 0; index < incidents.length(); index++) {
            JSONObject incident = incidents.optJSONObject(index);
            JSONObject properties = incident == null ? null : incident.optJSONObject("properties");
            JSONObject geometryObject = incident == null ? null : incident.optJSONObject("geometry");
            double[] point = firstCoordinate(geometryObject);
            if (point == null || properties == null) continue;
            TrafficIncident.Type type = mapIconCategory(properties.optInt("iconCategory", -1));
            if (type == null) continue; // Plain congestion coloring, not a specific point event.
            String description = "";
            JSONArray events = properties.optJSONArray("events");
            if (events != null && events.length() > 0) {
                JSONObject firstEvent = events.optJSONObject(0);
                if (firstEvent != null) description = firstEvent.optString("description", "");
            }
            String id = properties.optString("id", String.valueOf(index));
            results.add(new TrafficIncident(id, type, point[0], point[1], description, properties.optInt("delay", 0)));
        }
        return results;
    }

    /** TomTom's documented iconCategory codes: 1 accident, 6 road closed, 8 roadworks,
     *  9 broken-down vehicle, 14 dangerous conditions. Plain jam/congestion codes are
     *  intentionally excluded here - the periodic aggregate-ETA reroute check already covers
     *  general slowdowns. */
    private TrafficIncident.Type mapIconCategory(int code) {
        switch (code) {
            case 1: return TrafficIncident.Type.ACCIDENT;
            case 6: return TrafficIncident.Type.ROAD_CLOSED;
            case 8: return TrafficIncident.Type.ROADWORK;
            case 9:
            case 14: return TrafficIncident.Type.HAZARD;
            default: return null;
        }
    }

    private double[] firstCoordinate(JSONObject geometryObject) {
        if (geometryObject == null) return null;
        JSONArray coordinates = geometryObject.optJSONArray("coordinates");
        if (coordinates == null || coordinates.length() == 0) return null;
        String type = geometryObject.optString("type", "");
        if ("Point".equalsIgnoreCase(type)) {
            double lat = coordinates.optDouble(1, Double.NaN);
            double lng = coordinates.optDouble(0, Double.NaN);
            return Double.isNaN(lat) || Double.isNaN(lng) ? null : new double[]{lat, lng};
        }
        // LineString (typical for TomTom incidents): use the first vertex as the representative point.
        JSONArray firstVertex = coordinates.optJSONArray(0);
        if (firstVertex == null || firstVertex.length() < 2) return null;
        double lat = firstVertex.optDouble(1, Double.NaN);
        double lng = firstVertex.optDouble(0, Double.NaN);
        return Double.isNaN(lat) || Double.isNaN(lng) ? null : new double[]{lat, lng};
    }
}
