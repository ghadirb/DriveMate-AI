package ai.drivemate.traffic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private static final double MAX_BBOX_AREA_KM2 = 9_000d;
    private static final double BBOX_PADDING_DEGREES = 0.02d;
    private String apiKey;
    private boolean enabled = true;

    public TrafficIncidentProvider(String apiKey) {
        setApiKey(apiKey);
    }

    public void setApiKey(String value) { apiKey = value == null ? "" : value.trim(); }

    public boolean hasKey() {
        return enabled && apiKey.length() >= 20;
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Fetches once for the given route geometry; the caller decides how often to poll (see
     *  MainActivity's traffic-incident check cadence) so this never runs on every GPS sample. */
    public List<TrafficIncident> incidentsNear(List<RoutePoint> geometry) throws Exception {
        LinkedHashMap<String, TrafficIncident> results = new LinkedHashMap<>();
        if (!hasKey() || geometry == null || geometry.isEmpty()) return new ArrayList<>();
        for (BoundingBox bbox : routeBoundingBoxes(geometry)) {
            for (TrafficIncident incident : incidentsIn(bbox)) {
                results.put(incident.id, incident);
            }
        }
        return new ArrayList<>(results.values());
    }

    /** TomTom rejects bboxes larger than 10,000 km². Long routes are therefore split into
     * consecutive, overlapping windows rather than losing all live incident data. */
    private List<BoundingBox> routeBoundingBoxes(List<RoutePoint> geometry) {
        ArrayList<BoundingBox> windows = new ArrayList<>();
        BoundingBox current = null;
        RoutePoint previous = null;
        for (RoutePoint point : geometry) {
            BoundingBox candidate = current == null ? BoundingBox.from(point) : current.including(point);
            if (current != null && candidate.areaKm2WithPadding() > MAX_BBOX_AREA_KM2) {
                windows.add(current);
                BoundingBox overlapping = BoundingBox.from(previous == null ? point : previous).including(point);
                // A malformed geometry can contain one very large jump. A bbox covering both
                // endpoints would still be rejected, so keep the new point's local window valid.
                current = overlapping.areaKm2WithPadding() <= MAX_BBOX_AREA_KM2
                        ? overlapping : BoundingBox.from(point);
            } else {
                current = candidate;
            }
            previous = point;
        }
        if (current != null) windows.add(current);
        return windows;
    }

    private List<TrafficIncident> incidentsIn(BoundingBox bounds) throws Exception {
        ArrayList<TrafficIncident> results = new ArrayList<>();
        String bbox = (bounds.minLng - BBOX_PADDING_DEGREES) + "," + (bounds.minLat - BBOX_PADDING_DEGREES)
                + "," + (bounds.maxLng + BBOX_PADDING_DEGREES) + "," + (bounds.maxLat + BBOX_PADDING_DEGREES);
        String fields = "{incidents{type,geometry{type,coordinates},properties{iconCategory,events{description,code},delay,id}}}";
        String url = "https://api.tomtom.com/traffic/services/5/incidentDetails"
                + "?key=" + apiKey
                + "&bbox=" + bbox
                + "&fields=" + java.net.URLEncoder.encode(fields, "UTF-8")
                // TomTom's NGT language list does not include Persian (a request with
                // language=fa-IR fails outright with HTTP 400 "Unsupported language parameter
                // value", silently disabling this entire feature every time). en-GB is always
                // supported and is only used for the short English incident description text,
                // which MainActivity/MapActivity fold into their own Persian sentence anyway.
                + "&language=en-GB&timeValidityFilter=present";
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

    private static final class BoundingBox {
        final double minLat;
        final double maxLat;
        final double minLng;
        final double maxLng;

        private BoundingBox(double minLat, double maxLat, double minLng, double maxLng) {
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLng = minLng;
            this.maxLng = maxLng;
        }

        static BoundingBox from(RoutePoint point) {
            return new BoundingBox(point.latitude, point.latitude, point.longitude, point.longitude);
        }

        BoundingBox including(RoutePoint point) {
            return new BoundingBox(Math.min(minLat, point.latitude), Math.max(maxLat, point.latitude),
                    Math.min(minLng, point.longitude), Math.max(maxLng, point.longitude));
        }

        double areaKm2WithPadding() {
            double latitudeSpanKm = (maxLat - minLat + 2d * BBOX_PADDING_DEGREES) * 111.32d;
            double centerLatitude = (minLat + maxLat) / 2d;
            double longitudeSpanKm = (maxLng - minLng + 2d * BBOX_PADDING_DEGREES)
                    * 111.32d * Math.cos(Math.toRadians(centerLatitude));
            return Math.max(0d, latitudeSpanKm * longitudeSpanKm);
        }
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
