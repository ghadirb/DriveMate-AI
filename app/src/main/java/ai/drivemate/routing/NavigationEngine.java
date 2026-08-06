package ai.drivemate.routing;

import android.location.Location;

import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteStep;

/** Keeps route progress separate from the activity so GPS updates can be handled consistently. */
public class NavigationEngine {
    public interface Listener {
        void onInstruction(RouteStep step);
        void onOffRoute();
        void onArrived();
        /** Fired once when the next intermediate stop is close enough to announce. This is
         * separate from onWaypointReached(), so a driver can hear about a stop before arriving. */
        default void onWaypointApproaching(RouteStep step, int waypointOrdinal) { }
        /** Fired once, when the driver reaches an intermediate stop (see RouteStep.waypointOrdinal)
         *  rather than the final destination. Navigation continues on the same route afterward;
         *  this never stops the engine. Default no-op so existing Listener implementations compile
         *  and behave exactly as before unless they choose to override it. */
        default void onWaypointReached(RouteStep step, int waypointOrdinal) { }
        /** Fired after several accurate, on-route samples prove the driver is already beyond an
         * intermediate stop. The stop is removed from future reroutes instead of forcing a loop
         * back to a point the driver intentionally bypassed. */
        default void onWaypointSkipped(RouteStep step, int waypointOrdinal) { }
    }

    private RouteResult route;
    private int nextStep;
    private long lastOffRouteCallbackAt;
    private Listener listener;
    /** The actual requested final coordinate. Provider maneuver endpoints can be several streets
     * away from it, so arrival must never depend solely on the final maneuver's coordinate. */
    private RoutePoint finalDestination;
    private Location targetReference;
    private float targetDistanceAtReference;
    private int offRouteSamples;
    private long lastInstructionAt;
    private boolean currentInstructionAnnounced;
    private int announcedWaypointIndex = -1;
    private boolean instructionAnnouncementsEnabled = true;
    private final RouteProgressTracker progressTracker = new RouteProgressTracker();
    private double[] stepProgressMeters = new double[0];
    private static final long MIN_MS_BETWEEN_INSTRUCTIONS = 1800L;
    private static final float MAX_ACCURACY_FOR_ADVANCE_METERS = 60f;
    /** Minimum gap between onOffRoute() callbacks. Time-based rather than a one-shot latch that
     *  only clears on the next maneuver or a fresh start(): a one-shot latch can permanently lock
     *  up if the caller ever declines to act on a callback (e.g. its own reroute throttle), since
     *  nothing would ever clear it again for the rest of the trip. A cooldown always re-arms. */
    private static final long MIN_MS_BETWEEN_OFFROUTE_CALLBACKS = 10_000L;
    /** Consecutive confirming fixes required before trusting a maneuver has actually been reached
     *  (see onLocation). */
    private static final int STEP_ADVANCE_CONFIRM_SAMPLES = 2;
    private int advanceConfirmSamples;
    private int passedStepConfirmSamples;
    private int skippedWaypointConfirmSamples;
    private int finalArrivalConfirmSamples;
    private static final int FINAL_ARRIVAL_CONFIRM_SAMPLES = 2;
    private static final int WAYPOINT_SKIP_CONFIRM_SAMPLES = 3;
    private static final double PASSED_STEP_BUFFER_METERS = 35d;
    private static final double SKIPPED_WAYPOINT_BUFFER_METERS = 180d;
    private static final float GEOGRAPHIC_WAYPOINT_SKIP_ADVANTAGE_METERS = 180f;
    /** Modestly wider than the maneuver-advance radius: this is a one-shot check (no multi-sample
     *  confirmation, since a parked/stopped driver may only ever produce one fix inside it), so it
     *  needs its own buffer against GPS noise rather than sharing the tighter per-maneuver radius. */
    private static final float FINAL_ARRIVAL_RADIUS_METERS = 55f;

    public void start(RouteResult route, Listener listener) {
        start(route, listener, null);
    }

    /**
     * Route providers expose maneuver end points, not the route polyline. A maneuver that ends
     * hundreds of meters ahead must never be mistaken for an off-route position at trip start.
     */
    public void start(RouteResult route, Listener listener, Location currentLocation) {
        start(route, listener, currentLocation, null);
    }

    /** Starts navigation with the exact final destination requested by the user. */
    public void start(RouteResult route, Listener listener, Location currentLocation, RoutePoint finalDestination) {
        start(route, listener, currentLocation, finalDestination, 0);
    }

