package ai.drivemate.traffic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.TrafficIncident;
import ai.drivemate.routing.RoutingHttp;

/** Reads the public compact Iran traffic feed. No WazeAPI key is stored in the app. */
public final class TrafficIncidentProvider {
    private static final String SUMMARY_URL = "https://raw.githubusercontent.com/ghadirb/iran-traffic-data/main/mobile/summary.json";
    private static final String BASE_URL = "https://raw.githubusercontent.com/ghadirb/iran-traffic-data/main/mobile/";
    private static final long CACHE_MS = 5 * 60_000L;
    private static final long ANNOUNCED_TTL_MS = 30 * 60_000L;
    private static final double MAX_ROUTE_DISTANCE_METERS = 900d;
    private static final double MIN_AHEAD_METERS = 60d;
    private static final double BEHIND_TOLERANCE_METERS = 35d;

    private boolean enabled = true;
    private JSONObject cachedSummary;
    private long summaryAt;
    private final Map<String, CachedRegion> regionCache = new HashMap<>();
    private final Map<String, Long> seenIncidentAt = new HashMap<>();

    public TrafficIncidentProvider(String ignoredApiKey) { }
    public void setApiKey(String ignored) { }
    public boolean hasKey() { return enabled; }

    /** The app's remote Iran feed is always the selected traffic source; Waze credentials never enter the APK. */
    public void setEnabled(boolean ignored) { this.enabled = true; }

    public synchronized List<TrafficIncident> incidentsNear(List<RoutePoint> geometry) throws Exception {
        if (!enabled || geometry == null || geometry.size() < 2) return new ArrayList<>();
        purgeExpiredSeen();
        JSONObject summary = getSummary();
        if (summary == null) return new ArrayList<>();
        LinkedHashMap<String, TrafficIncident> result = new LinkedHashMap<>();
        JSONObject regionMap = summary.optJSONObject("regions");
        if (regionMap == null) return new ArrayList<>();
        Iterator<String> keys = regionMap.keys();
        while (keys.hasNext()) {
            String regionId = keys.next();
            JSONObject meta = regionMap.optJSONObject(regionId);
            JSONArray bbox = meta == null ? null : meta.optJSONArray("bbox");
            if (bbox == null || bbox.length() < 4 || !routeTouchesBox(geometry, bbox)) continue;
            JSONObject feed;
            try {
                feed = getRegion(regionId);
            } catch (Exception ignored) {
                // One stale/down region must never abort the whole traffic refresh.
                continue;
            }
            if (feed == null) continue;
            parseAlerts(feed.optJSONArray("a"), geometry, result);
            parseJams(feed.optJSONArray("j"), geometry, result);
        }
        return new ArrayList<>(result.values());
    }

