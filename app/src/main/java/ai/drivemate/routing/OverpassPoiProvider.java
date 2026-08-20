package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteSafetyAlert;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.model.SpeedLimitPoint;

/** Public OpenStreetMap fallback for nearby categories that Iranian commercial indexes miss,
 *  and for route hazards (speed cameras, speed bumps, and best-effort police/checkpoint points)
 *  that no Iranian commercial provider exposes through a public developer API at all. */
public final class OverpassPoiProvider {

    /** Hazard type codes returned as the third element of each hazardsNear() entry. */
    public static final double HAZARD_CAMERA = 0d;
    public static final double HAZARD_SPEED_BUMP = 1d;
    public static final double HAZARD_POLICE = 2d;
    public static final double HAZARD_TRAFFIC_SIGN = 3d;

    /**
     * Best-effort, community-maintained speed camera (highway=speed_camera), speed bump
     * (traffic_calming=*), police/checkpoint (amenity=police, barrier=checkpoint,
     * highway=checkpoint) and traffic-sign (highway=stop, highway=give_way, traffic_sign=*)
     * points that fall within a short distance of the given route geometry.
     * This is not official Neshan/map.ir data - their public developer API does not expose one,
     * only their own first-party consumer app has that - so coverage depends purely on what
     * OpenStreetMap contributors have mapped for that road. The police/checkpoint points are
     * static, community-tagged locations only; this never includes live police presence, which
     * has no static-map representation at all and must never be implied to the driver.
     */
    public List<double[]> hazardsNear(List<RoutePoint> geometry) throws Exception {
        if (geometry == null || geometry.size() < 2) return Collections.emptyList();
        double minLat = 90d, maxLat = -90d, minLon = 180d, maxLon = -180d;
        for (RoutePoint point : geometry) {
            minLat = Math.min(minLat, point.latitude);
            maxLat = Math.max(maxLat, point.latitude);
            minLon = Math.min(minLon, point.longitude);
            maxLon = Math.max(maxLon, point.longitude);
        }
        double pad = 0.01d; // roughly 1km, enough to cover route curvature near the bbox edges
        minLat -= pad; maxLat += pad; minLon -= pad; maxLon += pad;
        // A very long intercity route would make the bbox (and the Overpass query) too large and
        // slow; skip the hazard lookup rather than stall the trip on a multi-hundred-km request.
        if ((maxLat - minLat) > 3d || (maxLon - minLon) > 3d) return Collections.emptyList();
        String bbox = minLat + "," + minLon + "," + maxLat + "," + maxLon;
        String query = "[out:json][timeout:15];(node[\"highway\"=\"speed_camera\"](" + bbox + ");"
                + "node[\"traffic_calming\"](" + bbox + ");"
                + "node[\"amenity\"=\"police\"](" + bbox + ");"
                + "node[\"barrier\"=\"checkpoint\"](" + bbox + ");"
                + "node[\"highway\"=\"checkpoint\"](" + bbox + ");"
                + "node[\"highway\"=\"stop\"](" + bbox + ");"
                + "node[\"highway\"=\"give_way\"](" + bbox + ");"
                + "node[\"traffic_sign\"](" + bbox + "););out;";
        JSONObject body = request(query);
        JSONArray items = body.optJSONArray("elements");
        ArrayList<double[]> hazards = new ArrayList<>();
        if (items == null) return hazards;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            double lat = item.optDouble("lat", Double.NaN);
            double lon = item.optDouble("lon", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon) || !isInIran(lat, lon)) continue;
            // The bbox is a rectangle around the whole route, so a point inside it is not
            // necessarily on the road the driver will actually take; only keep points genuinely
            // close to the route line itself. Police/checkpoint points get a wider corridor since
            // they are sparser and their OSM position is often the station entrance, not the road.
            JSONObject tags = item.optJSONObject("tags");
            boolean isCamera = tags != null && "speed_camera".equals(tags.optString("highway", ""));
            boolean isPolice = tags != null && ("police".equals(tags.optString("amenity", ""))
                    || "checkpoint".equals(tags.optString("barrier", ""))
                    || "checkpoint".equals(tags.optString("highway", "")));
            boolean isSign = tags != null && ("stop".equals(tags.optString("highway", ""))
                    || "give_way".equals(tags.optString("highway", "")) || tags.has("traffic_sign"));
            double corridor = isPolice ? 150d : 60d;
            if (!nearRoute(geometry, lat, lon, corridor)) continue;
            double type = isCamera ? HAZARD_CAMERA : isPolice ? HAZARD_POLICE
                    : isSign ? HAZARD_TRAFFIC_SIGN : HAZARD_SPEED_BUMP;
            hazards.add(new double[]{lat, lon, type});
        }
        return hazards;
    }

    /**
     * Reads numeric {@code maxspeed} tags from roads close to a route.  This intentionally skips
     * country defaults, conditional values and advisory speeds: guessing a number from road shape
     * or national rules could produce a false "illegal speed" warning.  The provider returns only
     * a best-effort mapped value that can be shown as "OSM" in the UI.
     */
    public List<SpeedLimitPoint> speedLimitsNear(List<RoutePoint> geometry) throws Exception {
        if (geometry == null || geometry.size() < 2) return Collections.emptyList();
        double minLat = 90d, maxLat = -90d, minLon = 180d, maxLon = -180d;
        for (RoutePoint point : geometry) {
            minLat = Math.min(minLat, point.latitude);
            maxLat = Math.max(maxLat, point.latitude);
            minLon = Math.min(minLon, point.longitude);
            maxLon = Math.max(maxLon, point.longitude);
        }
        double pad = 0.004d; // roughly 400m: wide enough for parallel carriageways, not a city-wide scan
        minLat -= pad; maxLat += pad; minLon -= pad; maxLon += pad;
        if ((maxLat - minLat) > 3d || (maxLon - minLon) > 3d) return Collections.emptyList();
        String bbox = minLat + "," + minLon + "," + maxLat + "," + maxLon;
        List<SpeedLimitPoint> limits = taggedSpeedLimits(bbox, geometry);
        List<SpeedLimitPoint> estimated = estimatedIranSpeedLimits(bbox, geometry, limits);
        if (!estimated.isEmpty()) {
            ArrayList<SpeedLimitPoint> combined = new ArrayList<>(limits);
            combined.addAll(estimated);
            return combined;
        }
        return limits;
    }

    private List<SpeedLimitPoint> taggedSpeedLimits(String bbox, List<RoutePoint> geometry) throws Exception {
        JSONObject body = request("[out:json][timeout:18];way[\"highway\"][\"maxspeed\"](" + bbox
                + ");out tags geom 500;");
        JSONArray items = body.optJSONArray("elements");
        ArrayList<SpeedLimitPoint> limits = new ArrayList<>();
        if (items == null) return limits;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            JSONObject tags = item == null ? null : item.optJSONObject("tags");
            int limit = parseKilometersPerHour(tags == null ? "" : tags.optString("maxspeed", ""));
            if (limit <= 0) continue;
            JSONArray points = item.optJSONArray("geometry");
            if (points == null) continue;
            double closestLat = Double.NaN, closestLon = Double.NaN, closestDistance = Double.MAX_VALUE;
            for (int pointIndex = 0; pointIndex < points.length(); pointIndex++) {
                JSONObject point = points.optJSONObject(pointIndex);
                if (point == null) continue;
                double lat = point.optDouble("lat", Double.NaN);
                double lon = point.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon) || !isInIran(lat, lon)) continue;
                for (RoutePoint routePoint : geometry) {
                    double distance = distanceMeters(routePoint.latitude, routePoint.longitude, lat, lon);
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        closestLat = lat;
                        closestLon = lon;
                    }
                }
            }
            if (closestDistance <= 90d) limits.add(new SpeedLimitPoint(closestLat, closestLon, limit, "OSM"));
        }
        return limits;
    }

    /** Iran's official (day, car/pickup) legal maximums for the small set of {@code highway}
     *  classes that map cleanly to a single road type regardless of whether they sit inside or
     *  outside a city - see the آیین‌نامه راهنمایی و رانندگی table. Deliberately excludes
     *  primary/secondary/tertiary: OSM alone cannot tell whether one of those is an inter-city
     *  main road (95) or an ordinary urban arterial (60), and guessing wrong there is exactly the
     *  false-warning risk this feature must avoid. */
    private static int iranLegalDefaultKmh(String highway) {
        if (highway == null) return 0;
        switch (highway) {
            case "motorway": return 120; // آزادراه
            case "trunk": return 110;    // بزرگراه با خطوط جدا
            case "residential": return 30; // خیابان/کوچهٔ محله‌ای؛ عدد محافظه‌کارانه و قابل تنظیم
            default: return 0;
        }
    }

    /** Fills gaps left by {@link #taggedSpeedLimits} using {@link #iranLegalDefaultKmh}, only for
     *  ways whose {@code highway} class is in that safe-to-infer set and which carry no
     *  {@code maxspeed} tag at all. Skips any point that already has a tagged value nearby, so an
     *  estimate never overrides or duplicates a real, road-specific number. Each result is marked
     *  {@code estimated=true} so callers can render/announce it differently (see SpeedLimitPoint).
     */
    private List<SpeedLimitPoint> estimatedIranSpeedLimits(String bbox, List<RoutePoint> geometry,
            List<SpeedLimitPoint> tagged) throws Exception {
        JSONObject body = request("[out:json][timeout:18];way[\"highway\"~\"^(motorway|trunk|residential)$\"]"
                + "[!\"maxspeed\"](" + bbox + ");out tags geom 500;");
        JSONArray items = body.optJSONArray("elements");
        ArrayList<SpeedLimitPoint> estimates = new ArrayList<>();
        if (items == null) return estimates;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            JSONObject tags = item == null ? null : item.optJSONObject("tags");
            int limit = iranLegalDefaultKmh(tags == null ? null : tags.optString("highway", ""));
            if (limit <= 0) continue;
            JSONArray points = item.optJSONArray("geometry");
            if (points == null) continue;
            double closestLat = Double.NaN, closestLon = Double.NaN, closestDistance = Double.MAX_VALUE;
            for (int pointIndex = 0; pointIndex < points.length(); pointIndex++) {
                JSONObject point = points.optJSONObject(pointIndex);
                if (point == null) continue;
                double lat = point.optDouble("lat", Double.NaN);
                double lon = point.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon) || !isInIran(lat, lon)) continue;
                for (RoutePoint routePoint : geometry) {
                    double distance = distanceMeters(routePoint.latitude, routePoint.longitude, lat, lon);
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        closestLat = lat;
                        closestLon = lon;
                    }
                }
            }
            if (closestDistance > 90d) continue;
            boolean nearTagged = false;
            for (SpeedLimitPoint existing : tagged) {
                if (distanceMeters(existing.latitude, existing.longitude, closestLat, closestLon) <= 60d) {
                    nearTagged = true;
                    break;
                }
            }
            if (!nearTagged) estimates.add(new SpeedLimitPoint(closestLat, closestLon, limit, "قانون ایران (تخمینی)", true));
        }
        return estimates;
    }


    /**
     * Best-effort railway level crossings, school zones (the caller decides whether "now" is an
     * active school hour - this method only returns proximity), and any node explicitly tagged
     * with a generic OSM {@code hazard} key. That last one is the closest OpenStreetMap
     * equivalent to an official accident black-spot register; Iran has no free public API for a
     * real accident-density dataset, so this list is frequently empty on ordinary roads. It is a
     * best-effort community tag lookup, never a substitute for official accident statistics, and
     * the app must not imply otherwise in its warning text.
     */
    public List<RouteSafetyAlert> pointSafetyFeaturesNear(List<RoutePoint> geometry) throws Exception {
        if (geometry == null || geometry.size() < 2) return Collections.emptyList();
        double minLat = 90d, maxLat = -90d, minLon = 180d, maxLon = -180d;
        for (RoutePoint point : geometry) {
            minLat = Math.min(minLat, point.latitude);
            maxLat = Math.max(maxLat, point.latitude);
            minLon = Math.min(minLon, point.longitude);
            maxLon = Math.max(maxLon, point.longitude);
        }
        double pad = 0.01d;
        minLat -= pad; maxLat += pad; minLon -= pad; maxLon += pad;
        if ((maxLat - minLat) > 3d || (maxLon - minLon) > 3d) return Collections.emptyList();
        String bbox = minLat + "," + minLon + "," + maxLat + "," + maxLon;
        String query = "[out:json][timeout:18];(node[\"railway\"=\"level_crossing\"](" + bbox + ");"
                + "nwr[\"amenity\"=\"school\"](" + bbox + ");"
                + "node[\"hazard\"](" + bbox + "););out center;";
        JSONObject body = request(query);
        JSONArray items = body.optJSONArray("elements");
        ArrayList<RouteSafetyAlert> results = new ArrayList<>();
        if (items == null) return results;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            JSONObject center = item.optJSONObject("center");
            double lat = item.has("lat") ? item.optDouble("lat", Double.NaN)
                    : center == null ? Double.NaN : center.optDouble("lat", Double.NaN);
            double lon = item.has("lon") ? item.optDouble("lon", Double.NaN)
                    : center == null ? Double.NaN : center.optDouble("lon", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon) || !isInIran(lat, lon)) continue;
            JSONObject tags = item.optJSONObject("tags");
            if (tags == null) continue;
            RouteSafetyAlert.Type type;
            double corridor;
            if ("level_crossing".equals(tags.optString("railway", ""))) {
                type = RouteSafetyAlert.Type.RAILWAY_CROSSING;
                corridor = 80d;
            } else if ("school".equals(tags.optString("amenity", ""))) {
                type = RouteSafetyAlert.Type.SCHOOL_ZONE;
                corridor = 150d; // schools are often set back from the road itself
            } else if (tags.has("hazard")) {
                type = RouteSafetyAlert.Type.ACCIDENT_PRONE;
                corridor = 120d;
            } else {
                continue;
            }
            if (!nearRoute(geometry, lat, lon, corridor)) continue;
            results.add(new RouteSafetyAlert(type, lat, lon, 0d));
        }
        return results;
    }

    /**
     * Best-effort OpenStreetMap way tags for tunnels, narrow bridges and steep inclines close to
     * the route. Only explicit numeric/qualifying tags are used - no country default, no guess
     * from road class or shape - matching speedLimitsNear's own honesty rule: missing data means
     * no warning, not an invented one.
     */
    public List<RouteSafetyAlert> roadWayFeaturesNear(List<RoutePoint> geometry) throws Exception {
        if (geometry == null || geometry.size() < 2) return Collections.emptyList();
        double minLat = 90d, maxLat = -90d, minLon = 180d, maxLon = -180d;
        for (RoutePoint point : geometry) {
            minLat = Math.min(minLat, point.latitude);
            maxLat = Math.max(maxLat, point.latitude);
            minLon = Math.min(minLon, point.longitude);
            maxLon = Math.max(maxLon, point.longitude);
        }
        double pad = 0.004d;
        minLat -= pad; maxLat += pad; minLon -= pad; maxLon += pad;
        if ((maxLat - minLat) > 3d || (maxLon - minLon) > 3d) return Collections.emptyList();
        String bbox = minLat + "," + minLon + "," + maxLat + "," + maxLon;
        JSONObject body = request("[out:json][timeout:18];(way[\"highway\"][\"tunnel\"](" + bbox + ");"
                + "way[\"highway\"][\"bridge\"](" + bbox + ");"
                + "way[\"highway\"][\"incline\"](" + bbox + "););out tags geom 500;");
        JSONArray items = body.optJSONArray("elements");
        ArrayList<RouteSafetyAlert> results = new ArrayList<>();
        if (items == null) return results;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            JSONObject tags = item == null ? null : item.optJSONObject("tags");
            if (tags == null) continue;
            RouteSafetyAlert.Type type;
            double detail = 0d;
            String tunnel = tags.optString("tunnel", "");
            String bridge = tags.optString("bridge", "");
            if (!tunnel.isEmpty() && !"no".equals(tunnel)) {
                type = RouteSafetyAlert.Type.TUNNEL;
            } else if (!bridge.isEmpty() && !"no".equals(bridge) && isNarrowBridge(tags)) {
                type = RouteSafetyAlert.Type.NARROW_BRIDGE;
            } else if (tags.has("incline")) {
                double percent = parseInclinePercent(tags.optString("incline", ""));
                if (Double.isNaN(percent) || Math.abs(percent) < 7d) continue;
                type = RouteSafetyAlert.Type.STEEP_GRADE;
                detail = percent;
            } else {
                continue;
            }
            JSONArray points = item.optJSONArray("geometry");
            if (points == null) continue;
            double closestLat = Double.NaN, closestLon = Double.NaN, closestDistance = Double.MAX_VALUE;
            for (int pointIndex = 0; pointIndex < points.length(); pointIndex++) {
                JSONObject point = points.optJSONObject(pointIndex);
                if (point == null) continue;
                double lat = point.optDouble("lat", Double.NaN);
                double lon = point.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon) || !isInIran(lat, lon)) continue;
                for (RoutePoint routePoint : geometry) {
                    double distance = distanceMeters(routePoint.latitude, routePoint.longitude, lat, lon);
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        closestLat = lat;
                        closestLon = lon;
                    }
                }
            }
            if (closestDistance <= 90d) results.add(new RouteSafetyAlert(type, closestLat, closestLon, detail));
        }
        return results;
    }

    private boolean isNarrowBridge(JSONObject tags) {
        double maxwidth = parseMeters(tags.optString("maxwidth", ""));
        double width = parseMeters(tags.optString("width", ""));
        if (maxwidth > 0d && maxwidth < 3.5d) return true;
        if (width > 0d && width < 3.5d) return true;
        return "1".equals(tags.optString("lanes", "").trim());
    }

    private double parseMeters(String rawValue) {
        if (rawValue == null) return -1d;
        String value = rawValue.trim().toLowerCase().replace("m", "").trim();
        if (!value.matches("\\d{1,2}(\\.\\d+)?")) return -1d;
        try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return -1d; }
    }

    private double parseInclinePercent(String rawValue) {
        if (rawValue == null) return Double.NaN;
        String value = rawValue.trim().toLowerCase().replace("%", "").trim();
        if (!value.matches("-?\\d{1,2}(\\.\\d+)?")) return Double.NaN;
        try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return Double.NaN; }
    }

    private int parseKilometersPerHour(String rawValue) {
        if (rawValue == null) return -1;
        String value = rawValue.trim().toLowerCase();
        if (value.contains(";") || value.contains("@") || value.contains("mph") || !value.matches("\\d{1,3}(\\s*(km/h|kph))?")) {
            return -1;
        }
        String digits = value.replaceAll("[^0-9]", "");
        try {
            int parsed = Integer.parseInt(digits);
            return parsed >= 10 && parsed <= 160 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean nearRoute(List<RoutePoint> geometry, double lat, double lon, double thresholdMeters) {
        for (RoutePoint point : geometry) {
            if (distanceMeters(point.latitude, point.longitude, lat, lon) <= thresholdMeters) return true;
        }
        return false;
    }

    private double distanceMeters(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double a = Math.sin(latitudeDelta / 2d) * Math.sin(latitudeDelta / 2d)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(longitudeDelta / 2d) * Math.sin(longitudeDelta / 2d);
        return 6371000d * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    List<SavedPlace> searchNearby(String term, double latitude, double longitude) throws Exception {
        // 45km (was 20km, originally 10km): OSM is now queried as the primary, no-key source for
        // "اطراف من" and map POI layers (see PlaceSearchRepository), so its own radius needs to be wide
        // enough to stand on its own without Neshan/map.ir - not just fill in their gaps. A literal
        // whole-country query is deliberately avoided here: for a common category like fuel stations
        // it would return thousands of nodes in one shot, overload the public Overpass mirrors (the
        // same 429 rate-limiting already seen with only a handful of concurrent requests), and flood
        // the map with more markers than are useful at any single zoom level. See the overload below
        // for MapActivity's progressive, rate-limit-safe way of eventually covering much more ground.
        return searchNearby(term, latitude, longitude, 45_000d, 60);
    }

    /**
     * Same category lookup as above but with a caller-chosen radius/item cap, so a map POI layer can
     * widen its coverage outward in rings over time (see MapActivity's progressive layer expansion)
     * instead of only ever querying the tight "اطراف من" radius. This still goes through the same
     * request() method below, so a wider ring never bypasses the shared 700ms-minimum-gap/mirror-
     * failover throttling every other Overpass caller in the app is subject to.
     */
    public List<SavedPlace> searchNearby(String term, double latitude, double longitude, double radiusMeters, int itemCap) throws Exception {
        String selector = selectorFor(term);
        if (selector == null) return Collections.emptyList();
        String query = "[out:json][timeout:25];(nwr" + selector + "(around:" + Math.round(radiusMeters) + "," + latitude + "," + longitude
                + "););out center " + itemCap + ";";
        JSONObject body = request(query);
        JSONArray items = body.optJSONArray("elements");
        ArrayList<SavedPlace> places = new ArrayList<>();
        if (items == null) return places;
        for (int index = 0; index < items.length() && index < itemCap; index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            JSONObject center = item.optJSONObject("center");
            double resultLat = item.has("lat") ? item.optDouble("lat", Double.NaN)
                    : center == null ? Double.NaN : center.optDouble("lat", Double.NaN);
            double resultLng = item.has("lon") ? item.optDouble("lon", Double.NaN)
                    : center == null ? Double.NaN : center.optDouble("lon", Double.NaN);
            if (!isInIran(resultLat, resultLng)) continue;
            JSONObject tags = item.optJSONObject("tags");
            String title = tags == null ? "" : tags.optString("name:fa", tags.optString("name", ""));
            if (title.trim().isEmpty()) title = fallbackTitle(term);
            String address = tags == null ? "" : joinAddress(tags);
            String kind = tags == null ? "poi" : tags.optString("amenity", tags.optString("shop", "poi"));
            places.add(new SavedPlace(title, "search_osm_" + kind + "_" + item.optLong("id"), resultLat,
                    resultLng, address, System.currentTimeMillis(), false));
        }
        return places;
    }

    /** Overpass's public mirrors rate-limit (HTTP 429) or start timing out when several requests
     *  land at once - exactly what happened when multiple map POI layers (see MapActivity's
     *  refreshPoiLayers) each fired their own concurrent Overpass call. Serializing every
     *  Overpass call in the app through this single lock, with a minimum gap between them,
     *  keeps requests sequential so a burst of layers/hazard lookups no longer collide. */
    private static final Object REQUEST_LOCK = new Object();
    private static final long MIN_REQUEST_GAP_MS = 700L;
    private static long lastRequestFinishedAt = 0L;

    private JSONObject request(String query) throws Exception {
        String[] mirrors = {
                "https://overpass-api.de/api/interpreter",
                "https://overpass.private.coffee/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter"
        };
        synchronized (REQUEST_LOCK) {
            long waitMs = MIN_REQUEST_GAP_MS - (System.currentTimeMillis() - lastRequestFinishedAt);
            if (waitMs > 0) {
                try { Thread.sleep(waitMs); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            try {
                StringBuilder combinedFailure = new StringBuilder();
                for (String mirror : mirrors) {
                    try {
                        return RoutingHttp.postFormJson(mirror, "data", query);
                    } catch (Exception exception) {
                        if (combinedFailure.length() > 0) combinedFailure.append(" | ");
                        combinedFailure.append(mirror).append(": ").append(exception.getMessage());
                    }
                }
                throw new IllegalStateException("Overpass unavailable: " + combinedFailure);
            } finally {
                lastRequestFinishedAt = System.currentTimeMillis();
            }
        }
    }

    private String selectorFor(String rawTerm) {
        String term = normalize(rawTerm);
        if (contains(term, "\u067e\u0645\u067e", "fuel", "gas station")) return "[\"amenity\"=\"fuel\"]";
        if (contains(term, "\u062f\u0627\u0631\u0648\u062e\u0627\u0646\u0647", "pharmacy")) return "[\"amenity\"=\"pharmacy\"]";
        if (contains(term, "\u0628\u06cc\u0645\u0627\u0631\u0633\u062a\u0627\u0646", "hospital")) return "[\"amenity\"=\"hospital\"]";
        if (contains(term, "\u0631\u0633\u062a\u0648\u0631\u0627\u0646", "restaurant")) return "[\"amenity\"=\"restaurant\"]";
        if (contains(term, "\u06a9\u0627\u0641\u0647", "cafe")) return "[\"amenity\"=\"cafe\"]";
        if (contains(term, "\u067e\u0627\u0631\u06a9\u06cc\u0646\u06af", "parking")) return "[\"amenity\"=\"parking\"]";
        if (contains(term, "\u0627\u0633\u062a\u0631\u0627\u062d\u062a", "rest area", "services")) return "[\"highway\"=\"services\"]";
        if (contains(term, "\u0628\u0627\u0646\u06a9", "bank")) return "[\"amenity\"=\"bank\"]";
        if (contains(term, "cng")) return "[\"amenity\"=\"fuel\"]";
        if (contains(term, "\u062a\u0639\u0645\u06cc\u0631\u06af\u0627\u0647", "mechanic")) return "[\"shop\"=\"car_repair\"]";
        if (contains(term, "\u067e\u0646\u0686\u0631", "tyre", "tire")) return "[\"shop\"=\"tyres\"]";
        if (contains(term, "\u0628\u0627\u062a\u0631\u06cc")) return "[\"shop\"=\"car_parts\"]";
        if (contains(term, "\u062f\u0631\u0645\u0627\u0646\u06af\u0627\u0647", "clinic")) return "[\"amenity\"=\"clinic\"]";
        if (contains(term, "\u06a9\u0644\u0627\u0646\u062a\u0631\u06cc", "police station")) return "[\"amenity\"=\"police\"]";
        if (contains(term, "\u062f\u0648\u0631\u0628\u06cc\u0646\u0020\u0633\u0631\u0639\u062a", "\u062f\u0648\u0631\u0628\u06cc\u0646\u0020\u06a9\u0646\u062a\u0631\u0644\u0020\u0633\u0631\u0639\u062a", "speed camera")) return "[\"highway\"=\"speed_camera\"]";
        if (contains(term, "\u0633\u0631\u0639\u062a\u200c\u06af\u06cc\u0631", "\u0633\u0631\u0639\u062a\u0020\u06af\u06cc\u0631", "\u062f\u0633\u062a\u0020\u0627\u0646\u062f\u0627\u0632", "\u062f\u0633\u062a\u200c\u0627\u0646\u062f\u0627\u0632", "speed bump")) return "[\"traffic_calming\"]";
        if (contains(term, "\u0627\u06cc\u0633\u062a\u0020\u0628\u0627\u0632\u0631\u0633\u06cc", "checkpoint")) return "[\"barrier\"=\"checkpoint\"]";
        if (contains(term, "\u062a\u0627\u0628\u0644\u0648\u0020\u0627\u06cc\u0633\u062a", "\u062a\u0627\u0628\u0644\u0648\u06cc\u0020\u0627\u06cc\u0633\u062a", "stop sign")) return "[\"highway\"=\"stop\"]";
        if (contains(term, "\u0645\u0633\u062c\u062f", "mosque")) return "[\"amenity\"=\"place_of_worship\"]";
        if (contains(term, "\u0633\u0631\u0648\u06cc\u0633", "toilet", "restroom")) return "[\"amenity\"=\"toilets\"]";
        if (contains(term, "\u062e\u0648\u062f\u067e\u0631\u062f\u0627\u0632", "atm")) return "[\"amenity\"=\"atm\"]";
        return null;
    }

    private boolean contains(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\u064A', '\u06CC').replace('\u0643', '\u06A9')
                .replace("\u200C", " ").trim().toLowerCase();
    }

    private String fallbackTitle(String term) {
        return term == null || term.trim().isEmpty() ? "\u0645\u06a9\u0627\u0646 \u0627\u0637\u0631\u0627\u0641" : term;
    }

    private String joinAddress(JSONObject tags) {
        String street = tags.optString("addr:street", "");
        String city = tags.optString("addr:city", "");
        if (street.isEmpty()) return city;
        return city.isEmpty() ? street : street + " - " + city;
    }

    private boolean isInIran(double latitude, double longitude) {
        return latitude >= 24.0d && latitude <= 40.5d && longitude >= 44.0d && longitude <= 64.5d;
    }
}
