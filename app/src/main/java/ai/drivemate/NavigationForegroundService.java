package ai.drivemate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ai.drivemate.ai.OnlineSpeechClient;
import ai.drivemate.ai.RuntimeKeys;
import ai.drivemate.location.DeviceLocationTracker;
import ai.drivemate.navigation.BackgroundNavigationMonitor;
import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteStep;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.model.TripRecord;
import ai.drivemate.routing.NavigationEngine;
import ai.drivemate.routing.OverpassPoiProvider;
import ai.drivemate.model.SpeedLimitPoint;
import ai.drivemate.session.NavigationSessionStore;
import ai.drivemate.storage.TripStore;
import ai.drivemate.voice.VoiceGuidancePlayer;

/**
 * Stage 2 (service-owned navigation): this service now owns the entire live-trip core -
 * {@link DeviceLocationTracker}, {@link NavigationEngine}, {@link VoiceGuidancePlayer} and the
 * distance/path trip-tracking previously kept in MainActivity - so GPS, turn-by-turn, safety
 * voice guidance and trip completion all keep running (and get correctly recorded) after the
 * hosting Activity is destroyed or the whole process is killed and later restarted by Android via
 * {@code START_STICKY}.
 *
 * MainActivity/MapActivity are UI only: they bind via {@link LocalBinder}, read state through the
 * getters below, drive input through {@link #startNavigation} / {@link #stopNavigationSession},
 * and - only while actually bound - receive every {@link NavigationEngine.Listener} event through
 * {@link SessionCallback} so their existing AI-enhanced/queued voice layer (unchanged, still
 * Activity-side - see Stage 3) can speak it instead of the plain local fallback below. Exactly one
 * of "the bound Activity speaks it" or "this service speaks the plain local fallback" ever happens
 * per event - never both - so guidance is never duplicated, and safety/turn-by-turn voice never
 * waits on a bound Activity, AI or internet: with no callback registered this service always still
 * announces locally through {@link VoiceGuidancePlayer} on its own.
 *
 * What intentionally still lives in the Activity for now (out of this stage's scope): route
 * fetching/rerouting itself (network + provider selection), hazards/speed-limits/safety-alerts/
 * traffic-incident/weather lookups, SmartDriveCompanion, and the AI-enhanced voice-queue layer
 * (speakDrivingEvent/DrivingIntelligenceCoordinator) - see the ongoing refactor notes for the
 * later stages that address those.
 */
public class NavigationForegroundService extends Service implements BackgroundNavigationMonitor.Host {
    private static final String TAG = "DriveMateNavService";
    private static final int MIN_RECORDED_TRIP_DISTANCE_METERS = 100;
    public static final String ACTION_STOP = "ai.drivemate.action.STOP_NAVIGATION";
    public static final String ACTION_STOP_BROADCAST = "ai.drivemate.action.STOP_NAVIGATION_BROADCAST";
    // v2 intentionally avoids inheriting an old user/channel choice made for the previous LOW
    // channel. Navigation is an ongoing user-visible task and must remain discoverable in the
    // notification drawer; the user can still change the channel's behavior in system settings.
    private static final String CHANNEL_ID = "navigation_active_v2";
    private static final int NOTIFICATION_ID = 410;
    private static final int MIN_RECORDED_TRIP_DISTANCE_METERS = 100;

    /** Implemented by a bound Activity to receive every live navigation-engine/location event
     *  while it is on screen, so it can keep driving its own UI and its richer, AI-enhanced voice
     *  layer exactly as before this refactor. Never called while no Activity is bound - see the
     *  class javadoc for why the service speaks the plain local fallback itself in that case. */
    public interface SessionCallback {
        void onInstruction(RouteStep step);
        void onOffRoute();
        /** tripReport is the just-saved record (see TripStore) so the Activity can show the
         *  completion dialog immediately without a second read from disk. */
        void onArrived(SavedPlace destination, TripRecord tripReport);
        void onWaypointApproaching(RouteStep step, int waypointOrdinal);
        void onWaypointReached(RouteStep step, int waypointOrdinal);
        void onWaypointSkipped(RouteStep step, int waypointOrdinal);
        void onInstructionStage(RouteStep step, NavigationEngine.AnnouncementStage stage, int metersRemaining);
        void onLocationUpdate(Location location);
        void onLocationAvailabilityChanged(boolean available);
        /** Fired whenever startNavigation() (re)points the engine at a route - a fresh trip or a
         *  reroute, from whichever bound Activity triggered it. Route fetching still happens
         *  Activity-side (see class javadoc), so without this, an Activity that did NOT itself
         *  trigger the reroute (e.g. MapActivity when MainActivity's off-route handler is the one
         *  that actually replaced the engine's route) had no way to learn the engine's route had
         *  changed underneath it - it kept drawing/tracking the stale pre-reroute route while the
         *  engine (and the other Activity's voice guidance) had already moved on to the new one. */
        void onRouteReplaced(RouteResult route);
    }

