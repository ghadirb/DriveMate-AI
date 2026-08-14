package ai.drivemate;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import ai.drivemate.map.LatLng;
import ai.drivemate.map.Marker;
import ai.drivemate.map.OsmMapView;
import ai.drivemate.map.Polyline;
import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.TripRecord;

/** Displays the actual GPS trace saved in a trip report. */
public final class TripMapActivity extends Activity {
    public static final String EXTRA_TRIP_JSON = "trip_json";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TripRecord record = null;
        try {
            String json = getIntent().getStringExtra(EXTRA_TRIP_JSON);
            if (json != null) record = TripRecord.fromJson(new org.json.JSONObject(json));
        } catch (Exception ignored) { }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(record == null ? "مسیر سفر" : "مسیر پیموده‌شده — " + record.destinationName);
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        OsmMapView map = new OsmMapView(this);
        root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        if (record == null || record.traveledPath.size() < 2) {
            TextView empty = new TextView(this);
            empty.setText("برای این سفر مسیر GPS ذخیره‌شده کافی وجود ندارد.");
            empty.setPadding(dp(16), dp(12), dp(16), dp(12));
            root.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        final List<RoutePoint> path = new ArrayList<>(record.traveledPath);
        ArrayList<LatLng> points = new ArrayList<>();
        for (RoutePoint p : path) points.add(new LatLng(p.latitude, p.longitude));
        map.addPolyline(new Polyline(points, true));
        map.addMarker(new Marker(new LatLng(record.originLatitude, record.originLongitude), null));
        map.addMarker(new Marker(new LatLng(record.destinationLatitude, record.destinationLongitude), null));
        RoutePoint first = path.get(0);
        map.moveCamera(new LatLng(first.latitude, first.longitude), 0);
        map.setZoom(15f, 0);
        try {
            org.osmdroid.util.GeoPoint center = new org.osmdroid.util.GeoPoint(first.latitude, first.longitude);
            map.getController().setCenter(center);
            map.postDelayed(() -> map.zoomToBoundingBox(new org.osmdroid.util.BoundingBox(
                    maxLat(path), maxLon(path), minLat(path), minLon(path)), true, dp(40)), 250);
        } catch (Exception ignored) { }
    }

    private double minLat(List<RoutePoint> p) { double v = Double.MAX_VALUE; for (RoutePoint x : p) v = Math.min(v, x.latitude); return v; }
    private double maxLat(List<RoutePoint> p) { double v = -Double.MAX_VALUE; for (RoutePoint x : p) v = Math.max(v, x.latitude); return v; }
    private double minLon(List<RoutePoint> p) { double v = Double.MAX_VALUE; for (RoutePoint x : p) v = Math.min(v, x.longitude); return v; }
    private double maxLon(List<RoutePoint> p) { double v = -Double.MAX_VALUE; for (RoutePoint x : p) v = Math.max(v, x.longitude); return v; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
