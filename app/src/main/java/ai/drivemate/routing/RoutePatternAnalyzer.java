package ai.drivemate.routing;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import ai.drivemate.model.SavedPlace;
import ai.drivemate.model.TripRecord;

/**
 * Learns recurring destinations purely from on-device trip history (TripStore) and proposes a
 * likely destination for "right now" when the same place keeps recurring on the same weekday
 * and around the same time of day. Runs entirely on local data - no network call, no AI model,
 * no data leaves the device. This is deliberately a plain statistical/clustering heuristic, not
 * machine learning, so its behavior stays predictable and explainable to the driver.
 */
public class RoutePatternAnalyzer {

    /** Two destinations are treated as "the same place" when within this radius. */
    private static final double SAME_PLACE_RADIUS_METERS = 250d;
    /** A pattern must repeat at least this many times before it is ever suggested. */
    private static final int MIN_OCCURRENCES = 3;
    /** Window (in minutes) around a historical trip's start time that still counts as a match. */
    private static final int TIME_WINDOW_MINUTES = 45;
    /** Ignore clusters the driver is already standing at (or very close to) right now. */
    private static final double MIN_DISTANCE_FROM_ORIGIN_METERS = 400d;

    public static final class Suggestion {
        public final SavedPlace place;
        public final int totalOccurrences;
        public final int matchingOccurrences;
        public final String reason;

        Suggestion(SavedPlace place, int totalOccurrences, int matchingOccurrences, String reason) {
            this.place = place;
            this.totalOccurrences = totalOccurrences;
            this.matchingOccurrences = matchingOccurrences;
            this.reason = reason;
        }
    }

    private static final class Cluster {
        double latitude;
        double longitude;
        String name;
        final List<TripRecord> trips = new ArrayList<>();
    }

    /**
     * @param trips              full available trip history (as returned by TripStore.recent)
     * @param now                current time in millis
     * @param currentLatitude    current GPS latitude, or NaN if unknown
     * @param currentLongitude   current GPS longitude, or NaN if unknown
     * @return the strongest recurring-destination match for right now, or null if none qualifies
     */
    public Suggestion suggestForNow(List<TripRecord> trips, long now, double currentLatitude, double currentLongitude) {
        if (trips == null || trips.size() < MIN_OCCURRENCES) return null;

        List<Cluster> clusters = clusterByDestination(trips);
        Calendar nowCal = Calendar.getInstance();
        nowCal.setTimeInMillis(now);
        int nowWeekday = nowCal.get(Calendar.DAY_OF_WEEK);
        int nowMinuteOfDay = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE);

        Cluster best = null;
        int bestScore = 0;

        for (Cluster cluster : clusters) {
            if (cluster.trips.size() < MIN_OCCURRENCES) continue;
            boolean hasOrigin = !Double.isNaN(currentLatitude) && !Double.isNaN(currentLongitude);
            if (hasOrigin && distanceMeters(currentLatitude, currentLongitude, cluster.latitude, cluster.longitude)
                    < MIN_DISTANCE_FROM_ORIGIN_METERS) {
                continue; // driver is already there - not a useful suggestion
            }

            int weekdayMatches = 0;
            int timeMatches = 0;
            for (TripRecord trip : cluster.trips) {
                Calendar tripCal = Calendar.getInstance();
                tripCal.setTimeInMillis(trip.startedAt);
                int tripMinuteOfDay = tripCal.get(Calendar.HOUR_OF_DAY) * 60 + tripCal.get(Calendar.MINUTE);
                if (tripCal.get(Calendar.DAY_OF_WEEK) == nowWeekday) weekdayMatches++;
                if (minutesApart(tripMinuteOfDay, nowMinuteOfDay) <= TIME_WINDOW_MINUTES) timeMatches++;
            }

            // Require the pattern to repeat on this weekday AND roughly this time of day - either
            // signal alone (e.g. "always in the morning" or "always on Fridays") is too weak on
            // its own to justify a proactive interruption.
            int score = Math.min(weekdayMatches, timeMatches);
            if (score >= MIN_OCCURRENCES && score > bestScore) {
                best = cluster;
                bestScore = score;
            }
        }

        if (best == null) return null;
        SavedPlace place = new SavedPlace(best.name, "pattern",
                best.latitude, best.longitude, null, now, false);
        String reason = "این مقصد در " + best.trips.size() + " سفر گذشته ثبت شده و معمولاً همین روز هفته و همین بازه ساعتی تکرار می‌شود.";
        return new Suggestion(place, best.trips.size(), bestScore, reason);
    }

    private List<Cluster> clusterByDestination(List<TripRecord> trips) {
        List<Cluster> clusters = new ArrayList<>();
        for (TripRecord trip : trips) {
            Cluster match = null;
            for (Cluster cluster : clusters) {
                if (distanceMeters(trip.destinationLatitude, trip.destinationLongitude,
                        cluster.latitude, cluster.longitude) <= SAME_PLACE_RADIUS_METERS) {
                    match = cluster;
                    break;
                }
            }
            if (match == null) {
                match = new Cluster();
                match.latitude = trip.destinationLatitude;
                match.longitude = trip.destinationLongitude;
                match.name = trip.destinationName;
                clusters.add(match);
            }
            match.trips.add(trip);
        }
        return clusters;
    }

    private int minutesApart(int a, int b) {
        int diff = Math.abs(a - b);
        return Math.min(diff, 1440 - diff);
    }

    private double distanceMeters(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double value = Math.sin(latitudeDelta / 2d) * Math.sin(latitudeDelta / 2d)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(longitudeDelta / 2d) * Math.sin(longitudeDelta / 2d);
        return 6371000d * 2d * Math.atan2(Math.sqrt(value), Math.sqrt(1d - value));
    }
}
