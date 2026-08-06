package ai.drivemate.routing;

import java.util.List;

import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.TripRecord;

/**
 * Scores fresh routing choices against roads the driver has actually traveled before. It never
 * replaces a user-selected route and it only suggests a familiar route when it has at least two
 * matching completed trips and is not materially slower than the fastest current choice.
 */
public final class PersonalRouteAnalyzer {
    private static final float SAME_ENDPOINT_METERS = 1_200f;
    private static final float FAMILIAR_ROAD_METERS = 115f;
    private static final int MIN_SUPPORTING_TRIPS = 2;

    public static final class Suggestion {
        public final int routeIndex;
        public final int supportingTrips;
        public final int similarityPercent;

        private Suggestion(int routeIndex, int supportingTrips, int similarityPercent) {
            this.routeIndex = routeIndex;
            this.supportingTrips = supportingTrips;
            this.similarityPercent = similarityPercent;
        }
    }

    private PersonalRouteAnalyzer() { }

    public static Suggestion suggest(List<RouteResult> routes, List<TripRecord> trips,
                                     double originLatitude, double originLongitude,
                                     double destinationLatitude, double destinationLongitude) {
        if (routes == null || routes.size() < 2 || trips == null || trips.isEmpty()) return null;
        int fastestSeconds = Integer.MAX_VALUE;
        for (RouteResult route : routes) {
            if (route != null && route.durationSeconds > 0) fastestSeconds = Math.min(fastestSeconds, route.durationSeconds);
        }
        if (fastestSeconds == Integer.MAX_VALUE) return null;

        int bestIndex = -1;
        int bestSupport = 0;
        int bestSimilarity = 0;
        for (int index = 0; index < routes.size(); index++) {
            RouteResult candidate = routes.get(index);
            if (candidate == null || candidate.geometry == null || candidate.geometry.size() < 2) continue;
            // A familiar road is a preference, not a reason to add a meaningful delay.
            if (candidate.durationSeconds > fastestSeconds * 1.15f) continue;
            int support = 0;
            int similarityTotal = 0;
            for (TripRecord trip : trips) {
                if (!trip.completed || trip.traveledPath.size() < 3 || !sameEndpoints(trip,
                        originLatitude, originLongitude, destinationLatitude, destinationLongitude)) continue;
                int similarity = routeSimilarity(candidate.geometry, trip.traveledPath);
                if (similarity >= 55) {
                    support++;
                    similarityTotal += similarity;
                }
            }
            int averageSimilarity = support == 0 ? 0 : Math.round(similarityTotal / (float) support);
            if (support > bestSupport || support == bestSupport && averageSimilarity > bestSimilarity) {
                bestIndex = index;
                bestSupport = support;
                bestSimilarity = averageSimilarity;
            }
        }
        return bestSupport >= MIN_SUPPORTING_TRIPS
                ? new Suggestion(bestIndex, bestSupport, bestSimilarity) : null;
    }

    private static boolean sameEndpoints(TripRecord trip, double originLat, double originLng,
                                         double destinationLat, double destinationLng) {
        return distanceMeters(trip.originLatitude, trip.originLongitude, originLat, originLng) <= SAME_ENDPOINT_METERS
                && distanceMeters(trip.destinationLatitude, trip.destinationLongitude, destinationLat, destinationLng)
                <= SAME_ENDPOINT_METERS;
    }

    /** Samples candidate points and measures whether the historical trace used the same road. */
    private static int routeSimilarity(List<RoutePoint> candidate, List<RoutePoint> history) {
        int samples = Math.min(24, candidate.size());
        int familiar = 0;
        for (int sample = 0; sample < samples; sample++) {
            int index = samples == 1 ? 0 : Math.round(sample * (candidate.size() - 1f) / (samples - 1f));
            RoutePoint point = candidate.get(index);
            if (nearestDistance(point, history) <= FAMILIAR_ROAD_METERS) familiar++;
        }
        return Math.round(familiar * 100f / Math.max(1, samples));
    }

    private static float nearestDistance(RoutePoint point, List<RoutePoint> path) {
        float nearest = Float.MAX_VALUE;
        for (RoutePoint item : path) {
            nearest = Math.min(nearest, distanceMeters(point.latitude, point.longitude, item.latitude, item.longitude));
        }
        return nearest;
    }

    private static float distanceMeters(double firstLat, double firstLng, double secondLat, double secondLng) {
        double latitudeDelta = Math.toRadians(secondLat - firstLat);
        double longitudeDelta = Math.toRadians(secondLng - firstLng);
        double value = Math.sin(latitudeDelta / 2d) * Math.sin(latitudeDelta / 2d)
                + Math.cos(Math.toRadians(firstLat)) * Math.cos(Math.toRadians(secondLat))
                * Math.sin(longitudeDelta / 2d) * Math.sin(longitudeDelta / 2d);
        return (float) (6371000d * 2d * Math.atan2(Math.sqrt(value), Math.sqrt(1d - value)));
    }
}
