package ai.drivemate.map;

import android.content.Context;
import android.preference.PreferenceManager;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.views.overlay.MapEventsOverlay;

/** OpenStreetMap renderer used by the app. */
public final class OsmMapView extends org.osmdroid.views.MapView {
    public interface LongClickListener { void onLongClick(LatLng point); }
    public interface UserGestureListener { void onUserGestureStart(); }
    private boolean detached;
    private UserGestureListener userGestureListener;

    public OsmMapView(Context context) {
        super(context);
        // osmdroid needs its persistent configuration loaded before the first MapView is created.
        // MapActivity historically received this indirectly from its XML/lifecycle path, while
        // PersonalRouteActivity creates the map programmatically. Loading it here makes both paths
        // deterministic and, importantly, gives the tile provider a valid cache/user-agent setup.
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context));
        Configuration.getInstance().setUserAgentValue(context.getPackageName());
        setTileSource(TileSourceFactory.MAPNIK);
        setUseDataConnection(true);
        setMultiTouchControls(true);
        setBuiltInZoomControls(false);
        setClickable(true);
    }

    public void moveCamera(LatLng point, float ignoredDuration) {
        org.osmdroid.util.GeoPoint target = new org.osmdroid.util.GeoPoint(point.getLatitude(), point.getLongitude());
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

    public void setOnUserGestureListener(UserGestureListener listener) { this.userGestureListener = listener; }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && userGestureListener != null) {
            userGestureListener.onUserGestureStart();
        }
        return super.dispatchTouchEvent(event);
    }

    public boolean isReadyForOverlays() { return !detached; }

    @Override public void onResume() { if (isReadyForOverlays()) super.onResume(); }
    @Override public void onPause() { if (isReadyForOverlays()) super.onPause(); }
    @Override public void onDetach() { if (detached) return; detached = true; super.onDetach(); }

    public void addMarker(Marker marker) {
        if (marker == null || !isReadyForOverlays()) return;
        org.osmdroid.views.overlay.Marker overlay = marker.overlay(this);
        if (overlay != null) { getOverlays().add(overlay); invalidate(); }
    }
    public void removeMarker(Marker marker) {
        if (marker == null || !isReadyForOverlays()) return;
        org.osmdroid.views.overlay.Marker overlay = marker.existingOverlay();
        if (overlay != null) { getOverlays().remove(overlay); invalidate(); }
    }
    public void addPolyline(Polyline line) {
        if (line != null && isReadyForOverlays()) { getOverlays().add(line.overlay()); invalidate(); }
    }
    public void removePolyline(Polyline line) {
        if (line != null && isReadyForOverlays()) { getOverlays().remove(line.overlay()); invalidate(); }
    }

    private float currentBearing;
    private float currentTilt;
    private android.animation.ValueAnimator bearingAnimator;
    private android.animation.ValueAnimator tiltAnimator;

    public void setBearing(float bearing, float durationSeconds) {
        float target = ((bearing % 360f) + 360f) % 360f;
        if (bearingAnimator != null) bearingAnimator.cancel();
        float shortestDelta = ((target - currentBearing + 540f) % 360f) - 180f;
        float animateTo = currentBearing + shortestDelta;
        if (durationSeconds <= 0f) { currentBearing = target; setMapOrientation(currentBearing); return; }
        bearingAnimator = android.animation.ValueAnimator.ofFloat(currentBearing, animateTo);
        bearingAnimator.setDuration((long) (durationSeconds * 1000f));
        bearingAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        bearingAnimator.addUpdateListener(a -> { float value = (float) a.getAnimatedValue(); currentBearing = ((value % 360f) + 360f) % 360f; setMapOrientation(currentBearing); });
        bearingAnimator.start();
    }

    public void setTilt(float tilt, float durationSeconds) {
        float clamped = Math.max(0f, Math.min(58f, tilt));
        if (getWidth() == 0 || getHeight() == 0) { post(() -> setTilt(tilt, durationSeconds)); return; }
        setPivotX(getWidth() / 2f); setPivotY(getHeight());
        setCameraDistance(getContext().getResources().getDisplayMetrics().density * 14000f);
        if (tiltAnimator != null) tiltAnimator.cancel();
        if (durationSeconds <= 0f) { applyTilt(clamped); return; }
        tiltAnimator = android.animation.ValueAnimator.ofFloat(currentTilt, clamped);
        tiltAnimator.setDuration((long) (durationSeconds * 1000f));
        tiltAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        tiltAnimator.addUpdateListener(a -> applyTilt((float) a.getAnimatedValue()));
        tiltAnimator.start();
    }
    private void applyTilt(float degrees) {
        currentTilt = degrees; setRotationX(degrees);
        double radians = Math.toRadians(degrees);
        float compensation = (float) (1.08d / Math.max(0.05d, Math.cos(radians)));
        setScaleX(compensation); setScaleY(compensation);
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); setPivotX(w / 2f); setPivotY(h); }
}
