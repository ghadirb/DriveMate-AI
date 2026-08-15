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
import java.util.List;

import ai.drivemate.location.DeviceLocationTracker;
import ai.drivemate.navigation.BackgroundNavigationMonitor;
import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteStep;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.model.TripRecord;
import ai.drivemate.routing.NavigationEngine;
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
    }

    public final class LocalBinder extends Binder {
        public NavigationForegroundService getService() { return NavigationForegroundService.this; }
    }

    private final LocalBinder binder = new LocalBinder();
    private SessionCallback callback;

    private DeviceLocationTracker locationTracker;
    private final NavigationEngine navigationEngine = new NavigationEngine();
    private VoiceGuidancePlayer voicePlayer;
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
    private long lastSessionCheckpointAt;
    private BackgroundNavigationMonitor backgroundMonitor;

    @Override public void onCreate() {
        super.onCreate();
        sessionStore = new NavigationSessionStore(this);
        tripStore = new TripStore(this);
        voicePlayer = new VoiceGuidancePlayer(this);
        backgroundMonitor = new BackgroundNavigationMonitor(this, voicePlayer, this);
        locationTracker = new DeviceLocationTracker(this);
        locationTracker.setUpdateListener(new DeviceLocationTracker.UpdateListener() {
            @Override public void onLocationUpdate(Location location) {
                navigationEngine.onLocation(location);
                recordTripLocation(location);
                SessionCallback current = callback;
                if (current != null) current.onLocationUpdate(location);
            }
            @Override public void onLocationAvailabilityChanged(boolean available) {
                SessionCallback current = callback;
                if (current != null) current.onLocationAvailabilityChanged(available);
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
        locationTracker.start();
        Location current = locationTracker.getLastLocation();
        RoutePoint finalDestination = activeDestination == null ? null
                : new RoutePoint(activeDestination.latitude, activeDestination.longitude);
        navigationEngine.start(session.route, engineListener, current, finalDestination, session.currentStepIndex);
        ensureForeground();
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

    /** Only clears the callback if it is still the same instance that registered it, so an old
     *  Activity's onStop() racing with a new Activity's onStart() can never wipe out the new,
     *  legitimately-bound callback. */
    public void setCallback(SessionCallback callback) { this.callback = callback; }
    public void clearCallback(SessionCallback callback) { if (this.callback == callback) this.callback = null; }

    public NavigationEngine getNavigationEngine() { return navigationEngine; }
    public DeviceLocationTracker getLocationTracker() { return locationTracker; }
    public VoiceGuidancePlayer getVoicePlayer() { return voicePlayer; }
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
        activeMode = mode == null ? "" : mode;
        if (!preserveTripProgress || tripStartedAt == 0L) {
            tripStartedAt = System.currentTimeMillis();
            activeTripDistanceMeters = 0;
            activeTripPath.clear();
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
        int initialStepIndex = preserveTripProgress ? Math.max(0, navigationEngine.currentStepIndex()) : 0;
        navigationEngine.start(route, engineListener, origin, finalDestination, initialStepIndex);
        ensureForeground();
        checkpointSession();
        if (backgroundMonitor != null) backgroundMonitor.start();
    }

    /** Explicit driver-initiated stop (not arrival) - e.g. cancelling a trip from the UI. */
    public TripRecord stopNavigationSession() {
        TripRecord report = buildTripRecord(activeDestination, false);
        if (report != null) tripStore.add(report);
        navigationEngine.stop();
        sessionStore.clear();
        clearTripState();
        if (locationTracker != null) locationTracker.stop();
        if (voicePlayer != null) voicePlayer.interrupt();
        if (backgroundMonitor != null) backgroundMonitor.stop();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        return report;
    }

    /** Completes the active session from the service-owned arrival/Activity callback path. */
    public TripRecord finishNavigationSession() {
        SavedPlace destination = activeDestination;
        TripRecord report = buildTripRecord(destination, true);
        if (report != null) tripStore.add(report);
        navigationEngine.stop();
        sessionStore.clear();
        clearTripState();
        if (locationTracker != null) locationTracker.stop();
        if (voicePlayer != null) voicePlayer.interrupt();
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
        int step = Math.max(0, navigationEngine.currentStepIndex());
        navigationEngine.start(route, engineListener, origin,
                activeDestination == null ? null : new RoutePoint(activeDestination.latitude, activeDestination.longitude), step);
        sessionStore.save(activeRoute, activeDestination, activeWaypoints, activeMode, tripStartedAt,
                activeTripDistanceMeters, navigationEngine.currentStepIndex(), currentWaypointOrdinal(), activeTripPath);
        voicePlayer.announce("background_reroute", "مسیر جایگزین پیدا شد و حدود " + Math.max(1, gainSeconds / 60) + " دقیقه سریع‌تر است.");
    }

    private void clearTripState() {
        activeRoute = null;
        activeDestination = null;
        activeWaypoints = new ArrayList<>();
        tripStartedAt = 0L;
        activeTripDistanceMeters = 0;
        activeTripPath.clear();
        activeTripOriginLatitude = Double.NaN;
        activeTripOriginLongitude = Double.NaN;
        lastTripLocation = null;
    }

    private void checkpointSession() {
        if (activeRoute == null || !navigationEngine.isNavigating()) return;
        sessionStore.save(activeRoute, activeDestination, activeWaypoints, activeMode, tripStartedAt,
                activeTripDistanceMeters, navigationEngine.currentStepIndex(), currentWaypointOrdinal(), activeTripPath);
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
        if (!activeTripPath.isEmpty()) {
            RoutePoint previous = activeTripPath.get(activeTripPath.size() - 1);
            Location previousLocation = new Location("trip_path");
            previousLocation.setLatitude(previous.latitude);
            previousLocation.setLongitude(previous.longitude);
            if (previousLocation.distanceTo(location) < 20f) return;
        }
        if (activeTripPath.size() >= 240) compactTripPath();
        activeTripPath.add(new RoutePoint(location.getLatitude(), location.getLongitude()));
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
        activeTripPath.addAll(compacted);
    }

    private TripRecord buildTripRecord(SavedPlace destination, boolean completed) {
        if (destination == null || activeRoute == null || tripStartedAt == 0L) return null;
        if (!completed && activeTripDistanceMeters < MIN_RECORDED_TRIP_DISTANCE_METERS) return null;
        long endedAt = System.currentTimeMillis();
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
            SessionCallback current = callback;
            if (current != null) current.onInstruction(step);
            else voicePlayer.announce("route_step_custom", step.instruction);
            checkpointSession();
        }

        @Override public void onOffRoute() {
            SessionCallback current = callback;
            // Rerouting itself needs network + the Activity's route-provider stack (out of this
            // stage's scope - see class javadoc), so with no Activity bound this only keeps the
            // driver informed locally; the engine keeps guiding against the existing route.
            if (current != null) current.onOffRoute();
            else voicePlayer.announce("alternative_route", "از مسیر خارج شده‌اید. در حال بازیابی مسیر.");
        }

        @Override public void onArrived() {
            SavedPlace destination = activeDestination;
            TripRecord tripReport = buildTripRecord(destination, true);
            if (tripReport != null) tripStore.add(tripReport);
            SessionCallback current = callback;
            if (current != null) {
                current.onArrived(destination, tripReport);
            } else if (destination != null) {
                int minutes = tripStartedAt == 0L ? 0 : Math.max(1, (int) ((System.currentTimeMillis() - tripStartedAt) / 60_000L));
                double kilometers = activeTripDistanceMeters / 1000.0;
                voicePlayer.announce("destination_arrived", "به مقصد رسیدید. سفر حدود " + minutes + " دقیقه و "
                        + String.format(java.util.Locale.US, "%.1f", kilometers) + " کیلومتر بود.");
            }
            sessionStore.clear();
            clearTripState();
            if (locationTracker != null) locationTracker.stop();
            if (voicePlayer != null) voicePlayer.interrupt();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }

        @Override public void onWaypointApproaching(RouteStep step, int ordinal) {
            SessionCallback current = callback;
            if (current != null) current.onWaypointApproaching(step, ordinal);
            else voicePlayer.announce("continue_route", "توقف میانی " + (ordinal + 1) + " نزدیک است.");
        }

        @Override public void onWaypointReached(RouteStep step, int ordinal) {
            SessionCallback current = callback;
            if (current != null) current.onWaypointReached(step, ordinal);
            else voicePlayer.announce("continue_route", "به توقف میانی " + (ordinal + 1) + " رسیدید. مسیر به مقصد ادامه دارد.");
            checkpointSession();
        }

        @Override public void onWaypointSkipped(RouteStep step, int ordinal) {
            SessionCallback current = callback;
            if (current != null) current.onWaypointSkipped(step, ordinal);
            else voicePlayer.announce("continue_route", "توقف میانی " + (ordinal + 1) + " رد شد؛ مسیریابی به مقصد بعدی ادامه دارد.");
            checkpointSession();
        }

        @Override public void onInstructionStage(RouteStep step, NavigationEngine.AnnouncementStage stage, int metersRemaining) {
            SessionCallback current = callback;
            if (current != null) {
                current.onInstructionStage(step, stage, metersRemaining);
            } else if (stage == NavigationEngine.AnnouncementStage.INITIAL
                    || stage == NavigationEngine.AnnouncementStage.APPROACHING) {
                String distance = Math.max(10, Math.round(metersRemaining / 10f) * 10) + " متر";
                String prefix = stage == NavigationEngine.AnnouncementStage.INITIAL
                        ? "در " + distance + "، " : "تا " + distance + " دیگر، ";
                voicePlayer.speak(prefix + step.instruction);
            }
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
        super.onDestroy();
    }
}