    private JSONObject getSummary() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedSummary != null && now - summaryAt < CACHE_MS) return cachedSummary;
        JSONObject fresh = RoutingHttp.getJson(SUMMARY_URL);
        if (fresh == null) return cachedSummary;
        cachedSummary = fresh;
        summaryAt = now;
        return cachedSummary;
    }

    private JSONObject getRegion(String id) throws Exception {
        long now = System.currentTimeMillis();
        CachedRegion cached = regionCache.get(id);
        if (cached != null && now - cached.at < CACHE_MS) return cached.data;
        JSONObject data = RoutingHttp.getJson(BASE_URL + id + ".json");
        if (data == null) return cached == null ? null : cached.data;
        regionCache.put(id, new CachedRegion(data, now));
        return data;
    }

    private boolean routeTouchesBox(List<RoutePoint> geometry, JSONArray b) {
        double minLat = b.optDouble(0), minLng = b.optDouble(1), maxLat = b.optDouble(2), maxLng = b.optDouble(3);
        if (!validCoordinate(minLat, minLng) || !validCoordinate(maxLat, maxLng)) return false;
        for (RoutePoint p : geometry) {
            if (p == null || !validCoordinate(p.latitude, p.longitude)) continue;
            if (p.latitude >= minLat && p.latitude <= maxLat && p.longitude >= minLng && p.longitude <= maxLng) return true;
        }
        return false;
    }

    private void parseAlerts(JSONArray arr, List<RoutePoint> route, LinkedHashMap<String, TrafficIncident> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            if (x == null) continue;
            JSONArray p = x.optJSONArray("p");
            if (p == null || p.length() < 2) continue;
            double lat = p.optDouble(0), lng = p.optDouble(1);
            if (!validCoordinate(lat, lng)) continue;
            RoutePosition position = positionOnRoute(lat, lng, route);
            if (position.distanceMeters > MAX_ROUTE_DISTANCE_METERS || !position.ahead) continue;

            String type = x.optString("t", "OTHER");
            TrafficIncident.Type mapped;
            if ("ACCIDENT".equals(type)) mapped = TrafficIncident.Type.ACCIDENT;
            else if ("ROAD_CLOSED".equals(type)) mapped = TrafficIncident.Type.ROAD_CLOSED;
            else if ("ROADWORK".equals(type)) mapped = TrafficIncident.Type.ROADWORK;
            else mapped = TrafficIncident.Type.HAZARD;
            String detail = x.optString("d", "");
            if (detail.isEmpty()) detail = x.optString("st", "");
            if ("POLICE".equals(type)) detail = detail.isEmpty() ? "پلیس" : ("پلیس: " + detail);
            String id = stableId(x.optString("id", ""), type, lat, lng, i);
            if (shouldReturn(id, mapped)) {
                out.put(id, new TrafficIncident(id, mapped, lat, lng, detail, 0));
            }
        }
    }

    private void parseJams(JSONArray arr, List<RoutePoint> route, LinkedHashMap<String, TrafficIncident> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            if (x == null) continue;
            JSONArray line = x.optJSONArray("g");
            double bestLat = Double.NaN, bestLng = Double.NaN, best = Double.MAX_VALUE;
            int bestIndex = -1;
            if (line != null) for (int j = 0; j < line.length(); j++) {
                JSONArray p = line.optJSONArray(j);
                if (p == null || p.length() < 2) continue;
                double lat = p.optDouble(0), lng = p.optDouble(1);
                if (!validCoordinate(lat, lng)) continue;
                RoutePosition pos = positionOnRoute(lat, lng, route);
                if (pos.distanceMeters < best) {
                    best = pos.distanceMeters;
                    bestLat = lat;
                    bestLng = lng;
                    bestIndex = pos.index;
                }
            }
            if (Double.isNaN(bestLat) || best > MAX_ROUTE_DISTANCE_METERS || bestIndex < 0) continue;
            RoutePosition bestPosition = positionOnRoute(bestLat, bestLng, route);
            if (!bestPosition.ahead) continue;
            String street = x.optString("s", "");
            String detail = "ترافیک" + (street.isEmpty() ? "" : " در " + street);
            double speed = x.optDouble("v", -1);
            double delay = x.optDouble("d", 0);
            if (speed >= 0) detail += ", سرعت تقریبی " + Math.round(speed);
            if (delay > 0) detail += ", تأخیر " + Math.round(delay) + " ثانیه";
            String id = stableId(x.optString("id", ""), "jam", bestLat, bestLng, i);
            TrafficIncident.Type type = TrafficIncident.Type.TRAFFIC_JAM;
            if (shouldReturn(id, type)) {
                out.put(id, new TrafficIncident(id, type, bestLat, bestLng, detail, Math.max(0, (int) delay)));
            }
        }
    }

    /** Returns the nearest route vertex and whether the incident lies materially ahead of route start. */
    private RoutePosition positionOnRoute(double lat, double lng, List<RoutePoint> route) {
        double best = Double.MAX_VALUE;
        int bestIndex = -1;
        for (int i = 0; i < route.size(); i++) {
            RoutePoint p = route.get(i);
            if (p == null || !validCoordinate(p.latitude, p.longitude)) continue;
            double d = distanceMeters(lat, lng, p.latitude, p.longitude);
            if (d < best) { best = d; bestIndex = i; }
        }
        if (bestIndex < 0) return new RoutePosition(Double.MAX_VALUE, -1, false);
        if (bestIndex == 0) return new RoutePosition(best, 0, best >= MIN_AHEAD_METERS);
        double aheadDistance = distanceAlongRoute(route, 0, bestIndex);
        return new RoutePosition(best, bestIndex, aheadDistance >= MIN_AHEAD_METERS - BEHIND_TOLERANCE_METERS);
    }

    private double distanceAlongRoute(List<RoutePoint> route, int from, int to) {
        double total = 0d;
        int end = Math.min(to, route.size() - 1);
        for (int i = Math.max(0, from + 1); i <= end; i++) {
            RoutePoint a = route.get(i - 1), b = route.get(i);
            if (a == null || b == null) continue;
            total += distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude);
        }
        return total;
    }

    private boolean shouldReturn(String id, TrafficIncident.Type type) {
        if (id == null || id.isEmpty()) return false;
        Long last = seenIncidentAt.get(id);
        long now = System.currentTimeMillis();
        if (last != null && now - last < ANNOUNCED_TTL_MS) return false;
        // Important incidents are allowed to reappear after the TTL. The caller can then announce
        // a genuinely new feed occurrence without repeating every 90-second refresh forever.
        seenIncidentAt.put(id, now);
        return true;
    }

    private void purgeExpiredSeen() {
        long now = System.currentTimeMillis();
        java.util.Iterator<Map.Entry<String, Long>> it = seenIncidentAt.entrySet().iterator();
        while (it.hasNext()) if (now - it.next().getValue() >= ANNOUNCED_TTL_MS) it.remove();
    }

    private String stableId(String raw, String type, double lat, double lng, int index) {
        if (raw != null && !raw.trim().isEmpty()) return raw.trim();
        return type + "-" + Math.round(lat * 10_000d) + "-" + Math.round(lng * 10_000d) + "-" + index;
    }

    private boolean validCoordinate(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng) && !Double.isInfinite(lat) && !Double.isInfinite(lng)
                && lat >= -90d && lat <= 90d && lng >= -180d && lng <= 180d;
    }

    private double distanceMeters(double aLat, double aLng, double bLat, double bLng) {
        double dLat = Math.toRadians(bLat - aLat), dLng = Math.toRadians(bLng - aLng);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371000d * 2d * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0d, 1d - h)));
    }

    private static final class CachedRegion {
        final JSONObject data;
        final long at;
        CachedRegion(JSONObject d, long a) { data = d; at = a; }
    }

    private static final class RoutePosition {
        final double distanceMeters;
        final int index;
        final boolean ahead;
        RoutePosition(double distanceMeters, int index, boolean ahead) {
            this.distanceMeters = distanceMeters;
            this.index = index;
            this.ahead = ahead;
        }
    }
}