    public final class LocalBinder extends Binder {
        public NavigationForegroundService getService() { return NavigationForegroundService.this; }
    }

    private final LocalBinder binder = new LocalBinder();
    /** Every currently-bound Activity that wants live events (MainActivity always while alive,
     *  MapActivity while it's the visible screen). A plain single field here used to mean whichever
     *  Activity registered last silently cut the other off - e.g. opening the map screen would stop
     *  MainActivity's own voice/AI layer from ever hearing another event again, and nothing ever
     *  re-registered it. A list lets both hold a registration at once. */
    private final java.util.List<SessionCallback> callbacks = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Callbacks registered with providesVoice=false (see addCallback) - currently just
     *  MapActivity, which only drives its own turn banner/UI and was never wired to speak
     *  anything itself (see the onInstruction/onInstructionStage local-fallback checks below). If
     *  MainActivity's callback is the only voice-capable one and Android destroys that Activity
     *  instance (low-memory reclaim) while only the map screen is left on screen, callbacks stops
     *  being empty - so the old callbacks.isEmpty() local-fallback check never re-armed, and every
     *  navigation cue went silent even though the trip kept running correctly. Tracking voice
     *  capability per-callback lets the local fallback re-arm in exactly that case, without
     *  double-speaking when MainActivity is still alive alongside MapActivity. */
    private final java.util.Set<SessionCallback> silentCallbacks =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private DeviceLocationTracker locationTracker;
    private final NavigationEngine navigationEngine = new NavigationEngine();
    private VoiceGuidancePlayer voicePlayer;
    private OnlineSpeechClient onlineSpeechClient;
    private NavigationSessionStore sessionStore;
    private TripStore tripStore;
    private boolean restoreAttempted;

    // Live session state - the service-owned equivalent of MainActivity's former
    // activeRoute/activeDestination/activeWaypoints/trip-tracking fields.
    private RouteResult activeRoute;
    private SavedPlace activeDestination;
    private List<RoutePoint> activeWaypoints = new ArrayList<>();
    private String activeMode = "";
    private long tripStartedAt;
    private int activeTripDistanceMeters;
    private double activeTripOriginLatitude = Double.NaN;
    private double activeTripOriginLongitude = Double.NaN;
    private final List<RoutePoint> activeTripPath = new ArrayList<>();
    private Location lastTripLocation;
    /** Last geometry vertex used while building the matched trip trace. Reset whenever a
     *  provider replaces the active route so an old route cannot be joined to the new one. */
    private int lastTripGeometryIndex = -1;
    private RoutePoint fallbackAnnouncedWaypoint;
    private int completedWaypointCount;
    private final Set<String> handledWaypointKeys = new HashSet<>();
    private long waypointSpeechGeneration;
    private long lastSessionCheckpointAt;
    private BackgroundNavigationMonitor backgroundMonitor;
    private final OverpassPoiProvider speedLimitProvider = new OverpassPoiProvider();
    private List<SpeedLimitPoint> activeSpeedLimits = new ArrayList<>();
    private int overspeedSamples;
    private long lastOverspeedWarningAt;
    private boolean overspeedWarningActive;

    @Override public void onCreate() {
        super.onCreate();
        sessionStore = new NavigationSessionStore(this);
        tripStore = new TripStore(this);
        voicePlayer = new VoiceGuidancePlayer(this);
        onlineSpeechClient = new OnlineSpeechClient(this, BuildConfig.AI_API_KEY);
        backgroundMonitor = new BackgroundNavigationMonitor(this, voicePlayer, this);
        locationTracker = new DeviceLocationTracker(this);
        locationTracker.setUpdateListener(new DeviceLocationTracker.UpdateListener() {
            @Override public void onLocationUpdate(Location location) {
                navigationEngine.onLocation(location);
                checkFallbackWaypoint(location);
                recordTripLocation(location);
                if (noVoiceCapableCallback()) checkSpeedLimit(location);
                for (SessionCallback cb : callbacks) cb.onLocationUpdate(location);
            }
            @Override public void onLocationAvailabilityChanged(boolean available) {
                for (SessionCallback cb : callbacks) cb.onLocationAvailabilityChanged(available);
            }
        });
        restoreSessionIfNeeded();
    }

