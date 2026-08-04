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
        /** Fired once, when the driver reaches an intermediate stop (see RouteStep.waypointOrdinal)
         *  rather than the final destination. Navigation continues on the same route afterward;
         *  this never stops the engine. Default no-op so existing Listener implementations compile
         *  and behave exactly as before unless they choose to override it. */
        default void onWaypointReached(RouteStep step, int waypointOrdinal) { }
    }

    private RouteResult route;
    private int nextStep;
    private long lastOffRouteCallbackAt;
    private Listener listener;
    private Location targetReference;
    private float targetDistanceAtReference;
    private int offRouteSamples;
    private long lastInstructionAt;
    private boolean currentInstructionAnnounced;
    private static final long MIN_MS_BETWEEN_INSTRUCTIONS = 1800L;
    private static final float MAX_ACCURACY_FOR_ADVANCE_METERS = 60f;
    /** Minimum gap between onOffRoute() callbacks. Time-based rather than a one-shot latch that
     *  only clears on the next maneuver or a fresh start(): a one-shot latch can permanently lock
     *  up if the caller ever declines to act on a callback (e.g. its own reroute throttle), since
     *  nothing would ever clear it again for the rest of the trip. A cooldown always re-arms. */
    private static final long MIN_MS_BETWEEN_OFFROUTE_CALLBACKS = 10_000L;

    public void start(RouteResult route, Listener listener) {
        start(route, listener, null);
    }

    /**
     * Route providers expose maneuver end points, not the route polyline. A maneuver that ends
     * hundreds of meters ahead must never be mistaken for an off-route position at trip start.
     */
    public void start(RouteResult route, Listener listener, Location currentLocation) {
        this.route = route;
        this.listener = listener;
        this.nextStep = 0;
        this.lastOffRouteCallbackAt = 0L;
        this.offRouteSamples = 0;
        this.lastInstructionAt = 0L;
        this.currentInstructionAnnounced = false;
        updateTargetReference(currentLocation);
    }

    public void stop() {
        route = null;
        listener = null;
        targetReference = null;
        offRouteSamples = 0;
        currentInstructionAnnounced = false;
    }
    public boolean isNavigating() { return route != null; }

    /** Current maneuver target, or null when no route is active. Lets a UI show live progress
     *  toward the same step this engine is tracking, without duplicating its step logic. */
    public RouteStep currentStep() {
        if (route == null || route.steps.isEmpty()) return null;
        return route.steps.get(Math.min(nextStep, route.steps.size() - 1));
    }

    public int currentStepIndex() { return nextStep; }

    public void onLocation(Location location) {
        if (route == null || listener == null) return;
        if (route.steps.isEmpty()) return;
        RouteStep target = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
        float meters = location.distanceTo(asLocation(target));
        if (nextStep == route.steps.size() - 1 && meters < 45f) {
            Listener callback = listener;
            stop();
            callback.onArrived();
            return;
        }
        // Announce before reaching the maneuver. A no-map experience needs the next action in
        // advance, while route progression still waits until the maneuver endpoint is reached.
        boolean accuracyOk = !location.hasAccuracy() || location.getAccuracy() <= MAX_ACCURACY_FOR_ADVANCE_METERS;
        boolean cooldownOk = System.currentTimeMillis() - lastInstructionAt >= MIN_MS_BETWEEN_INSTRUCTIONS;
        float announceDistance = Math.max(70f, Math.min(250f, Math.max(100f, target.distanceMeters * 0.55f)));
        if (accuracyOk && cooldownOk && !currentInstructionAnnounced && meters <= announceDistance) {
            announceCurrentInstruction();
        }

        float reachedDistance = Math.max(28f, Math.min(65f, target.distanceMeters * 0.15f));
        if (accuracyOk && meters <= reachedDistance) {
            RouteStep justReached = target;
            nextStep = Math.min(nextStep + 1, route.steps.size() - 1);
            currentInstructionAnnounced = false;
            updateTargetReference(location);
            if (justReached.waypointOrdinal >= 0) listener.onWaypointReached(justReached, justReached.waypointOrdinal);
            RouteStep next = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
            float nextDistance = location.distanceTo(asLocation(next));
            float nextAnnounceDistance = Math.max(90f, Math.min(260f, Math.max(120f, next.distanceMeters * 0.65f)));
            if (nextDistance <= nextAnnounceDistance) announceCurrentInstruction();
            return;
        }
        long now = System.currentTimeMillis();
        if (isReliablyOffRoute(location, meters) && now - lastOffRouteCallbackAt >= MIN_MS_BETWEEN_OFFROUTE_CALLBACKS) {
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
        if (route == null || listener == null || route.steps.isEmpty() || currentInstructionAnnounced
                || !hasActionableCurrentInstruction()) return false;
        currentInstructionAnnounced = true;
        lastInstructionAt = System.currentTimeMillis();
        listener.onInstruction(route.steps.get(Math.min(nextStep, route.steps.size() - 1)));
        return true;
    }

    private boolean isReliablyOffRoute(Location location, float targetDistance) {
        if (nextStep >= route.steps.size() - 1 || targetReference == null) return false;
        if (!location.hasAccuracy() || location.getAccuracy() > 50f) return false;

        // Route geometry is much more reliable than distance to a maneuver endpoint on city streets.
        // Three consecutive samples beyond the corridor are required before rerouting, so a single
        // noisy/jumpy fix (common on narrow streets between tall buildings) cannot trigger a reroute
        // by itself - only a sustained deviation does.
        float routeDistance = distanceToRoute(location);
        float movedFromReference = location.distanceTo(targetReference);
        if (routeDistance > 70f && movedFromReference >= 20f) {
            offRouteSamples++;
            return offRouteSamples >= 3;
        }
        if (routeDistance <= 40f) offRouteSamples = 0;

        // Without a route polyline, a far maneuver end point cannot be used for off-route detection.
        if (targetDistanceAtReference > 160f) return false;
        boolean movingAway = movedFromReference >= 80f
                && targetDistance > Math.max(180f, targetDistanceAtReference + 100f);
        offRouteSamples = movingAway ? offRouteSamples + 1 : 0;
        return offRouteSamples >= 3;
    }

    private float distanceToRoute(Location location) {
        if (route == null || route.geometry == null || route.geometry.isEmpty()) return Float.MAX_VALUE;
        float nearest = Float.MAX_VALUE;
        for (RoutePoint point : route.geometry) {
            Location routePoint = new Location("route");
            routePoint.setLatitude(point.latitude);
            routePoint.setLongitude(point.longitude);
            nearest = Math.min(nearest, location.distanceTo(routePoint));
        }
        return nearest;
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
