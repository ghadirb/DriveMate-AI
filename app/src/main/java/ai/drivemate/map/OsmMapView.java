package ai.drivemate.map;

import android.content.Context;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.views.overlay.MapEventsOverlay;

/** OpenStreetMap renderer used by MapActivity. It never initializes the Neshan map SDK. */
public final class OsmMapView extends org.osmdroid.views.MapView {
    public interface LongClickListener { void onLongClick(LatLng point); }
    private boolean detached;

    public OsmMapView(Context context) {
        super(context);
        Configuration.getInstance().setUserAgentValue(context.getPackageName());
        setTileSource(TileSourceFactory.MAPNIK);
        setMultiTouchControls(true);
        setBuiltInZoomControls(false);
    }

    public void moveCamera(LatLng point, float ignoredDuration) {
        org.osmdroid.util.GeoPoint target =
                new org.osmdroid.util.GeoPoint(point.getLatitude(), point.getLongitude());
        if (ignoredDuration <= 0f) getController().setCenter(target);
        else getController().animateTo(target);
    }

    public void setZoom(float zoom, float ignoredDuration) { getController().setZoom(Math.round(zoom)); }

    public void setOnMapLongClickListener(LongClickListener listener) {
        if (!isReadyForOverlays() || listener == null) return;
        getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(org.osmdroid.util.GeoPoint point) {
                listener.onLongClick(new LatLng(point.getLatitude(), point.getLongitude()));
                return true;
            }
            @Override public boolean longPressHelper(org.osmdroid.util.GeoPoint point) {
                listener.onLongClick(new LatLng(point.getLatitude(), point.getLongitude()));
                return true;
            }
        }));
    }

    public boolean isReadyForOverlays() { return !detached; }

    @Override public void onResume() {
        if (isReadyForOverlays()) super.onResume();
    }

    @Override public void onPause() {
        if (isReadyForOverlays()) super.onPause();
    }

    @Override public void onDetach() {
        if (detached) return;
        detached = true;
        super.onDetach();
    }

    public void addMarker(Marker marker) {
        if (marker == null || !isReadyForOverlays()) return;
        org.osmdroid.views.overlay.Marker overlay = marker.overlay(this);
        if (overlay != null) {
            getOverlays().add(overlay);
            invalidate();
        }
    }

    public void removeMarker(Marker marker) {
        if (marker == null || !isReadyForOverlays()) return;
        org.osmdroid.views.overlay.Marker overlay = marker.existingOverlay();
        if (overlay != null) {
            getOverlays().remove(overlay);
            invalidate();
        }
    }

    public void addPolyline(Polyline line) {
        if (line != null && isReadyForOverlays()) {
            getOverlays().add(line.overlay());
            invalidate();
        }
    }

    public void removePolyline(Polyline line) {
        if (line != null && isReadyForOverlays()) {
            getOverlays().remove(line.overlay());
            invalidate();
        }
    }
    public void setBearing(float ignoredBearing, float ignoredDuration) { }
    public void setTilt(float ignoredTilt, float ignoredDuration) { }
}
