package ai.drivemate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ai.drivemate.location.DeviceLocationTracker;
import ai.drivemate.map.LatLng;
import ai.drivemate.map.Marker;
import ai.drivemate.map.OsmMapView;
import ai.drivemate.map.Polyline;
import ai.drivemate.model.PersonalRoute;
import ai.drivemate.model.RoutePoint;
import ai.drivemate.storage.PersonalRouteStore;

/** Full-screen personal-route editor. Long-press the map to add mandatory waypoints. */
public final class PersonalRouteActivity extends Activity {
    public static final String ACTION_START_PERSONAL_ROUTE = "ai.drivemate.action.START_PERSONAL_ROUTE";
    public static final String EXTRA_PERSONAL_ROUTE_JSON = "personal_route_json";
    private static final int REQUEST_LOCATION = 7101;

    private OsmMapView map;
    private PersonalRouteStore store;
    private DeviceLocationTracker locationTracker;
    private Marker myLocationMarker;
    private PersonalRoute pendingNavigationRoute;
    private final List<RoutePoint> draftPoints = new ArrayList<>();
    private final List<Marker> draftMarkers = new ArrayList<>();
    private Polyline draftLine;
    private LinearLayout list;
    private TextView draftInfo;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new PersonalRouteStore(this);
        buildUi();
        map.moveCamera(new LatLng(35.7219, 51.3347), 0);
        locationTracker = new DeviceLocationTracker(this);
        locationTracker.setUpdateListener(new DeviceLocationTracker.UpdateListener() {
            @Override public void onLocationUpdate(Location location) {
                showMyLocation(location, true);
                if (pendingNavigationRoute != null && locationTracker.isLocationEnabled()) {
                    PersonalRoute route = pendingNavigationRoute;
                    pendingNavigationRoute = null;
                    startRouteNow(route);
                }
            }
            @Override public void onLocationAvailabilityChanged(boolean available) {
                if (!available) showLocationDisabledDialog(false);
            }
        });
        renderSavedRoutes();
        startLocationUpdatesIfPermitted();
    }

    @Override protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
        if (locationTracker != null && hasLocationPermission()) {
            if (!locationTracker.isLocationEnabled()) {
                // Do not nag on every resume; only act when a route is waiting or the user taps the button.
                if (pendingNavigationRoute != null) showLocationDisabledDialog(false);
            } else {
                locationTracker.start();
            }
        }
    }

    @Override protected void onPause() {
        if (map != null) map.onPause();
        if (locationTracker != null) locationTracker.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (locationTracker != null) locationTracker.stop();
        if (map != null) map.onDetach();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff7f8fa);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setPadding(dp(10), dp(6), dp(10), dp(6));
        TextView title = text("مسیرهای شخصی", 20, true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        Button myLocation = button("📍 موقعیت من");
        myLocation.setOnClickListener(v -> centerOnMyLocation());
        toolbar.addView(myLocation, new LinearLayout.LayoutParams(-2, -2));
        root.addView(toolbar);

        LinearLayout mapFrame = new LinearLayout(this);
        mapFrame.setOrientation(LinearLayout.VERTICAL);
        map = new OsmMapView(this);
        map.setZoom(15f, 0);
        map.setOnMapLongClickListener(this::addDraftPoint);
        mapFrame.addView(map, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView hint = text("لمس طولانی روی نقشه = افزودن نقطه اجباری  •  📍 = موقعیت فعلی", 13, false);
        hint.setPadding(dp(12), dp(5), dp(12), dp(5));
        mapFrame.addView(hint);
        root.addView(mapFrame, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(12), dp(8), dp(12), dp(8));
        draftInfo = text("برای ساخت مسیر، روی نقشه لمس طولانی کنید.", 14, false);
        sheet.addView(draftInfo);
        LinearLayout actions = new LinearLayout(this);
        Button save = button("ذخیره مسیر"), undo = button("حذف آخرین"), clear = button("پاک کردن");
        actions.addView(save, weight()); actions.addView(undo, weight()); actions.addView(clear, weight());
        sheet.addView(actions);
        save.setOnClickListener(v -> saveDraft());
        undo.setOnClickListener(v -> undoDraftPoint());
        clear.setOnClickListener(v -> clearDraft());
        TextView savedTitle = text("مسیرهای ذخیره‌شده", 16, true);
        savedTitle.setPadding(0, dp(7), 0, dp(5));
        sheet.addView(savedTitle);
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, dp(145)));
        root.addView(sheet, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocationUpdatesIfPermitted() {
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        if (locationTracker.isLocationEnabled()) locationTracker.start();
    }

    private void centerOnMyLocation() {
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        if (!locationTracker.isLocationEnabled()) {
            showLocationDisabledDialog(true);
            return;
        }
        Location location = locationTracker.getLastLocation();
        if (location != null) {
            showMyLocation(location, true);
        } else {
            draftInfo.setText("در حال دریافت موقعیت فعلی شما…");
            locationTracker.start();
            Toast.makeText(this, "در حال دریافت موقعیت GPS…", Toast.LENGTH_SHORT).show();
        }
    }

    private void showMyLocation(Location location, boolean moveCamera) {
        LatLng point = new LatLng(location.getLatitude(), location.getLongitude());
        if (myLocationMarker != null) map.removeMarker(myLocationMarker);
        myLocationMarker = new Marker(point, null);
        map.addMarker(myLocationMarker);
        if (moveCamera) map.moveCamera(point, 0);
    }

    private void showLocationDisabledDialog(boolean fromButton) {
        String message = "موقعیت مکانی (GPS/Location) دستگاه خاموش است. برای نمایش موقعیت شما و شروع مسیریابی مسیر شخصی، Location را فعال کنید.";
        new AlertDialog.Builder(this)
                .setTitle("موقعیت مکانی خاموش است")
                .setMessage(message)
                .setNegativeButton("بعداً", null)
                .setPositiveButton("فعال کردن موقعیت", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
                    catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                }).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION) return;
        if (hasLocationPermission()) {
            if (locationTracker.isLocationEnabled()) locationTracker.start();
            else showLocationDisabledDialog(true);
        } else {
            new AlertDialog.Builder(this).setTitle("دسترسی موقعیت لازم است")
                    .setMessage("برای مرکز کردن نقشه روی موقعیت شما و شروع مسیریابی، اجازه دسترسی به موقعیت مکانی لازم است.")
                    .setNegativeButton("بعداً", null)
                    .setPositiveButton("تنظیمات", (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(android.net.Uri.parse("package:" + getPackageName())); startActivity(i);
                    }).show();
        }
    }

    private void addDraftPoint(LatLng point) {
        draftPoints.add(new RoutePoint(point.getLatitude(), point.getLongitude()));
        Marker marker = new Marker(point, null);
        draftMarkers.add(marker); map.addMarker(marker); redrawDraft();
        draftInfo.setText(draftPoints.size() + " نقطه انتخاب شده — نقاط میانی اجباری هستند.");
    }

    private void redrawDraft() {
        if (draftLine != null) map.removePolyline(draftLine);
        ArrayList<LatLng> points = new ArrayList<>();
        for (RoutePoint p : draftPoints) points.add(new LatLng(p.latitude, p.longitude));
        if (points.size() >= 2) { draftLine = new Polyline(points, true); map.addPolyline(draftLine); }
    }

    private void undoDraftPoint() {
        if (draftPoints.isEmpty()) return;
        map.removeMarker(draftMarkers.remove(draftMarkers.size() - 1));
        draftPoints.remove(draftPoints.size() - 1); redrawDraft();
        draftInfo.setText(draftPoints.isEmpty() ? "برای ساخت مسیر، روی نقشه لمس طولانی کنید." : draftPoints.size() + " نقطه انتخاب شده.");
    }

    private void clearDraft() {
        for (Marker m : draftMarkers) map.removeMarker(m);
        draftMarkers.clear(); draftPoints.clear();
        if (draftLine != null) map.removePolyline(draftLine);
        draftLine = null; draftInfo.setText("برای ساخت مسیر، روی نقشه لمس طولانی کنید.");
    }

    private void saveDraft() {
        if (draftPoints.size() < 2) { Toast.makeText(this, "حداقل دو نقطه انتخاب کنید.", Toast.LENGTH_SHORT).show(); return; }
        EditText name = new EditText(this); name.setHint("مثلاً مسیر محل کار");
        new AlertDialog.Builder(this).setTitle("نام مسیر").setView(name)
                .setNegativeButton("انصراف", null)
                .setPositiveButton("ذخیره", (d, w) -> {
                    String n = name.getText().toString().trim(); if (n.isEmpty()) n = "مسیر شخصی";
                    store.upsert(new PersonalRoute(UUID.randomUUID().toString(), n, System.currentTimeMillis(), new ArrayList<>(draftPoints)));
                    clearDraft(); renderSavedRoutes(); Toast.makeText(this, "مسیر ذخیره شد.", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void renderSavedRoutes() {
        list.removeAllViews(); List<PersonalRoute> routes = store.all();
        if (routes.isEmpty()) { list.addView(text("هنوز مسیر شخصی ذخیره نشده است.", 14, false)); return; }
        for (PersonalRoute r : routes) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(7), dp(5), dp(7), dp(5));
            row.addView(text(r.name + "  •  " + r.points.size() + " نقطه اجباری", 15, true));
            LinearLayout b = new LinearLayout(this);
            Button use = button("مسیریابی"), show = button("نمایش روی نقشه"), del = button("حذف");
            b.addView(use, weight()); b.addView(show, weight()); b.addView(del, weight()); row.addView(b);
            use.setOnClickListener(v -> startRoute(r)); show.setOnClickListener(v -> showRoute(r));
            del.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("حذف مسیر").setMessage("این مسیر حذف شود؟")
                    .setNegativeButton("انصراف", null).setPositiveButton("حذف", (d, w) -> { store.remove(r.id); renderSavedRoutes(); }).show());
            list.addView(row);
        }
    }

    private void showRoute(PersonalRoute r) {
        clearDraft(); draftPoints.addAll(r.points);
        for (RoutePoint p : draftPoints) { Marker m = new Marker(new LatLng(p.latitude, p.longitude), null); draftMarkers.add(m); map.addMarker(m); }
        redrawDraft();
        if (!r.points.isEmpty()) map.moveCamera(new LatLng(r.points.get(0).latitude, r.points.get(0).longitude), 0);
        draftInfo.setText(r.name + " — " + r.points.size() + " نقطه اجباری");
    }

    private void startRoute(PersonalRoute r) {
        if (r.points.size() < 2) { Toast.makeText(this, "این مسیر نقطه کافی برای مسیریابی ندارد.", Toast.LENGTH_SHORT).show(); return; }
        pendingNavigationRoute = r;
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        if (!locationTracker.isLocationEnabled()) {
            showLocationDisabledDialog(false);
            return;
        }
        startRouteNow(r);
    }

    private void startRouteNow(PersonalRoute r) {
        if (!hasLocationPermission() || !locationTracker.isLocationEnabled()) return;
        pendingNavigationRoute = null;
        try {
            String json = r.toJson().toString();
            Intent i = new Intent(this, MainActivity.class);
            i.setAction(ACTION_START_PERSONAL_ROUTE);
            i.putExtra(EXTRA_PERSONAL_ROUTE_JSON, json);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (org.json.JSONException e) {
            Toast.makeText(this, "مسیر شخصی برای مسیریابی قابل خواندن نیست.", Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(String s, int size, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); if (bold) t.setTypeface(null, Typeface.BOLD); return t; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1f); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
