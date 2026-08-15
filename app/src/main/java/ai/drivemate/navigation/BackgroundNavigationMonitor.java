package ai.drivemate.navigation;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ai.drivemate.BuildConfig;
import ai.drivemate.ai.RuntimeKeys;
import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteSafetyAlert;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.model.TrafficIncident;
import ai.drivemate.routing.MapIrRoutingProvider;
import ai.drivemate.routing.NeshanRoutingProvider;
import ai.drivemate.routing.OfflineRoadSafetyProvider;
import ai.drivemate.routing.OpenRouteServiceRoutingProvider;
import ai.drivemate.routing.RouteRepository;
import ai.drivemate.routing.TomTomRoutingProvider;
import ai.drivemate.traffic.TrafficIncidentProvider;
import ai.drivemate.voice.VoiceGuidancePlayer;

/** Background traffic/safety monitor; it never owns or stops the navigation engine. */
public final class BackgroundNavigationMonitor {
    public interface Host {
        RouteResult activeRoute();
        SavedPlace activeDestination();
        List<RoutePoint> activeWaypoints();
        Location currentLocation();
        boolean isNavigating();
        void applyTrafficReroute(RouteResult route, Location origin, int gainSeconds);
    }

    private static final long REFRESH_MS = 5 * 60_000L;
    private static final int MIN_GAIN_SECONDS = 180;
    private static final long MIN_REROUTE_INTERVAL_MS = 10 * 60_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final VoiceGuidancePlayer voice;
    private final TrafficIncidentProvider traffic;
    private final OfflineRoadSafetyProvider safety;
    private final Host host;
    private final Set<String> announcedTraffic = new HashSet<>();
    private final Set<String> announcedSafety = new HashSet<>();
    private volatile RouteRepository routes;
    private boolean rerouteInFlight;
    private long lastRerouteAt;
    public BackgroundNavigationMonitor(Context context, VoiceGuidancePlayer voice, Host host) {
        this.voice = voice;
        this.host = host;
        this.traffic = new TrafficIncidentProvider("");
        this.safety = new OfflineRoadSafetyProvider(context);
        initializeRoutes();
    }

    private void initializeRoutes() {
        new Thread(() -> {
            try {
                RuntimeKeys keys = RuntimeKeys.fetchDefault(BuildConfig.KEYS_DECRYPTION_SECRET);
                NeshanRoutingProvider n = new NeshanRoutingProvider(keys.get("NESHAN_API_KEY"));
                MapIrRoutingProvider m = new MapIrRoutingProvider(keys.get("MAPIR_API_KEY"));
                TomTomRoutingProvider t = new TomTomRoutingProvider(keys.get("TOMTOM_API_KEY"));
                OpenRouteServiceRoutingProvider o = new OpenRouteServiceRoutingProvider(keys.get("OPENROUTESERVICE_API_KEY"));
                n.setEnabled(keys.providerEnabled("NESHAN", true));
                m.setEnabled(keys.providerEnabled("MAPIR", true));
                t.setEnabled(keys.providerEnabled("TOMTOM", true));
                o.setEnabled(keys.providerEnabled("OPENROUTESERVICE", true));
                routes = new RouteRepository(n, m, t, o);
            } catch (Exception ignored) { routes = null; }
        }).start();
    }

    public void start() {
        handler.removeCallbacks(task);
        if (host.isNavigating()) handler.post(task);
    }

    public void stop() { handler.removeCallbacks(task); }

    public void resetAnnouncements() {
        announcedTraffic.clear();
        announcedSafety.clear();
    }

    private final Runnable task = () -> refreshAndReschedule();

