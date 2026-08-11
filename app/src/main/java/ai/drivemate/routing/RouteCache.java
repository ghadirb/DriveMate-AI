package ai.drivemate.routing;

import ai.drivemate.model.RouteResult;

/** Very small in-memory, same-process cache of the most recently confirmed active-navigation
 *  route. MapActivity is fully destroyed and recreated every time it is reopened (e.g. tapping
 *  the dashboard tab and then the map tab again during an active trip), so without this cache it
 *  always had to ask a live routing provider for the very same route it already had a moment ago
 *  - and previously showed a blank/broken screen (no polyline, no turn banner) if that request
 *  failed, e.g. because the internet connection dropped. Both MainActivity (on every successful
 *  route fetch during a trip) and MapActivity now feed this cache, and MapActivity falls back to
 *  it whenever a fresh fetch fails while navigation is active for the same destination.
 *
 *  This is intentionally temporary/best-effort, not a durable trip-storage layer: it lives only
 *  in process memory, is capped at MAX_AGE_MS, and is only trusted when the requested destination
 *  coordinates still match what was cached. */
public final class RouteCache {
    private static final long MAX_AGE_MS = 30 * 60_000L;
    private static final double COORDINATE_TOLERANCE_DEGREES = 0.0005d; // roughly 50 meters

    private static RouteResult route;
    private static double destinationLatitude = Double.NaN;
    private static double destinationLongitude = Double.NaN;
    private static long storedAt;

    private RouteCache() { }

    public static synchronized void store(RouteResult value, double destinationLat, double destinationLng) {
        if (value == null) return;
        route = value;
        destinationLatitude = destinationLat;
        destinationLongitude = destinationLng;
        storedAt = System.currentTimeMillis();
    }

    /** Returns the cached route only if it is still fresh and matches the requested destination;
     *  null otherwise (including when nothing has ever been cached), so a stale or unrelated
     *  entry can never silently steer a different trip. */
    public static synchronized RouteResult get(double destinationLat, double destinationLng) {
        if (route == null) return null;
        if (System.currentTimeMillis() - storedAt > MAX_AGE_MS) return null;
        // A NaN destination (e.g. a saved place whose coordinates never restored correctly) must
        // never match: Math.abs(NaN - x) is NaN, and NaN > tolerance evaluates to false in Java,
        // so the two out-of-tolerance checks below would silently let ANY previously cached route
        // - for a completely unrelated destination - through as a "match". Reject explicitly first.
        if (Double.isNaN(destinationLat) || Double.isNaN(destinationLng)
                || Double.isNaN(destinationLatitude) || Double.isNaN(destinationLongitude)) return null;
        if (Math.abs(destinationLatitude - destinationLat) > COORDINATE_TOLERANCE_DEGREES
                || Math.abs(destinationLongitude - destinationLng) > COORDINATE_TOLERANCE_DEGREES) return null;
        return route;
    }

    public static synchronized void clear() {
        route = null;
        destinationLatitude = Double.NaN;
        destinationLongitude = Double.NaN;
        storedAt = 0L;
    }
}
