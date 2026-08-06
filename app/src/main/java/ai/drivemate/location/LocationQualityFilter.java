package ai.drivemate.location;

import android.location.Location;
import android.os.SystemClock;

/**
 * Shared navigation-grade location filter for every UI surface.
 *
 * A single implausible fix is never accepted as a recovery. When the device genuinely relocates
 * after a stale/bad reference fix, several mutually-consistent samples must form a new cluster
 * before the reference point moves. This prevents GPS multipath jumps from moving route progress,
 * the map marker, arrival detection, or proximity warnings.
 */
public final class LocationQualityFilter {
    private static final float MAX_ACCURACY_METERS = 75f;
    private static final float MAX_PLAUSIBLE_SPEED_MPS = 55f;
    private static final int RELOCATION_CONFIRM_SAMPLES = 3;
    private static final long RELOCATION_MIN_SPAN_MS = 1_500L;
    private static final long MAX_SEED_AGE_MS = 2 * 60_000L;

    private Location acceptedLocation;
    private long acceptedAtRealtimeMs;
    private Location relocationCandidate;
    private int relocationSamples;
    private long relocationStartedAtRealtimeMs;
    private boolean hasStableBearing;
    private float stableBearing;

    public synchronized void reset() {
        acceptedLocation = null;
        acceptedAtRealtimeMs = 0L;
        clearRelocationCandidate();
        hasStableBearing = false;
        stableBearing = 0f;
    }