    private void refreshAndReschedule() {
        refresh();
        handler.postDelayed(task, REFRESH_MS);
    }
    private void refresh() {
        if (!host.isNavigating()) return;
        RouteResult route = host.activeRoute();
        if (route == null || route.geometry == null || route.geometry.size() < 2) return;
        Location current = host.currentLocation();
        new Thread(() -> {
            try {
                List<TrafficIncident> incidents = traffic.incidentsNear(route.geometry);
                Set<String> currentTrafficIds = new HashSet<>();
                for (TrafficIncident incident : incidents) {
                    if (!ahead(incident.latitude, incident.longitude, route.geometry, current)) continue;
                    String id = incident.id == null ? incident.type.name()+":"+incident.latitude+":"+incident.longitude : incident.id;
                    currentTrafficIds.add(id);
                    if (announcedTraffic.add(id)) voice.announce("bg-traffic:" + id, trafficText(incident));
                }
                announcedTraffic.retainAll(currentTrafficIds);
                List<RouteSafetyAlert> alerts = safety.safetyAlertsNear(route.geometry);
                Set<String> currentSafetyIds = new HashSet<>();
                for (RouteSafetyAlert alert : alerts) {
                    if (!ahead(alert.latitude, alert.longitude, route.geometry, current)) continue;
                    String id = alert.type.name()+":"+Math.round(alert.latitude*10000)+":"+Math.round(alert.longitude*10000);
                    currentSafetyIds.add(id);
                    if (announcedSafety.add(id)) voice.announce("bg-safety:" + id, safetyText(alert));
                }
                announcedSafety.retainAll(currentSafetyIds);
                maybeReroute(current);
            } catch (Exception ignored) { }
        }).start();
    }

    private void maybeReroute(Location current) {
        if (current == null || rerouteInFlight || !host.isNavigating()) return;
        long now = System.currentTimeMillis();
        if (now - lastRerouteAt < MIN_REROUTE_INTERVAL_MS) return;
        RouteResult old = host.activeRoute();
        SavedPlace destination = host.activeDestination();
        RouteRepository repository = routes;
        if (old == null || destination == null || repository == null || !repository.hasConfiguredProvider()) return;
        rerouteInFlight = true;
        repository.getRoute(current.getLatitude(), current.getLongitude(), host.activeWaypoints(),
                destination.latitude, destination.longitude, route -> {
                    int gain = old.durationSeconds - route.durationSeconds;
                    boolean better = old.durationSeconds >= 300 && route.durationSeconds > 0
                            && gain >= MIN_GAIN_SECONDS && gain * 100 >= old.durationSeconds * 12;
                    if (better && route.geometry != null && route.geometry.size() >= 2) {
                        host.applyTrafficReroute(route, current, gainSeconds(gain));
                        lastRerouteAt = now;
                    }
                    rerouteInFlight = false;
                }, message -> rerouteInFlight = false);
    }

    private int gainSeconds(int gain) { return Math.max(0, gain); }
    private boolean ahead(double lat, double lng, List<RoutePoint> geometry, Location current) {
        if (current == null) return true;
        int currentIndex = nearest(current.getLatitude(), current.getLongitude(), geometry);
        int incidentIndex = nearest(lat, lng, geometry);
        return currentIndex < 0 || incidentIndex < 0 || incidentIndex > currentIndex;
    }

    private int nearest(double lat, double lng, List<RoutePoint> geometry) {
        int bestIndex = -1;
        float best = Float.MAX_VALUE;
        for (int i=0; i<geometry.size(); i++) {
            RoutePoint p = geometry.get(i);
            if (p == null) continue;
            float[] d = new float[1];
            Location.distanceBetween(lat, lng, p.latitude, p.longitude, d);
            if (d[0] < best) { best=d[0]; bestIndex=i; }
        }
        return bestIndex;
    }

    private String trafficText(TrafficIncident incident) {
        switch (incident.type) {
            case ACCIDENT: return "هشدار: تصادف در مسیر شما جلوتر است.";
            case ROAD_CLOSED: return "هشدار: انسداد مسیر در جلو وجود دارد.";
            case ROADWORK: return "هشدار: عملیات عمرانی در مسیر شما جلوتر است.";
            case TRAFFIC_JAM: return "هشدار: ترافیک در مسیر شما جلوتر است.";
            default: return incident.description == null || incident.description.isEmpty()
                    ? "هشدار: مانع در مسیر شما جلوتر است." : "هشدار مسیر: " + incident.description;
        }
    }

    private String safetyText(RouteSafetyAlert alert) {
        switch (alert.type) {
            case RAILWAY_CROSSING: return "هشدار: تقاطع راه‌آهن در پیش است.";
            case SPEED_CAMERA: return "هشدار: دوربین کنترل سرعت در پیش است.";
            case SPEED_BUMP: return "هشدار: سرعت‌گیر در پیش است.";
            case STOP_SIGN: return "هشدار: تابلو ایست در پیش است.";
            default: return "هشدار ایمنی مسیر در پیش است.";
        }
    }
}