    /** Restores a shared navigation session at the authoritative step already reached elsewhere. */
    public void start(RouteResult route, Listener listener, Location currentLocation,
                      RoutePoint finalDestination, int initialStepIndex) {
        this.route = route;
        this.listener = listener;
        this.finalDestination = finalDestination;
        this.nextStep = route == null || route.steps.isEmpty() ? 0
                : Math.max(0, Math.min(initialStepIndex, route.steps.size() - 1));
        this.lastOffRouteCallbackAt = 0L;
        this.offRouteSamples = 0;
        this.advanceConfirmSamples = 0;
        this.passedStepConfirmSamples = 0;
        this.skippedWaypointConfirmSamples = 0;
        this.finalArrivalConfirmSamples = 0;
        this.lastInstructionAt = 0L;
        this.currentInstructionAnnounced = false;
        this.announcedWaypointIndex = -1;
        this.instructionAnnouncementsEnabled = true;
        progressTracker.reset(route, currentLocation);
        buildStepProgress();
        updateTargetReference(currentLocation);
    }

    public void stop() {
        route = null;
        listener = null;
        finalDestination = null;
        targetReference = null;
        offRouteSamples = 0;
        advanceConfirmSamples = 0;
        passedStepConfirmSamples = 0;
        skippedWaypointConfirmSamples = 0;
        finalArrivalConfirmSamples = 0;
        currentInstructionAnnounced = false;
        announcedWaypointIndex = -1;
        instructionAnnouncementsEnabled = true;
        stepProgressMeters = new double[0];
        progressTracker.clear();
    }
    public boolean isNavigating() { return route != null; }

    /** Current maneuver target, or null when no route is active. Lets a UI show live progress
     *  toward the same step this engine is tracking, without duplicating its step logic. */
    public RouteStep currentStep() {
        if (route == null || route.steps.isEmpty()) return null;
        return route.steps.get(Math.min(nextStep, route.steps.size() - 1));
    }

    public int currentStepIndex() { return nextStep; }

    public int remainingMeters() {
        RouteProgressTracker.Snapshot progress = progressTracker.current();
        return progress == null ? route == null ? 0 : route.distanceMeters : progress.remainingMeters;
    }

    public RoutePoint snappedRoutePosition() {
        RouteProgressTracker.Snapshot progress = progressTracker.current();
        return progress == null || !progress.onRoute ? null : progress.snappedPoint;
    }

    public float routeHeading() {
        RouteProgressTracker.Snapshot progress = progressTracker.current();
        return progress == null ? 0f : progress.headingDegrees;
    }

    public boolean isPointAhead(double latitude, double longitude, double minimumAheadMeters,
                                double maximumAheadMeters, float maximumLateralMeters) {
        return progressTracker.isPointAhead(latitude, longitude, minimumAheadMeters,
                maximumAheadMeters, maximumLateralMeters);
    }

