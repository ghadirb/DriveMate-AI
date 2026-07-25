package ai.drivemate.routing;

import android.location.Location;

import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteStep;

/** Keeps route progress separate from the activity so GPS updates can be handled consistently. */
public class NavigationEngine {
    public interface Listener {
        void onInstruction(RouteStep step);
        void onOffRoute();
        void onArrived();
    }

    private RouteResult route;
    private int nextStep;
    private boolean rerouteRequested;
    private Listener listener;
    private Location targetReference;
    private float targetDistanceAtReference;
    private int offRouteSamples;
    private long lastInstructionAt;
    private static final long MIN_MS_BETWEEN_INSTRUCTIONS = 4000L;
    private static final float MAX_ACCURACY_FOR_ADVANCE_METERS = 60f;

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
        this.rerouteRequested = false;
        this.offRouteSamples = 0;
        this.lastInstructionAt = 0L;
        updateTargetReference(currentLocation);
    }

    public void stop() {
        route = null;
        listener = null;
        targetReference = null;
        offRouteSamples = 0;
    }
    public boolean isNavigating() { return route != null; }

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
        // A noisy/jumpy fix (common indoors or right after a cold GPS start) must never be allowed
        // to walk the engine through several maneuver points back to back; require both a
        // trustworthy accuracy reading and a minimum gap since the last spoken instruction.
        boolean accuracyOk = !location.hasAccuracy() || location.getAccuracy() <= MAX_ACCURACY_FOR_ADVANCE_METERS;
        boolean cooldownOk = System.currentTimeMillis() - lastInstructionAt >= MIN_MS_BETWEEN_INSTRUCTIONS;
        if (accuracyOk && cooldownOk && meters < Math.max(35f, Math.min(120f, target.distanceMeters * 0.18f))) {
            lastInstructionAt = System.currentTimeMillis();
            listener.onInstruction(target);
            nextStep = Math.min(nextStep + 1, route.steps.size() - 1);
            rerouteRequested = false;
            updateTargetReference(location);
            return;
        }
        if (isReliablyOffRoute(location, meters) && !rerouteRequested) {
            rerouteRequested = true;
            listener.onOffRoute();
        }
    }

    private boolean isReliablyOffRoute(Location location, float targetDistance) {
        if (nextStep >= route.steps.size() - 1 || targetReference == null) return false;
        if (!location.hasAccuracy() || location.getAccuracy() > 50f) return false;

        // Without a route polyline, a far maneuver end point cannot be used for off-route detection.
        if (targetDistanceAtReference > 160f) return false;
        float movedFromReference = location.distanceTo(targetReference);
        boolean movingAway = movedFromReference >= 80f
                && targetDistance > Math.max(180f, targetDistanceAtReference + 100f);
        offRouteSamples = movingAway ? offRouteSamples + 1 : 0;
        return offRouteSamples >= 3;
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
