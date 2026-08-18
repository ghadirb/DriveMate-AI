package ai.drivemate.routing;

import android.location.Location;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteStep;

/**
 * Tracks monotonic progress on ordered route geometry.
 *
 * Nearest-point-only map matching can jump to an earlier parallel segment or to the return side of
 * a loop. This tracker constrains matching around the previously accepted progress and never lets
 * normal GPS jitter move route state backwards.
 */
public final class RouteProgressTracker {
    public static final class Snapshot {
        public final RoutePoint snappedPoint;
        public final double progressMeters;
        public final int remainingMeters;
        public final float distanceToRouteMeters;
        public final float headingDegrees;
        public final int segmentIndex;
        public final boolean onRoute;

        private Snapshot(RoutePoint snappedPoint, double progressMeters, int remainingMeters,
                         float distanceToRouteMeters, float headingDegrees, int segmentIndex,
                         boolean onRoute) {
            this.snappedPoint = snappedPoint;
            this.progressMeters = progressMeters;
            this.remainingMeters = remainingMeters;
            this.distanceToRouteMeters = distanceToRouteMeters;
            this.headingDegrees = headingDegrees;
            this.segmentIndex = segmentIndex;
            this.onRoute = onRoute;
        }
    }

    private RouteResult route;
    private List<RoutePoint> geometry = Collections.emptyList();
    private double[] cumulativeMeters = new double[0];
    private double totalGeometryMeters;
    private Snapshot snapshot;
    private long lastOnRouteRealtimeMs;
    private final ArrayDeque<Location> recentLocations = new ArrayDeque<>();
    private static final int LOCATION_HISTORY_SIZE = 6;
    private static final float MIN_HEADING_DISTANCE_METERS = 6f;

    public synchronized void reset(RouteResult route, Location initialLocation) {
        this.route = route;
        this.geometry = buildGeometry(route, initialLocation);
        this.cumulativeMeters = cumulativeDistances(geometry);
        this.totalGeometryMeters = cumulativeMeters.length == 0
                ? 0d : cumulativeMeters[cumulativeMeters.length - 1];
        this.snapshot = null;
        this.lastOnRouteRealtimeMs = 0L;
        this.recentLocations.clear();
        if (initialLocation != null && geometry.size() >= 2) update(initialLocation);
    }

    public synchronized void clear() {
        route = null;
        geometry = Collections.emptyList();
        cumulativeMeters = new double[0];
        totalGeometryMeters = 0d;
        snapshot = null;
        lastOnRouteRealtimeMs = 0L;
        recentLocations.clear();
    }

    public synchronized Snapshot update(Location location) {
        if (location == null || geometry.size() < 2) return snapshot;
        long nowRealtimeMs = SystemClock.elapsedRealtime();
        Projection projection = bestContinuousProjection(location, nowRealtimeMs);
        if (projection == null) return snapshot;

        float accuracyMeters = location.hasAccuracy() ? location.getAccuracy() : 35f;
        float routeCorridorMeters = Math.max(45f, Math.min(130f,
                accuracyMeters * 1.8f + Math.min(24f, motionSpeedMps(location) * 1.5f)));
        boolean onRoute = projection.distanceMeters <= routeCorridorMeters;
        double progressMeters = snapshot == null ? projection.progressMeters : snapshot.progressMeters;
        int segmentIndex = snapshot == null ? projection.segmentIndex : snapshot.segmentIndex;
        RoutePoint snappedPoint = projection.point;

        if (onRoute) {
            if (snapshot != null && projection.progressMeters < snapshot.progressMeters - 18d) {
                progressMeters = snapshot.progressMeters;
                snappedPoint = pointAt(progressMeters);
                segmentIndex = segmentIndexAt(progressMeters);
            } else {
                progressMeters = snapshot == null
                        ? projection.progressMeters : Math.max(snapshot.progressMeters, projection.progressMeters);
                snappedPoint = pointAt(progressMeters);
                segmentIndex = segmentIndexAt(progressMeters);
            }
            lastOnRouteRealtimeMs = nowRealtimeMs;
        }

        double fractionRemaining = totalGeometryMeters <= 1d
                ? 1d : Math.max(0d, Math.min(1d, (totalGeometryMeters - progressMeters) / totalGeometryMeters));
        int remainingMeters = route == null
                ? (int) Math.round(Math.max(0d, totalGeometryMeters - progressMeters))
                : (int) Math.round(Math.max(0d, route.distanceMeters * fractionRemaining));
        float heading = headingAt(progressMeters, location);
        snapshot = new Snapshot(snappedPoint, progressMeters, remainingMeters,
                projection.distanceMeters, heading, segmentIndex, onRoute);
        rememberLocation(location);
        return snapshot;
    }

    public synchronized Snapshot current() {
        return snapshot;
    }

    public synchronized double progressAt(double latitude, double longitude, double minimumProgressMeters) {
        if (geometry.size() < 2) return Double.NaN;
        Location point = new Location("route_query");
        point.setLatitude(latitude);
        point.setLongitude(longitude);
        Projection projection = projectBetween(point, Math.max(0d, minimumProgressMeters),
                totalGeometryMeters, false);
        return projection == null ? Double.NaN : projection.progressMeters;
    }