    public void onLocation(Location location) {
        if (route == null || listener == null) return;
        if (route.steps.isEmpty()) return;
        RouteProgressTracker.Snapshot routeProgress = progressTracker.update(location);
        // Checked independently of nextStep: sequential step advancement (below) requires getting
        // close to each intermediate maneuver point in order, which never happens if the traveled
        // path diverges from the routed streets (a pedestrian shortcut through an alley/park, a
        // driver cutting through a lot). Without this, arrival could never fire in that case - the
        // engine would keep waiting to reach maneuver points on a path that was never taken, even
        // while standing at the actual destination.
        RouteStep destinationStep = route.steps.get(route.steps.size() - 1);
        float metersToDestination = location.distanceTo(finalDestination == null
                ? asLocation(destinationStep) : asLocation(finalDestination));
        if (accuracyOk(location) && metersToDestination < FINAL_ARRIVAL_RADIUS_METERS) {
            finalArrivalConfirmSamples++;
        } else {
            finalArrivalConfirmSamples = 0;
        }
        if (finalArrivalConfirmSamples >= FINAL_ARRIVAL_CONFIRM_SAMPLES) {
            Listener callback = listener;
            stop();
            callback.onArrived();
            return;
        }
        int nextWaypointIndex = nextWaypointIndex();
        if (nextWaypointIndex >= 0) {
            RouteStep waypoint = route.steps.get(nextWaypointIndex);
            float metersToWaypoint = location.distanceTo(asLocation(waypoint));
            if (waypointWasSkipped(location, routeProgress, nextWaypointIndex, metersToWaypoint)) {
                skipWaypoint(nextWaypointIndex, location, waypoint);
                return;
            }
            float waypointAnnounceDistance = Math.max(90f, Math.min(260f,
                    Math.max(120f, waypoint.distanceMeters * 0.65f)));
            if (instructionAnnouncementsEnabled && announcedWaypointIndex != nextWaypointIndex
                    && metersToWaypoint <= waypointAnnounceDistance) {
                announcedWaypointIndex = nextWaypointIndex;
                listener.onWaypointApproaching(waypoint, waypoint.waypointOrdinal);
            }
            // A shortcut can reach a stop without ever touching all of the provider's maneuver
            // points. Advance directly past that stop so navigation continues to the next one.
            if (accuracyOk(location) && metersToWaypoint <= FINAL_ARRIVAL_RADIUS_METERS) {
                advancePastWaypoint(nextWaypointIndex, location, waypoint);
                return;
            }
        } else {
            skippedWaypointConfirmSamples = 0;
        }
        advancePastPassedSteps(location, routeProgress);
        RouteStep target = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
        float meters = location.distanceTo(asLocation(target));
        // Announce before reaching the maneuver. A no-map experience needs the next action in
        // advance, while route progression still waits until the maneuver endpoint is reached.
        boolean accuracyOk = accuracyOk(location);
        boolean cooldownOk = System.currentTimeMillis() - lastInstructionAt >= MIN_MS_BETWEEN_INSTRUCTIONS;
        float announceDistance = Math.max(35f, Math.min(220f, target.distanceMeters * 0.6f));
        if (instructionAnnouncementsEnabled && accuracyOk && cooldownOk && !currentInstructionAnnounced && meters <= announceDistance) {
            announceCurrentInstruction();
        }

        float reachedDistance = Math.max(28f, Math.min(65f, target.distanceMeters * 0.15f));
        if (accuracyOk && meters <= reachedDistance) {
            // A single close fix is not enough to trust: GPS multipath in narrow alleys can put a
            // momentarily-jumped reading within range of a maneuver the driver has not actually
            // reached, advancing past the true next step and announcing its (wrong) direction
            // instead. Two consecutive confirming fixes (roughly a second apart) are required
            // before the advance is trusted.
            advanceConfirmSamples++;
            if (advanceConfirmSamples < STEP_ADVANCE_CONFIRM_SAMPLES) return;
            advanceConfirmSamples = 0;
            RouteStep justReached = target;
            if (justReached.waypointOrdinal >= 0) {
                advancePastWaypoint(nextStep, location, justReached);
                return;
            }
            nextStep = Math.min(nextStep + 1, route.steps.size() - 1);
            currentInstructionAnnounced = false;
            updateTargetReference(location);
            RouteStep next = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
            float nextDistance = location.distanceTo(asLocation(next));
            float nextAnnounceDistance = Math.max(35f, Math.min(220f, next.distanceMeters * 0.6f));
            if (instructionAnnouncementsEnabled && nextDistance <= nextAnnounceDistance) announceCurrentInstruction();
            return;
        }
        advanceConfirmSamples = 0;
        long now = System.currentTimeMillis();
        if (isReliablyOffRoute(location, meters, routeProgress)
                && now - lastOffRouteCallbackAt >= MIN_MS_BETWEEN_OFFROUTE_CALLBACKS) {
            lastOffRouteCallbackAt = now;
            listener.onOffRoute();
        }
    }

    /** True when the current step is a maneuver rather than a destination-only fallback step. */
    public boolean hasActionableCurrentInstruction() {
        if (route == null || route.steps.isEmpty()) return false;
        RouteStep step = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
        if (step.waypointOrdinal >= 0) return false;
        String instruction = step.instruction == null ? "" : step.instruction;
        String lower = instruction.toLowerCase(java.util.Locale.ROOT);
        return !(lower.contains("arriv")
                || instruction.contains("\u0645\u0642\u0635\u062f")
                || instruction.contains("\u0631\u0633\u06cc\u062f"));
    }

    /** Announces the first actionable provider instruction as soon as a route is ready. */
    public boolean announceCurrentInstruction() {
        if (!instructionAnnouncementsEnabled || route == null || listener == null || route.steps.isEmpty() || currentInstructionAnnounced
                || !hasActionableCurrentInstruction()) return false;
        currentInstructionAnnounced = true;
        lastInstructionAt = System.currentTimeMillis();
        listener.onInstruction(route.steps.get(Math.min(nextStep, route.steps.size() - 1)));
        return true;
    }

    /** Prevents the first maneuver from being consumed while the trip-start summary is playing. */
    public void setInstructionAnnouncementsEnabled(boolean enabled) {
        instructionAnnouncementsEnabled = enabled;
    }

