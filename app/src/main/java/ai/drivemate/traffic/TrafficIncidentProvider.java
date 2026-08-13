package ai.drivemate.traffic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.TrafficIncident;
import ai.drivemate.routing.RoutingHttp;

/** Reads the public compact Iran traffic feed. No WazeAPI key is stored in the app. */
public final class TrafficIncidentProvider {
    private static final String SUMMARY_URL = "https://raw.githubusercontent.com/ghadirb/iran-traffic-data/main/mobile/summary.json";
    private static final String BASE_URL = "https://raw.githubusercontent.com/ghadirb/iran-traffic-data/main/mobile/";
    private static final long CACHE_MS = 5 * 60_000L;
    private boolean enabled = true;
    private JSONObject cachedSummary;
    private long summaryAt;
    private final java.util.Map<String, CachedRegion> regionCache = new HashMap<>();

    public TrafficIncidentProvider(String ignoredApiKey) { }
    public void setApiKey(String ignored) { }
    public boolean hasKey() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<TrafficIncident> incidentsNear(List<RoutePoint> geometry) throws Exception {
        if (!enabled || geometry == null || geometry.isEmpty()) return new ArrayList<>();
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
            JSONObject feed = getRegion(regionId);
            if (feed == null) continue;
            parseAlerts(feed.optJSONArray("a"), geometry, result);
            parseJams(feed.optJSONArray("j"), geometry, result);
        }
        return new ArrayList<>(result.values());
    }

    private JSONObject getSummary() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedSummary != null && now - summaryAt < CACHE_MS) return cachedSummary;
        cachedSummary = RoutingHttp.getJson(SUMMARY_URL);
        summaryAt = now;
        return cachedSummary;
    }

    private JSONObject getRegion(String id) throws Exception {
        long now = System.currentTimeMillis();
        CachedRegion cached = regionCache.get(id);
        if (cached != null && now - cached.at < CACHE_MS) return cached.data;
        JSONObject data = RoutingHttp.getJson(BASE_URL + id + ".json");
        regionCache.put(id, new CachedRegion(data, now));
        return data;
    }

    private boolean routeTouchesBox(List<RoutePoint> geometry, JSONArray b) {
        double minLat=b.optDouble(0), minLng=b.optDouble(1), maxLat=b.optDouble(2), maxLng=b.optDouble(3);
        for (RoutePoint p : geometry) if (p.latitude >= minLat && p.latitude <= maxLat && p.longitude >= minLng && p.longitude <= maxLng) return true;
        return false;
    }

    private void parseAlerts(JSONArray arr, List<RoutePoint> route, LinkedHashMap<String, TrafficIncident> out) {
        if (arr == null) return;
        for (int i=0;i<arr.length();i++) {
            JSONObject x=arr.optJSONObject(i); if (x==null) continue;
            JSONArray p=x.optJSONArray("p"); if (p==null || p.length()<2) continue;
            double lat=p.optDouble(0), lng=p.optDouble(1);
            if (distanceToRouteMeters(lat,lng,route) > 900) continue;
            String type=x.optString("t","OTHER");
            TrafficIncident.Type mapped;
            if ("ACCIDENT".equals(type)) mapped=TrafficIncident.Type.ACCIDENT;
            else if ("ROAD_CLOSED".equals(type)) mapped=TrafficIncident.Type.ROAD_CLOSED;
            else if ("ROADWORK".equals(type)) mapped=TrafficIncident.Type.ROADWORK;
            else mapped=TrafficIncident.Type.HAZARD;
            String detail=x.optString("d","");
            if (detail.isEmpty()) detail=x.optString("st","");
            if ("POLICE".equals(type)) detail=detail.isEmpty()?"پلیس":("پلیس: "+detail);
            out.put(x.optString("id", type+"-"+i), new TrafficIncident(x.optString("id", type+"-"+i),mapped,lat,lng,detail,0));
        }
    }

    private void parseJams(JSONArray arr, List<RoutePoint> route, LinkedHashMap<String, TrafficIncident> out) {
        if (arr == null) return;
        for (int i=0;i<arr.length();i++) {
            JSONObject x=arr.optJSONObject(i); if (x==null) continue;
            JSONArray line=x.optJSONArray("g");
            double bestLat=Double.NaN,bestLng=Double.NaN,best=Double.MAX_VALUE;
            if (line != null) for(int j=0;j<line.length();j++) {
                JSONArray p=line.optJSONArray(j); if(p==null||p.length()<2) continue;
                double lat=p.optDouble(0),lng=p.optDouble(1),d=distanceToRouteMeters(lat,lng,route);
                if(d<best){best=d;bestLat=lat;bestLng=lng;}
            }
            if(Double.isNaN(bestLat)||best>900) continue;
            String street=x.optString("s","");
            String detail="ترافیک"+(street.isEmpty()?"":" در "+street);
            double speed=x.optDouble("v",-1); double delay=x.optDouble("d",0);
            if(speed>=0) detail += ", سرعت تقریبی "+Math.round(speed);
            if(delay>0) detail += ", تأخیر "+Math.round(delay)+" ثانیه";
            String id=x.optString("id","jam-"+i);
            out.put(id,new TrafficIncident(id,TrafficIncident.Type.TRAFFIC_JAM,bestLat,bestLng,detail,Math.max(0,(int)delay)));
        }
    }

    private double distanceToRouteMeters(double lat,double lng,List<RoutePoint> route){
        double best=Double.MAX_VALUE;
        for(RoutePoint p:route){ double d=distanceMeters(lat,lng,p.latitude,p.longitude); if(d<best)best=d; }
        return best;
    }
    private double distanceMeters(double aLat,double aLng,double bLat,double bLng){
        double dLat=Math.toRadians(bLat-aLat),dLng=Math.toRadians(bLng-aLng);
        double h=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(Math.toRadians(aLat))*Math.cos(Math.toRadians(bLat))*Math.sin(dLng/2)*Math.sin(dLng/2);
        return 6371000d*2d*Math.atan2(Math.sqrt(h),Math.sqrt(1d-h));
    }
    private static final class CachedRegion { final JSONObject data; final long at; CachedRegion(JSONObject d,long a){data=d;at=a;} }
}