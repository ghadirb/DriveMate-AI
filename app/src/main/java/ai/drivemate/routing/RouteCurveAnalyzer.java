package ai.drivemate.routing;

import android.location.Location;

import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteSafetyAlert;
import ai.drivemate.model.RouteStep;

/**
 * Flags sharp turns directly from the route polyline itself: a bearing change of at least
 * SHARP_TURN_DEGREES between two consecutive road segments, each at least MIN_SEGMENT_METERS
 * long (so GPS/polyline jitter on an almost-straight road never reads as a "curve"), and not
 * coinciding with an already-announced turn maneuver (see MANEUVER_EXCLUSION_METERS). This needs
 * no network call and no elevation/curve-radius feed from any provider - it is a geometric proxy
 * for "پیچ خطرناک", not an official curve-advisory-speed database, and purely local computation
 * keeps it free to run for every route.
 */
public final class RouteCurveAnalyzer {
    private RouteCurveAnalyzer() { }

    private static final double MIN_SEGMENT_METERS = 25d;
    /** A plain city-street corner or T-junction routinely bends 60-90 degrees and is already
     *  announced as a normal turn instruction by the route step itself; this analyzer exists to
     *  flag something extra - a genuinely sharp, sustained curve worth a separate safety alert -
     *  not every ordinary intersection. The threshold used to sit at 70 degrees, inside that
     *  normal-turn range, so an everyday 80-90 degree corner routinely fired the same "sharp curve"
     *  alert as a real hairpin. Raised above the ordinary-turn range so only bends sharper than a
     *  standard right-angle intersection - real curves, not corners - are flagged. */
    private static final double SHARP_TURN_DEGREES = 100d;
    /** A bend within this distance of an actual route-step maneuver point is treated as that
     *  maneuver's own turn (already covered by ordinary "turn left/right" guidance), not a
     *  separate, additional hazard - without this, essentially every turn in the route also fired
     *  a redundant "dangerous curve" alert at the same intersection. */
    private static final float MANEUVER_EXCLUSION_METERS = 40f;

    public static List<RouteSafetyAlert> sharpCurves(List<RoutePoint> geometry, List<RouteStep> steps) {
        ArrayList<RouteSafetyAlert> curves = new ArrayList<>();
        if (geometry == null || geometry.size() < 3) return curves;
        int lastIndex = 0;
        for (int index = 1; index < geometry.size() - 1; index++) {
            RoutePoint previous = geometry.get(lastIndex);
            RoutePoint current = geometry.get(index);
            RoutePoint next = geometry.get(index + 1);
            Location a = toLocation(previous);
            Location b = toLocation(current);
            Location c = toLocation(next);
            float segmentMeters = a.distanceTo(b);
            float outgoingMeters = b.distanceTo(c);
            if (segmentMeters < MIN_SEGMENT_METERS || outgoingMeters < MIN_SEGMENT_METERS) continue;
            float bearingIn = a.bearingTo(b);
            float bearingOut = b.bearingTo(c);
            double delta = Math.abs(angleDifference(bearingIn, bearingOut));
            if (delta >= SHARP_TURN_DEGREES && !nearAnyManeuver(b, steps)) {
                curves.add(new RouteSafetyAlert(RouteSafetyAlert.Type.SHARP_CURVE, current.latitude, current.longitude, delta));
            }
            lastIndex = index;
        }
        return curves;
    }

    private static boolean nearAnyManeuver(Location bendLocation, List<RouteStep> steps) {
        if (steps == null) return false;
        for (RouteStep step : steps) {
            Location maneuver = new Location("maneuver");
            maneuver.setLatitude(step.latitude);
            maneuver.setLongitude(step.longitude);
            if (bendLocation.distanceTo(maneuver) <= MANEUVER_EXCLUSION_METERS) return true;
        }
        return false;
    }

    private static double angleDifference(float from, float to) {
        double diff = to - from;
        while (diff > 180d) diff -= 360d;
        while (diff < -180d) diff += 360d;
        return diff;
    }

    private static Location toLocation(RoutePoint point) {
        Location location = new Location("route");
        location.setLatitude(point.latitude);
        location.setLongitude(point.longitude);
        return location;
    }
}
