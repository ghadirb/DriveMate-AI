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
    private float currentBearing;
    private float currentTilt;
    private android.animation.ValueAnimator bearingAnimator;
    private android.animation.ValueAnimator tiltAnimator;

    /** Rotates the tile layer so the given heading points up (osmdroid's native map rotation),
     *  animating over the shortest angular path instead of always spinning forward through 360°. */
    public void setBearing(float bearing, float durationSeconds) {
        float target = ((bearing % 360f) + 360f) % 360f;
        if (bearingAnimator != null) bearingAnimator.cancel();
        float shortestDelta = ((target - currentBearing + 540f) % 360f) - 180f;
        float animateTo = currentBearing + shortestDelta;
        if (durationSeconds <= 0f) {
            currentBearing = target;
            setMapOrientation(currentBearing);
            return;
        }
        bearingAnimator = android.animation.ValueAnimator.ofFloat(currentBearing, animateTo);
        bearingAnimator.setDuration((long) (durationSeconds * 1000f));
        bearingAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        bearingAnimator.addUpdateListener(a -> {
            float value = (float) a.getAnimatedValue();
            currentBearing = ((value % 360f) + 360f) % 360f;
            setMapOrientation(currentBearing);
        });
        bearingAnimator.start();
    }

    /** osmdroid renders a flat 2D plane with no true camera pitch, so a real perspective tilt is
     *  faked with a standard Android trick: rotate the whole view around its bottom edge on the
     *  X axis (the road ahead tips back "into" the screen the way a real 3D chase-cam would show
     *  it) and scale it back up so the foreshortened plane still fully covers the container - the
     *  same approach other 2D-tile navigation apps use when they don't have a true 3D map engine. */
    public void setTilt(float tilt, float durationSeconds) {
        float clamped = Math.max(0f, Math.min(58f, tilt));
        if (getWidth() == 0 || getHeight() == 0) {
            post(() -> setTilt(tilt, durationSeconds));
            return;
        }
        setPivotX(getWidth() / 2f);
        setPivotY(getHeight());
        setCameraDistance(getContext().getResources().getDisplayMetrics().density * 14000f);
        if (tiltAnimator != null) tiltAnimator.cancel();
        if (durationSeconds <= 0f) {
            applyTilt(clamped);
            return;
        }
        tiltAnimator = android.animation.ValueAnimator.ofFloat(currentTilt, clamped);
        tiltAnimator.setDuration((long) (durationSeconds * 1000f));
        tiltAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        tiltAnimator.addUpdateListener(a -> applyTilt((float) a.getAnimatedValue()));
        tiltAnimator.start();
    }

    private void applyTilt(float degrees) {
        currentTilt = degrees;
        setRotationX(degrees);
        // The previous version clamped the cosine at 0.62, which under-compensates for any tilt
        // past ~52 degrees - at the actual 58-degree navigation tilt this left the scaled plane
        // roughly 15% short of covering the container, exposing the plain background color as a
        // visible band at the top of the screen, right under the turn banner. Compensate for the
        // real angle (with a small safety margin for rounding/device variance) instead.
        double radians = Math.toRadians(degrees);
        float compensation = (float) (1.08d / Math.max(0.05d, Math.cos(radians)));
        setScaleX(compensation);
        setScaleY(compensation);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        setPivotX(w / 2f);
        setPivotY(h);
    }
}