    /** Runs once per process (onCreate fires exactly once per service instance, including the
     *  fresh instance Android creates via START_STICKY after a process kill), so this is the one
     *  place a killed trip resumes without any Activity involved. A restore failure (corrupt
     *  session, engine rejects the route) must never crash the service - it simply leaves no
     *  active session, same as a clean start. */
    private void restoreSessionIfNeeded() {
        if (restoreAttempted) return;
        restoreAttempted = true;
        NavigationSessionStore.Session session = sessionStore.load();
        if (session == null || session.route == null || session.route.steps.isEmpty()) return;
        activeRoute = session.route;
        activeDestination = session.destination;
        activeWaypoints = session.waypoints == null ? new ArrayList<>() : new ArrayList<>(session.waypoints);
        activeMode = session.mode;
        tripStartedAt = session.tripStartAtMillis;
        activeTripDistanceMeters = Math.round(session.travelledMeters);
        activeTripPath.clear();
        if (session.tripPath != null) activeTripPath.addAll(session.tripPath);
        completedWaypointCount = 0;
        fallbackAnnouncedWaypoint = null;
        handledWaypointKeys.clear();
        waypointSpeechGeneration++;
        locationTracker.start();
        Location current = locationTracker.getLastLocation();
        RoutePoint finalDestination = activeDestination == null ? null
                : new RoutePoint(activeDestination.latitude, activeDestination.longitude);
        navigationEngine.start(session.route, engineListener, current, finalDestination, session.currentStepIndex);
        ensureForeground();
        fetchSpeedLimits(session.route);
        if (backgroundMonitor != null) backgroundMonitor.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            sendBroadcast(new Intent(ACTION_STOP_BROADCAST).setPackage(getPackageName()));
            stopNavigationSession();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        restoreSessionIfNeeded();
        ensureForeground();
        return START_STICKY;
    }