    private boolean isReliablyOffRoute(Location location, float targetDistance,
                                       RouteProgressTracker.Snapshot routeProgress) {
        if (nextStep >= route.steps.size() - 1 || targetReference == null) return false;
        if (!location.hasAccuracy() || location.getAccuracy() > 50f) return false;

        // Route geometry is much more reliable than distance to a maneuver endpoint on city streets.
        // The corridor scales with the reported GPS accuracy and requires three fixes, preventing
        // one bad network-location sample from repeatedly re-routing a driver on narrow streets.
        float routeDistance = routeProgress == null ? distanceToRoute(location) : routeProgress.distanceToRouteMeters;
        float movedFromReference = location.distanceTo(targetReference);
        float corridorMeters = Math.max(80f, location.getAccuracy() * 2.5f);
        if (routeDistance > corridorMeters && movedFromReference >= 25f) {
            offRouteSamples++;
            return offRouteSamples >= 3;
        }
        if (routeDistance <= corridorMeters * 0.65f) offRouteSamples = 0;

        // Without a route polyline, a far maneuver end point cannot be used for off-route detection.
        if (targetDistanceAtReference > 160f) return false;
        boolean movingAway = movedFromReference >= 80f
                && targetDistance > Math.max(180f, targetDistanceAtReference + 100f);
        offRouteSamples = movingAway ? offRouteSamples + 1 : 0;
        return offRouteSamples >= 3;
    }

    private boolean accuracyOk(Location location) {
        return !location.hasAccuracy() || location.getAccuracy() <= MAX_ACCURACY_FOR_ADVANCE_METERS;
    }

    private int nextWaypointIndex() {
        if (route == null || route.steps == null) return -1;
        for (int index = Math.max(0, nextStep); index < route.steps.size(); index++) {
            if (route.steps.get(index).waypointOrdinal >= 0) return index;
        }
        return -1;
    }

    private void advancePastWaypoint(int waypointIndex, Location location, RouteStep waypoint) {
        nextStep = Math.min(waypointIndex + 1, route.steps.size() - 1);
        currentInstructionAnnounced = false;
        skippedWaypointConfirmSamples = 0;
        updateTargetReference(location);
        listener.onWaypointReached(waypoint, waypoint.waypointOrdinal);
        RouteStep next = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
        float nextDistance = location.distanceTo(asLocation(next));
        float nextAnnounceDistance = Math.max(90f, Math.min(260f, Math.max(120f, next.distanceMeters * 0.65f)));
        if (instructionAnnouncementsEnabled && nextDistance <= nextAnnounceDistance) announceCurrentInstruction();
    }

    private void skipWaypoint(int waypointIndex, Location location, RouteStep waypoint) {
        nextStep = Math.min(waypointIndex + 1, route.steps.size() - 1);
        currentInstructionAnnounced = false;
        skippedWaypointConfirmSamples = 0;
        announcedWaypointIndex = -1;
        updateTargetReference(location);
        listener.onWaypointSkipped(waypoint, waypoint.waypointOrdinal);
        RouteStep next = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
        float nextDistance = location.distanceTo(asLocation(next));
        float nextAnnounceDistance = Math.max(90f, Math.min(260f,
                Math.max(120f, next.distanceMeters * 0.65f)));
        if (instructionAnnouncementsEnabled && nextDistance <= nextAnnounceDistance) announceCurrentInstruction();
    }

    private boolean waypointWasSkipped(Location location, RouteProgressTracker.Snapshot routeProgress,
                                       int waypointIndex, float metersToWaypoint) {
        if (!accuracyOk(location) || location.hasAccuracy() && location.getAccuracy() > 40f
                || waypointIndex >= stepProgressMeters.length
                || Double.isNaN(stepProgressMeters[waypointIndex])) {
            skippedWaypointConfirmSamples = 0;
            return false;
        }
        boolean passedOnRoute = routeProgress != null && routeProgress.onRoute
                && routeProgress.progressMeters >= stepProgressMeters[waypointIndex] + SKIPPED_WAYPOINT_BUFFER_METERS;
        boolean isCloserToFinalDestination = finalDestination != null
                && location.distanceTo(asLocation(finalDestination))
                + GEOGRAPHIC_WAYPOINT_SKIP_ADVANTAGE_METERS < metersToWaypoint;
        boolean clearlyPast = passedOnRoute || isCloserToFinalDestination;
        boolean didNotReachStop = metersToWaypoint > FINAL_ARRIVAL_RADIUS_METERS * 1.5f;
        if (!clearlyPast || !didNotReachStop) {
            skippedWaypointConfirmSamples = 0;
            return false;
        }
        skippedWaypointConfirmSamples++;
        return skippedWaypointConfirmSamples >= WAYPOINT_SKIP_CONFIRM_SAMPLES;
    }

