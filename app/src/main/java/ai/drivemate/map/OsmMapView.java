package ai.drivemate.map;

import android.content.Context;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.views.overlay.MapEventsOverlay;

/** OpenStreetMap renderer used by MapActivity. It never initializes the Neshan map SDK. */
public final class OsmMapView extends org.osmdroid.views.MapView {
    public interface LongClickListener { void onLongClick(LatLng point); }

    public OsmMapView(Context context) {
        super(context);
        Configuration.getInstance().setUserAgentValue(context.getPackageName());
        setTileSource(TileSourceFactory.MAPNIK);
        setMultiTouchControls(true);
        setBuiltInZoomControls(false);
    }

    public void moveCamera(LatLng point, float ignoredDuration) {
        getController().animateTo(new org.osmdroid.util.GeoPoint(point.getLatitude(), point.getLongitude()));
    }

    public void setZoom(float zoom, float ignoredDuration) { getController().setZoom(Math.round(zoom)); }

    public void setOnMapLongClickListener(LongClickListener listener) {
        getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(org.osmdroid.util.GeoPoint point) { return false; }
            @Override public boolean longPressHelper(org.osmdroid.util.GeoPoint point) {
                listener.onLongClick(new LatLng(point.getLatitude(), point.getLongitude()));
                return true;
            }
        }));
    }

    public void addMarker(Marker marker) { if (marker != null) { getOverlays().add(marker.overlay(this)); invalidate(); } }
    public void removeMarker(Marker marker) { if (marker != null) { getOverlays().remove(marker.overlay(this)); invalidate(); } }
    public void addPolyline(Polyline line) { if (line != null) { getOverlays().add(line.overlay()); invalidate(); } }
    public void removePolyline(Polyline line) { if (line != null) { getOverlays().remove(line.overlay()); invalidate(); } }
    public void setBearing(float ignoredBearing, float ignoredDuration) { }
    public void setTilt(float ignoredTilt, float ignoredDuration) { }
}
