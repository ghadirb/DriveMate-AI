package ai.drivemate;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.carto.graphics.Color;
import com.carto.styles.LineStyle;
import com.carto.styles.LineStyleBuilder;
import com.carto.styles.MarkerStyle;
import com.carto.styles.MarkerStyleBuilder;
import com.carto.utils.BitmapUtils;
import com.google.android.material.card.MaterialCardView;

import org.neshan.common.model.LatLng;
import org.neshan.mapsdk.MapView;
import org.neshan.mapsdk.model.Marker;
import org.neshan.mapsdk.model.Polyline;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteStep;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.routing.MapIrRoutingProvider;
import ai.drivemate.routing.NavigationEngine;
import ai.drivemate.routing.NeshanRoutingProvider;
import ai.drivemate.routing.PlaceSearchRepository;
import ai.drivemate.routing.RouteRepository;
import ai.drivemate.storage.PlaceStore;

/** Map UI is isolated from the driving activity; it returns a selected destination to the existing engine. */
public class MapActivity extends Activity implements LocationListener, NavigationEngine.Listener {
    public static final String EXTRA_ORIGIN_LATITUDE = "origin_latitude";
    public static final String EXTRA_ORIGIN_LONGITUDE = "origin_longitude";
    public static final String EXTRA_NESHAN_KEY = "neshan_key";
    public static final String EXTRA_MAPIR_KEY = "mapir_key";
    public static final String RESULT_LATITUDE = "destination_latitude";
    public static final String RESULT_LONGITUDE = "destination_longitude";
    public static final String RESULT_NAME = "destination_name";
    public static final String RESULT_ADDRESS = "destination_address";
    public static final String RESULT_START_VOICE = "start_voice";
    public static final String RESULT_OPEN_NAVIGATION_MAP = "open_navigation_map";
    public static final String RESULT_ROUTE_INDEX = "route_index";
    public static final String RESULT_STOP_NAVIGATION = "stop_navigation";
    public static final String EXTRA_NAVIGATION_MODE = "navigation_mode";
    public static final String EXTRA_DESTINATION_LATITUDE = "navigation_destination_latitude";
    public static final String EXTRA_DESTINATION_LONGITUDE = "navigation_destination_longitude";
    public static final String EXTRA_DESTINATION_NAME = "navigation_destination_name";
    public static final String EXTRA_DESTINATION_ADDRESS = "navigation_destination_address";
    public static final String EXTRA_NAVIGATION_ROUTE_INDEX = "navigation_route_index";

    private static final double DEFAULT_LATITUDE = 35.7219;
    private static final double DEFAULT_LONGITUDE = 51.3347;
    private MapView map;
    private Marker currentMarker;
    private Marker vehicleMarker;
    private Marker destinationMarker;
    private Polyline routePolyline;
    private List<RouteResult> routeOptions = new ArrayList<>();
    private RouteResult selectedRoute;
    private TextView destinationText;
    private TextView routeText;
    private EditText searchText;
    private Button searchClearButton;
    private ProgressBar searchProgress;
    private ScrollView searchResultsPanel;
    private LinearLayout searchResultsContent;
    private SavedPlace destination;
    private double originLatitude;
    private double originLongitude;
    private PlaceSearchRepository placeSearchRepository;
    private RouteRepository routeRepository;
    private PlaceStore placeStore;
    private LocationManager locationManager;
    private boolean navigationMode;
    private boolean followVehicle = true;
    private float lastBearing;
    private boolean hasHeading;
    private boolean navigationCameraEnabled;
    private int navigationRouteIndex;
    private final NavigationEngine navigationEngine = new NavigationEngine();
    private final SimpleDateFormat etaFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private View turnBannerContainer;
    private TextView turnArrowText;
    private TextView turnDistanceText;
    private TextView turnInstructionText;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSuggestionSearch;
    private int activeSearchRequest;
    private boolean selectingSearchResult;
    private boolean orientationWarningLogged;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        placeStore = new PlaceStore(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        originLatitude = getIntent().getDoubleExtra(EXTRA_ORIGIN_LATITUDE, DEFAULT_LATITUDE);
        originLongitude = getIntent().getDoubleExtra(EXTRA_ORIGIN_LONGITUDE, DEFAULT_LONGITUDE);
        navigationMode = getIntent().getBooleanExtra(EXTRA_NAVIGATION_MODE, false);
        navigationRouteIndex = Math.max(0, getIntent().getIntExtra(EXTRA_NAVIGATION_ROUTE_INDEX, 0));
        String neshanKey = getIntent().getStringExtra(EXTRA_NESHAN_KEY);
        String mapIrKey = getIntent().getStringExtra(EXTRA_MAPIR_KEY);
        NeshanRoutingProvider neshan = new NeshanRoutingProvider(neshanKey);
        MapIrRoutingProvider mapIr = new MapIrRoutingProvider(mapIrKey);
        placeSearchRepository = new PlaceSearchRepository(neshan, mapIr);
        routeRepository = new RouteRepository(neshan, mapIr);

        destinationText = findViewById(R.id.mapDestinationText);
        routeText = findViewById(R.id.mapRouteText);
        searchText = findViewById(R.id.mapSearchText);
        searchClearButton = findViewById(R.id.mapSearchClearButton);
        searchProgress = findViewById(R.id.mapSearchProgress);
        searchResultsPanel = findViewById(R.id.searchResultsPanel);
        searchResultsContent = findViewById(R.id.searchResultsContent);
        turnBannerContainer = findViewById(R.id.turnBannerContainer);
        turnArrowText = findViewById(R.id.turnArrowText);
        turnDistanceText = findViewById(R.id.turnDistanceText);
        turnInstructionText = findViewById(R.id.turnInstructionText);
        turnBannerContainer.setOnClickListener(v -> showTurnByTurnSteps());
        wireControls();
        initializeMap();
        if (navigationMode) {
            navigationCameraEnabled = true;
            findViewById(R.id.startMapNavigationButton).setEnabled(true);
            ((Button) findViewById(R.id.startMapNavigationButton)).setText("بازگشت به داشبورد");
            SavedPlace active = new SavedPlace(getIntent().getStringExtra(EXTRA_DESTINATION_NAME), "active_navigation",
                    getIntent().getDoubleExtra(EXTRA_DESTINATION_LATITUDE, 0d),
                    getIntent().getDoubleExtra(EXTRA_DESTINATION_LONGITUDE, 0d),
                    getIntent().getStringExtra(EXTRA_DESTINATION_ADDRESS), System.currentTimeMillis(), false);
            if (active.latitude != 0d && active.longitude != 0d) selectDestinationWithOptions(active);
            findViewById(R.id.stopMapNavigationButton).setVisibility(View.VISIBLE);
            findViewById(R.id.drivingOverviewButton).setVisibility(View.VISIBLE);
            findViewById(R.id.navigationCameraButton).setVisibility(View.VISIBLE);
            findViewById(R.id.routeOptionsButton).setVisibility(View.GONE);
            findViewById(R.id.saveMapPlaceButton).setVisibility(View.GONE);
            findViewById(R.id.mapSearchBarRow).setVisibility(View.GONE);
            findViewById(R.id.savedPlacesButton).setVisibility(View.GONE);
        }
    }

