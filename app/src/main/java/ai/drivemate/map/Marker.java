package ai.drivemate.map;

import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;

/** OSM marker wrapper. */
public final class Marker {
    private final LatLng position;
    private final MarkerStyle style;
    private org.osmdroid.views.overlay.Marker overlay;

    public Marker(LatLng position, MarkerStyle style) {
        this.position = position;
        this.style = style;
    }

    org.osmdroid.views.overlay.Marker overlay(OsmMapView map) {
        if (overlay == null) {
            overlay = new org.osmdroid.views.overlay.Marker(map);
            overlay.setPosition(new org.osmdroid.util.GeoPoint(position.getLatitude(), position.getLongitude()));
            overlay.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                    org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM);
            if (style != null && style.bitmap != null) {
                overlay.setIcon(new BitmapDrawable(map.getResources(), style.bitmap));
            } else {
                GradientDrawable pin = new GradientDrawable();
                pin.setShape(GradientDrawable.OVAL);
                pin.setColor(Color.rgb(25, 118, 210));
                pin.setStroke(2, Color.WHITE);
                pin.setSize(28, 28);
                overlay.setIcon(pin);
            }
        }
        return overlay;
    }

    public LatLng getLatLng() { return position; }
}
