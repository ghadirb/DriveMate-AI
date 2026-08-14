from pathlib import Path
import re

path = Path('app/src/main/java/ai/drivemate/MainActivity.java')
s = path.read_text(encoding='utf-8')

# 1) Safety must be deterministic/local and never wait for AI/network.
old = '''    private void playDrivingAnnouncement(DrivingAnnouncement announcement) {
        DrivingIntelligenceCoordinator.Priority priority = announcement.priority;
        String prompt = announcement.prompt;
        String clipName = announcement.clipName;
        String fallback = announcement.fallback;
        long expiresInMs = Math.max(1_000L, announcement.expiresAt - System.currentTimeMillis());
        final long epoch = guidanceEpoch;
        // The route engine immediately speaks the first real maneuver; avoid a second generic
'''
new = '''    private void playDrivingAnnouncement(DrivingAnnouncement announcement) {
        DrivingIntelligenceCoordinator.Priority priority = announcement.priority;
        String prompt = announcement.prompt;
        String clipName = announcement.clipName;
        String fallback = announcement.fallback;
        long expiresInMs = Math.max(1_000L, announcement.expiresAt - System.currentTimeMillis());
        final long epoch = guidanceEpoch;
        // Safety is deterministic and local-first. Never wait for AI/network and never speak an
        // AI copy later: the local clip/TTS is the single authoritative safety announcement.
        if (priority == DrivingIntelligenceCoordinator.Priority.SAFETY) {
            boolean played = playDrivingFallback(clipName, fallback);
            setStatus(played ? "هشدار ایمنی پخش شد." : "هشدار ایمنی آماده شد، ولی صدای دستگاه در دسترس نیست.");
            return;
        }
        // The route engine immediately speaks the first real maneuver; avoid a second generic
'''
if old not in s:
    raise SystemExit('safety anchor not found')
s = s.replace(old, new, 1)

# 2) Direct Smart safety requests also bypass AI entirely.
old = '''    private void requestIntelligence(DrivingIntelligenceCoordinator.Priority priority, String prompt, String fallback,
                                     boolean onlineInEconomy, long expiresInMs) {
        setStatus("در حال آماده کردن پاسخ صوتی...");
'''
new = '''    private void requestIntelligence(DrivingIntelligenceCoordinator.Priority priority, String prompt, String fallback,
                                     boolean onlineInEconomy, long expiresInMs) {
        if (priority == DrivingIntelligenceCoordinator.Priority.SAFETY) {
            boolean played = voicePlayer.announce("danger_ahead", fallback);
            setStatus(played ? "هشدار ایمنی پخش شد." : "هشدار ایمنی آماده شد، ولی صدای دستگاه در دسترس نیست.");
            return;
        }
        setStatus("در حال آماده کردن پاسخ صوتی...");
'''
if old not in s:
    raise SystemExit('requestIntelligence anchor not found')
s = s.replace(old, new, 1)

# 3) Personal routes are user-drawn geometry. Do not send them through an online routing provider;
# build a deterministic offline RouteResult from the saved polyline and keep every saved point as
# an explicit waypoint, with the final point as the destination.
old = '''            ArrayList<RoutePoint> mandatory = new ArrayList<>();
            for (int i = 0; i < route.points.size() - 1; i++) mandatory.add(route.points.get(i));
            startNavigation(destination, mandatory);
            setStatus("مسیریابی مسیر شخصی «" + route.name + "» با نقاط اجباری آغاز شد.");
'''
new = '''            startPersonalRouteNavigation(route, destination);
'''
if old not in s:
    raise SystemExit('personal route anchor not found')
s = s.replace(old, new, 1)

