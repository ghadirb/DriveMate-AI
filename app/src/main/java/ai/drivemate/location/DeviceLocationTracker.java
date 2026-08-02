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
    private UpdateListener updateListener;

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
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 3f, this);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 8f, this);
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
        lastLocation = location;
        if (updateListener != null) updateListener.onLocationUpdate(location);
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