    private void ensureForeground() {
        createChannel();
        // Must happen immediately after startForegroundService() on Android O+.
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override public IBinder onBind(Intent intent) {
        restoreSessionIfNeeded();
        return binder;
    }

    /** Registers a bound Activity for live events - safe to call from more than one Activity at
     *  once (see the callbacks field javadoc). Adding the same instance twice is a no-op. */
    public void addCallback(SessionCallback cb) { addCallback(cb, true); }

    /** providesVoice=false for a callback (e.g. MapActivity) that only updates its own UI and
     *  relies on some other voice-capable callback (MainActivity) to actually speak - see
     *  silentCallbacks above for why this matters. */
    public void addCallback(SessionCallback cb, boolean providesVoice) {
        if (!callbacks.contains(cb)) callbacks.add(cb);
        if (providesVoice) silentCallbacks.remove(cb); else silentCallbacks.add(cb);
    }

    public void removeCallback(SessionCallback cb) {
        callbacks.remove(cb);
        silentCallbacks.remove(cb);
    }

    /** True when nobody currently registered is able to speak a navigation cue - either no
     *  Activity is bound at all, or every bound Activity registered with providesVoice=false. */
    private boolean noVoiceCapableCallback() {
        for (SessionCallback cb : callbacks) if (!silentCallbacks.contains(cb)) return false;
        return true;
    }

    public NavigationEngine getNavigationEngine() { return navigationEngine; }
    public DeviceLocationTracker getLocationTracker() { return locationTracker; }
    public VoiceGuidancePlayer getVoicePlayer() { return voicePlayer; }
    public void setRuntimeKeys(RuntimeKeys keys) {
        if (onlineSpeechClient != null) onlineSpeechClient.setRuntimeKeys(keys);
    }
    public boolean hasVoiceCapableCallback() { return !noVoiceCapableCallback(); }
    public boolean isNavigating() { return navigationEngine.isNavigating(); }
    public RouteResult getActiveRoute() { return activeRoute; }
    public SavedPlace getActiveDestination() { return activeDestination; }
    public List<RoutePoint> getActiveWaypoints() { return new ArrayList<>(activeWaypoints); }
    public long getTripStartedAt() { return tripStartedAt; }
    public int getTripDistanceMeters() { return activeTripDistanceMeters; }
    public List<RoutePoint> getTripPath() { return new ArrayList<>(activeTripPath); }

    /** Starts (or resumes, when preserveTripProgress is true and a trip is already running) a
     *  navigation session. The caller (Activity) is still responsible for fetching/selecting the
     *  route itself - only the already-resolved RouteResult crosses this boundary. */
    public void startNavigation(RouteResult route, SavedPlace destination, List<RoutePoint> waypoints,
                                String mode, Location origin, boolean preserveTripProgress) {
        activeRoute = route;
        activeDestination = destination;
        activeWaypoints = waypoints == null ? new ArrayList<>() : new ArrayList<>(waypoints);
        lastTripGeometryIndex = -1;
        fallbackAnnouncedWaypoint = null;
        if (!preserveTripProgress) completedWaypointCount = 0;
        if (!preserveTripProgress) handledWaypointKeys.clear();
        activeMode = mode == null ? "" : mode;
        if (!preserveTripProgress || tripStartedAt == 0L) {
            tripStartedAt = System.currentTimeMillis();
            activeTripDistanceMeters = 0;
            activeTripPath.clear();
            lastTripGeometryIndex = -1;
            if (origin != null) {
                activeTripOriginLatitude = origin.getLatitude();
                activeTripOriginLongitude = origin.getLongitude();
                appendTripPath(origin);
            }
        }
        lastTripLocation = origin == null ? null : new Location(origin);
        lastSessionCheckpointAt = 0L;
        locationTracker.start();
        RoutePoint finalDestination = destination == null ? null
                : new RoutePoint(destination.latitude, destination.longitude);
        // A reroute is calculated from the driver's current position, so the new route's step list has a different index space.
        // Never carry the old route's step index into the new route; the engine will select its first actionable
        // maneuver from the new route and then advance monotonically from live GPS.
        int initialStepIndex = 0;
        navigationEngine.start(route, engineListener, origin, finalDestination, initialStepIndex);
        android.util.Log.i(TAG, "start route waypoints=" + activeWaypoints.size()
                + " waypointSteps=" + navigationEngine.hasWaypointSteps());
        if (!activeWaypoints.isEmpty()) {
            RoutePoint firstWaypoint = activeWaypoints.get(0);
            android.util.Log.i(TAG, "first waypoint lat=" + firstWaypoint.latitude
                    + " lng=" + firstWaypoint.longitude);
        }
        for (SessionCallback cb : callbacks) cb.onRouteReplaced(route);
        ensureForeground();
        checkpointSession();
        fetchSpeedLimits(route);
        if (backgroundMonitor != null) backgroundMonitor.start();
    }

    /** Explicit driver-initiated stop (not arrival) - e.g. cancelling a trip from the UI. */
    public TripRecord stopNavigationSession() {
        TripRecord report = buildTripRecord(activeDestination, false);
        android.util.Log.i(TAG, "explicit stop report=" + (report != null) + " distance=" + activeTripDistanceMeters
                + " navigating=" + navigationEngine.isNavigating());
        if (report != null) tripStore.add(report);
        navigationEngine.stop();
        sessionStore.clear();
        clearTripState();
        if (locationTracker != null) locationTracker.stop();
        if (voicePlayer != null) voicePlayer.interrupt();
        if (onlineSpeechClient != null) onlineSpeechClient.stopPlayback();
        if (backgroundMonitor != null) backgroundMonitor.stop();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        return report;
    }

    /** Completes the active session from the service-owned arrival/Activity callback path. */
    public TripRecord finishNavigationSession() {
        SavedPlace destination = activeDestination;
        android.util.Log.i(TAG, "finishNavigationSession destination=" + (destination == null ? "null" : destination.name)
                + " distance=" + activeTripDistanceMeters + " navigating=" + navigationEngine.isNavigating());
        TripRecord report = buildTripRecord(destination, true);
        if (report != null) tripStore.add(report);
        navigationEngine.stop();
        sessionStore.clear();
        clearTripState();
        if (locationTracker != null) locationTracker.stop();
        if (voicePlayer != null) voicePlayer.interrupt();
        if (onlineSpeechClient != null) onlineSpeechClient.stopPlayback();
        if (backgroundMonitor != null) backgroundMonitor.stop();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        return report;
    }

    @Override public RouteResult activeRoute() { return activeRoute; }
    @Override public SavedPlace activeDestination() { return activeDestination; }
    @Override public List<RoutePoint> activeWaypoints() { return new ArrayList<>(activeWaypoints); }
    @Override public Location currentLocation() { return lastTripLocation == null ? locationTracker.getLastLocation() : new Location(lastTripLocation); }
    @Override public void applyTrafficReroute(RouteResult route, Location origin, int gainSeconds) {
        if (route == null || origin == null || !navigationEngine.isNavigating()) return;
        activeRoute = route;
        lastTripGeometryIndex = -1;
        fallbackAnnouncedWaypoint = null;
        // Traffic reroutes also replace the route geometry and therefore must restart step indexing at the new route origin.
        int step = 0;
        navigationEngine.start(route, engineListener, origin,
                activeDestination == null ? null : new RoutePoint(activeDestination.latitude, activeDestination.longitude), step);
        sessionStore.save(activeRoute, activeDestination, activeWaypoints, activeMode, tripStartedAt,
                activeTripDistanceMeters, navigationEngine.currentStepIndex(), currentWaypointOrdinal(), activeTripPath);
        voicePlayer.announce("background_reroute", "مسیر جایگزین پیدا شد و حدود " + Math.max(1, gainSeconds / 60) + " دقیقه سریع‌تر است.");
    }


    private void fetchSpeedLimits(RouteResult route) {
        activeSpeedLimits = new ArrayList<>();
        overspeedSamples = 0;
        overspeedWarningActive = false;
        if (route == null || route.geometry == null || route.geometry.size() < 2) return;
        final List<RoutePoint> geometry = new ArrayList<>(route.geometry);
        final List<SpeedLimitPoint> provider = route.providerSpeedLimits == null ? new ArrayList<>() : new ArrayList<>(route.providerSpeedLimits);
        new Thread(() -> {
            List<SpeedLimitPoint> resolved = provider;
            try {
                List<SpeedLimitPoint> osm = speedLimitProvider.speedLimitsNear(geometry);
                if (osm != null && !osm.isEmpty()) resolved = osm;
            } catch (Exception ignored) { }
            final List<SpeedLimitPoint> result = resolved;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> activeSpeedLimits = result);
        }).start();
    }

