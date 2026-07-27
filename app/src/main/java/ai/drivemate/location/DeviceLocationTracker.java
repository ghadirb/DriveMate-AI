package ai.drivemate.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

public class DeviceLocationTracker implements LocationListener {
    public interface UpdateListener { void onLocationUpdate(Location location); }
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
        Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        lastLocation = gps != null ? gps : network;
        // Short urban blocks need denser samples than a background location indicator.
        // NavigationEngine still debounces spoken instructions, so this does not create voice spam.
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 3f, this);
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 8f, this);
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
        locationManager.removeUpdates(this);
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
    }

    @Override
    public void onProviderDisabled(String provider) {
    }
}
