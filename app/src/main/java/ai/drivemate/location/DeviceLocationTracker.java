package ai.drivemate.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

public class DeviceLocationTracker implements LocationListener {
    public interface UpdateListener {
        void onLocationUpdate(Location location);
        /** Fired when GPS/network providers are toggled off or back on (e.g. the driver turns
         *  location off mid-trip, or Android drops the provider). Default no-op so existing
         *  callers compile unchanged. Callers should keep navigation running on the last known
         *  fix and only warn the driver - never stop or tear anything down from this alone. */
        default void onLocationAvailabilityChanged(boolean available) { }
    }
    private final Context context;
    private final LocationManager locationManager;
    private Location lastLocation;
    /** The last fix actually accepted by isUsableFix(), used as the jump-plausibility reference.
     *  Deliberately separate from lastLocation, which callers may read via getLastLocation(). */
    private Location lastAcceptedLocation;
    private int consecutiveJumpRejections;
    private UpdateListener updateListener;

    /** Fixes worse than this are essentially useless for street-level navigation and are dropped
     *  outright rather than acted on. */
    private static final float MAX_USABLE_ACCURACY_METERS = 100f;
    /** Generous upper bound (~200 km/h) on how fast a car could plausibly move between two fixes;
     *  anything beyond this, scaled by the elapsed time, is a GPS jump (multipath/urban-canyon
     *  reflection) rather than real movement - this is the "location jumped" behavior reported on
     *  two different test phones. */
    private static final float MAX_PLAUSIBLE_SPEED_MPS = 55f;
    /** After this many consecutive rejections, accept the next fix anyway - guards against the
     *  reference point itself having been a bad fix, which would otherwise lock the filter out for
     *  the rest of the trip. */
    private static final int MAX_CONSECUTIVE_JUMP_REJECTIONS = 3;

    public DeviceLocationTracker(Context context) {
        this.context = context;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void start() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            lastLocation = gps != null ? gps : network;
        } catch (SecurityException | IllegalArgumentException ignored) {
            // A provider that is missing or was just revoked must never crash trip start.
        }
        // Short urban blocks need denser samples than a background location indicator.
        // NavigationEngine still debounces spoken instructions, so this does not create voice spam.
        // Each provider is requested independently and defensively: some OEM builds throw when a
        // provider is fully switched off (rather than just "disabled"), and losing GPS must never
        // take network updates down with it, or vice versa.
        // minDistance is intentionally 0 for both providers: a non-zero minDistance makes Android
        // withhold updates once the driver stops moving (e.g. parking within the arrival radius),
        // which silently starves onLocation()-based checks (arrival, off-route, hazards) of any
        // fresh fix to re-evaluate - navigation would then never detect arrival at all. Time-based
        // cadence alone (minTime) is what should govern update frequency here.
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, this);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    public boolean isLocationEnabled() {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    public Location getLastLocation() {
        return lastLocation;
    }

    public void setUpdateListener(UpdateListener listener) { this.updateListener = listener; }

    public void stop() {
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!isUsableFix(location)) return;
        lastLocation = location;
        lastAcceptedLocation = location;
        if (updateListener != null) updateListener.onLocationUpdate(location);
    }

    /** Rejects fixes too imprecise to act on, and implausible instantaneous jumps from the last
     *  accepted fix. Recovers automatically after a few consecutive rejections (see
     *  MAX_CONSECUTIVE_JUMP_REJECTIONS) so a genuinely bad reference point can't lock this out. */
    private boolean isUsableFix(Location location) {
        if (location.hasAccuracy() && location.getAccuracy() > MAX_USABLE_ACCURACY_METERS) return false;
        if (lastAcceptedLocation == null) return true;
        long elapsedMs = location.getTime() - lastAcceptedLocation.getTime();
        if (elapsedMs <= 0) elapsedMs = 1000L; // some OEM providers do not set a reliable timestamp delta
        float distance = lastAcceptedLocation.distanceTo(location);
        float accuracyMargin = (lastAcceptedLocation.hasAccuracy() ? lastAcceptedLocation.getAccuracy() : 0f)
                + (location.hasAccuracy() ? location.getAccuracy() : 0f);
        float maxPlausibleDistance = (elapsedMs / 1000f) * MAX_PLAUSIBLE_SPEED_MPS + accuracyMargin + 30f;
        if (distance <= maxPlausibleDistance) {
            consecutiveJumpRejections = 0;
            return true;
        }
        consecutiveJumpRejections++;
        return consecutiveJumpRejections > MAX_CONSECUTIVE_JUMP_REJECTIONS;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (updateListener != null) updateListener.onLocationAvailabilityChanged(isLocationEnabled());
    }

    @Override
    public void onProviderDisabled(String provider) {
        // Only GPS_PROVIDER and NETWORK_PROVIDER are ever requested above, so this only fires for
        // one of those two - never treat it as "all location is gone": isLocationEnabled() checks
        // both before deciding whether to warn the driver.
        if (updateListener != null) updateListener.onLocationAvailabilityChanged(isLocationEnabled());
    }
}
