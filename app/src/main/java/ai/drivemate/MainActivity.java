package ai.drivemate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Button;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

import ai.drivemate.ai.AiAssistant;
import ai.drivemate.ai.OnlineSpeechClient;
import ai.drivemate.ai.RuntimeKeys;
import ai.drivemate.location.AddressResolver;
import ai.drivemate.location.DeviceLocationTracker;
import ai.drivemate.location.SharedLocationParser;
import ai.drivemate.model.RouteStep;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.model.TripRecord;
import ai.drivemate.routing.MapIrRoutingProvider;
import ai.drivemate.routing.NeshanRoutingProvider;
import ai.drivemate.routing.NavigationEngine;
import ai.drivemate.routing.PlaceSearchRepository;
import ai.drivemate.routing.RouteRepository;
import ai.drivemate.storage.PlaceStore;
import ai.drivemate.storage.TripStore;
import ai.drivemate.voice.Command;
import ai.drivemate.voice.VoiceCommandParser;
import ai.drivemate.voice.VoiceGuidancePlayer;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 10;
    private static final String PREFS_SETTINGS = "drivemate_settings";
    public static final String ACTION_VOICE_FROM_NOTIFICATION = "ai.drivemate.action.VOICE_FROM_NOTIFICATION";

    private TextView statusText;
    private TextView listText;
    private Button voiceButton;
    private PlaceStore placeStore;
    private TripStore tripStore;
    private VoiceGuidancePlayer voicePlayer;
    private DeviceLocationTracker locationTracker;
    private NeshanRoutingProvider neshanRoutingProvider;
    private MapIrRoutingProvider mapIrRoutingProvider;
    private RouteRepository routeRepository;
    private PlaceSearchRepository placeSearchRepository;
    private VoiceCommandParser commandParser;
    private AiAssistant aiAssistant;
    private OnlineSpeechClient onlineSpeechClient;
    private final NavigationEngine navigationEngine = new NavigationEngine();
    private RuntimeKeys runtimeKeys = new RuntimeKeys();
    private String lastInstruction = "start_navigation";
    private SavedPlace activeDestination;
    private boolean recordingOnlineSpeech;
    private boolean runtimeKeysLoading = true;
    private boolean voiceRequestedWhileKeysLoad;
    private final android.os.Handler voiceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable automaticStop = this::finishOnlineRecording;
    private final BroadcastReceiver navigationStopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { stopNavigation("مسیریابی از اعلان متوقف شد."); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        listText = findViewById(R.id.listText);
        voiceButton = findViewById(R.id.voiceButton);
        placeStore = new PlaceStore(this);
        tripStore = new TripStore(this);
        voicePlayer = new VoiceGuidancePlayer(this);
        locationTracker = new DeviceLocationTracker(this);
        neshanRoutingProvider = new NeshanRoutingProvider(BuildConfig.NESHAN_API_KEY);
        mapIrRoutingProvider = new MapIrRoutingProvider(BuildConfig.MAPIR_API_KEY);
        routeRepository = new RouteRepository(neshanRoutingProvider, mapIrRoutingProvider);
        placeSearchRepository = new PlaceSearchRepository(neshanRoutingProvider, mapIrRoutingProvider);
        commandParser = new VoiceCommandParser();
        aiAssistant = new AiAssistant(BuildConfig.AI_API_KEY);
        onlineSpeechClient = new OnlineSpeechClient(this, BuildConfig.AI_API_KEY);

        wireButtons();
        requestCorePermissions();
        refreshList();
        voicePlayer.play("welcome");
        loadRuntimeKeys();
        promptEnableLocationIfNeeded();
        locationTracker.setUpdateListener(location -> navigationEngine.onLocation(location));
        handleSharedIntent(getIntent());
        registerNavigationReceiver();
        if (ACTION_VOICE_FROM_NOTIFICATION.equals(getIntent().getAction())) voiceHandler.postDelayed(this::toggleVoiceInput, 350L);
    }

    private void wireButtons() {
        voiceButton.setOnClickListener(v -> toggleVoiceInput());
        findViewById(R.id.saveButton).setOnClickListener(v -> promptSaveCurrentPlace());
        findViewById(R.id.homeButton).setOnClickListener(v -> openHomeOrWork("home", "خانه"));
        findViewById(R.id.workButton).setOnClickListener(v -> openHomeOrWork("work", "محل کار"));
        findViewById(R.id.favoritesButton).setOnClickListener(v -> showPlaces(true));
        findViewById(R.id.recentButton).setOnClickListener(v -> showRecent());
        findViewById(R.id.settingsButton).setOnClickListener(v -> cycleVolume());
        findViewById(R.id.stopButton).setOnClickListener(v -> stopNavigation("مسیریابی متوقف شد."));
    }

    private void requestCorePermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            locationTracker.start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            locationTracker.start();
        }
    }

    private void toggleVoiceInput() {
        if (recordingOnlineSpeech) {
            finishOnlineRecording();
            return;
        }
        if (runtimeKeysLoading && !onlineSpeechClient.canUseOnlineSpeech()) {
            voiceRequestedWhileKeysLoad = true;
            setStatus("در حال آماده‌سازی سرویس گفتار GapGPT...");
            return;
        }
        if (onlineSpeechClient.canUseOnlineSpeech() && onlineSpeechClient.startRecording()) {
            recordingOnlineSpeech = true;
            voicePlayer.play("listening");
            voiceButton.setText("در حال ضبط... برای پایان لمس کنید");
            setStatus("در حال ضبط با کیفیت بالا است. پس از ۱۰ ثانیه خودکار ارسال می‌شود.");
            voiceHandler.postDelayed(automaticStop, 10000L);
            return;
        }
        setStatus("سرویس گفتار آنلاین آماده نیست. کلید GapGPT یا لیارا دریافت نشد.");
    }

    private void finishOnlineRecording() {
        if (!recordingOnlineSpeech) return;
        recordingOnlineSpeech = false;
        voiceHandler.removeCallbacks(automaticStop);
        voiceButton.setEnabled(false);
        voiceButton.setText("در حال ارسال به GapGPT...");
        setStatus("صدا به GapGPT ارسال شد؛ در صورت خطا لیارا استفاده می‌شود.");
        onlineSpeechClient.stopAndTranscribe(new OnlineSpeechClient.TextCallback() {
            @Override public void onResult(String text) { runOnUiThread(() -> {
                restoreVoiceButton();
                if (text == null || text.trim().isEmpty()) setStatus("پاسخ صوتی خالی بود؛ دوباره ضبط کنید.");
                else handleVoiceText(text);
            }); }
            @Override public void onError(String message) { runOnUiThread(() -> {
                restoreVoiceButton();
                setStatus(message + " لطفاً اتصال و کلیدهای آنلاین را بررسی کنید.");
            }); }
        });
    }

    private void restoreVoiceButton() {
        voiceButton.setEnabled(true);
        voiceButton.setText("مقصد را بگویید");
    }

    private void handleVoiceText(String text) {
        Command command = commandParser.parse(text);
        setStatus("شنیدم: " + text);

        switch (command.type) {
            case SAVE_HOME:
                saveCurrentPlace("خانه", "home");
                break;
            case SAVE_WORK:
                saveCurrentPlace("محل کار", "work");
                break;
            case NAVIGATE_HOME:
                navigateToKnownPlace("home");
                break;
            case NAVIGATE_WORK:
                navigateToKnownPlace("work");
                break;
            case NAVIGATE_NAMED_PLACE:
                SavedPlace requestedPlace = placeStore.findByNameInText(text);
                if (requestedPlace != null) startNavigation(requestedPlace);
                else searchAndNavigate(cleanDestinationText(text));
                break;
            case FIND_FUEL:
                searchAndNavigate("پمپ بنزین");
                break;
            case VOLUME_UP:
                voicePlayer.increaseVolume();
                voicePlayer.play("voice_louder");
                setStatus("صدای راهنما بیشتر شد.");
                break;
            case VOLUME_DOWN:
                voicePlayer.decreaseVolume();
                voicePlayer.play("voice_lower");
                setStatus("صدای راهنما کمتر شد.");
                break;
            case REPEAT:
                voicePlayer.play(lastInstruction);
                break;
            case ASK_AI:
                askAi(text);
                break;
            default:
                SavedPlace namedPlace = placeStore.findByNameInText(text);
                if (namedPlace != null) {
                    startNavigation(namedPlace);
                } else if (commandParser.isExplicitQuestion(text)) {
                    askAi(text);
                } else {
                    searchAndNavigate(cleanDestinationText(text));
                }
        }
    }

    private void promptSaveCurrentPlace() {
        final EditText input = new EditText(this);
        input.setHint("مثلاً خانه مادر، باشگاه، مدرسه");
        input.setText("مکان جدید");
        new AlertDialog.Builder(this)
                .setTitle("نام مکان ذخیره‌شده")
                .setView(input)
                .setPositiveButton("ذخیره", (dialog, which) -> saveCurrentPlace(input.getText().toString(), "custom_" + System.currentTimeMillis()))
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void saveCurrentPlace(String name, String kind) {
        Location location = locationTracker.getLastLocation();
        if (location == null) {
            setStatus("هنوز موقعیت GPS آماده نیست.");
            voicePlayer.play("gps_lost");
            return;
        }
        String finalName = (name == null || name.trim().isEmpty()) ? "مکان جدید" : name.trim();
        new Thread(() -> {
            String address = AddressResolver.resolve(this, location.getLatitude(), location.getLongitude());
            SavedPlace place = new SavedPlace(finalName, kind, location.getLatitude(), location.getLongitude(), address, System.currentTimeMillis(), true);
            placeStore.upsert(place);
            runOnUiThread(() -> {
                voicePlayer.play("home".equals(kind) ? "home_saved" : "work".equals(kind) ? "work_saved" : "place_saved");
                setStatus(finalName + " ذخیره شد.");
                refreshList();
            });
        }).start();
    }

    private void navigateToKnownPlace(String kind) {
        SavedPlace place = placeStore.findByKind(kind);
        if (place == null) {
            setStatus(("home".equals(kind) ? "خانه" : "محل کار") + " هنوز ذخیره نشده است.");
            return;
        }
        startNavigation(place);
    }

    private void startNavigation(SavedPlace destination) {
        if (!routeRepository.hasConfiguredProvider()) {
            setStatus("کلید مسیریابی نشان یا map.ir در این APK موجود نیست. GitHub Secrets را بررسی کنید.");
            return;
        }
        Location origin = locationTracker.getLastLocation();
        if (origin == null) {
            setStatus("برای شروع مسیر، GPS باید آماده باشد.");
            voicePlayer.play("gps_lost");
            return;
        }
        setStatus("در حال دریافت مسیر به " + destination.name + "...");
        voicePlayer.play("searching_route");
        final double originLatitude = origin.getLatitude();
        final double originLongitude = origin.getLongitude();
        routeRepository.getRoute(originLatitude, originLongitude, destination.latitude, destination.longitude,
                route -> runOnUiThread(() -> {
                    placeStore.addRecent(destination);
                    tripStore.add(new TripRecord(destination.name, originLatitude, originLongitude, destination.latitude, destination.longitude,
                            route.distanceMeters, route.durationSeconds, System.currentTimeMillis()));
                    activeDestination = destination;
                    startBackgroundNavigation();
                    navigationEngine.start(route, new NavigationEngine.Listener() {
                        @Override public void onInstruction(RouteStep step) {
                            runOnUiThread(() -> announceRouteStep(step));
                        }
                        @Override public void onOffRoute() {
                            runOnUiThread(() -> rerouteFromCurrentLocation());
                        }
                        @Override public void onArrived() {
                            runOnUiThread(() -> {
                                voicePlayer.play("arrived");
                                setStatus("به " + activeDestination.name + " رسیدید.");
                                activeDestination = null;
                                stopBackgroundNavigation();
                            });
                        }
                    });
                    lastInstruction = "start_navigation";
                    voicePlayer.play("start_navigation");
                    setStatus("مسیر آماده است. سرویس: " + route.providerName + "، فاصله تقریبی: " + route.distanceMeters + " متر");
                    refreshList();
                }),
                error -> runOnUiThread(() -> {
                    voicePlayer.play("api_error");
                    setStatus("خطا در دریافت مسیر: " + error);
                }));
    }

    private void openHomeOrWork(String kind, String defaultName) {
        SavedPlace place = placeStore.findByKind(kind);
        if (place != null) { startNavigation(place); return; }
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(defaultName);
        input.setSelectAllOnFocus(false);
        new AlertDialog.Builder(this)
                .setTitle("ذخیره " + defaultName)
                .setMessage("نام دلخواه را وارد کنید؛ موقعیت فعلی GPS ذخیره می‌شود.")
                .setView(input)
                .setPositiveButton("ذخیره", (dialog, which) -> saveCurrentPlace(input.getText().toString(), kind))
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void searchAndNavigate(String term) {
        Location location = locationTracker.getLastLocation();
        if (location == null) { setStatus("برای پیدا کردن مقصد، GPS باید آماده باشد."); return; }
        if (term.isEmpty()) { speakShort("نام مقصد را دوباره بگویید."); return; }
        setStatus("در حال پیدا کردن " + term + "...");
        placeSearchRepository.search(term, location.getLatitude(), location.getLongitude(),
                place -> runOnUiThread(() -> startNavigation(place)),
                error -> runOnUiThread(() -> { setStatus(error); speakShort("مقصد پیدا نشد. نام آن را دوباره بگویید."); }));
    }

    private void loadRuntimeKeys() {
        new Thread(() -> {
            runtimeKeys = RuntimeKeys.fetch(new String[]{
                    "https://abrehamrahi.ir/o/public/eUFcsXOX",
                    "https://gist.githubusercontent.com/ghadirb/626a804df3009e49045a2948dad89fe5/raw/c93c06d1b2f38c65ee30f092c134a89998326d12/keys.txt"
            }, BuildConfig.KEYS_DECRYPTION_SECRET);
            aiAssistant.setRuntimeKeys(runtimeKeys);
            onlineSpeechClient.setRuntimeKeys(runtimeKeys);
            neshanRoutingProvider.setApiKey(runtimeKeys.get("NESHAN_API_KEY"));
            mapIrRoutingProvider.setApiKey(runtimeKeys.get("MAPIR_API_KEY"));
            runOnUiThread(() -> {
                runtimeKeysLoading = false;
                boolean onlineReady = onlineSpeechClient.canUseOnlineSpeech();
                setStatus(onlineReady ? "کلیدهای آنلاین فعال شدند." : "کلید آنلاین دریافت نشد؛ حالت تشخیص گفتار گوشی فعال است.");
                if (voiceRequestedWhileKeysLoad) {
                    voiceRequestedWhileKeysLoad = false;
                    toggleVoiceInput();
                }
            });
        }).start();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedIntent(intent);
        if (ACTION_VOICE_FROM_NOTIFICATION.equals(intent.getAction())) voiceHandler.postDelayed(this::toggleVoiceInput, 350L);
    }

    private void handleSharedIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction()) || intent.getType() == null || !intent.getType().startsWith("text/")) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) return;
        setStatus("در حال خواندن مکان اشتراک‌گذاری‌شده...");
        SharedLocationParser.resolve(this, text, new SharedLocationParser.Callback() {
            @Override public void onResolved(SavedPlace place) { runOnUiThread(() -> promptSaveSharedPlace(place)); }
            @Override public void onFailure() { runOnUiThread(() -> setStatus("مختصات این مکان پیدا نشد. لینک کامل Google Maps یا نشان را اشتراک‌گذاری کنید.")); }
        });
    }

    private void promptSaveSharedPlace(SavedPlace place) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(place.name);
        new AlertDialog.Builder(this)
                .setTitle("ذخیره مکان اشتراک‌گذاری‌شده")
                .setMessage(place.address)
                .setView(input)
                .setPositiveButton("ذخیره", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    placeStore.upsert(new SavedPlace(name.isEmpty() ? "مکان اشتراک‌گذاری‌شده" : name, place.kind, place.latitude, place.longitude, place.address, System.currentTimeMillis(), true));
                    voicePlayer.play("place_saved");
                    setStatus("مکان ذخیره شد.");
                    refreshList();
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void promptEnableLocationIfNeeded() {
        if (!locationTracker.isLocationEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("فعال‌سازی مکان گوشی")
                    .setMessage("برای ذخیره و مسیریابی، مکان/GPS گوشی را روشن کنید.")
                    .setPositiveButton("باز کردن تنظیمات", (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("بعداً", null)
                    .show();
        }
    }

    private void askAi(String question) {
        setStatus("در حال آماده کردن پاسخ صوتی...");
        aiAssistant.answer(question, drivingContext(), answer -> runOnUiThread(() -> {
            speakShort(answer);
        }));
    }

    private void speakShort(String answer) {
        String shortAnswer = answer == null ? "" : answer.trim();
        if (shortAnswer.length() > 190) shortAnswer = shortAnswer.substring(0, 190);
        setStatus("پاسخ صوتی پخش شد.");
        onlineSpeechClient.speak(shortAnswer);
    }

    private String cleanDestinationText(String text) {
        return text == null ? "" : text.replaceFirst("^(برو|به|مسیریابی)\\s+", "").trim();
    }

    private String drivingContext() {
        StringBuilder context = new StringBuilder();
        if (activeDestination != null) context.append("مقصد فعلی: ").append(activeDestination.name).append(". ");
        ArrayList<SavedPlace> places = new ArrayList<>(placeStore.allPlaces());
        if (!places.isEmpty()) {
            context.append("مکان‌های ذخیره‌شده: ");
            for (int i = 0; i < places.size() && i < 6; i++) context.append(places.get(i).name).append(i == Math.min(places.size(), 6) - 1 ? ". " : "، ");
        }
        java.util.List<TripRecord> trips = tripStore.recent(5);
        if (!trips.isEmpty()) {
            context.append("مقصدهای سفر اخیر: ");
            for (int i = 0; i < trips.size(); i++) context.append(trips.get(i).destinationName).append(i == trips.size() - 1 ? "." : "، ");
        }
        return context.toString();
    }

    private void cycleVolume() {
        final String background = backgroundNavigationEnabled() ? "غیرفعال کردن ادامه در پس‌زمینه" : "فعال کردن ادامه در پس‌زمینه";
        final String[] choices = {"افزایش صدا", "کاهش صدا", "توقف مسیریابی", background};
        new AlertDialog.Builder(this).setTitle("تنظیمات راهنما").setItems(choices, (d, which) -> {
            if (which == 0) { voicePlayer.increaseVolume(); voicePlayer.play("voice_louder"); setStatus("صدای راهنما بیشتر شد."); }
            else if (which == 1) { voicePlayer.decreaseVolume(); voicePlayer.play("voice_lower"); setStatus("صدای راهنما کمتر شد."); }
            else if (which == 2) stopNavigation("مسیریابی متوقف شد.");
            else {
                boolean enabled = !backgroundNavigationEnabled();
                getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).edit().putBoolean("background_navigation", enabled).apply();
                if (enabled && navigationEngine.isNavigating()) startBackgroundNavigation();
                else if (!enabled) stopBackgroundNavigation();
                setStatus(enabled ? "ادامه مسیریابی در پس‌زمینه فعال شد." : "ادامه مسیریابی در پس‌زمینه غیرفعال شد.");
            }
        }).show();
    }

    private void showPlaces(boolean favoritesOnly) {
        ArrayList<SavedPlace> places = new ArrayList<>();
        for (SavedPlace place : placeStore.allPlaces()) if (!favoritesOnly || place.favorite) places.add(place);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < places.size(); i++) {
            SavedPlace place = places.get(i);
            builder.append(i + 1).append(". ").append(place.name).append(" - ").append(place.address).append("\n");
        }
        listText.setText(builder.length() == 0 ? "هنوز مقصدی ذخیره نشده است." : builder.toString());
        listText.setOnClickListener(v -> choosePlace(places));
    }

    private void choosePlace(ArrayList<SavedPlace> places) {
        if (places.isEmpty()) return;
        String[] names = new String[places.size()];
        for (int i = 0; i < places.size(); i++) names[i] = places.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("انتخاب مکان")
                .setItems(names, (d, which) -> editOrNavigatePlace(places.get(which)))
                .show();
    }

    private void editOrNavigatePlace(SavedPlace place) {
        final EditText input = new EditText(this);
        input.setText(place.name);
        new AlertDialog.Builder(this)
                .setTitle("ویرایش یا مسیریابی")
                .setView(input)
                .setPositiveButton("مسیریابی", (d, w) -> startNavigation(place))
                .setNeutralButton("ذخیره نام", (d, w) -> {
                    placeStore.upsert(new SavedPlace(input.getText().toString(), place.kind, place.latitude, place.longitude, place.address, System.currentTimeMillis(), place.favorite));
                    refreshList();
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void showRecent() {
        ArrayList<SavedPlace> recent = new ArrayList<>(placeStore.recentPlaces());
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < recent.size(); i++) {
            builder.append(i + 1).append(". ").append(recent.get(i).name).append("\n");
        }
        listText.setText(builder.length() == 0 ? "هنوز مقصد اخیری وجود ندارد." : builder.toString());
        listText.setOnClickListener(v -> choosePlace(recent));
    }

    private void refreshList() {
        showPlaces(false);
    }

    private void setStatus(String message) {
        statusText.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void rerouteFromCurrentLocation() {
        if (activeDestination == null || locationTracker.getLastLocation() == null) return;
        voicePlayer.play("route_recalculated");
        setStatus("از مسیر خارج شدید؛ در حال محاسبه مسیر جدید...");
        askAi("کاربر از مسیر خارج شده است. یک هشدار خیلی کوتاه و آرام برای ادامه مسیر بگو.");
        startNavigation(activeDestination);
    }

    private boolean backgroundNavigationEnabled() {
        return getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).getBoolean("background_navigation", true);
    }

    private void startBackgroundNavigation() {
        if (!backgroundNavigationEnabled()) return;
        Intent intent = new Intent(this, NavigationForegroundService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private void stopBackgroundNavigation() {
        stopService(new Intent(this, NavigationForegroundService.class));
    }

    private void stopNavigation(String message) {
        navigationEngine.stop();
        activeDestination = null;
        stopBackgroundNavigation();
        voicePlayer.play("stop_navigation");
        setStatus(message);
    }

    private void registerNavigationReceiver() {
        IntentFilter filter = new IntentFilter(NavigationForegroundService.ACTION_STOP_BROADCAST);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(navigationStopReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(navigationStopReceiver, filter);
    }

    private void announceRouteStep(RouteStep step) {
        String text = step.instruction == null || step.instruction.trim().isEmpty() ? "ادامه مسیر" : step.instruction;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("left") || text.contains("چپ")) lastInstruction = "turn_left";
        else if (lower.contains("right") || text.contains("راست")) lastInstruction = "turn_right";
        else if (lower.contains("arriv") || text.contains("مقصد")) lastInstruction = "arrived";
        else lastInstruction = "continue_route";
        voicePlayer.play(lastInstruction);
        setStatus(text);
    }

    @Override protected void onDestroy() {
        voiceHandler.removeCallbacks(automaticStop);
        onlineSpeechClient.cancelRecording();
        unregisterReceiver(navigationStopReceiver);
        navigationEngine.stop();
        stopBackgroundNavigation();
        locationTracker.stop();
        super.onDestroy();
    }
}