    private void checkSpeedLimit(Location location) {
        if (location == null || activeSpeedLimits.isEmpty() || !navigationEngine.isNavigating() || !location.hasSpeed()) return;
        if (location.hasAccuracy() && location.getAccuracy() > 50f) return;
        SpeedLimitPoint nearest = null; float nearestDistance = Float.MAX_VALUE;
        for (SpeedLimitPoint point : activeSpeedLimits) {
            float[] d = new float[1];
            Location.distanceBetween(location.getLatitude(), location.getLongitude(), point.latitude, point.longitude, d);
            if (d[0] < nearestDistance) { nearestDistance = d[0]; nearest = point; }
        }
        if (nearest == null || nearestDistance > 100f || nearest.kilometersPerHour <= 0) { overspeedSamples = 0; overspeedWarningActive = false; return; }
        float speedKmh = location.getSpeed() * 3.6f;
        if (speedKmh >= nearest.kilometersPerHour + 5f) overspeedSamples++;
        else if (speedKmh <= nearest.kilometersPerHour + 2f) { overspeedSamples = 0; overspeedWarningActive = false; }
        if (overspeedSamples >= 2 && !overspeedWarningActive && System.currentTimeMillis() - lastOverspeedWarningAt >= 60000L) {
            overspeedWarningActive = true;
            lastOverspeedWarningAt = System.currentTimeMillis();
            voicePlayer.announce("speed_limit_attention", "هشدار سرعت: سرعت مجاز این مسیر " + nearest.kilometersPerHour + " کیلومتر بر ساعت است؛ سرعت خود را کاهش دهید.");
        }
    }

    private void clearTripState() {
        activeRoute = null;
        activeDestination = null;
        activeWaypoints = new ArrayList<>();
        tripStartedAt = 0L;
        activeTripDistanceMeters = 0;
        activeTripPath.clear();
        lastTripGeometryIndex = -1;
        fallbackAnnouncedWaypoint = null;
        completedWaypointCount = 0;
        handledWaypointKeys.clear();
        waypointSpeechGeneration++;
        activeTripOriginLatitude = Double.NaN;
        activeTripOriginLongitude = Double.NaN;
        lastTripLocation = null;
    }

    private void checkpointSession() {
        if (activeRoute == null || !navigationEngine.isNavigating()) return;
        sessionStore.save(activeRoute, activeDestination, activeWaypoints, activeMode, tripStartedAt,
                activeTripDistanceMeters, navigationEngine.currentStepIndex(), currentWaypointOrdinal(), activeTripPath);
    }

    private void removeActiveWaypoint(RouteStep reachedStep) {
        if (reachedStep == null || activeWaypoints.isEmpty()) return;
        int closestIndex = -1;
        float closestDistance = Float.MAX_VALUE;
        Location reached = new Location("waypoint_reached");
        reached.setLatitude(reachedStep.latitude);
        reached.setLongitude(reachedStep.longitude);
        for (int i = 0; i < activeWaypoints.size(); i++) {
            RoutePoint waypoint = activeWaypoints.get(i);
            Location candidate = new Location("waypoint");
            candidate.setLatitude(waypoint.latitude);
            candidate.setLongitude(waypoint.longitude);
            float distance = reached.distanceTo(candidate);
            if (distance < closestDistance) { closestDistance = distance; closestIndex = i; }
        }
        if (closestIndex >= 0 && closestDistance <= 150f) activeWaypoints.remove(closestIndex);
    }

    private int currentWaypointOrdinal() {
        RouteStep step = navigationEngine.currentStep();
        return step == null ? -1 : step.waypointOrdinal;
    }

    /** Mirrors MainActivity's former recordTripLocation: only plausible movement samples count,
     *  so a weak/jumpy fix cannot inflate the recorded trip distance. */
    private void recordTripLocation(Location location) {
        if (location == null || !navigationEngine.isNavigating() || tripStartedAt == 0L) return;
        if (location.hasAccuracy() && location.getAccuracy() > 45f) return;
        if (lastTripLocation == null) {
            lastTripLocation = new Location(location);
            return;
        }
        float delta = lastTripLocation.distanceTo(location);
        boolean hasMovingSpeed = location.hasSpeed() && location.getSpeed() >= 0.8f;
        boolean movementIsPlausible = (hasMovingSpeed && delta >= 3f) || (!location.hasSpeed() && delta >= 12f);
        if (movementIsPlausible && delta <= 1_500f) {
            activeTripDistanceMeters += Math.round(delta);
            appendTripPath(location);
        }
        lastTripLocation = new Location(location);
        long now = System.currentTimeMillis();
        if (now - lastSessionCheckpointAt >= 5_000L) {
            lastSessionCheckpointAt = now;
            checkpointSession();
        }
    }