    private void wireControls() {
        findViewById(R.id.mapSearchButton).setOnClickListener(v -> searchDestinations());
        searchClearButton.setOnClickListener(v -> {
            searchText.setText("");
            searchText.requestFocus();
            showRecentSearches();
        });
        findViewById(R.id.mapCloseButton).setOnClickListener(v -> finish());
        findViewById(R.id.myLocationButton).setOnClickListener(v -> focusOrigin());
        findViewById(R.id.savedPlacesButton).setOnClickListener(v -> chooseSavedPlace());
        findViewById(R.id.saveMapPlaceButton).setOnClickListener(v -> saveSelectedPlace());
        findViewById(R.id.routeOptionsButton).setOnClickListener(v -> chooseRouteOption());
        findViewById(R.id.drivingOverviewButton).setOnClickListener(v -> showRouteOverview());
        View navigationCameraButton = findViewById(R.id.navigationCameraButton);
        navigationCameraButton.setTooltipText("نمای رانندگی و دنبال کردن خودرو");
        navigationCameraButton.setOnClickListener(v -> enableNavigationCamera());
        findViewById(R.id.stopMapNavigationButton).setOnClickListener(v -> stopNavigationFromMap());
        findViewById(R.id.startMapNavigationButton).setOnClickListener(v -> {
            if (navigationMode) finish(); else startSelectedDestination();
        });
        searchText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { searchDestinations(); return true; }
            return false;
        });
        searchText.setOnFocusChangeListener((view, focused) -> {
            if (focused && searchText.getText().toString().trim().isEmpty()) showRecentSearches();
        });
        searchText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                String term = value == null ? "" : value.toString().trim();
                searchClearButton.setVisibility(term.isEmpty() ? View.GONE : View.VISIBLE);
                if (selectingSearchResult) return;
                activeSearchRequest++;
                if (pendingSuggestionSearch != null) searchHandler.removeCallbacks(pendingSuggestionSearch);
                if (term.isEmpty()) {
                    setSearchLoading(false);
                    showRecentSearches();
                    return;
                }
                if (term.length() < 2) {
                    hideSearchResults();
                    return;
                }
                pendingSuggestionSearch = () -> performSearch(term, false);
                searchHandler.postDelayed(pendingSuggestionSearch, 350L);
            }
            @Override public void afterTextChanged(Editable value) { }
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
            map.setOnMapLongClickListener(point -> {
                final double latitude = point.getLatitude();
                final double longitude = point.getLongitude();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    try {
                        selectDestinationWithOptions(new SavedPlace(
                                "نقطه انتخاب‌شده روی نقشه", "map_pin", latitude, longitude,
                                String.format(Locale.US, "%.6f, %.6f", latitude, longitude), System.currentTimeMillis(), false));
                    } catch (RuntimeException error) {
                        Log.e("DriveMateMap", "Could not select map point", error);
                        routeText.setText("انتخاب نقطه روی نقشه انجام نشد. دوباره تلاش کنید.");
                    }
                });
            });
        } catch (LinkageError error) {
            // A malformed or stale SDK artifact must not close the app or block destination search.
            Log.e("DriveMateMap", "Neshan MapView runtime could not be loaded", error);
            map = null;
            routeText.setText("نقشه نشان آماده نشد؛ جست‌وجو و مکان‌های ذخیره‌شده همچنان در دسترس هستند.");
            Toast.makeText(this, "نمایش نقشه در این نسخه آماده نشد.", Toast.LENGTH_LONG).show();
        }
    }

    private void searchDestinations() {
        String term = searchText.getText().toString().trim();
        if (term.isEmpty()) {
            showRecentSearches();
            return;
        }
        if (pendingSuggestionSearch != null) searchHandler.removeCallbacks(pendingSuggestionSearch);
        performSearch(term, true);
    }

    private void performSearch(String term, boolean userInitiated) {
        final int requestId = ++activeSearchRequest;
        setSearchLoading(true);
        if (userInitiated) routeText.setText("در حال جست‌وجوی مقصد...");
        placeSearchRepository.searchAll(term, originLatitude, originLongitude,
                places -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || requestId != activeSearchRequest) return;
                    setSearchLoading(false);
                    showSearchResults(places, term);
                }),
                error -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || requestId != activeSearchRequest) return;
                    setSearchLoading(false);
                    if (userInitiated) routeText.setText(error);
                    hideSearchResults();
                }));
    }

    private void showSearchResults(List<SavedPlace> places, String query) {
        if (places == null || places.isEmpty()) {
            searchResultsContent.removeAllViews();
            addSectionTitle("نتیجه‌ای پیدا نشد");
            showSearchResultsPanel();
            return;
        }
        searchResultsContent.removeAllViews();
        LinkedHashMap<String, List<SavedPlace>> groups = new LinkedHashMap<>();
        for (SavedPlace place : places) {
            String group = placeGroup(place);
            List<SavedPlace> groupItems = groups.get(group);
            if (groupItems == null) {
                groupItems = new ArrayList<>();
                groups.put(group, groupItems);
            }
            groupItems.add(place);
        }
        for (Map.Entry<String, List<SavedPlace>> entry : groups.entrySet()) {
            addSectionTitle(entry.getKey());
            for (SavedPlace place : entry.getValue()) addSearchResultCard(place, query);
        }
        showSearchResultsPanel();
    }

    private void showRecentSearches() {
        if (navigationMode) return;
        List<SavedPlace> recent = placeStore.recentPlaces();
        if (recent == null || recent.isEmpty()) {
            hideSearchResults();
            return;
        }
        searchResultsContent.removeAllViews();
        addSectionTitle("جست‌وجوهای اخیر");
        int limit = Math.min(6, recent.size());
        for (int i = 0; i < limit; i++) addSearchResultCard(recent.get(i), "");
        showSearchResultsPanel();
    }

    private void addSectionTitle(String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(0xff3f5362);
        header.setTextSize(14f);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setPadding(dp(8), dp(10), dp(8), dp(4));
        searchResultsContent.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addSearchResultCard(SavedPlace place, String query) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(dp(2));
        card.setRadius(dp(14));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(0xffd9e2e8);
        card.setCardBackgroundColor(0xffffffff);
        card.setRippleColor(ColorStateList.valueOf(0x223d8fb0));
        card.setClickable(true);
        card.setFocusable(true);
        card.setUseCompatPadding(true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(3), 0, dp(7));
        searchResultsContent.addView(card, cardParams);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));
        card.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView icon = new TextView(this);
        icon.setText(placeIcon(place));
        icon.setGravity(android.view.Gravity.CENTER);
        icon.setTextSize(23f);
        icon.setTextColor(0xff176b87);
        row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(46)));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), 0, dp(4), 0);
        row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(highlight(place.name == null ? "مکان بدون نام" : place.name, query));
        title.setTextColor(0xff16222b);
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(1);
        content.addView(title);

        String hierarchy = administrativeHierarchy(place);
        if (!hierarchy.isEmpty()) {
            TextView subtitle = new TextView(this);
            subtitle.setText(highlight(hierarchy, query));
            subtitle.setTextColor(0xff566a78);
            subtitle.setTextSize(13f);
            subtitle.setMaxLines(3);
            subtitle.setPadding(0, dp(3), 0, 0);
            content.addView(subtitle);
        }

        TextView metadata = new TextView(this);
        metadata.setText("\u2570 " + formatDistance(place) + " - " + placeTypeLabel(place));
        metadata.setTextColor(0xff71838e);
        metadata.setTextSize(12f);
        metadata.setPadding(0, dp(6), 0, 0);
        content.addView(metadata);

        card.setOnClickListener(view -> {
            placeStore.addRecent(place);
            selectingSearchResult = true;
            searchText.setText(place.name == null ? "" : place.name);
            selectingSearchResult = false;
            closeSearchUi();
            selectDestinationWithOptions(place);
        });
        if (query == null || query.trim().isEmpty()) {
            card.setOnLongClickListener(view -> {
                editRecentSearch(place);
                return true;
            });
        }
    }

    private void editRecentSearch(SavedPlace place) {
        new AlertDialog.Builder(this)
                .setTitle("جست‌وجوی اخیر")
                .setItems(new String[]{"ویرایش نام", "حذف از تاریخچه"}, (dialog, action) -> {
                    if (action == 1) {
                        placeStore.removeRecent(place);
                        showRecentSearches();
                        return;
                    }
                    EditText input = new EditText(this);
                    input.setSingleLine(true);
                    input.setText(place.name);
                    new AlertDialog.Builder(this)
                            .setTitle("ویرایش نام مکان")
                            .setView(input)
                            .setPositiveButton("ذخیره", (saveDialog, which) -> {
                                String name = input.getText().toString().trim();
                                if (!name.isEmpty()) placeStore.renameRecent(place, name);
                                showRecentSearches();
                            })
                            .setNegativeButton("انصراف", null)
                            .show();
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    private void showSearchResultsPanel() {
        if (searchResultsPanel.getVisibility() != View.VISIBLE) {
            searchResultsPanel.setAlpha(0f);
            searchResultsPanel.setVisibility(View.VISIBLE);
            searchResultsPanel.animate().alpha(1f).setDuration(160L).start();
        }
        searchResultsPanel.post(() -> searchResultsPanel.fullScroll(View.FOCUS_UP));
    }

    private void hideSearchResults() {
        if (searchResultsPanel.getVisibility() != View.VISIBLE) return;
        searchResultsPanel.animate().alpha(0f).setDuration(120L).withEndAction(() -> {
            searchResultsPanel.setVisibility(View.GONE);
            searchResultsPanel.setAlpha(1f);
        }).start();
    }

    private void closeSearchUi() {
        if (pendingSuggestionSearch != null) searchHandler.removeCallbacks(pendingSuggestionSearch);
        activeSearchRequest++;
        setSearchLoading(false);
        hideSearchResults();
        searchText.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(searchText.getWindowToken(), 0);
    }

    private void setSearchLoading(boolean loading) {
        searchProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private String placeGroup(SavedPlace place) {
        String type = placeTypeLabel(place);
        return "مکان‌های " + type;
    }

    private String placeTypeLabel(SavedPlace place) {
        String value = normalizeForUi((place.name == null ? "" : place.name) + " "
                + (place.address == null ? "" : place.address) + " " + (place.kind == null ? "" : place.kind));
        if (value.contains("پمپ بنزین") || value.contains("جایگاه سوخت")) return "سوخت";
        if (value.contains("بیمارستان") || value.contains("درمانگاه") || value.contains("داروخانه")) return "درمانی";
        if (value.contains("رستوران") || value.contains("کافه")) return "غذا و نوشیدنی";
        if (value.contains("روستا") || value.contains("village")) return "روستا";
        if (value.contains("شهر") || value.contains("بخش") || value.contains("استان")
                || value.contains("city") || value.contains("county")) return "شهر و منطقه";
        return "مکان‌ها";
    }

    private String placeIcon(SavedPlace place) {
        String type = placeTypeLabel(place);
        if ("سوخت".equals(type)) return "\u26fd";
        if ("درمانی".equals(type)) return "\u2695";
        if ("غذا و نوشیدنی".equals(type)) return "\u2615";
        if ("روستا".equals(type)) return "\u2302";
        if ("شهر و منطقه".equals(type)) return "\u25c9";
        return "\u25cf";
    }

    private String administrativeHierarchy(SavedPlace place) {
        String address = place.address == null ? "" : place.address.trim();
        String type = placeTypeLabel(place);
        String prefix = "";
        if ("روستا".equals(type) && !normalizeForUi(address).contains("روستا")) prefix = "روستای " + place.name;
        else if ("شهر و منطقه".equals(type) && !normalizeForUi(address).contains("شهر")) prefix = "شهر یا منطقه";
        if (address.isEmpty()) return prefix;
        String[] parts = address.split("[،,]");
        StringBuilder hierarchy = new StringBuilder(prefix);
        for (int i = 0; i < parts.length && i < 4; i++) {
            String part = parts[i].trim();
            if (part.isEmpty() || part.equals(place.name)) continue;
            if (hierarchy.length() > 0) hierarchy.append('\n');
            hierarchy.append(part);
        }
        return hierarchy.toString();
    }

    private String formatDistance(SavedPlace place) {
        double distanceKm = distanceKm(originLatitude, originLongitude, place.latitude, place.longitude);
        if (distanceKm < 1d) return Math.max(1, (int) Math.round(distanceKm * 1000d)) + " متر تا شما";
        return String.format(Locale.US, "%.1f کیلومتر تا شما", distanceKm);
    }

    private double distanceKm(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double value = Math.sin(latitudeDelta / 2d) * Math.sin(latitudeDelta / 2d)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(longitudeDelta / 2d) * Math.sin(longitudeDelta / 2d);
        return 6371d * 2d * Math.atan2(Math.sqrt(value), Math.sqrt(1d - value));
    }

    private CharSequence highlight(String value, String query) {
        SpannableString styled = new SpannableString(value == null ? "" : value);
        if (query == null || query.trim().isEmpty()) return styled;
        String lowerValue = normalizeForHighlight(styled.toString());
        String lowerQuery = normalizeForHighlight(query.trim());
        int start = lowerValue.indexOf(lowerQuery);
        if (start >= 0) {
            int end = start + lowerQuery.length();
            styled.setSpan(new ForegroundColorSpan(0xff087e8b), start, end, 0);
            styled.setSpan(new StyleSpan(Typeface.BOLD), start, end, 0);
        }
        return styled;
    }

    private String normalizeForUi(String value) {
        if (value == null) return "";
        return value.replace('\u064a', '\u06cc').replace('\u0649', '\u06cc').replace('\u0643', '\u06a9')
                .replace("\u200c", " ").trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String normalizeForHighlight(String value) {
        if (value == null) return "";
        return value.replace('\u064a', '\u06cc').replace('\u0649', '\u06cc').replace('\u0643', '\u06a9')
                .replace("\u200c", " ").toLowerCase(Locale.ROOT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void searchDestination() {
        String term = searchText.getText().toString().trim();
        if (term.isEmpty()) { Toast.makeText(this, "نام یا آدرس مقصد را وارد کنید.", Toast.LENGTH_SHORT).show(); return; }
        routeText.setText("در حال جست‌وجوی مقصد...");
        placeSearchRepository.search(term, originLatitude, originLongitude,
                place -> runOnUiThread(() -> selectDestinationWithOptions(place)),
                error -> runOnUiThread(() -> routeText.setText(error)));
    }

    private void chooseSavedPlace() {
        ArrayList<SavedPlace> places = new ArrayList<>(placeStore.allPlaces());
        if (places.isEmpty()) { Toast.makeText(this, "مکان ذخیره‌شده‌ای وجود ندارد.", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[places.size()];
        for (int i = 0; i < places.size(); i++) names[i] = places.get(i).name;
        new AlertDialog.Builder(this).setTitle("انتخاب مقصد ذخیره‌شده")
                .setItems(names, (dialog, which) -> selectDestinationWithOptions(places.get(which))).show();
    }

    private void selectDestinationWithOptions(SavedPlace place) {
        destination = place;
        destinationText.setText(place.name);
        routeText.setText("در حال آماده‌سازی مسیرهای پیشنهادی...");
        showDestinationMarker(place);
        routeRepository.getRoutes(originLatitude, originLongitude, place.latitude, place.longitude,
                routes -> runOnUiThread(() -> showRouteOptions(routes)),
                error -> runOnUiThread(() -> routeText.setText("دریافت مسیر انجام نشد: " + error)));
    }

    private void showDestinationMarker(SavedPlace place) {
        if (map == null) return;
        if (destinationMarker != null) map.removeMarker(destinationMarker);
        destinationMarker = new Marker(new LatLng(place.latitude, place.longitude), markerStyle(0xff176b87));
        map.addMarker(destinationMarker);
        map.moveCamera(destinationMarker.getLatLng(), 0.25f);
        map.setZoom(15f, 0.25f);
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

    private void showRouteOptions(List<RouteResult> routes) {
        routeOptions = routes == null ? new ArrayList<>() : new ArrayList<>(routes);
        if (routeOptions.isEmpty()) {
            routeText.setText("مسیر قابل استفاده‌ای پیدا نشد.");
            return;
        }
        selectedRoute = routeOptions.get(navigationMode
                ? Math.min(navigationRouteIndex, routeOptions.size() - 1) : 0);
        showRoutePreview(selectedRoute);
        if (navigationMode) startTurnByTurn(selectedRoute);
        if (!navigationMode && routeOptions.size() > 1) chooseRouteOption();
    }

    private void chooseRouteOption() {
        if (routeOptions.isEmpty()) {
            Toast.makeText(this, "ابتدا یک مقصد انتخاب کنید.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[routeOptions.size()];
        for (int i = 0; i < routeOptions.size(); i++) {
            RouteResult route = routeOptions.get(i);
            labels[i] = "مسیر " + (i + 1) + " - " + Math.max(1, (int) Math.ceil(route.durationSeconds / 60.0))
                    + " دقیقه - " + String.format(Locale.US, "%.1f", route.distanceMeters / 1000.0) + " کیلومتر";
        }
        new AlertDialog.Builder(this).setTitle("مسیرهای پیشنهادی")
                .setSingleChoiceItems(labels, Math.max(0, routeOptions.indexOf(selectedRoute)), (dialog, which) -> {
                    selectedRoute = routeOptions.get(which);
                    showRoutePreview(selectedRoute);
                    dialog.dismiss();
                }).show();
    }

    private void showRoutePreview(RouteResult route) {
        int minutes = Math.max(1, (int) Math.ceil(route.durationSeconds / 60.0));
        routeText.setText("مسیر پیشنهادی " + route.providerName + " | " + minutes + " دقیقه | "
                + String.format(Locale.US, "%.1f", route.distanceMeters / 1000.0) + " کیلومتر");
        if (map == null) return;
        if (routePolyline != null) map.removePolyline(routePolyline);
        ArrayList<LatLng> points = new ArrayList<>();
        for (RoutePoint point : route.geometry) points.add(new LatLng(point.latitude, point.longitude));
        if (points.size() < 2) {
            points.add(new LatLng(originLatitude, originLongitude));
            for (RouteStep step : route.steps) points.add(new LatLng(step.latitude, step.longitude));
        }
        if (points.size() > 1) {
            routePolyline = new Polyline(points, routeLineStyle());
            map.addPolyline(routePolyline);
            if (!navigationMode) {
                map.moveCamera(new LatLng(originLatitude, originLongitude), 0.25f);
                map.setZoom(12.5f, 0.25f);
            }
        }
    }

    /** Starts real turn-by-turn tracking for the active route: shows the first maneuver right
     *  away and keeps the banner in sync with this activity's own location stream. This engine
     *  instance is independent from MainActivity's (which drives voice guidance), so opening or
     *  closing the map never disturbs the background voice session. */
    private void startTurnByTurn(RouteResult route) {
        Location current = new Location("gps");
        current.setLatitude(originLatitude);
        current.setLongitude(originLongitude);
        current.setBearing(lastBearing);
        navigationEngine.start(route, this, current);
        turnBannerContainer.setVisibility(View.VISIBLE);
        turnDistanceText.setText("");
        if (!navigationEngine.announceCurrentInstruction()) {
            turnInstructionText.setText("به سمت مقصد حرکت کنید");
            turnArrowText.setText("↑");
        }
        enableNavigationCamera();
    }

    /** Live per-tick update: distance to the upcoming maneuver plus the driving HUD line. The
     *  instruction text itself only changes through onInstruction, so it never flickers between
     *  GPS samples. */
    private void updateTurnBanner(Location location) {
        RouteStep step = navigationEngine.currentStep();
        if (step == null) return;
        Location target = new Location("route");
        target.setLatitude(step.latitude);
        target.setLongitude(step.longitude);
        float metersToTurn = location.distanceTo(target);
        turnDistanceText.setText(formatDistance(Math.round(metersToTurn)));
        updateDrivingHud(metersToTurn);
    }

    /** Approximates remaining distance/time by adding the live distance to the next maneuver to
     *  the provider's per-step distances for every maneuver still ahead, then scales the route's
     *  total duration by that same fraction. It is an estimate (no live traffic per segment), but
     *  it moves with the car instead of freezing at the numbers shown when the route was chosen. */
    private void updateDrivingHud(float metersToCurrentTarget) {
        if (selectedRoute == null || selectedRoute.steps.isEmpty()) return;
        int index = navigationEngine.currentStepIndex();
        int remainingMeters = Math.round(metersToCurrentTarget);
        for (int i = index + 1; i < selectedRoute.steps.size(); i++) {
            remainingMeters += selectedRoute.steps.get(i).distanceMeters;
        }
        int totalMeters = Math.max(1, selectedRoute.distanceMeters);
        double fraction = Math.max(0.02, Math.min(1.0, remainingMeters / (double) totalMeters));
        int remainingSeconds = (int) Math.round(selectedRoute.durationSeconds * fraction);
        long arrivalAt = System.currentTimeMillis() + remainingSeconds * 1000L;
        routeText.setText(formatDistance(remainingMeters) + " مانده • "
                + formatDuration(remainingSeconds) + " دیگر • رسیدن ساعت " + etaFormat.format(new Date(arrivalAt)));
    }

    private String formatDistance(int meters) {
        if (meters < 1000) return Math.max(0, meters) + " متر";
        return String.format(Locale.US, "%.1f کیلومتر", meters / 1000.0);
    }

    private String formatDuration(int seconds) {
        int minutes = Math.max(1, Math.round(seconds / 60f));
        return minutes + " دقیقه";
    }

    /** Provider instructions are free-form Persian text (no maneuver-type enum), so the arrow is a
     *  best-effort keyword match rather than an exact turn code. */
    private String arrowForInstruction(String instruction) {
        if (instruction == null) return "↑";
        if (instruction.contains("راست")) return "↗";
        if (instruction.contains("چپ")) return "↖";
        if (instruction.contains("دوربرگردان") || instruction.contains("برگردان")) return "↺";
        if (instruction.contains("خروج")) return "⤴";
        if (instruction.contains("میدان") || instruction.contains("فلکه")) return "↻";
        return "↑";
    }

    /** Re-fetches a route from the current position after an off-route detection and restarts
     *  turn-by-turn on it, instead of silently continuing to track a maneuver the driver has
     *  already left behind. */
    private void recalculateActiveRoute() {
        if (destination == null) return;
        routeRepository.getRoute(originLatitude, originLongitude, destination.latitude, destination.longitude,
                route -> runOnUiThread(() -> {
                    selectedRoute = route;
                    showRoutePreview(route);
                    startTurnByTurn(route);
                }),
                error -> runOnUiThread(() -> turnInstructionText.setText("بازیابی مسیر انجام نشد: " + error)));
    }

    @Override public void onInstruction(RouteStep step) {
        runOnUiThread(() -> {
            String instruction = step.instruction == null || step.instruction.isEmpty()
                    ? "ادامه در مسیر" : step.instruction;
            turnInstructionText.setText(instruction);
            turnArrowText.setText(arrowForInstruction(instruction));
        });
    }

    @Override public void onOffRoute() {
        runOnUiThread(() -> {
            turnInstructionText.setText("در حال محاسبه مجدد مسیر...");
            turnArrowText.setText("↻");
        });
        recalculateActiveRoute();
    }

    @Override public void onArrived() {
        runOnUiThread(() -> {
            turnInstructionText.setText("به مقصد رسیدید");
            turnDistanceText.setText("");
            turnArrowText.setText("●");
            routeText.setText("سفر به پایان رسید.");
        });
    }

    private void focusOrigin() {
        if (!isLocationEnabled()) {
            Toast.makeText(this, "مکان گوشی خاموش است. آن را روشن کنید.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        if (navigationMode) {
            followVehicle = true;
            navigationCameraEnabled = true;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location latest = gps != null ? gps : network;
            if (latest != null) {
                originLatitude = latest.getLatitude();
                originLongitude = latest.getLongitude();
                showCurrentMarker();
            }
        }
        if (navigationMode && navigationCameraEnabled) {
            updateNavigationCamera();
            return;
        }
        if (map == null) return;
        map.moveCamera(new LatLng(originLatitude, originLongitude), 0.25f);
        map.setZoom(15f, 0.25f);
    }

    private boolean isLocationEnabled() {
        return locationManager != null && (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    private void showCurrentMarker() {
        if (map == null) return;
        if (navigationMode) {
            showVehicleMarker(lastBearing);
            return;
        }
        if (currentMarker != null) map.removeMarker(currentMarker);
        currentMarker = new Marker(new LatLng(originLatitude, originLongitude), markerStyle(0xff2e8b57));
        map.addMarker(currentMarker);
    }

    private void showVehicleMarker(float bearing) {
        if (map == null) return;
        if (vehicleMarker != null) map.removeMarker(vehicleMarker);
        vehicleMarker = new Marker(drivingPosition(), vehicleMarkerStyle(bearing));
        map.addMarker(vehicleMarker);
    }

    private LatLng drivingPosition() {
        if (selectedRoute == null || selectedRoute.geometry.isEmpty()) {
            return new LatLng(originLatitude, originLongitude);
        }
        RoutePoint nearest = null;
        float nearestMeters = Float.MAX_VALUE;
        Location current = new Location("gps");
        current.setLatitude(originLatitude);
        current.setLongitude(originLongitude);
        for (RoutePoint point : selectedRoute.geometry) {
            Location candidate = new Location("route");
            candidate.setLatitude(point.latitude);
            candidate.setLongitude(point.longitude);
            float meters = current.distanceTo(candidate);
            if (meters < nearestMeters) {
                nearestMeters = meters;
                nearest = point;
            }
        }
        return nearest != null && nearestMeters <= 45f
                ? new LatLng(nearest.latitude, nearest.longitude)
                : new LatLng(originLatitude, originLongitude);
    }

    private void showRouteOverview() {
        if (map == null || selectedRoute == null || selectedRoute.geometry.isEmpty()) return;
        followVehicle = false;
        navigationCameraEnabled = false;
        applyMapOrientation(0f, 90f, 0.2f);
        map.moveCamera(new LatLng(originLatitude, originLongitude), 0.25f);
        map.setZoom(12.5f, 0.25f);
    }

    /** Restores the driver-first viewport: the vehicle stays below center and the road points up. */
    private void enableNavigationCamera() {
        if (!navigationMode) return;
        navigationCameraEnabled = true;
        followVehicle = true;
        updateNavigationCamera();
    }

    private void updateNavigationCamera() {
        if (map == null || !navigationMode || !navigationCameraEnabled) return;
        float heading = navigationHeading();
        LatLng vehiclePosition = drivingPosition();
        LatLng cameraTarget = pointAhead(vehiclePosition, heading, 68d);
        applyMapOrientation(heading, 58f, 0.28f);
        map.moveCamera(cameraTarget, 0.28f);
        map.setZoom(17.25f, 0.28f);
    }

    private float navigationHeading() {
        LatLng position = drivingPosition();
        if (selectedRoute != null) {
            int nearestIndex = -1;
            float nearestDistance = Float.MAX_VALUE;
            for (int i = 0; i < selectedRoute.geometry.size(); i++) {
                RoutePoint point = selectedRoute.geometry.get(i);
                Location from = new Location("route");
                from.setLatitude(position.getLatitude());
                from.setLongitude(position.getLongitude());
                Location to = new Location("route");
                to.setLatitude(point.latitude);
                to.setLongitude(point.longitude);
                float distance = from.distanceTo(to);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestIndex = i;
                }
            }
            if (nearestIndex >= 0) {
                Location from = new Location("route");
                from.setLatitude(position.getLatitude());
                from.setLongitude(position.getLongitude());
                float accumulated = 0f;
                for (int i = nearestIndex + 1; i < selectedRoute.geometry.size(); i++) {
                    RoutePoint point = selectedRoute.geometry.get(i);
                    Location to = new Location("route");
                    to.setLatitude(point.latitude);
                    to.setLongitude(point.longitude);
                    accumulated += from.distanceTo(to);
                    if (accumulated >= 28f) return from.bearingTo(to);
                    from = to;
                }
            }
        }
        return lastBearing;
    }

    private void showTurnByTurnSteps() {
        if (selectedRoute == null || selectedRoute.steps.isEmpty()) return;
        String[] steps = new String[selectedRoute.steps.size()];
        for (int i = 0; i < selectedRoute.steps.size(); i++) {
            RouteStep step = selectedRoute.steps.get(i);
            String instruction = step.instruction == null || step.instruction.trim().isEmpty()
                    ? "ادامه مسیر" : step.instruction;
            steps[i] = formatDistance(Math.max(0, step.distanceMeters)) + "\n" + instruction;
        }
        new AlertDialog.Builder(this)
                .setTitle("راهنمای مسیر تا مقصد")
                .setSingleChoiceItems(steps, Math.min(navigationEngine.currentStepIndex(), steps.length - 1), null)
                .setPositiveButton("بستن", null)
                .show();
    }

    private LatLng pointAhead(LatLng origin, float bearing, double meters) {
        double radians = Math.toRadians(bearing);
        double latitude = origin.getLatitude() + (meters * Math.cos(radians) / 111320d);
        double longitude = origin.getLongitude() + (meters * Math.sin(radians)
                / (111320d * Math.max(0.1d, Math.cos(Math.toRadians(origin.getLatitude())))));
        return new LatLng(latitude, longitude);
    }

    /**
     * mobile-sdk exposes these through its Carto-backed MapView. Reflection keeps releases using
     * an older Neshan artifact operational: they retain follow mode instead of crashing.
     */
    private void applyMapOrientation(float heading, float tilt, float durationSeconds) {
        if (map == null) return;
        try {
            map.getClass().getMethod("setBearing", float.class, float.class)
                    .invoke(map, heading, durationSeconds);
        } catch (Exception ignored) {
            logMissingOrientationSupport();
        }
        try {
            map.getClass().getMethod("setTilt", float.class, float.class)
                    .invoke(map, tilt, durationSeconds);
        } catch (Exception ignored) {
            logMissingOrientationSupport();
        }
    }

    private void logMissingOrientationSupport() {
        if (orientationWarningLogged) return;
        orientationWarningLogged = true;
        Log.w("DriveMateMap", "Navigation camera orientation is unavailable in this SDK build.");
    }

    private void stopNavigationFromMap() {
        navigationEngine.stop();
        Intent result = new Intent();
        result.putExtra(RESULT_STOP_NAVIGATION, true);
        setResult(RESULT_OK, result);
        finish();
    }

    private void saveSelectedPlace() {
        if (destination == null) {
            Toast.makeText(this, "ابتدا یک مقصد انتخاب کنید.", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(destination.name);
        new AlertDialog.Builder(this).setTitle("ذخیره مکان")
                .setView(input)
                .setPositiveButton("ذخیره", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = destination.name;
                    placeStore.upsert(new SavedPlace(name, "custom_" + System.currentTimeMillis(), destination.latitude,
                            destination.longitude, destination.address, System.currentTimeMillis(), true));
                    Toast.makeText(this, "مکان ذخیره شد.", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("انصراف", null).show();
    }

    private void startSelectedDestination() {
        if (destination == null) { Toast.makeText(this, "ابتدا مقصد را انتخاب کنید.", Toast.LENGTH_SHORT).show(); return; }
        Intent result = new Intent();
        result.putExtra(RESULT_LATITUDE, destination.latitude);
        result.putExtra(RESULT_LONGITUDE, destination.longitude);
        result.putExtra(RESULT_NAME, destination.name);
        result.putExtra(RESULT_ADDRESS, destination.address);
        result.putExtra(RESULT_OPEN_NAVIGATION_MAP, true);
        result.putExtra(RESULT_ROUTE_INDEX, Math.max(0, routeOptions.indexOf(selectedRoute)));
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

    private MarkerStyle vehicleMarkerStyle(float bearing) {
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.rotate(bearing, 48f, 48f);
        Path arrow = new Path();
        arrow.moveTo(48f, 8f);
        arrow.lineTo(76f, 76f);
        arrow.lineTo(48f, 62f);
        arrow.lineTo(20f, 76f);
        arrow.close();
        paint.setColor(0xff176b87);
        canvas.drawPath(arrow, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(0xffffffff);
        canvas.drawPath(arrow, paint);
        MarkerStyleBuilder builder = new MarkerStyleBuilder();
        builder.setSize(46f);
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

    @Override protected void onResume() {
        super.onResume();
        if (locationManager == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        if (isLocationEnabled()) {
            long minTimeMs = navigationMode ? 1000L : 2500L;
            float minDistanceM = navigationMode ? 4f : 8f;
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMs, minDistanceM, this);
        }
    }

    @Override protected void onPause() {
        if (locationManager != null) locationManager.removeUpdates(this);
        super.onPause();
    }

    @Override public void onLocationChanged(Location location) {
        originLatitude = location.getLatitude();
        originLongitude = location.getLongitude();
        if (location.hasBearing()) {
            lastBearing = location.getBearing();
            hasHeading = true;
        }
        runOnUiThread(() -> {
            showCurrentMarker();
            if (navigationMode && navigationEngine.isNavigating()) {
                navigationEngine.onLocation(location);
                updateTurnBanner(location);
            }
            if (navigationMode && followVehicle && navigationCameraEnabled) {
                updateNavigationCamera();
            } else if (navigationMode && followVehicle && map != null) {
                map.moveCamera(drivingPosition(), 0.35f);
                map.setZoom(17f, 0.35f);
            }
        });
    }
}
