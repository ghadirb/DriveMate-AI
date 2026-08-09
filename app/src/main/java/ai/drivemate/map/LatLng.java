package ai.drivemate.map;

/** Small map-provider-neutral coordinate used by the map screen. */
public final class LatLng {
    private final double latitude;
    private final double longitude;

    public LatLng(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
}
