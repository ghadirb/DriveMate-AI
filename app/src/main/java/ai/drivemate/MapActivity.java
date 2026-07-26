package ai.drivemate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.carto.graphics.Color;
import com.carto.styles.LineStyle;
import com.carto.styles.LineStyleBuilder;
import com.carto.styles.MarkerStyle;
import com.carto.styles.MarkerStyleBuilder;
import com.carto.utils.BitmapUtils;

import org.neshan.common.model.LatLng;
import org.neshan.mapsdk.MapView;
import org.neshan.mapsdk.model.Marker;
import org.neshan.mapsdk.model.Polyline;

import java.util.ArrayList;
import java.util.Locale;

import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RouteStep;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.routing.MapIrRoutingProvider;
import ai.drivemate.routing.NeshanRoutingProvider;
import ai.drivemate.routing.PlaceSearchRepository;
import ai.drivemate.routing.RouteRepository;
import ai.drivemate.storage.PlaceStore;

/** Map UI is isolated from the driving activity; it returns a selected destination to the existing engine. */
public class MapActivity extends Activity {
    public static final String EXTRA_ORIGIN_LATITUDE = "origin_latitude";
    public static final String EXTRA_ORIGIN_LONGITUDE = "origin_longitude";
    public static final String EXTRA_NESHAN_KEY = "neshan_key";
    public static final String EXTRA_MAPIR_KEY = "mapir_key";
    public static final String RESULT_LATITUDE = "destination_latitude";
    public static final String RESULT_LONGITUDE = "destination_longitude";
    public static final String RESULT_NAME = "destination_name";
    public static final String RESULT_ADDRESS = "destination_address";
    public static final String RESULT_START_VOICE = "start_voice";

    private static final double DEFAULT_LATITUDE = 35.7219;
    private static final double DEFAULT_LONGITUDE = 51.3347;
    private MapView map;
    private Marker currentMarker;
    private Marker destinationMarker;
    private Polyline routePolyline;
    private TextView destinationText;
    private TextView routeText;
    private EditText searchText;
    private SavedPlace destination;
    private double originLatitude;
    private double originLongitude;
    private PlaceSearchRepository placeSearchRepository;
    private RouteRepository routeRepository;
    private PlaceStore placeStore;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        placeStore = new PlaceStore(this);
        originLatitude = getIntent().getDoubleExtra(EXTRA_ORIGIN_LATITUDE, DEFAULT_LATITUDE);
        originLongitude = getIntent().getDoubleExtra(EXTRA_ORIGIN_LONGITUDE, DEFAULT_LONGITUDE);
        String neshanKey = getIntent().getStringExtra(EXTRA_NESHAN_KEY);
        String mapIrKey = getIntent().getStringExtra(EXTRA_MAPIR_KEY);
        NeshanRoutingProvider neshan = new NeshanRoutingProvider(neshanKey);
        MapIrRoutingProvider mapIr = new MapIrRoutingProvider(mapIrKey);
        placeSearchRepository = new PlaceSearchRepository(neshan, mapIr);
        routeRepository = new RouteRepository(neshan, mapIr);

