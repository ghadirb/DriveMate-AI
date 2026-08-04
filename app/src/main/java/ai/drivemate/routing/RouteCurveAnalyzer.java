package ai.drivemate.routing;

import android.location.Location;

import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteSafetyAlert;

/**
 * Flags sharp turns directly from the route polyline itself: a bearing change of at least
 * SHARP_TURN_DEGREES between two consecutive road segments, each at least MIN_SEGMENT_METERS
 * long (so GPS/polyline jitter on an almost-straight road never reads as a "curve"). This needs
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
     *  not every ordinary intersection. */
    private static final double SHARP_TURN_DEGREES = 70d;

    public static List<RouteSafetyAlert> sharpCurves(List<RoutePoint> geometry) {
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
            if (delta >= SHARP_TURN_DEGREES) {
                curves.add(new RouteSafetyAlert(RouteSafetyAlert.Type.SHARP_CURVE, current.latitude, current.longitude, delta));
            }
            lastIndex = index;
        }
        return curves;
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