    private void appendTripPath(Location location) {
        if (location == null) return;
        RoutePoint snapped = navigationEngine.snappedRoutePosition();
        if (snapped != null && activeRoute != null && activeRoute.geometry != null
                && activeRoute.geometry.size() >= 2) {
            int currentIndex = nearestGeometryIndex(snapped);
            if (currentIndex >= 0 && lastTripGeometryIndex >= 0
                    && currentIndex >= lastTripGeometryIndex) {
                for (int index = lastTripGeometryIndex + 1;
                     index <= currentIndex && index < activeRoute.geometry.size(); index++) {
                    appendTripPoint(activeRoute.geometry.get(index));
                }
            } else {
                appendTripPoint(snapped);
            }
            lastTripGeometryIndex = currentIndex;
            appendTripPoint(snapped);
            return;
        }
        appendTripPoint(new RoutePoint(location.getLatitude(), location.getLongitude()));
    }

    /** Provider-independent safety net for a malformed or delayed multi-stop response. The engine
     *  normally owns these events; this proximity check ensures a requested stop is still
     *  announced and removed from future reroutes when its synthetic step is missing or late. */
    private void checkFallbackWaypoint(Location location) {
        if (location == null || !navigationEngine.isNavigating() || activeWaypoints.isEmpty()) return;
        RoutePoint waypoint = activeWaypoints.get(0);
        Location target = new Location("fallback_waypoint");
        target.setLatitude(waypoint.latitude);
        target.setLongitude(waypoint.longitude);
        float distance = location.distanceTo(target);
        float speed = location.hasSpeed() ? Math.max(1f, location.getSpeed()) : 8.3f;
        float announceDistance = Math.max(120f, Math.min(320f, speed * 8f));
        RouteStep step = new RouteStep(waypoint.latitude, waypoint.longitude,
                "به توقف میانی می‌رسید", 0, null, completedWaypointCount);
        if (fallbackAnnouncedWaypoint == null && distance <= announceDistance) {
            fallbackAnnouncedWaypoint = waypoint;
            android.util.Log.i(TAG, "fallback waypoint approaching ordinal=" + completedWaypointCount
                    + " distance=" + Math.round(distance));
            engineListener.onWaypointApproaching(step, completedWaypointCount);
        }
        if (distance <= 110f) {
            android.util.Log.i(TAG, "fallback waypoint reached ordinal=" + completedWaypointCount
                    + " distance=" + Math.round(distance));
            engineListener.onWaypointReached(step, completedWaypointCount);
        }
    }

    private String waypointKey(RouteStep step) {
        if (step == null) return "";
        return String.format(java.util.Locale.US, "%.6f,%.6f", step.latitude, step.longitude);
    }

    private void speakWaypoint(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (onlineSpeechClient != null && onlineSpeechClient.canUseOnlineTts()) {
            waypointSpeechGeneration++;
            if (voicePlayer != null) voicePlayer.interrupt();
            onlineSpeechClient.stopPlayback();
            android.util.Log.i(TAG, "waypoint speech path=online textLength=" + text.length());
            final long generation = waypointSpeechGeneration;
            final java.util.concurrent.atomic.AtomicBoolean delivered =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final android.os.Handler fallbackHandler =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            final Runnable localFallback = () -> {
                if (generation != waypointSpeechGeneration
                        || !delivered.compareAndSet(false, true)) return;
                // Cancel a slow network response before its audio can arrive after the local
                // safety cue has already been spoken.
                onlineSpeechClient.stopPlayback();
                android.util.Log.w(TAG, "waypoint online TTS watchdog; local fallback");
                if (voicePlayer != null) voicePlayer.speak(text);
            };
            fallbackHandler.postDelayed(localFallback, 1_500L);
            onlineSpeechClient.speak(text, new OnlineSpeechClient.SpeechCallback() {
                @Override public void onPlayed() {
                    if (!delivered.compareAndSet(false, true)) return;
                    fallbackHandler.removeCallbacks(localFallback);
                    android.util.Log.i(TAG, "waypoint TTS provider="
                            + onlineSpeechClient.getLastTtsProvider());
                }
                @Override public void onError() {
                    fallbackHandler.removeCallbacks(localFallback);
                    if (!delivered.compareAndSet(false, true)) return;
                    android.util.Log.w(TAG, "waypoint online TTS failed; local fallback");
                    if (generation == waypointSpeechGeneration
                            && navigationEngine.isNavigating() && voicePlayer != null) {
                        voicePlayer.speak(text);
                    }
                }
            });
            return;
        }
        android.util.Log.i(TAG, "waypoint speech path=local textLength=" + text.length());
        if (voicePlayer != null) voicePlayer.speak(text);
    }

