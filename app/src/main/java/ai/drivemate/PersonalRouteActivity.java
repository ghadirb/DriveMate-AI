package ai.drivemate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import ai.drivemate.storage.PersonalRouteStore;

/** Create, inspect, delete and start user-created routes. Every intermediate point is a mandatory routing waypoint. */
public final class PersonalRouteActivity extends Activity {
    public static final String ACTION_START_PERSONAL_ROUTE = "ai.drivemate.action.START_PERSONAL_ROUTE";
    public static final String EXTRA_PERSONAL_ROUTE_JSON = "personal_route_json";

    private OsmMapView map;
    private PersonalRouteStore store;
    private final List<RoutePoint> draftPoints = new ArrayList<>();
    private final List<Marker> draftMarkers = new ArrayList<>();
    private Polyline draftLine;
    private LinearLayout list;
    private TextView draftInfo;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new PersonalRouteStore(this);
        buildUi();
        renderSavedRoutes();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("مسیرهای شخصی");
        title.setTextSize(21f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView help = new TextView(this);
        help.setText("برای ساخت مسیر، روی نقشه نقطه‌ها را به ترتیب لمس کنید. نقطه‌های میانی در مسیریابی اجباری هستند.");
        help.setTextSize(14f);
        help.setPadding(0, dp(6), 0, dp(8));
        root.addView(help, new LinearLayout.LayoutParams(-1, -2));

        map = new OsmMapView(this);
        map.setZoom(15f, 0);
        map.setOnMapLongClickListener(point -> addDraftPoint(point));
        root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1f));

        draftInfo = new TextView(this);
        draftInfo.setText("هنوز نقطه‌ای انتخاب نشده است.");
        draftInfo.setPadding(0, dp(8), 0, dp(4));
        root.addView(draftInfo, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button save = button("ذخیره مسیر");
        Button undo = button("حذف آخرین نقطه");
        Button clear = button("پاک کردن");
        actions.addView(save, weight());
        actions.addView(undo, weight());
        actions.addView(clear, weight());
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        save.setOnClickListener(v -> saveDraft());
        undo.setOnClickListener(v -> undoDraftPoint());
        clear.setOnClickListener(v -> clearDraft());

        TextView savedTitle = new TextView(this);
        savedTitle.setText("مسیرهای ذخیره‌شده");
        savedTitle.setTextSize(17f);
        savedTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        savedTitle.setPadding(0, dp(12), 0, dp(6));
        root.addView(savedTitle, new LinearLayout.LayoutParams(-1, -2));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(190)));

        setContentView(root);
    }

    private void addDraftPoint(LatLng point) {
        draftPoints.add(new RoutePoint(point.getLatitude(), point.getLongitude()));
        Marker marker = new Marker(point, null);
        draftMarkers.add(marker);
        map.addMarker(marker);
        redrawDraft();
        draftInfo.setText("تعداد نقاط: " + draftPoints.size() + " — همه نقاط میانی در مسیریابی اجباری هستند.");
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
        draftInfo.setText(draftPoints.isEmpty() ? "هنوز نقطه‌ای انتخاب نشده است." : "تعداد نقاط: " + draftPoints.size());
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
        list.removeAllViews();
        List<PersonalRoute> routes = store.all();
        if (routes.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("هنوز مسیر شخصی ذخیره نشده است.");
            list.addView(empty);
            return;
        }
        for (PersonalRoute route : routes) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(6), dp(8), dp(6));
            TextView text = new TextView(this);
            text.setText(route.name + "\n" + route.points.size() + " نقطه — نقاط میانی اجباری");
            text.setTextSize(15f);
            row.addView(text);
            LinearLayout buttons = new LinearLayout(this);
            Button use = button("استفاده در مسیریابی");
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
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void showRoute(PersonalRoute route) {
        clearDraft();
        draftPoints.addAll(route.points);
        for (RoutePoint p : draftPoints) {
            Marker marker = new Marker(new LatLng(p.latitude, p.longitude), null);
            draftMarkers.add(marker); map.addMarker(marker);
        }
        redrawDraft();
        if (!route.points.isEmpty()) map.moveCamera(new LatLng(route.points.get(0).latitude, route.points.get(0).longitude), 0);
        draftInfo.setText(route.name + " — " + route.points.size() + " نقطه");
    }

    private void startRoute(PersonalRoute route) {
        if (route.points.size() < 2) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(ACTION_START_PERSONAL_ROUTE);
        intent.putExtra(EXTRA_PERSONAL_ROUTE_JSON, routeToJson(route));
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        Toast.makeText(this, "مسیر شخصی برای شروع مسیریابی آماده شد.", Toast.LENGTH_SHORT).show();
    }

    private String routeToJson(PersonalRoute route) {
        try { return route.toJson().toString(); } catch (Exception e) { return ""; }
    }

    private Button button(String text) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); return b;
    }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1f); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