    public synchronized boolean isPointAhead(double latitude, double longitude, double minimumAheadMeters,
                                             double maximumAheadMeters, float maximumLateralMeters) {
        if (snapshot == null || geometry.size() < 2) return false;
        Location point = new Location("route_query");
        point.setLatitude(latitude);
        point.setLongitude(longitude);
        double searchStart = Math.max(0d, snapshot.progressMeters + minimumAheadMeters - 80d);
        double searchEnd = Math.min(totalGeometryMeters, snapshot.progressMeters + maximumAheadMeters + 120d);
        Projection projection = projectBetween(point, searchStart, searchEnd, false);
        if (projection == null || projection.distanceMeters > maximumLateralMeters) return false;
        double aheadMeters = projection.progressMeters - snapshot.progressMeters;
        return aheadMeters >= minimumAheadMeters && aheadMeters <= maximumAheadMeters;
    }

    private Projection bestContinuousProjection(Location location, long nowRealtimeMs) {
        if (snapshot == null) return projectBetween(location, 0d, totalGeometryMeters, false);

        long elapsedOffRouteMs = lastOnRouteRealtimeMs == 0L
                ? 1_000L : Math.max(1_000L, nowRealtimeMs - lastOnRouteRealtimeMs);
        float expectedSpeedMps = Math.max(18f, Math.min(60f, motionSpeedMps(location) + 22f));
        double maximumForwardMeters = Math.max(250d,
                elapsedOffRouteMs / 1000d * expectedSpeedMps + 120d);
        double startMeters = Math.max(0d, snapshot.progressMeters - 45d);
        double endMeters = Math.min(totalGeometryMeters, snapshot.progressMeters + maximumForwardMeters);
        Projection local = projectBetween(location, startMeters, endMeters, false);
        if (local == null) return projectBetween(location, 0d, totalGeometryMeters, false);

        float accuracyMeters = location.hasAccuracy() ? location.getAccuracy() : 35f;
        if (local.distanceMeters <= Math.max(90f, accuracyMeters * 2.5f)) return local;

        Projection global = projectBetween(location, 0d, totalGeometryMeters, false);
        if (global != null && global.distanceMeters + 15f < local.distanceMeters
                && global.progressMeters >= startMeters && global.progressMeters <= endMeters) {
            return global;
        }
        return local;
    }

    private Projection projectBetween(Location location, double startMeters, double endMeters,
                                      boolean preferLaterOnTie) {
        Projection best = null;
        float movementHeading = movementHeading(location);
        float headingPenaltyMeters = headingPenaltyMeters(location, movementHeading);
        double latitudeRadians = Math.toRadians(location.getLatitude());
        double metersPerLatitude = 111_320d;
        double metersPerLongitude = Math.max(1d, metersPerLatitude * Math.cos(latitudeRadians));
        for (int index = 1; index < geometry.size(); index++) {
            double segmentStart = cumulativeMeters[index - 1];
            double segmentEnd = cumulativeMeters[index];
            if (segmentEnd < startMeters || segmentStart > endMeters) continue;

            RoutePoint first = geometry.get(index - 1);
            RoutePoint second = geometry.get(index);
            double ax = (first.longitude - location.getLongitude()) * metersPerLongitude;
            double ay = (first.latitude - location.getLatitude()) * metersPerLatitude;
            double bx = (second.longitude - location.getLongitude()) * metersPerLongitude;
            double by = (second.latitude - location.getLatitude()) * metersPerLatitude;
            double dx = bx - ax;
            double dy = by - ay;
            double lengthSquared = dx * dx + dy * dy;
            double fraction = lengthSquared <= 0d ? 0d
                    : Math.max(0d, Math.min(1d, -(ax * dx + ay * dy) / lengthSquared));
            double projectedX = ax + fraction * dx;
            double projectedY = ay + fraction * dy;
            float distanceMeters = (float) Math.sqrt(projectedX * projectedX + projectedY * projectedY);
            double progressMeters = segmentStart + (segmentEnd - segmentStart) * fraction;
            if (progressMeters < startMeters || progressMeters > endMeters) continue;
            float scoreMeters = distanceMeters;
            if (!Float.isNaN(movementHeading) && headingPenaltyMeters > 0f) {
                float segmentHeading = asLocation(first).bearingTo(asLocation(second));
                scoreMeters += shortestAngleDifference(movementHeading, segmentHeading)
                        / 180f * headingPenaltyMeters;
            }
            boolean better = best == null || scoreMeters < best.scoreMeters - 0.5f
                    || (preferLaterOnTie && Math.abs(scoreMeters - best.scoreMeters) <= 0.5f
                    && progressMeters > best.progressMeters);
            if (!better) continue;
            double latitude = first.latitude + (second.latitude - first.latitude) * fraction;
            double longitude = first.longitude + (second.longitude - first.longitude) * fraction;
            best = new Projection(new RoutePoint(latitude, longitude), progressMeters,
                    distanceMeters, scoreMeters, index - 1);
        }
        return best;
    }