    private void appendTripPoint(RoutePoint point) {
        if (point == null) return;
        if (!activeTripPath.isEmpty()) {
            RoutePoint previous = activeTripPath.get(activeTripPath.size() - 1);
            Location previousLocation = new Location("trip_path");
            previousLocation.setLatitude(previous.latitude);
            previousLocation.setLongitude(previous.longitude);
            Location currentLocation = new Location("trip_path");
            currentLocation.setLatitude(point.latitude);
            currentLocation.setLongitude(point.longitude);
            if (previousLocation.distanceTo(currentLocation) < 8f) return;
        }
        if (activeTripPath.size() >= 1000) compactTripPath();
        activeTripPath.add(point);
    }

    private int nearestGeometryIndex(RoutePoint point) {
        if (point == null || activeRoute == null || activeRoute.geometry == null) return -1;
        Location target = new Location("trip_route");
        target.setLatitude(point.latitude);
        target.setLongitude(point.longitude);
        int nearest = -1;
        float nearestMeters = Float.MAX_VALUE;
        for (int index = 0; index < activeRoute.geometry.size(); index++) {
            RoutePoint candidate = activeRoute.geometry.get(index);
            Location location = new Location("trip_route");
            location.setLatitude(candidate.latitude);
            location.setLongitude(candidate.longitude);
            float distance = target.distanceTo(location);
            if (distance < nearestMeters) {
                nearestMeters = distance;
                nearest = index;
            }
        }
        return nearest;
    }

    private void compactTripPath() {
        if (activeTripPath.size() < 3) return;
        List<RoutePoint> compacted = new ArrayList<>();
        compacted.add(activeTripPath.get(0));
        for (int index = 2; index < activeTripPath.size() - 1; index += 2) {
            compacted.add(activeTripPath.get(index));
        }
        compacted.add(activeTripPath.get(activeTripPath.size() - 1));
        activeTripPath.clear();
        lastTripGeometryIndex = -1;
        activeTripPath.addAll(compacted);
    }

    private TripRecord buildTripRecord(SavedPlace destination, boolean completed) {
        if (destination == null || activeRoute == null || tripStartedAt == 0L) return null;
        if (activeTripDistanceMeters < MIN_RECORDED_TRIP_DISTANCE_METERS) {
            android.util.Log.i(TAG, "trip report skipped: distance below minimum="
                    + activeTripDistanceMeters + "m");
            return null;
        }
        // Explicit driver stop is a valid trip-end action after the minimum real movement; do not
        // discard a report merely because the trip ended before reaching its planned destination.
        long endedAt = System.currentTimeMillis();
        android.util.Log.i(TAG, "trip report pathPoints=" + activeTripPath.size()
                + " distance=" + activeTripDistanceMeters);
        return new TripRecord(destination.name, activeTripOriginLatitude, activeTripOriginLongitude,
                destination.latitude, destination.longitude, activeRoute.distanceMeters, activeRoute.durationSeconds,
                tripStartedAt, endedAt, activeTripDistanceMeters, activeRoute.providerName,
                activeWaypoints.size(), completed, activeTripPath);
    }