    public synchronized void seed(Location location) {
        if (!basicQualityOk(location)) return;
        long ageMs = location.getTime() <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - location.getTime());
        if (ageMs > MAX_SEED_AGE_MS) return;
        acceptedLocation = new Location(location);
        acceptedAtRealtimeMs = SystemClock.elapsedRealtime();
        if (location.hasBearing()) {
            stableBearing = normalizeBearing(location.getBearing());
            hasStableBearing = true;
        }
    }

    public synchronized Location filter(Location candidate) {
        if (!basicQualityOk(candidate)) return null;
        long nowRealtimeMs = SystemClock.elapsedRealtime();
        if (acceptedLocation == null) return accept(candidate, nowRealtimeMs);

        long timestampDeltaMs = timestampDeltaMs(candidate, acceptedLocation);
        if (timestampDeltaMs < -2_000L) return null;
        long elapsedMs = elapsedSinceAcceptedMs(candidate, timestampDeltaMs, nowRealtimeMs);

        float candidateAccuracy = accuracyOr(candidate, MAX_ACCURACY_METERS);
        float acceptedAccuracy = accuracyOr(acceptedLocation, MAX_ACCURACY_METERS);
        if (isFreshGpsFix(acceptedLocation, elapsedMs)
                && LocationManagerNames.NETWORK.equals(candidate.getProvider())
                && candidateAccuracy > acceptedAccuracy + 15f) {
            return null;
        }
        if (elapsedMs < 12_000L && candidateAccuracy > acceptedAccuracy + 30f) return null;

        float distanceMeters = acceptedLocation.distanceTo(candidate);
        float uncertaintyMeters = Math.min(45f, Math.max(candidateAccuracy, acceptedAccuracy));
        float plausibleMeters = Math.max(45f,
                elapsedMs / 1000f * MAX_PLAUSIBLE_SPEED_MPS + uncertaintyMeters + 12f);
        if (distanceMeters <= plausibleMeters) {
            clearRelocationCandidate();
            return accept(candidate, nowRealtimeMs);
        }

        return considerRelocation(candidate, nowRealtimeMs);
    }

    public synchronized Location getLastAcceptedLocation() {
        return acceptedLocation == null ? null : new Location(acceptedLocation);
    }

    private Location considerRelocation(Location candidate, long nowRealtimeMs) {
        if (relocationCandidate == null || !sameRelocationCluster(relocationCandidate, candidate)) {
            relocationCandidate = new Location(candidate);
            relocationSamples = 1;
            relocationStartedAtRealtimeMs = nowRealtimeMs;
            return null;
        }

        relocationSamples++;
        if (accuracyOr(candidate, MAX_ACCURACY_METERS)
                <= accuracyOr(relocationCandidate, MAX_ACCURACY_METERS)) {
            relocationCandidate = new Location(candidate);
        }
        if (relocationSamples < RELOCATION_CONFIRM_SAMPLES
                || nowRealtimeMs - relocationStartedAtRealtimeMs < RELOCATION_MIN_SPAN_MS) {
            return null;
        }

        Location confirmed = new Location(relocationCandidate);
        clearRelocationCandidate();
        return accept(confirmed, nowRealtimeMs);
    }

    private boolean sameRelocationCluster(Location first, Location second) {
        float radiusMeters = Math.max(35f,
                Math.min(90f, Math.max(accuracyOr(first, 40f), accuracyOr(second, 40f)) + 20f));
        return first.distanceTo(second) <= radiusMeters;
    }

    private Location accept(Location candidate, long nowRealtimeMs) {
        Location previous = acceptedLocation;
        Location accepted = new Location(candidate);
        updateStableBearing(previous, accepted);
        acceptedLocation = new Location(accepted);
        acceptedAtRealtimeMs = nowRealtimeMs;
        return accepted;
    }

    private void updateStableBearing(Location previous, Location accepted) {
        float movedMeters = previous == null ? 0f : previous.distanceTo(accepted);
        boolean movingBearing = accepted.hasBearing()
                && ((accepted.hasSpeed() && accepted.getSpeed() >= 1.2f) || movedMeters >= 5f);
        boolean derivedBearing = previous != null && movedMeters >= 7f;
        if (!movingBearing && !derivedBearing) {
            if (hasStableBearing) accepted.setBearing(stableBearing);
            return;
        }

        float observed = movingBearing ? accepted.getBearing() : previous.bearingTo(accepted);
        observed = normalizeBearing(observed);
        if (!hasStableBearing) {
            stableBearing = observed;
            hasStableBearing = true;
        } else {
            float difference = shortestAngle(stableBearing, observed);
            if (Math.abs(difference) <= 120f || movedMeters >= 20f) {
                float weight = accuracyOr(accepted, 50f) <= 20f ? 0.45f : 0.28f;
                stableBearing = normalizeBearing(stableBearing + difference * weight);
            }
        }
        accepted.setBearing(stableBearing);
    }

    private long elapsedSinceAcceptedMs(Location candidate, long timestampDeltaMs, long nowRealtimeMs) {
        if (candidate.getElapsedRealtimeNanos() > 0L && acceptedLocation.getElapsedRealtimeNanos() > 0L) {
            long elapsedNanos = candidate.getElapsedRealtimeNanos() - acceptedLocation.getElapsedRealtimeNanos();
            if (elapsedNanos > 0L) return Math.max(1L, elapsedNanos / 1_000_000L);
        }
        if (timestampDeltaMs > 0L) return timestampDeltaMs;
        return Math.max(1L, nowRealtimeMs - acceptedAtRealtimeMs);
    }

    private static long timestampDeltaMs(Location candidate, Location accepted) {
        if (candidate.getTime() <= 0L || accepted.getTime() <= 0L) return 0L;
        return candidate.getTime() - accepted.getTime();
    }

    private static boolean basicQualityOk(Location location) {
        if (location == null) return false;
        if (Double.isNaN(location.getLatitude()) || Double.isNaN(location.getLongitude())) return false;
        return !location.hasAccuracy() || location.getAccuracy() <= MAX_ACCURACY_METERS;
    }

    private static boolean isFreshGpsFix(Location location, long elapsedMs) {
        return LocationManagerNames.GPS.equals(location.getProvider()) && elapsedMs < 10_000L;
    }

    private static float accuracyOr(Location location, float fallback) {
        return location != null && location.hasAccuracy() ? location.getAccuracy() : fallback;
    }

    private static float shortestAngle(float from, float to) {
        float difference = normalizeBearing(to) - normalizeBearing(from);
        while (difference > 180f) difference -= 360f;
        while (difference < -180f) difference += 360f;
        return difference;
    }

    private static float normalizeBearing(float bearing) {
        float normalized = bearing % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }

    private void clearRelocationCandidate() {
        relocationCandidate = null;
        relocationSamples = 0;
        relocationStartedAtRealtimeMs = 0L;
    }

    private static final class LocationManagerNames {
        static final String GPS = "gps";
        static final String NETWORK = "network";

        private LocationManagerNames() { }
    }
}
