package ai.drivemate.map;

import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;

/** OSM polyline wrapper. */
public final class Polyline {
    private final List<LatLng> points;
    private final boolean primary;
    private org.osmdroid.views.overlay.Polyline overlay;

    public Polyline(List<LatLng> points, boolean primary) {
        this.points = points == null ? new ArrayList<>() : new ArrayList<>(points);
        this.primary = primary;
    }

    org.osmdroid.views.overlay.Polyline overlay() {
        if (overlay == null) {
            overlay = new org.osmdroid.views.overlay.Polyline();
            ArrayList<org.osmdroid.util.GeoPoint> geoPoints = new ArrayList<>();
            for (LatLng point : points) geoPoints.add(new org.osmdroid.util.GeoPoint(point.getLatitude(), point.getLongitude()));
            overlay.setPoints(geoPoints);
            overlay.getOutlinePaint().setColor(primary ? Color.rgb(17, 107, 135) : Color.rgb(117, 117, 117));
            overlay.getOutlinePaint().setStrokeWidth(primary ? 12f : 7f);
        }
        return overlay;
    }
}