anchor = '''    private void handleSharedIntent(Intent intent) {\n'''
method = r'''    /** Starts a saved user-drawn route without requiring any routing API. The saved points are
     * the actual geometry; the first point is reached from the current GPS fix, intermediate
     * points remain mandatory waypoints, and the last point is the final destination. */
    private void startPersonalRouteNavigation(PersonalRoute personalRoute, SavedPlace destination) {
        if (personalRoute == null || personalRoute.points.size() < 2) {
            setStatus("مسیر شخصی نقطه کافی برای مسیریابی ندارد.");
            return;
        }
        Location origin = locationTracker.getLastLocation();
        if (origin == null) {
            setStatus("برای شروع مسیر شخصی، GPS باید آماده باشد.");
            voicePlayer.announce("gps_lost", "موقعیت مکانی هنوز در دسترس نیست.");
            return;
        }
        ArrayList<RoutePoint> geometry = new ArrayList<>();
        geometry.add(new RoutePoint(origin.getLatitude(), origin.getLongitude()));
        for (RoutePoint point : personalRoute.points) {
            if (point == null || !Double.isFinite(point.latitude) || !Double.isFinite(point.longitude)
                    || (point.latitude == 0d && point.longitude == 0d)) continue;
            if (geometry.isEmpty() || distanceMeters(geometry.get(geometry.size() - 1), point) >= 2d) {
                geometry.add(point);
            }
        }
        if (geometry.size() < 2) {
            setStatus("هندسه مسیر شخصی معتبر نیست.");
            return;
        }
        ArrayList<RouteStep> steps = new ArrayList<>();
        int totalMeters = 0;
        final float averageSpeedMps = 11.1f; // ~40 km/h, used only for an offline ETA estimate.
        for (int i = 1; i < geometry.size(); i++) {
            RoutePoint point = geometry.get(i);
            int segmentMeters = Math.max(1, Math.round((float) distanceMeters(geometry.get(i - 1), point)));
            totalMeters += segmentMeters;
            boolean finalPoint = i == geometry.size() - 1;
            int ordinal = finalPoint ? -1 : i - 1;
            String instruction = finalPoint
                    ? "به مقصد مسیر شخصی برسید."
                    : "به نقطه میانی مسیر شخصی ادامه دهید.";
            steps.add(new RouteStep(point.latitude, point.longitude, instruction, segmentMeters, null, ordinal));
        }
        int durationSeconds = Math.max(1, Math.round(totalMeters / averageSpeedMps));
        RouteResult personalResult = new RouteResult("مسیر شخصی آفلاین", totalMeters, durationSeconds,
                "Saved user-drawn route", steps, geometry);

        pendingNavigationDestination = null;
        pendingNavigationWaypoints = null;
        stopAnyOtherActiveSessionBeforeStartingHere();
        resetGuidance(true);
        ++routeRequestSequence;
        final long requestSequence = routeRequestSequence;
        observingBackgroundSession = false;
        activeSessionOwner = new java.lang.ref.WeakReference<>(MainActivity.this);
        activeDestination = destination;
        activeRoute = personalResult;
        activeWaypoints = new ArrayList<>();
        tripStartedAt = System.currentTimeMillis();
        activeTripDistanceMeters = 0;
        activeTripPath.clear();
        appendTripPath(origin);
        activeTripOriginLatitude = origin.getLatitude();
        activeTripOriginLongitude = origin.getLongitude();
        lastTripLocation = new Location(origin);
        lastAlertMovementLocation = new Location(origin);
        alertMovingUntil = 0L;
        rerouteInFlight = false;
        smartCompanion.start();
        fetchRouteHazards(personalResult);
        fetchRouteSafetyAlerts(personalResult);
        startBackgroundNavigation();
        navigationEngine.start(personalResult, new NavigationEngine.Listener() {
            @Override public void onInstruction(RouteStep step) { runOnUiThread(() -> announceRouteStep(step)); }
            @Override public void onOffRoute() {
                // A saved route is a user-drawn line, not a provider road graph. Do not replace it
                // with an online route when the driver temporarily leaves the line; keep following
                // the chosen personal geometry and let the next GPS fix rejoin it.
                runOnUiThread(() -> setStatus("از مسیر شخصی فاصله گرفته‌اید؛ مسیر ذخیره‌شده حفظ شد."));
            }
            @Override public void onArrived() { runOnUiThread(() -> finishTrip(destination)); }
            @Override public void onWaypointApproaching(RouteStep step, int ordinal) { runOnUiThread(() -> announceWaypointApproaching(step, ordinal)); }
            @Override public void onWaypointReached(RouteStep step, int ordinal) { runOnUiThread(() -> announceWaypointReached(step, ordinal)); }
            @Override public void onWaypointSkipped(RouteStep step, int ordinal) { runOnUiThread(() -> announceWaypointSkipped(step, ordinal)); }
            @Override public void onInstructionStage(RouteStep step, NavigationEngine.AnnouncementStage stage, int metersRemaining) {
                runOnUiThread(() -> announceInstructionStage(step, stage, metersRemaining));
            }
        }, origin, new RoutePoint(destination.latitude, destination.longitude));
        navigationEngine.setInstructionAnnouncementsEnabled(false);
        initialGuidanceHeldUntil = System.currentTimeMillis() + 500L;
        setStatus("مسیریابی مسیر شخصی «" + personalRoute.name + "" آغاز شد.");
        guidanceHandler.postDelayed(() -> {
            if (requestSequence != routeRequestSequence || activeDestination != destination || !navigationEngine.isNavigating()) return;
            navigationEngine.setInstructionAnnouncementsEnabled(true);
            if (!navigationEngine.announceCurrentInstruction()) announceTripStart(personalResult, destination);
        }, 500L);
        showTripAnalysis(personalResult, destination);
        refreshList();
    }

    private double distanceMeters(RoutePoint a, RoutePoint b) {
        if (a == null || b == null) return Double.POSITIVE_INFINITY;
        double lat1 = Math.toRadians(a.latitude), lat2 = Math.toRadians(b.latitude);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.longitude - a.longitude);
        double h = Math.sin(dLat / 2d) * Math.sin(dLat / 2d)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2d) * Math.sin(dLon / 2d);
        return 6371000d * 2d * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0d, 1d - h)));
    }

'''
if anchor not in s:
    raise SystemExit('shared intent anchor not found')
s = s.replace(anchor, method + anchor, 1)

# Fix a malformed quote if this script is ever edited by a formatter; keep generated Java valid.
s = s.replace('setStatus("مسیریابی مسیر شخصی «" + personalRoute.name + "" آغاز شد.");',
              'setStatus("مسیریابی مسیر شخصی «" + personalRoute.name + "» آغاز شد.");')

path.write_text(s, encoding='utf-8')

# 4) The foreground owner must remain strong while the service is active. The existing field is
# already present; clear it only when the real owner stops, not when a mirror Activity is destroyed.
# startBackgroundNavigation already assigns it; stopBackgroundNavigation already clears it.
print('navigation regression repair applied')
