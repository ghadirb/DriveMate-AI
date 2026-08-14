package ai.drivemate.map;

import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;

/** OSM polyline wrapper. */
public final class Polyline {
    private final List<LatLng> points;
    private final boolean primary;
    private final Integer customColor;
    private final Float customWidth;
    private org.osmdroid.views.overlay.Polyline overlay;

    public Polyline(List<LatLng> points, boolean primary) {
        this(points, primary, null, null);
    }

    /** Explicit style for overlays such as the actual traveled track. */
    public Polyline(List<LatLng> points, int color, float width) {
        this(points, false, color, width);
    }

    private Polyline(List<LatLng> points, boolean primary, Integer customColor, Float customWidth) {
        this.points = points == null ? new ArrayList<>() : new ArrayList<>(points);
        this.primary = primary;
        this.customColor = customColor;
        this.customWidth = customWidth;
    }

    org.osmdroid.views.overlay.Polyline overlay() {
        if (overlay == null) {
            overlay = new org.osmdroid.views.overlay.Polyline();
            ArrayList<org.osmdroid.util.GeoPoint> geoPoints = new ArrayList<>();
            for (LatLng point : points) geoPoints.add(new org.osmdroid.util.GeoPoint(point.getLatitude(), point.getLongitude()));
            overlay.setPoints(geoPoints);
            overlay.getOutlinePaint().setColor(customColor != null ? customColor : (primary ? Color.rgb(0, 94, 255) : Color.rgb(90, 90, 90)));
            overlay.getOutlinePaint().setStrokeWidth(customWidth != null ? customWidth : (primary ? 20f : 9f));
            overlay.getOutlinePaint().setAntiAlias(true);
            overlay.getOutlinePaint().setStrokeCap(android.graphics.Paint.Cap.ROUND);
            overlay.getOutlinePaint().setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }
        return overlay;
    }
}