    /** The single NavigationEngine.Listener for the whole service - used both for a fresh
     *  startNavigation() and for a restored session. See the class javadoc for the
     *  local-voice-vs-forward-to-Activity rule this follows for every event. */
    private final NavigationEngine.Listener engineListener = new NavigationEngine.Listener() {
        @Override public void onInstruction(RouteStep step) {
            if (noVoiceCapableCallback()) speakWaypoint(step.instruction);
            for (SessionCallback cb : callbacks) cb.onInstruction(step);
            checkpointSession();
        }

        @Override public void onOffRoute() {
            // Rerouting itself needs network + the Activity's route-provider stack (out of this
            // stage's scope - see class javadoc), so with no Activity bound this only keeps the
            // driver informed locally; the engine keeps guiding against the existing route.
            if (noVoiceCapableCallback()) {
                voicePlayer.announce("alternative_route", "از مسیر خارج شده‌اید. در حال بازیابی مسیر.");
                if (backgroundMonitor != null) backgroundMonitor.requestOffRouteReroute();
            }
            for (SessionCallback cb : callbacks) cb.onOffRoute();
        }

        @Override public void onArrived() {
            SavedPlace destination = activeDestination;
            TripRecord tripReport = buildTripRecord(destination, true);
            if (tripReport != null) tripStore.add(tripReport);
            if (noVoiceCapableCallback() && destination != null) {
                int minutes = tripStartedAt == 0L ? 0 : Math.max(1, (int) ((System.currentTimeMillis() - tripStartedAt) / 60_000L));
                double kilometers = activeTripDistanceMeters / 1000.0;
                voicePlayer.announce("destination_arrived", "به مقصد رسیدید. سفر حدود " + minutes + " دقیقه و "
                        + String.format(java.util.Locale.US, "%.1f", kilometers) + " کیلومتر بود.");
            }
            for (SessionCallback cb : callbacks) cb.onArrived(destination, tripReport);
            sessionStore.clear();
            clearTripState();
            if (locationTracker != null) locationTracker.stop();
            // Let the arrival announcement finish; explicit user stop still interrupts voice.
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }

        @Override public void onWaypointApproaching(RouteStep step, int ordinal) {
            android.util.Log.i(TAG, "waypoint approaching ordinal=" + ordinal);
            if (step != null) {
                fallbackAnnouncedWaypoint = new RoutePoint(step.latitude, step.longitude);
            }
            speakWaypoint("مقصد میانی " + (ordinal + 1) + " نزدیک است.");
            for (SessionCallback cb : callbacks) cb.onWaypointApproaching(step, ordinal);
        }

        @Override public void onWaypointReached(RouteStep step, int ordinal) {
            android.util.Log.i(TAG, "waypoint reached ordinal=" + ordinal);
            String key = waypointKey(step);
            if (handledWaypointKeys.contains(key)) {
                removeActiveWaypoint(step);
                checkpointSession();
                return;
            }
            handledWaypointKeys.add(key);
            removeActiveWaypoint(step);
            completedWaypointCount = Math.max(completedWaypointCount, ordinal + 1);
            fallbackAnnouncedWaypoint = null;
            speakWaypoint("به مقصد میانی " + (ordinal + 1) + " رسیدید. مسیر به مقصد بعدی ادامه دارد.");
            for (SessionCallback cb : callbacks) cb.onWaypointReached(step, ordinal);
            checkpointSession();
        }

        @Override public void onWaypointSkipped(RouteStep step, int ordinal) {
            String key = waypointKey(step);
            if (handledWaypointKeys.contains(key)) {
                removeActiveWaypoint(step);
                checkpointSession();
                return;
            }
            handledWaypointKeys.add(key);
            removeActiveWaypoint(step);
            completedWaypointCount = Math.max(completedWaypointCount, ordinal + 1);
            fallbackAnnouncedWaypoint = null;
            speakWaypoint("مقصد میانی " + (ordinal + 1) + " رد شد؛ مسیریابی به مقصد بعدی ادامه دارد.");
            for (SessionCallback cb : callbacks) cb.onWaypointSkipped(step, ordinal);
            checkpointSession();
        }

        @Override public void onInstructionStage(RouteStep step, NavigationEngine.AnnouncementStage stage, int metersRemaining) {
            if (noVoiceCapableCallback()
                    && (stage == NavigationEngine.AnnouncementStage.INITIAL
                        || stage == NavigationEngine.AnnouncementStage.APPROACHING)) {
                String distance = Math.max(10, Math.round(metersRemaining / 10f) * 10) + " متر";
                String prefix = stage == NavigationEngine.AnnouncementStage.INITIAL
                        ? "در " + distance + "، " : "تا " + distance + " دیگر، ";
                speakWaypoint(prefix + step.instruction);
            }
            for (SessionCallback cb : callbacks) cb.onInstructionStage(step, stage, metersRemaining);
        }
    };

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_VOICE_FROM_NOTIFICATION)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, NavigationForegroundService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("همراه راننده فعال است")
                .setContentText("مسیریابی و راهنمای صوتی در پس‌زمینه ادامه دارد")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "مقصد را بگویید", open).build())
                .addAction(new Notification.Action.Builder(null, "توقف", stop).build());
        if (Build.VERSION.SDK_INT >= 21) builder.setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private void createChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        if (existing == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "مسیریابی فعال", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("اعلان دائمی برای ادامه مسیریابی و راهنمای صوتی در پس‌زمینه");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // The user can stop navigation explicitly from the notification. Removing the launcher
        // task alone must not terminate the active foreground navigation session.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        // A normal service teardown releases resources. If Android is recreating a START_STICKY
        // service after process death, the durable session remains in NavigationSessionStore and
        // onCreate() starts the tracker/engine again; this cleanup therefore cannot lose the trip.
        if (locationTracker != null) locationTracker.stop();
        if (voicePlayer != null) voicePlayer.shutdown();
        if (onlineSpeechClient != null) onlineSpeechClient.stopPlayback();
        super.onDestroy();
    }
}