        destinationText = findViewById(R.id.mapDestinationText);
        routeText = findViewById(R.id.mapRouteText);
        searchText = findViewById(R.id.mapSearchText);
        wireControls();
        initializeMap();
    }

    private void wireControls() {
        findViewById(R.id.mapSearchButton).setOnClickListener(v -> searchDestination());
        findViewById(R.id.mapVoiceButton).setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(RESULT_START_VOICE, true);
            setResult(RESULT_OK, result);
            finish();
        });
        findViewById(R.id.myLocationButton).setOnClickListener(v -> focusOrigin());
        findViewById(R.id.savedPlacesButton).setOnClickListener(v -> chooseSavedPlace());
        findViewById(R.id.startMapNavigationButton).setOnClickListener(v -> startSelectedDestination());
        searchText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { searchDestination(); return true; }
            return false;
        });
    }

    private void initializeMap() {
        if (getResources().getIdentifier("neshan", "raw", getPackageName()) == 0) {
            routeText.setText("فایل neshan.license در res/raw برنامه وجود ندارد.");
            return;
        }
        try {
            map = new MapView(this);
            ((FrameLayout) findViewById(R.id.mapContainer)).addView(map,
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            map.moveCamera(new LatLng(originLatitude, originLongitude), 0f);
            map.setZoom(14f, 0f);
            showCurrentMarker();
            map.setOnMapLongClickListener(point -> selectDestination(new SavedPlace(
                    "نقطه انتخاب‌شده روی نقشه", "map_pin", point.getLatitude(), point.getLongitude(),
                    String.format(Locale.US, "%.6f, %.6f", point.getLatitude(), point.getLongitude()), System.currentTimeMillis(), false)));
        } catch (LinkageError error) {
            // A malformed or stale SDK artifact must not close the app or block destination search.
            Log.e("DriveMateMap", "Neshan MapView runtime could not be loaded", error);
            map = null;
            routeText.setText("نقشه نشان آماده نشد؛ جست‌وجو و مکان‌های ذخیره‌شده همچنان در دسترس هستند.");
            Toast.makeText(this, "نمایش نقشه در این نسخه آماده نشد.", Toast.LENGTH_LONG).show();
        }
    }

    private void searchDestination() {
        String term = searchText.getText().toString().trim();
        if (term.isEmpty()) { Toast.makeText(this, "نام یا آدرس مقصد را وارد کنید.", Toast.LENGTH_SHORT).show(); return; }
        routeText.setText("در حال جست‌وجوی مقصد...");
        placeSearchRepository.search(term, originLatitude, originLongitude,
                place -> runOnUiThread(() -> selectDestination(place)),
                error -> runOnUiThread(() -> routeText.setText(error)));
    }

    private void chooseSavedPlace() {
        ArrayList<SavedPlace> places = new ArrayList<>(placeStore.allPlaces());
        if (places.isEmpty()) { Toast.makeText(this, "مکان ذخیره‌شده‌ای وجود ندارد.", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[places.size()];
        for (int i = 0; i < places.size(); i++) names[i] = places.get(i).name;
        new AlertDialog.Builder(this).setTitle("انتخاب مقصد ذخیره‌شده")
                .setItems(names, (dialog, which) -> selectDestination(places.get(which))).show();
    }

    private void selectDestination(SavedPlace place) {
        destination = place;
        destinationText.setText(place.name);
        routeText.setText("در حال آماده‌سازی پیش‌نمایش مسیر...");
        if (map != null) {
            if (destinationMarker != null) map.removeMarker(destinationMarker);
            destinationMarker = new Marker(new LatLng(place.latitude, place.longitude), markerStyle(0xff176b87));
            map.addMarker(destinationMarker);
            map.moveCamera(destinationMarker.getLatLng(), 0.25f);
            map.setZoom(15f, 0.25f);
        }
        routeRepository.getRoute(originLatitude, originLongitude, place.latitude, place.longitude,
                route -> runOnUiThread(() -> showRoutePreview(route)),
                error -> runOnUiThread(() -> routeText.setText("پیش‌نمایش مسیر در دسترس نیست: " + error)));
    }

    private void showRoutePreview(RouteResult route) {
        int minutes = Math.max(1, (int) Math.ceil(route.durationSeconds / 60.0));
        routeText.setText("مسیر پیشنهادی " + route.providerName + " | " + minutes + " دقیقه | "
                + String.format(Locale.US, "%.1f", route.distanceMeters / 1000.0) + " کیلومتر");
        if (map == null) return;
        if (routePolyline != null) map.removePolyline(routePolyline);
        ArrayList<LatLng> points = new ArrayList<>();
        points.add(new LatLng(originLatitude, originLongitude));
        for (RouteStep step : route.steps) points.add(new LatLng(step.latitude, step.longitude));
        if (points.size() > 1) {
            routePolyline = new Polyline(points, routeLineStyle());
            map.addPolyline(routePolyline);
        }
    }

    private void focusOrigin() {
        if (map == null) return;
        map.moveCamera(new LatLng(originLatitude, originLongitude), 0.25f);
        map.setZoom(15f, 0.25f);
    }

    private void showCurrentMarker() {
        if (map == null) return;
        if (currentMarker != null) map.removeMarker(currentMarker);
        currentMarker = new Marker(new LatLng(originLatitude, originLongitude), markerStyle(0xff2e8b57));
        map.addMarker(currentMarker);
    }

    private void startSelectedDestination() {
        if (destination == null) { Toast.makeText(this, "ابتدا مقصد را انتخاب کنید.", Toast.LENGTH_SHORT).show(); return; }
        Intent result = new Intent();
        result.putExtra(RESULT_LATITUDE, destination.latitude);
        result.putExtra(RESULT_LONGITUDE, destination.longitude);
        result.putExtra(RESULT_NAME, destination.name);
        result.putExtra(RESULT_ADDRESS, destination.address);
        setResult(RESULT_OK, result);
        finish();
    }

    private MarkerStyle markerStyle(int color) {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        canvas.drawCircle(32f, 32f, 22f, paint);
        paint.setColor(0xffffffff);
        canvas.drawCircle(32f, 32f, 9f, paint);
        MarkerStyleBuilder builder = new MarkerStyleBuilder();
        builder.setSize(34f);
        builder.setBitmap(BitmapUtils.createBitmapFromAndroidBitmap(bitmap));
        return builder.buildStyle();
    }

    private LineStyle routeLineStyle() {
        LineStyleBuilder builder = new LineStyleBuilder();
        builder.setColor(new Color((short) 23, (short) 107, (short) 135, (short) 230));
        builder.setWidth(9f);
        builder.setStretchFactor(0f);
        return builder.buildStyle();
    }
}
