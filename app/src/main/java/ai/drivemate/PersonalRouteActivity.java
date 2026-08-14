package ai.drivemate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
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
import ai.drivemate.storage.PersonalRouteStore;

/** Full-screen personal-route editor. Long-press the map to add mandatory waypoints. */
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
        root.setBackgroundColor(0xfff7f8fa);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setPadding(dp(14), dp(8), dp(14), dp(8));
        TextView title = text("مسیرهای شخصی", 20, true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        Button trips = button("مسیرهای پیموده‌شده");
        trips.setOnClickListener(v -> Toast.makeText(this,
                "مسیر پیموده‌شده هر سفر از کارت همان سفر با گزینه «نمایش مسیر روی نقشه» باز می‌شود.", Toast.LENGTH_LONG).show());
        toolbar.addView(trips, new LinearLayout.LayoutParams(-2, -2));
        root.addView(toolbar);

        LinearLayout mapFrame = new LinearLayout(this);
        mapFrame.setOrientation(LinearLayout.VERTICAL);
        map = new OsmMapView(this);
        map.setZoom(15f, 0);
        map.setOnMapLongClickListener(this::addDraftPoint);
        mapFrame.addView(map, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView hint = text("لمس طولانی روی نقشه = افزودن نقطه اجباری", 13, false);
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
        if (r.points.size() < 2) return;
        try {
            String json = r.toJson().toString();
            Intent i = new Intent(this, MainActivity.class);
            i.setAction(ACTION_START_PERSONAL_ROUTE);
            i.putExtra(EXTRA_PERSONAL_ROUTE_JSON, json);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (org.json.JSONException e) {
            Toast.makeText(this, "ذخیره مسیر برای مسیریابی قابل خواندن نیست.", Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(String s, int size, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); if (bold) t.setTypeface(null, Typeface.BOLD); return t; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1f); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