    private RoutePoint pointAt(double progressMeters) {
        if (geometry.isEmpty()) return null;
        if (geometry.size() == 1 || progressMeters <= 0d) return geometry.get(0);
        if (progressMeters >= totalGeometryMeters) return geometry.get(geometry.size() - 1);
        int segmentIndex = segmentIndexAt(progressMeters);
        double start = cumulativeMeters[segmentIndex];
        double end = cumulativeMeters[segmentIndex + 1];
        double fraction = end <= start ? 0d : (progressMeters - start) / (end - start);
        RoutePoint first = geometry.get(segmentIndex);
        RoutePoint second = geometry.get(segmentIndex + 1);
        return new RoutePoint(first.latitude + (second.latitude - first.latitude) * fraction,
                first.longitude + (second.longitude - first.longitude) * fraction);
    }

    private int segmentIndexAt(double progressMeters) {
        if (geometry.size() < 2) return 0;
        for (int index = 1; index < cumulativeMeters.length; index++) {
            if (cumulativeMeters[index] >= progressMeters) return index - 1;
        }
        return geometry.size() - 2;
    }

    private float headingAt(double progressMeters, Location fallback) {
        RoutePoint fromPoint = pointAt(progressMeters);
        RoutePoint toPoint = pointAt(Math.min(totalGeometryMeters, progressMeters + 35d));
        if (fromPoint != null && toPoint != null
                && (fromPoint.latitude != toPoint.latitude || fromPoint.longitude != toPoint.longitude)) {
            Location from = asLocation(fromPoint);
            return from.bearingTo(asLocation(toPoint));
        }
        return fallback.hasBearing() ? fallback.getBearing() : 0f;
    }

    private static List<RoutePoint> buildGeometry(RouteResult route, Location initialLocation) {
        if (route == null) return Collections.emptyList();
        if (route.geometry != null && route.geometry.size() >= 2) return new ArrayList<>(route.geometry);
        ArrayList<RoutePoint> fallback = new ArrayList<>();
        if (initialLocation != null) {
            fallback.add(new RoutePoint(initialLocation.getLatitude(), initialLocation.getLongitude()));
        }
        if (route.steps != null) {
            for (RouteStep step : route.steps) fallback.add(new RoutePoint(step.latitude, step.longitude));
        }
        return fallback;
    }

    private static double[] cumulativeDistances(List<RoutePoint> points) {
        double[] cumulative = new double[points.size()];
        for (int index = 1; index < points.size(); index++) {
            cumulative[index] = cumulative[index - 1]
                    + asLocation(points.get(index - 1)).distanceTo(asLocation(points.get(index)));
        }
        return cumulative;
    }

    private static Location asLocation(RoutePoint point) {
        Location location = new Location("route");
        location.setLatitude(point.latitude);
        location.setLongitude(point.longitude);
        return location;
    }

    private float movementHeading(Location location) {
        if (location.hasBearing() && location.hasSpeed() && location.getSpeed() >= 1.0f) {
            return location.getBearing();
        }
        Location oldest = recentLocations.peekFirst();
        if (oldest == null || oldest.distanceTo(location) < MIN_HEADING_DISTANCE_METERS) {
            return Float.NaN;
        }
        return oldest.bearingTo(location);
    }

    private float motionSpeedMps(Location location) {
        if (location.hasSpeed()) return Math.max(0f, location.getSpeed());
        Location oldest = recentLocations.peekFirst();
        if (oldest == null) return 0f;
        long elapsedMs = location.getElapsedRealtimeNanos() > 0L && oldest.getElapsedRealtimeNanos() > 0L
                ? (location.getElapsedRealtimeNanos() - oldest.getElapsedRealtimeNanos()) / 1_000_000L
                : location.getTime() - oldest.getTime();
        if (elapsedMs <= 0L) return 0f;
        return oldest.distanceTo(location) / Math.max(0.5f, elapsedMs / 1000f);
    }

    private float headingPenaltyMeters(Location location, float movementHeading) {
        if (Float.isNaN(movementHeading)) return 0f;
        float speedMps = motionSpeedMps(location);
        if (speedMps < 1.0f) return 0f;
        return Math.min(38f, 12f + speedMps * 2.2f);
    }

    private void rememberLocation(Location location) {
        recentLocations.addLast(new Location(location));
        while (recentLocations.size() > LOCATION_HISTORY_SIZE) recentLocations.removeFirst();
    }

    private static float shortestAngleDifference(float first, float second) {
        float difference = Math.abs((first - second) % 360f);
        return difference > 180f ? 360f - difference : difference;
    }

    private static final class Projection {
        final RoutePoint point;
        final double progressMeters;
        final float distanceMeters;
        final float scoreMeters;
        final int segmentIndex;

        Projection(RoutePoint point, double progressMeters, float distanceMeters,
                   float scoreMeters, int segmentIndex) {
            this.point = point;
            this.progressMeters = progressMeters;
            this.distanceMeters = distanceMeters;
            this.scoreMeters = scoreMeters;
            this.segmentIndex = segmentIndex;
        }
    }
}