    private void advancePastPassedSteps(Location location, RouteProgressTracker.Snapshot routeProgress) {
        if (routeProgress == null || !routeProgress.onRoute || !accuracyOk(location)
                || nextStep >= route.steps.size() - 1 || nextStep >= stepProgressMeters.length) {
            passedStepConfirmSamples = 0;
            return;
        }
        RouteStep target = route.steps.get(nextStep);
        if (target.waypointOrdinal >= 0 || Double.isNaN(stepProgressMeters[nextStep])
                || routeProgress.progressMeters < stepProgressMeters[nextStep] + PASSED_STEP_BUFFER_METERS) {
            passedStepConfirmSamples = 0;
            return;
        }
        passedStepConfirmSamples++;
        if (passedStepConfirmSamples < STEP_ADVANCE_CONFIRM_SAMPLES) return;
        passedStepConfirmSamples = 0;
        while (nextStep < route.steps.size() - 1) {
            RouteStep step = route.steps.get(nextStep);
            if (step.waypointOrdinal >= 0 || nextStep >= stepProgressMeters.length
                    || Double.isNaN(stepProgressMeters[nextStep])
                    || routeProgress.progressMeters < stepProgressMeters[nextStep] + PASSED_STEP_BUFFER_METERS) {
                break;
            }
            nextStep++;
        }
        currentInstructionAnnounced = false;
        advanceConfirmSamples = 0;
        updateTargetReference(location);
        if (instructionAnnouncementsEnabled) announceCurrentInstruction();
    }

    private void buildStepProgress() {
        if (route == null || route.steps == null || route.steps.isEmpty()) {
            stepProgressMeters = new double[0];
            return;
        }
        stepProgressMeters = new double[route.steps.size()];
        double minimumProgress = 0d;
        for (int index = 0; index < route.steps.size(); index++) {
            RouteStep step = route.steps.get(index);
            double progress = progressTracker.progressAt(step.latitude, step.longitude, minimumProgress);
            stepProgressMeters[index] = progress;
            if (!Double.isNaN(progress)) minimumProgress = progress;
        }
    }

    private float distanceToRoute(Location location) {
        if (route == null || route.geometry == null || route.geometry.isEmpty()) return Float.MAX_VALUE;
        if (route.geometry.size() == 1) return location.distanceTo(asLocation(route.geometry.get(0)));
        double latitudeRadians = Math.toRadians(location.getLatitude());
        double metersPerLatitude = 111_320d;
        double metersPerLongitude = Math.max(1d, metersPerLatitude * Math.cos(latitudeRadians));
        double nearestSquared = Double.MAX_VALUE;
        for (int i = 1; i < route.geometry.size(); i++) {
            RoutePoint first = route.geometry.get(i - 1);
            RoutePoint second = route.geometry.get(i);
            double ax = (first.longitude - location.getLongitude()) * metersPerLongitude;
            double ay = (first.latitude - location.getLatitude()) * metersPerLatitude;
            double bx = (second.longitude - location.getLongitude()) * metersPerLongitude;
            double by = (second.latitude - location.getLatitude()) * metersPerLatitude;
            double dx = bx - ax;
            double dy = by - ay;
            double lengthSquared = dx * dx + dy * dy;
            double fraction = lengthSquared == 0d ? 0d : Math.max(0d, Math.min(1d, -(ax * dx + ay * dy) / lengthSquared));
            double px = ax + fraction * dx;
            double py = ay + fraction * dy;
            nearestSquared = Math.min(nearestSquared, px * px + py * py);
        }
        return (float) Math.sqrt(nearestSquared);
    }

    private Location asLocation(RoutePoint point) {
        Location location = new Location("route");
        location.setLatitude(point.latitude);
        location.setLongitude(point.longitude);
        return location;
    }

    private void updateTargetReference(Location location) {
        offRouteSamples = 0;
        if (location == null || route == null || route.steps.isEmpty()) {
            targetReference = null;
            targetDistanceAtReference = Float.MAX_VALUE;
            return;
        }
        targetReference = new Location(location);
        RouteStep target = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
        targetDistanceAtReference = location.distanceTo(asLocation(target));
    }

    private Location asLocation(RouteStep step) {
        Location location = new Location("route");
        location.setLatitude(step.latitude);
        location.setLongitude(step.longitude);
        return location;
    }

}
