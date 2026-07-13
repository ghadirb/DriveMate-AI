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

    public void start(RouteResult route, Listener listener) {
        this.route = route;
        this.listener = listener;
        this.nextStep = 0;
        this.rerouteRequested = false;
    }

    public void stop() { route = null; listener = null; }
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
        if (meters < Math.max(35f, Math.min(120f, target.distanceMeters * 0.18f))) {
            listener.onInstruction(target);
            nextStep = Math.min(nextStep + 1, route.steps.size() - 1);
            rerouteRequested = false;
            return;
        }
        if (meters > 180f && !rerouteRequested) {
            rerouteRequested = true;
            listener.onOffRoute();
        }
    }

    private Location asLocation(RouteStep step) {
        Location location = new Location("route");
        location.setLatitude(step.latitude);
        location.setLongitude(step.longitude);
        return location;
    }
}
