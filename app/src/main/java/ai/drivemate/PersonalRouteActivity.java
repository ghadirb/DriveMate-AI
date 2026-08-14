package ai.drivemate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ai.drivemate.map.LatLng;
import ai.drivemate.map.Marker;
import ai.drivemate.map.OsmMapView;
import ai.drivemate.map.Polyline;
import ai.drivemate.model.PersonalRoute;
import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.TripRecord;
import ai.drivemate.storage.PersonalRouteStore;
import ai.drivemate.storage.TripStore;

/**
 * Full-screen personal-route editor. The map deliberately occupies most of the screen; the
 * controls are a compact bottom panel so route drawing is usable on a phone while driving.
 * Long-press adds ordered points. All intermediate points are passed to routing as mandatory
 * waypoints when the saved route is started.
 */
public final class PersonalRouteActivity extends Activity {
    public static final String ACTION_START_PERSONAL_ROUTE = "ai.drivemate.action.START_PERSONAL_ROUTE";
    public static final String EXTRA_PERSONAL_ROUTE_JSON = "personal_route_json";

    private OsmMapView map;
    private PersonalRouteStore store;
    private TripStore tripStore;
    private final List<RoutePoint> draftPoints = new ArrayList<>();
    private final List<Marker> draftMarkers = new ArrayList<>();
    private Polyline draftLine;
    private LinearLayout savedRoutesList;
    private LinearLayout tripsList;
    private TextView draftInfo;
    private TextView modeInfo;
    private boolean showingTrips;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new PersonalRouteStore(this);
        tripStore = new TripStore(this);
        buildUi();
        renderSavedRoutes();
        renderTrips();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);

        map = new OsmMapView(this);
        map.setZoom(14f, 0);
        map.setOnMapLongClickListener(this::addDraftPoint);
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));

        // A small translucent instruction bar at the top keeps the map visually dominant.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(14), dp(10), dp(14), dp(10));
        top.setBackgroundColor(0xDDFFFFFF);
        TextView title = text("مسیرهای شخصی", 20, true);
        top.addView(title);
        modeInfo = text("برای ساخت مسیر، روی نقشه لمس طولانی کنید.", 13, false);
        top.addView(modeInfo);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        topLp.setMargins(dp(10), dp(10), dp(10), 0);
        root.addView(top, topLp);

        // Bottom sheet-style panel: the map remains visible while the controls can scroll.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(10));
        panel.setBackgroundColor(0xF7FFFFFF);

        draftInfo = text("هنوز نقطه‌ای انتخاب نشده است.", 14, false);
        panel.addView(draftInfo);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button save = button("ذخیره مسیر");
        Button undo = button("حذف آخرین نقطه");
        Button clear = button("پاک کردن");
        actions.addView(save, weight());
        actions.addView(undo, weight());
        actions.addView(clear, weight());
        panel.addView(actions);
        save.setOnClickListener(v -> saveDraft());
        undo.setOnClickListener(v -> undoDraftPoint());
        clear.setOnClickListener(v -> clearDraft());

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button routesTab = button("مسیرهای شخصی");
        Button tripsTab = button("مسیرهای پیموده‌شده");
        tabs.addView(routesTab, weight());
        tabs.addView(tripsTab, weight());
        panel.addView(tabs);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        savedRoutesList = new LinearLayout(this);
        savedRoutesList.setOrientation(LinearLayout.VERTICAL);
        tripsList = new LinearLayout(this);
        tripsList.setOrientation(LinearLayout.VERTICAL);
        content.addView(savedRoutesList);
        content.addView(tripsList);
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(180)));

        routesTab.setOnClickListener(v -> showRoutesSection());
        tripsTab.setOnClickListener(v -> showTripsSection());

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        panelLp.setMargins(dp(8), 0, dp(8), dp(8));
        root.addView(panel, panelLp);
        setContentView(root);
    }

    private void showRoutesSection() {
        showingTrips = false;
        savedRoutesList.setVisibility(View.VISIBLE);
        tripsList.setVisibility(View.GONE);
        modeInfo.setText("ساخت مسیر: روی نقشه لمس طولانی کنید؛ نقاط میانی اجباری هستند.");
    }

    private void showTripsSection() {
        showingTrips = true;
        savedRoutesList.setVisibility(View.GONE);
        tripsList.setVisibility(View.VISIBLE);
        modeInfo.setText("مسیر واقعی هر سفر ذخیره‌شده را انتخاب کنید تا روی نقشه باز شود.");
    }

    private void addDraftPoint(LatLng point) {
        draftPoints.add(new RoutePoint(point.getLatitude(), point.getLongitude()));
        Marker marker = new Marker(point, null);
        draftMarkers.add(marker);
        map.addMarker(marker);
        redrawDraft();
        draftInfo.setText("تعداد نقاط مسیر: " + draftPoints.size() + " — نقاط میانی اجباری هستند.");
    }

    private void redrawDraft() {
        if (draftLine != null) map.removePolyline(draftLine);
        ArrayList<LatLng> points = new ArrayList<>();
        for (RoutePoint p : draftPoints) points.add(new LatLng(p.latitude, p.longitude));
        if (points.size() >= 2) {
            draftLine = new Polyline(points, true);
            map.addPolyline(draftLine);
        }
    }

    private void undoDraftPoint() {
        if (draftPoints.isEmpty()) return;
        Marker marker = draftMarkers.remove(draftMarkers.size() - 1);
        map.removeMarker(marker);
        draftPoints.remove(draftPoints.size() - 1);
        redrawDraft();
        draftInfo.setText(draftPoints.isEmpty() ? "هنوز نقطه‌ای انتخاب نشده است." : "تعداد نقاط مسیر: " + draftPoints.size());
    }

    private void clearDraft() {
        for (Marker marker : draftMarkers) map.removeMarker(marker);
        draftMarkers.clear();
        draftPoints.clear();
        if (draftLine != null) map.removePolyline(draftLine);
        draftLine = null;
        draftInfo.setText("هنوز نقطه‌ای انتخاب نشده است.");
    }

    private void saveDraft() {
        if (draftPoints.size() < 2) {
            Toast.makeText(this, "برای ذخیره حداقل دو نقطه انتخاب کنید.", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText name = new EditText(this);
        name.setHint("مثلاً مسیر محل کار");
        new AlertDialog.Builder(this).setTitle("نام مسیر").setView(name)
                .setNegativeButton("انصراف", null)
                .setPositiveButton("ذخیره", (d, w) -> {
                    String routeName = name.getText().toString().trim();
                    if (routeName.isEmpty()) routeName = "مسیر شخصی";
                    store.upsert(new PersonalRoute(UUID.randomUUID().toString(), routeName,
                            System.currentTimeMillis(), new ArrayList<>(draftPoints)));
                    clearDraft();
                    renderSavedRoutes();
                    Toast.makeText(this, "مسیر ذخیره شد.", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void renderSavedRoutes() {
        savedRoutesList.removeAllViews();
        List<PersonalRoute> routes = store.all();
        if (routes.isEmpty()) {
            savedRoutesList.addView(text("هنوز مسیر شخصی ذخیره نشده است.", 14, false));
            return;
        }
        for (PersonalRoute route : routes) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(6), dp(5), dp(6), dp(5));
            TextView label = text(route.name + "  •  " + route.points.size() + " نقطه اجباری", 14, true);
            row.addView(label);
            LinearLayout buttons = new LinearLayout(this);
            Button use = button("استفاده");
            Button show = button("نمایش");
            Button delete = button("حذف");
            buttons.addView(use, weight());
            buttons.addView(show, weight());
            buttons.addView(delete, weight());
            row.addView(buttons);
            use.setOnClickListener(v -> startRoute(route));
            show.setOnClickListener(v -> showRoute(route));
            delete.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("حذف مسیر")
                    .setMessage("مسیر «" + route.name + "» حذف شود؟")
                    .setNegativeButton("انصراف", null).setPositiveButton("حذف", (d, w) -> {
                        store.remove(route.id); renderSavedRoutes();
                    }).show());
            savedRoutesList.addView(row);
        }
    }

    private void renderTrips() {
        tripsList.removeAllViews();
        List<TripRecord> records = tripStore.recent(60);
        if (records.isEmpty()) {
            tripsList.addView(text("هنوز مسیر پیموده‌شده‌ای ثبت نشده است. ابتدا یک سفر حداقل حدود ۱۰۰ متر انجام دهید.", 14, false));
            return;
        }
        for (TripRecord record : records) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(6), dp(5), dp(6), dp(5));
            String title = "سفر به " + (record.destinationName == null || record.destinationName.isEmpty() ? "مقصد" : record.destinationName);
            String meta = record.traveledPath.size() + " نقطه GPS  •  " + record.traveledDistanceMeters + " متر";
            row.addView(text(title, 14, true));
            row.addView(text(meta, 13, false));
            Button show = button(record.traveledPath.size() >= 2 ? "نمایش مسیر روی نقشه" : "مسیر GPS کافی نیست");
            show.setEnabled(record.traveledPath.size() >= 2);
            row.addView(show, new LinearLayout.LayoutParams(-1, -2));
            show.setOnClickListener(v -> openTripMap(record));
            tripsList.addView(row);
        }
    }

    private void showRoute(PersonalRoute route) {
        showRoutesSection();
        clearDraft();
        draftPoints.addAll(route.points);
        for (RoutePoint p : draftPoints) {
            Marker marker = new Marker(new LatLng(p.latitude, p.longitude), null);
            draftMarkers.add(marker);
            map.addMarker(marker);
        }
        redrawDraft();
        fitRoute(route.points);
        draftInfo.setText(route.name + " — " + route.points.size() + " نقطه اجباری");
    }

    private void fitRoute(List<RoutePoint> points) {
        if (points == null || points.isEmpty()) return;
        try {
            org.osmdroid.util.BoundingBox box = new org.osmdroid.util.BoundingBox(
                    maxLat(points), maxLon(points), minLat(points), minLon(points));
            map.postDelayed(() -> map.zoomToBoundingBox(box, true, dp(40)), 150);
        } catch (Exception ignored) {
            RoutePoint p = points.get(0);
            map.moveCamera(new LatLng(p.latitude, p.longitude), 0);
        }
    }

    private void openTripMap(TripRecord record) {
        Intent intent = new Intent(this, TripMapActivity.class);
        try { intent.putExtra(TripMapActivity.EXTRA_TRIP_JSON, record.toJson().toString()); }
        catch (Exception e) { Toast.makeText(this, "اطلاعات سفر قابل نمایش نیست.", Toast.LENGTH_SHORT).show(); return; }
        startActivity(intent);
    }

    private void startRoute(PersonalRoute route) {
        if (route.points.size() < 2) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(ACTION_START_PERSONAL_ROUTE);
        intent.putExtra(EXTRA_PERSONAL_ROUTE_JSON, routeToJson(route));
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        Toast.makeText(this, "مسیر شخصی برای مسیریابی آماده شد.", Toast.LENGTH_SHORT).show();
    }

    private String routeToJson(PersonalRoute route) {
        try { return route.toJson().toString(); } catch (Exception e) { return ""; }
    }

    private double minLat(List<RoutePoint> p) { double v = Double.MAX_VALUE; for (RoutePoint x : p) v = Math.min(v, x.latitude); return v; }
    private double maxLat(List<RoutePoint> p) { double v = -Double.MAX_VALUE; for (RoutePoint x : p) v = Math.max(v, x.latitude); return v; }
    private double minLon(List<RoutePoint> p) { double v = Double.MAX_VALUE; for (RoutePoint x : p) v = Math.min(v, x.longitude); return v; }
    private double maxLon(List<RoutePoint> p) { double v = -Double.MAX_VALUE; for (RoutePoint x : p) v = Math.max(v, x.longitude); return v; }

    private TextView text(String value, int size, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }
    private Button button(String value) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); return b; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1f); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onResume() {
        super.onResume();
        if (map != null && map.isReadyForOverlays()) map.onResume();
        if (tripStore != null) renderTrips();
    }

    @Override protected void onPause() {
        if (map != null && map.isReadyForOverlays()) map.onPause();
        super.onPause();
    }
}
