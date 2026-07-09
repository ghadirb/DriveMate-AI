package ai.drivemate.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

public class DeviceLocationTracker implements LocationListener {
    private final Context context;
    private final LocationManager locationManager;
    private Location lastLocation;

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
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2500L, 8f, this);
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 15f, this);
    }

    public Location getLastLocation() {
        return lastLocation;
    }

    @Override
    public void onLocationChanged(Location location) {
        lastLocation = location;
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
