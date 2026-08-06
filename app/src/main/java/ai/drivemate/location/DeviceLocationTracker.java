package ai.drivemate.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

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
    private final LocationQualityFilter locationFilter = new LocationQualityFilter();
    private Location lastLocation;
    private UpdateListener updateListener;

    /** Fused location (GPS + WiFi + cell + on-device sensor fusion via Google Play Services) is
     *  meaningfully stronger than raw GPS alone, especially in dense urban areas / narrow streets
     *  between tall buildings where GPS-only multipath reflection is worst. The dependency was
     *  already in build.gradle but never actually used - this activates it as the primary source,
     *  falling back to the plain LocationManager path below when Play Services is unavailable
     *  (device without Google services, or the API call itself fails for any reason) so the app
     *  never simply stops getting location on such devices. */
    private FusedLocationProviderClient fusedClient;
    private LocationCallback fusedCallback;
    private boolean usingFusedProvider;

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
            lastLocation = bestLastKnown(gps, network);
            locationFilter.seed(lastLocation);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // A provider that is missing or was just revoked must never crash trip start.
        }
        if (startFusedProvider()) {
            usingFusedProvider = true;
            return;
        }
        usingFusedProvider = false;
        startLegacyProviders();
    }

    /** @return true if Fused location was successfully started and should be relied on instead of
     *  the raw LocationManager path. */
    private boolean startFusedProvider() {
        try {
            fusedClient = LocationServices.getFusedLocationProviderClient(context);
            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                    .setMinUpdateIntervalMillis(500L)
                    // Deliberately no setMinUpdateDistanceMeters: a nonzero value makes Android
                    // withhold updates once the driver stops moving (e.g. parking within the
                    // arrival radius), which silently starves onLocation()-based checks (arrival,
                    // off-route, hazards) of any fresh fix to re-evaluate.
                    .build();
            fusedCallback = new LocationCallback() {
                @Override public void onLocationResult(LocationResult result) {
                    Location location = result.getLastLocation();
                    if (location != null) onLocationChanged(location);
                }
            };
            // requestLocationUpdates typically fails asynchronously via the returned Task (Play
            // Services missing/outdated, no Google account) rather than throwing synchronously, so
            // the fallback to legacy providers must be wired to that failure callback too - relying
            // only on the surrounding try/catch would silently leave such devices with no location
            // updates at all, since the synchronous call itself usually succeeds.
            fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                    .addOnFailureListener(e -> {
                        if (usingFusedProvider) {
                            usingFusedProvider = false;
                            startLegacyProviders();
                        }
                    });
            return true;
        } catch (SecurityException | IllegalStateException | NoClassDefFoundError | RuntimeException e) {
            // Any synchronous failure here (Play Services missing/outdated, no Google account, or
            // anything else) must fall back to the legacy path, never leave the app with no
            // location at all.
            fusedClient = null;
            fusedCallback = null;
            return false;
        }
    }

    private void startLegacyProviders() {
        // Short urban blocks need denser samples than a background location indicator.
        // NavigationEngine still debounces spoken instructions, so this does not create voice spam.
        // Each provider is requested independently and defensively: some OEM builds throw when a
        // provider is fully switched off (rather than just "disabled"), and losing GPS must never
        // take network updates down with it, or vice versa.
        // minDistance is intentionally 0 for both providers: a non-zero minDistance makes Android
        // withhold updates once the driver stops moving (e.g. parking within the arrival radius),
        // which silently starves onLocation()-based checks (arrival, off-route, hazards) of any
        // fresh fix to re-evaluate. Time-based cadence alone (minTime) is what should govern
        // update frequency here.
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
        if (fusedClient != null && fusedCallback != null) {
            try {
                fusedClient.removeLocationUpdates(fusedCallback);
            } catch (RuntimeException ignored) {
            }
        }
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        Location accepted = locationFilter.filter(location);
        if (accepted == null) return;
        lastLocation = accepted;
        if (updateListener != null) updateListener.onLocationUpdate(new Location(accepted));
    }

    private Location bestLastKnown(Location gps, Location network) {
        if (gps == null) return network;
        if (network == null) return gps;
        long timeDifferenceMs = gps.getTime() - network.getTime();
        if (Math.abs(timeDifferenceMs) > 10_000L) return timeDifferenceMs > 0L ? gps : network;
        float gpsAccuracy = gps.hasAccuracy() ? gps.getAccuracy() : Float.MAX_VALUE;
        float networkAccuracy = network.hasAccuracy() ? network.getAccuracy() : Float.MAX_VALUE;
        return gpsAccuracy <= networkAccuracy ? gps : network;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
        // Only relevant to the legacy path; the Fused client manages its own provider/settings
        // state internally and does not deliver this callback.
        if (!usingFusedProvider && updateListener != null) updateListener.onLocationAvailabilityChanged(isLocationEnabled());
    }

    @Override
    public void onProviderDisabled(String provider) {
        // Only GPS_PROVIDER and NETWORK_PROVIDER are ever requested above, so this only fires for
        // one of those two - never treat it as "all location is gone": isLocationEnabled() checks
        // both before deciding whether to warn the driver.
        if (!usingFusedProvider && updateListener != null) updateListener.onLocationAvailabilityChanged(isLocationEnabled());
    }
}
