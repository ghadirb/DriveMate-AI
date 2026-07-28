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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.drivemate.ai.AiAssistant;
import ai.drivemate.ai.DrivingIntelligenceCoordinator;
import ai.drivemate.ai.OnlineSpeechClient;
import ai.drivemate.ai.RuntimeKeys;
import ai.drivemate.ai.SmartDriveCompanion;
import ai.drivemate.location.AddressResolver;
import ai.drivemate.location.DeviceLocationTracker;
import ai.drivemate.location.SharedLocationParser;
import ai.drivemate.model.RouteStep;
import ai.drivemate.model.RouteResult;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.model.TripRecord;
import ai.drivemate.routing.MapIrRoutingProvider;
import ai.drivemate.routing.NeshanRoutingProvider;
import ai.drivemate.routing.OpenRouteServiceRoutingProvider;
import ai.drivemate.routing.NavigationEngine;
import ai.drivemate.routing.PlaceSearchRepository;
import ai.drivemate.routing.PoiCategory;
import ai.drivemate.routing.RouteRepository;
import ai.drivemate.storage.PlaceStore;
import ai.drivemate.storage.TripStore;
import ai.drivemate.storage.BackupManager;
import ai.drivemate.voice.Command;
import ai.drivemate.voice.LocalSpeechRecognizer;
import ai.drivemate.voice.VoiceCommandParser;
import ai.drivemate.voice.VoiceGuidancePlayer;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 10;
    private static final int REQ_EXPORT_BACKUP = 11;
    private static final int REQ_IMPORT_BACKUP = 12;
    private static final int REQ_MAP = 13;
    private static final long TRAFFIC_CHECK_INTERVAL_MS = 8 * 60_000L;
    private static final int TRAFFIC_REROUTE_MIN_GAIN_SECONDS = 180;
    private static final String PREFS_SETTINGS = "drivemate_settings";
    private static final String KEY_INTELLIGENCE_MODE = "driving_intelligence_mode";
    private static final String KEY_INTELLIGENCE_ONBOARDING_SHOWN = "intelligence_onboarding_shown_v2";
    public static final String ACTION_VOICE_FROM_NOTIFICATION = "ai.drivemate.action.VOICE_FROM_NOTIFICATION";

    private TextView statusText;
    private TextView aiStatusText;
    private TextView analysisTitleText;
    private TextView analysisBodyText;
    private View analysisPanel;
    private View tripStatsPanel;
    private TextView tripEtaText;
    private TextView tripRemainingText;
    private TextView tripElapsedText;
    private TextView tripSpeedText;
    private final SimpleDateFormat tripEtaFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private TextView listText;
    private TextView savedPlacesTabText;
    private ScrollView dashboardPage;
    private ScrollView savedPlacesPage;
    private ScrollView profilePage;
    private Button voiceButton;
    private Button notificationButton;
    private Button intelligenceButton;
    private PlaceStore placeStore;
    private TripStore tripStore;
    private BackupManager backupManager;
    private VoiceGuidancePlayer voicePlayer;
    private DeviceLocationTracker locationTracker;
    private NeshanRoutingProvider neshanRoutingProvider;
    private MapIrRoutingProvider mapIrRoutingProvider;
    private OpenRouteServiceRoutingProvider openRouteServiceRoutingProvider;
    private RouteRepository routeRepository;
    private PlaceSearchRepository placeSearchRepository;
    private VoiceCommandParser commandParser;
    private AiAssistant aiAssistant;
    private DrivingIntelligenceCoordinator intelligenceCoordinator;
    private OnlineSpeechClient onlineSpeechClient;
    private LocalSpeechRecognizer localSpeechRecognizer;
    private SmartDriveCompanion smartCompanion;
    private final NavigationEngine navigationEngine = new NavigationEngine();
    private RouteResult activeRoute;
    private RuntimeKeys runtimeKeys = new RuntimeKeys();
    private String lastInstruction = "start_navigation";
    private String lastInstructionText = "";
    private SavedPlace activeDestination;
    private int lastTrafficEtaSeconds;
    private long lastTrafficEtaMeasuredAt;
    private long routeRequestSequence;
    private int pendingRouteOptionIndex;
    private boolean recordingOnlineSpeech;
    private boolean recordingLocalSpeech;
    private boolean runtimeKeysLoading = true;
    private boolean voiceRequestedWhileKeysLoad;
    private long tripStartedAt;
    private int activeTripDistanceMeters;
    private long initialGuidanceHeldUntil;
    private SavedPlace pendingSuggestionPlace;
    private PoiCategory pendingSuggestionCategory;
    private final android.os.Handler voiceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable automaticStop = this::finishOnlineRecording;
    private final Runnable trafficCheck = this::checkTrafficAndMaybeReroute;
    private final Runnable tripAnalysisHide = this::hideTripAnalysis;
    private final BroadcastReceiver navigationStopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { stopNavigation("مسیریابی از اعلان متوقف شد."); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        aiStatusText = findViewById(R.id.aiStatusText);
        analysisPanel = findViewById(R.id.analysisPanel);
        analysisTitleText = findViewById(R.id.analysisTitleText);
        analysisBodyText = findViewById(R.id.analysisBodyText);
        tripStatsPanel = findViewById(R.id.tripStatsPanel);
        tripEtaText = findViewById(R.id.tripEtaText);
        tripRemainingText = findViewById(R.id.tripRemainingText);
        tripElapsedText = findViewById(R.id.tripElapsedText);
        tripSpeedText = findViewById(R.id.tripSpeedText);
        listText = findViewById(R.id.listText);
        savedPlacesTabText = findViewById(R.id.savedPlacesTabText);
        dashboardPage = findViewById(R.id.dashboardPage);
        savedPlacesPage = findViewById(R.id.savedPlacesPage);
        profilePage = findViewById(R.id.profilePage);
        voiceButton = findViewById(R.id.voiceButton);
        notificationButton = findViewById(R.id.notificationButton);
        intelligenceButton = findViewById(R.id.intelligenceButton);
        placeStore = new PlaceStore(this);
        tripStore = new TripStore(this);
        backupManager = new BackupManager(this, placeStore, tripStore);
        writeAutomaticBackup();
        voicePlayer = new VoiceGuidancePlayer(this);
        locationTracker = new DeviceLocationTracker(this);
        neshanRoutingProvider = new NeshanRoutingProvider(BuildConfig.NESHAN_API_KEY);
        mapIrRoutingProvider = new MapIrRoutingProvider(BuildConfig.MAPIR_API_KEY);
        openRouteServiceRoutingProvider = new OpenRouteServiceRoutingProvider(BuildConfig.OPENROUTESERVICE_API_KEY);
        routeRepository = new RouteRepository(neshanRoutingProvider, mapIrRoutingProvider, openRouteServiceRoutingProvider);
        placeSearchRepository = new PlaceSearchRepository(neshanRoutingProvider, mapIrRoutingProvider,
                BuildConfig.TOMTOM_API_KEY);
        commandParser = new VoiceCommandParser();
        aiAssistant = new AiAssistant(BuildConfig.AI_API_KEY);
        intelligenceCoordinator = new DrivingIntelligenceCoordinator(aiAssistant);
        intelligenceCoordinator.setMode(readIntelligenceMode());
        onlineSpeechClient = new OnlineSpeechClient(this, BuildConfig.AI_API_KEY);
        localSpeechRecognizer = new LocalSpeechRecognizer(this);
        smartCompanion = new SmartDriveCompanion((event, facts) -> runOnUiThread(() -> handleSmartEvent(event, facts)));

        wireButtons();
        requestCorePermissions();
        refreshList();
        voicePlayer.announce("welcome", "به همراه راننده خوش آمدید.");
        loadRuntimeKeys();
        promptEnableLocationIfNeeded();
        locationTracker.setUpdateListener(location -> {
            navigationEngine.onLocation(location);
            smartCompanion.onLocation(location);
            updateTripStats(location);
        });
        handleSharedIntent(getIntent());
        registerNavigationReceiver();
        refreshNotificationButton();
        refreshIntelligenceButton();
        refreshAiStatus();
        voiceHandler.postDelayed(this::maybeShowIntelligenceOnboarding, 500L);
        if (ACTION_VOICE_FROM_NOTIFICATION.equals(getIntent().getAction())) voiceHandler.postDelayed(this::toggleVoiceInput, 350L);
    }

    private void wireButtons() {
        voiceButton.setOnClickListener(v -> toggleVoiceInput());
        findViewById(R.id.mapButton).setOnClickListener(v -> openMap());
        findViewById(R.id.saveButton).setOnClickListener(v -> promptSaveCurrentPlace());
        findViewById(R.id.homeButton).setOnClickListener(v -> openHomeOrWork("home", "خانه"));
        findViewById(R.id.workButton).setOnClickListener(v -> openHomeOrWork("work", "محل کار"));
        findViewById(R.id.favoritesButton).setOnClickListener(v -> showPlaces(true));
        findViewById(R.id.recentButton).setOnClickListener(v -> showRecent());
        findViewById(R.id.settingsButton).setOnClickListener(v -> showSettingsMenu());
        intelligenceButton.setOnClickListener(v -> showIntelligenceModeDialog());
        findViewById(R.id.stopButton).setOnClickListener(v -> stopNavigation("مسیریابی متوقف شد."));
        notificationButton.setOnClickListener(v -> toggleBackgroundNavigation());
        findViewById(R.id.backupButton).setOnClickListener(v -> showBackupDialog());
        findViewById(R.id.tabDashboardButton).setOnClickListener(v -> selectMainTab(0));
        findViewById(R.id.tabMapButton).setOnClickListener(v -> openMap());
        findViewById(R.id.tabSavedButton).setOnClickListener(v -> selectMainTab(1));
        findViewById(R.id.tabProfileButton).setOnClickListener(v -> selectMainTab(2));
        findViewById(R.id.addSavedPlaceTabButton).setOnClickListener(v -> promptSaveCurrentPlace());
        findViewById(R.id.manageSavedPlacesTabButton).setOnClickListener(v -> choosePlace(new ArrayList<>(placeStore.allPlaces())));
        findViewById(R.id.profileSettingsButton).setOnClickListener(v -> showSettingsMenu());
        findViewById(R.id.profileSubscriptionButton).setOnClickListener(v -> showSubscriptionInfo());
        findViewById(R.id.profileBackupButton).setOnClickListener(v -> showBackupDialog());
        findViewById(R.id.profileAboutButton).setOnClickListener(v -> showAboutDialog());
        selectMainTab(0);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MAP && resultCode == RESULT_OK && data != null) {
            String requestedTab = data.getStringExtra(MapActivity.RESULT_MAIN_TAB);
            if (requestedTab != null) {
                selectMainTab("saved".equals(requestedTab) ? 1 : "profile".equals(requestedTab) ? 2 : 0);
            } else if (data.getBooleanExtra(MapActivity.RESULT_START_VOICE, false)) {
                toggleVoiceInput();
            } else if (data.getBooleanExtra(MapActivity.RESULT_STOP_NAVIGATION, false)) {
                stopNavigation("مسیریابی متوقف شد.");
            } else if (data.hasExtra(MapActivity.RESULT_LATITUDE) && data.hasExtra(MapActivity.RESULT_LONGITUDE)) {
                SavedPlace destination = new SavedPlace(
                        data.getStringExtra(MapActivity.RESULT_NAME), "map_" + System.currentTimeMillis(),
                        data.getDoubleExtra(MapActivity.RESULT_LATITUDE, 0d),
                        data.getDoubleExtra(MapActivity.RESULT_LONGITUDE, 0d),
                        data.getStringExtra(MapActivity.RESULT_ADDRESS), System.currentTimeMillis(), false);
                int selectedRouteIndex = Math.max(0, data.getIntExtra(MapActivity.RESULT_ROUTE_INDEX, 0));
                pendingRouteOptionIndex = selectedRouteIndex;
                startNavigation(destination);
                if (data.getBooleanExtra(MapActivity.RESULT_OPEN_NAVIGATION_MAP, false)) openNavigationMap(destination, selectedRouteIndex);
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_BACKUP) {
            new Thread(() -> {
                try {
                    backupManager.exportTo(uri);
                    runOnUiThread(() -> setStatus("پشتیبان در محل انتخاب‌شده ذخیره شد."));
                } catch (Exception error) {
                    runOnUiThread(() -> setStatus("ذخیره پشتیبان انجام نشد."));
                }
            }).start();
        } else if (requestCode == REQ_IMPORT_BACKUP) {
            confirmRestore(uri);
        }
    }

    private void openMap() {
        if (navigationEngine.isNavigating() && activeDestination != null) {
            openNavigationMap(activeDestination);
            return;
        }
        Intent intent = new Intent(this, MapActivity.class);
        Location location = locationTracker.getLastLocation();
        if (location != null) {
            intent.putExtra(MapActivity.EXTRA_ORIGIN_LATITUDE, location.getLatitude());
            intent.putExtra(MapActivity.EXTRA_ORIGIN_LONGITUDE, location.getLongitude());
        }
        // The encrypted runtime payload may contain only AI keys. Keep routing keys injected
        // by GitHub Actions available to the map as a fallback.
        intent.putExtra(MapActivity.EXTRA_NESHAN_KEY, routingKey("NESHAN_API_KEY", BuildConfig.NESHAN_API_KEY));
        intent.putExtra(MapActivity.EXTRA_MAPIR_KEY, routingKey("MAPIR_API_KEY", BuildConfig.MAPIR_API_KEY));
        intent.putExtra(MapActivity.EXTRA_TOMTOM_KEY, BuildConfig.TOMTOM_API_KEY);
        intent.putExtra(MapActivity.EXTRA_OPENROUTESERVICE_KEY, BuildConfig.OPENROUTESERVICE_API_KEY);
        startActivityForResult(intent, REQ_MAP);
    }

    private void openNavigationMap(SavedPlace destination) {
        openNavigationMap(destination, 0);
    }

    private void openNavigationMap(SavedPlace destination, int selectedRouteIndex) {
        Intent intent = new Intent(this, MapActivity.class);
        Location location = locationTracker.getLastLocation();
        if (location != null) {
            intent.putExtra(MapActivity.EXTRA_ORIGIN_LATITUDE, location.getLatitude());
            intent.putExtra(MapActivity.EXTRA_ORIGIN_LONGITUDE, location.getLongitude());
        }
        intent.putExtra(MapActivity.EXTRA_NESHAN_KEY, routingKey("NESHAN_API_KEY", BuildConfig.NESHAN_API_KEY));
        intent.putExtra(MapActivity.EXTRA_MAPIR_KEY, routingKey("MAPIR_API_KEY", BuildConfig.MAPIR_API_KEY));
        intent.putExtra(MapActivity.EXTRA_TOMTOM_KEY, BuildConfig.TOMTOM_API_KEY);
        intent.putExtra(MapActivity.EXTRA_OPENROUTESERVICE_KEY, BuildConfig.OPENROUTESERVICE_API_KEY);
        intent.putExtra(MapActivity.EXTRA_NAVIGATION_MODE, true);
        intent.putExtra(MapActivity.EXTRA_DESTINATION_LATITUDE, destination.latitude);
        intent.putExtra(MapActivity.EXTRA_DESTINATION_LONGITUDE, destination.longitude);
        intent.putExtra(MapActivity.EXTRA_DESTINATION_NAME, destination.name);
        intent.putExtra(MapActivity.EXTRA_DESTINATION_ADDRESS, destination.address);
        intent.putExtra(MapActivity.EXTRA_NAVIGATION_ROUTE_INDEX, selectedRouteIndex);
        startActivityForResult(intent, REQ_MAP);
    }

    private String routingKey(String name, String buildConfigFallback) {
        String runtimeValue = runtimeKeys == null ? null : runtimeKeys.get(name);
        return runtimeValue == null || runtimeValue.trim().isEmpty() ? buildConfigFallback : runtimeValue;
    }

    private void showBackupDialog() {
        String[] options = {
                "ساخت نسخه خودکار در گوشی",
                "ذخیره دستی یا Google Drive",
                "ارسال فایل پشتیبان",
                "بازگردانی از فایل"
        };
        new AlertDialog.Builder(this).setTitle("پشتیبان‌گیری و بازیابی")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) createLocalBackup();
                    else if (which == 1) exportBackup();
                    else if (which == 2) shareBackup();
                    else importBackup();
                }).show();
    }

    private void createLocalBackup() {
        new Thread(() -> {
            try {
                backupManager.writeAutomaticSnapshot();
                runOnUiThread(() -> setStatus("نسخه پشتیبان در حافظه برنامه ساخته شد."));
            } catch (Exception error) {
                runOnUiThread(() -> setStatus("ساخت پشتیبان انجام نشد."));
            }
        }).start();
    }

    private void exportBackup() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType(BackupManager.MIME_TYPE);
        intent.putExtra(Intent.EXTRA_TITLE, backupManager.suggestedFileName());
        startActivityForResult(intent, REQ_EXPORT_BACKUP);
    }

    private void shareBackup() {
        new Thread(() -> {
            try {
                File file = backupManager.createShareSnapshot();
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
                runOnUiThread(() -> {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType(BackupManager.MIME_TYPE);
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "ارسال پشتیبان DriveMate"));
                });
            } catch (Exception error) {
                runOnUiThread(() -> setStatus("آماده‌سازی فایل پشتیبان انجام نشد."));
            }
        }).start();
    }

    private void importBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(BackupManager.MIME_TYPE);
        startActivityForResult(intent, REQ_IMPORT_BACKUP);
    }

    private void confirmRestore(Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle("بازگردانی پشتیبان")
                .setMessage("مکان‌ها، مقصدهای اخیر، سفرها و تنظیمات فعلی با نسخه پشتیبان جایگزین می‌شوند.")
                .setPositiveButton("بازگردانی", (dialog, which) -> new Thread(() -> {
                    try {
                        backupManager.restoreFrom(uri);
                        backupManager.writeAutomaticSnapshot();
                        runOnUiThread(() -> {
                            refreshNotificationButton();
                            refreshList();
                            setStatus("اطلاعات از پشتیبان بازگردانی شد.");
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> setStatus("فایل پشتیبان معتبر نیست یا بازگردانی نشد."));
                    }
                }).start())
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void writeAutomaticBackup() {
        new Thread(() -> {
            try { backupManager.writeAutomaticSnapshot(); }
            catch (Exception ignored) { }
        }).start();
    }

    private void toggleVoiceInput() {
        if (recordingOnlineSpeech) {
            finishOnlineRecording();
            return;
        }
        if (recordingLocalSpeech) {
            localSpeechRecognizer.cancel();
            restoreVoiceButton();
            return;
        }
        if (runtimeKeysLoading && !onlineSpeechClient.canUseOnlineSpeech()) {
            if (!startLocalVoiceRecognition()) {
                voiceRequestedWhileKeysLoad = true;
                setStatus("در حال آماده‌سازی سرویس گفتار GapGPT...");
            }
            return;
        }
        if (onlineSpeechClient.canUseOnlineSpeech() && onlineSpeechClient.startRecording()) {
            recordingOnlineSpeech = true;
            voicePlayer.announce("listening", "در حال گوش دادن هستم.");
            voiceButton.setText("در حال ضبط... برای پایان لمس کنید");
            setStatus("در حال ضبط با کیفیت بالا است. پس از ۱۰ ثانیه خودکار ارسال می‌شود.");
            voiceHandler.postDelayed(automaticStop, 10000L);
            return;
        }
        if (!startLocalVoiceRecognition()) {
            setStatus("سرویس گفتار آنلاین آماده نیست و تشخیص گفتار آفلاین فارسی روی این گوشی در دسترس نیست.");
        }
    }

    private boolean startLocalVoiceRecognition() {
        boolean started = localSpeechRecognizer.start(new LocalSpeechRecognizer.Callback() {
            @Override public void onText(String text) { runOnUiThread(() -> {
                restoreVoiceButton();
                handleVoiceText(text);
            }); }
            @Override public void onError() { runOnUiThread(() -> {
                restoreVoiceButton();
                setStatus("تشخیص گفتار آفلاین انجام نشد.");
            }); }
        });
        if (!started) return false;
        recordingLocalSpeech = true;
        voiceButton.setText("در حال گوش دادن آفلاین...");
        setStatus("تشخیص گفتار آفلاین فعال است.");
        return true;
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
        recordingLocalSpeech = false;
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
            case FIND_REST:
                searchAndNavigate("مجتمع خدماتی");
                break;
            case FIND_PLACE:
                suggestNearbyPlace(command.poiCategory);
                break;
            case CONFIRM_SUGGESTION:
                confirmPendingSuggestion();
                break;
            case DECLINE_SUGGESTION:
                declinePendingSuggestion();
                break;
            case FUEL_REFILLED:
                smartCompanion.resetFuelDistance();
                voicePlayer.announce("fuel_refilled", "باشه، شمارش مسافت از آخرین سوخت‌گیری از نو شروع شد.");
                setStatus("شمارش مسافت سوخت بازنشانی شد.");
                break;
            case VOLUME_UP:
                voicePlayer.increaseVolume();
                voicePlayer.announce("voice_louder", "صدای راهنما بیشتر شد.");
                setStatus("صدای راهنما بیشتر شد.");
                break;
            case VOLUME_DOWN:
                voicePlayer.decreaseVolume();
                voicePlayer.announce("voice_lower", "صدای راهنما کمتر شد.");
                setStatus("صدای راهنما کمتر شد.");
                break;
            case REPEAT:
                if (isFullIntelligenceMode() && !lastInstructionText.isEmpty()) {
                    speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                            "راهنمای قبلی مسیر را با یک جمله فارسی کوتاه و طبیعی تکرار کن: " + lastInstructionText,
                            lastInstruction, lastInstructionText, 10_000L);
                } else {
                    voicePlayer.play(lastInstruction);
                }
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
            voicePlayer.announce("gps_lost", "موقعیت مکانی هنوز در دسترس نیست.");
            return;
        }
        String finalName = (name == null || name.trim().isEmpty()) ? "مکان جدید" : name.trim();
        new Thread(() -> {
            String address = AddressResolver.resolve(this, location.getLatitude(), location.getLongitude());
            SavedPlace place = new SavedPlace(finalName, kind, location.getLatitude(), location.getLongitude(), address, System.currentTimeMillis(), true);
            placeStore.upsert(place);
            writeAutomaticBackup();
            runOnUiThread(() -> {
                String savedFallback = "home".equals(kind) ? "آدرس خانه ذخیره شد." : "work".equals(kind) ? "آدرس محل کار ذخیره شد." : finalName + " ذخیره شد.";
                voicePlayer.announce("home".equals(kind) ? "home_saved" : "work".equals(kind) ? "work_saved" : "place_saved", savedFallback);
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
        intelligenceCoordinator.cancelAll();
        final long requestSequence = ++routeRequestSequence;
        final int preferredRouteIndex = pendingRouteOptionIndex;
        pendingRouteOptionIndex = 0;
        voiceHandler.removeCallbacks(tripAnalysisHide);
        hideTripAnalysis();
        if (!routeRepository.hasConfiguredProvider()) {
            setStatus("کلید مسیریابی نشان، map.ir یا OpenRouteService در این APK موجود نیست. GitHub Secrets را بررسی کنید.");
            return;
        }
        Location origin = locationTracker.getLastLocation();
        if (origin == null) {
            setStatus("برای شروع مسیر، GPS باید آماده باشد.");
            voicePlayer.announce("gps_lost", "موقعیت مکانی هنوز در دسترس نیست.");
            return;
        }
        setStatus("در حال دریافت مسیر به " + destination.name + "...");
        showRouteAnalysisLoading(destination);
        if (!isFullIntelligenceMode()) {
            voicePlayer.announce("searching_route", "در حال یافتن مسیر هستم.");
        }
        final double originLatitude = origin.getLatitude();
        final double originLongitude = origin.getLongitude();
        routeRepository.getRoutes(originLatitude, originLongitude, destination.latitude, destination.longitude,
                routes -> runOnUiThread(() -> {
                    if (routes == null || routes.isEmpty()) return;
                    RouteResult route = routes.get(Math.min(preferredRouteIndex, routes.size() - 1));
                    if (requestSequence != routeRequestSequence) return;
                    placeStore.addRecent(destination);
                    tripStore.add(new TripRecord(destination.name, originLatitude, originLongitude, destination.latitude, destination.longitude,
                            route.distanceMeters, route.durationSeconds, System.currentTimeMillis()));
                    writeAutomaticBackup();
                    activeDestination = destination;
                    activeRoute = route;
                    tripStartedAt = System.currentTimeMillis();
                    activeTripDistanceMeters = route.distanceMeters;
                    smartCompanion.start();
                    startBackgroundNavigation();
                    lastTrafficEtaSeconds = route.durationSeconds;
                    lastTrafficEtaMeasuredAt = System.currentTimeMillis();
                    String firstRouteInstruction = route.steps.isEmpty() ? "<none>" : route.steps.get(0).instruction;
                    android.util.Log.i("DriveMateRoute", "provider=" + route.providerName + " steps=" + route.steps.size()
                            + " first=" + firstRouteInstruction);
                    navigationEngine.start(route, new NavigationEngine.Listener() {
                        @Override public void onInstruction(RouteStep step) {
                            runOnUiThread(() -> announceRouteStep(step));
                        }
                        @Override public void onOffRoute() {
                            runOnUiThread(() -> rerouteFromCurrentLocation());
                        }
                        @Override public void onArrived() {
                            runOnUiThread(() -> finishTrip(destination));
                        }
                    }, origin);
                    initialGuidanceHeldUntil = System.currentTimeMillis() + 2_600L;
                    lastInstruction = "start_navigation";
                    lastInstructionText = "مسیر به " + destination.name + " آماده است.";
                    setStatus("مسیر آماده است. سرویس: " + route.providerName + "، فاصله تقریبی: " + route.distanceMeters + " متر");
                    showTripAnalysis(route, destination);
                    voiceHandler.postDelayed(() -> {
                        if (requestSequence != routeRequestSequence || activeDestination != destination
                                || !navigationEngine.isNavigating()) return;
                        announceTripStart(route, destination);
                    }, 2_600L);
                    scheduleTrafficCheck();
                    refreshList();
                }),
                error -> runOnUiThread(() -> {
                    if (requestSequence != routeRequestSequence) return;
                    hideTripAnalysis();
                    voicePlayer.announce("api_error", "در دریافت مسیر خطایی رخ داد.");
                    setStatus("خطا در دریافت مسیر: " + error);
                }));
    }

    /** Keeps the dashboard's trip card current on every GPS sample instead of only right after
     *  the route is chosen, so ETA, remaining distance, elapsed time and speed never go stale
     *  while a trip is active. */
    private void updateTripStats(Location location) {
        if (tripStatsPanel == null) return;
        if (!navigationEngine.isNavigating() || activeRoute == null || activeDestination == null) {
            tripStatsPanel.setVisibility(View.GONE);
            return;
        }
        tripStatsPanel.setVisibility(View.VISIBLE);
        RouteStep step = navigationEngine.currentStep();
        int remainingMeters;
        if (step != null) {
            Location target = new Location("route");
            target.setLatitude(step.latitude);
            target.setLongitude(step.longitude);
            remainingMeters = Math.round(location.distanceTo(target));
            int index = navigationEngine.currentStepIndex();
            for (int i = index + 1; i < activeRoute.steps.size(); i++) remainingMeters += activeRoute.steps.get(i).distanceMeters;
        } else {
            remainingMeters = activeRoute.distanceMeters;
        }
        int totalMeters = Math.max(1, activeRoute.distanceMeters);
        double fraction = Math.max(0.02, Math.min(1.0, remainingMeters / (double) totalMeters));
        int remainingSeconds = (int) Math.round(activeRoute.durationSeconds * fraction);
        long arrivalAt = System.currentTimeMillis() + remainingSeconds * 1000L;
        int elapsedMinutes = tripStartedAt == 0L ? 0 : Math.max(0, (int) ((System.currentTimeMillis() - tripStartedAt) / 60_000L));
        float speedKmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
        tripEtaText.setText("رسیدن ساعت " + tripEtaFormat.format(new java.util.Date(arrivalAt))
                + " • " + formatTripDistance(remainingMeters) + " مانده");
        tripRemainingText.setText(Math.max(1, Math.round(remainingSeconds / 60f)) + " دقیقه مانده");
        tripElapsedText.setText(elapsedMinutes + " دقیقه طی شده");
        tripSpeedText.setText(Math.round(speedKmh) + " کیلومتر/ساعت");
    }

    private String formatTripDistance(int meters) {
        if (meters < 1000) return Math.max(0, meters) + " متر";
        return String.format(Locale.US, "%.1f کیلومتر", meters / 1000.0);
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

    /** Shows only provider-confirmed route facts; it never invents traffic or alternative routes. */
    private void showRouteAnalysisLoading(SavedPlace destination) {
        if (analysisPanel == null) return;
        analysisTitleText.setText("DriveMate AI | تحلیل مسیر");
        analysisBodyText.setText("در حال تحلیل مسیر تا " + destination.name + "...\n"
                + "بررسی زمان رسیدن\n"
                + "آماده‌سازی راهنمای صوتی\n"
                + "پایش دوره‌ای ترافیک و مسیر جایگزین پس از شروع سفر");
        analysisPanel.setVisibility(View.VISIBLE);
    }

    private void showTripAnalysis(RouteResult route, SavedPlace destination) {
        if (analysisPanel == null) return;
        int minutes = Math.max(1, (int) Math.ceil(route.durationSeconds / 60.0));
        double kilometers = route.distanceMeters / 1000.0;
        analysisTitleText.setText("DriveMate AI | تحلیل مسیر");
        analysisBodyText.setText("بررسی مسیر انجام شد\n"
                + "زمان تقریبی: " + minutes + " دقیقه\n"
                + "مسافت: " + String.format(Locale.US, "%.1f", kilometers) + " کیلومتر\n"
                + "مسیر پیشنهادی از " + route.providerName + " آماده است. پایش دوره‌ای ترافیک فعال است.");
        analysisPanel.setVisibility(View.VISIBLE);
        voiceHandler.removeCallbacks(tripAnalysisHide);
        voiceHandler.postDelayed(tripAnalysisHide, 2_800L);
    }

    private void hideTripAnalysis() {
        if (analysisPanel != null) analysisPanel.setVisibility(View.GONE);
    }

    private void announceTripStart(RouteResult route, SavedPlace destination) {
        int minutes = Math.max(1, (int) Math.ceil(route.durationSeconds / 60.0));
        String firstInstruction = firstRouteInstruction(route);
        String fallback = "مسیر " + destination.name + " آماده است. حدود " + minutes + " دقیقه زمان دارد. "
                + (firstInstruction.isEmpty() ? "با احتیاط حرکت کنید." : firstInstruction);
        String prompt = "شروع سفر به " + destination.name + " است. زمان تقریبی " + minutes
                + " دقیقه است. " + (firstInstruction.isEmpty() ? "" : "نخستین راهنما: " + firstInstruction + ". ")
                + "در یک یا دو جمله فارسی بسیار کوتاه و طبیعی بگو: خودت را همراه راننده معرفی کن، وضعیت مسیر را پایش می‌کنی، "
                + "و در صورت وجود، نخستین راهنما را دقیق بگو. از ادعای ترافیک یا مسیر جایگزین بدون داده خودداری کن.";
        if (isFullIntelligenceMode()) {
            speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING, prompt, null, fallback, 20_000L);
        } else {
            voicePlayer.speak(fallback);
            setStatus("خلاصه مسیر با راهنمای محلی پخش شد.");
        }
    }

    private String firstRouteInstruction(RouteResult route) {
        if (route.steps == null || route.steps.isEmpty()) return "";
        String instruction = route.steps.get(0).instruction == null ? "" : route.steps.get(0).instruction.trim();
        if (instruction.length() > 110) instruction = instruction.substring(0, 110);
        return instruction;
    }

    private void finishTrip(SavedPlace destination) {
        if (activeDestination == null) return;
        int minutes = tripStartedAt == 0L ? 0 : Math.max(1, (int) ((System.currentTimeMillis() - tripStartedAt) / 60_000L));
        double kilometers = activeTripDistanceMeters / 1000.0;
        String fallback = "به مقصد رسیدید. سفر حدود " + minutes + " دقیقه و "
                + String.format(Locale.US, "%.1f", kilometers) + " کیلومتر بود.";
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                "سفر به " + destination.name + " پایان یافته است. مدت سفر " + minutes + " دقیقه و مسافت "
                        + String.format(Locale.US, "%.1f", kilometers)
                        + " کیلومتر است. یک پیام فارسی کوتاه، طبیعی و دوستانه برای پایان سفر بگو.",
                "destination_arrived", fallback, 15_000L);
        setStatus("به " + destination.name + " رسیدید.");
        activeDestination = null;
        activeRoute = null;
        tripStartedAt = 0L;
        activeTripDistanceMeters = 0;
        initialGuidanceHeldUntil = 0L;
        smartCompanion.stop();
        voiceHandler.removeCallbacks(trafficCheck);
        stopBackgroundNavigation();
        hideTripAnalysis();
        if (tripStatsPanel != null) tripStatsPanel.setVisibility(View.GONE);
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
            StringBuilder found = new StringBuilder();
            for (String name : new String[]{"GAPGPT_API_KEY", "LIARA_API_KEY", "AI_API_KEY", "NESHAN_API_KEY", "MAPIR_API_KEY"}) {
                if (runtimeKeys.has(name)) found.append(name).append(' ');
            }
            android.util.Log.d("DriveMateKeys", found.length() == 0
                    ? "no runtime keys were parsed from either URL — online AI/TTS will always fall back to offline text"
                    : "runtime keys parsed: " + found);
            runOnUiThread(() -> {
                runtimeKeysLoading = false;
                boolean onlineReady = onlineSpeechClient.canUseOnlineSpeech();
                refreshAiStatus();
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
                    writeAutomaticBackup();
                    voicePlayer.announce("place_saved", "مکان ذخیره شد.");
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
        requestIntelligence(DrivingIntelligenceCoordinator.Priority.CONVERSATION, question,
                "پاسخ آنلاین در دسترس نیست.", true, 45_000L);
    }

    private void requestIntelligence(DrivingIntelligenceCoordinator.Priority priority, String prompt, String fallback,
                                     boolean onlineInEconomy, long expiresInMs) {
        setStatus("در حال آماده کردن پاسخ صوتی...");
        intelligenceCoordinator.request(priority, prompt, drivingContext(), fallback, onlineInEconomy, expiresInMs,
                (id, text, online) -> runOnUiThread(() -> {
                    onlineSpeechClient.stopPlayback();
                    voicePlayer.interrupt();
                    if (online) {
                        speakShort(text);
                    } else {
                        voicePlayer.speak(text);
                        setStatus("پاسخ آفلاین پخش شد.");
                    }
                }));
    }

    private boolean isFullIntelligenceMode() {
        return readIntelligenceMode() == DrivingIntelligenceCoordinator.Mode.FULL;
    }

    /** Uses the local clip immediately in economy mode; full mode gives online AI/TTS first refusal. */
    private void speakDrivingEvent(DrivingIntelligenceCoordinator.Priority priority, String prompt, String clipName,
                                   String fallback, long expiresInMs) {
        // The route engine immediately speaks the first real maneuver; avoid a second generic
        // "start moving" prompt that would delay the actionable instruction.
        if ("start_navigation".equals(clipName) && navigationEngine.hasActionableCurrentInstruction()) return;
        if (isFullIntelligenceMode()) {
            setStatus("\u062f\u0631 \u062d\u0627\u0644 \u0622\u0645\u0627\u062f\u0647 \u06a9\u0631\u062f\u0646 \u067e\u0627\u0633\u062e \u0635\u0648\u062a\u06cc \u0647\u0648\u0634\u0645\u0646\u062f...");
            final AtomicBoolean delivered = new AtomicBoolean(false);
            final long watchdogDelay = priority == DrivingIntelligenceCoordinator.Priority.SAFETY ? 2_000L : 3_750L;
            voiceHandler.postDelayed(() -> {
                if (!delivered.compareAndSet(false, true)) return;
                playOnlineTtsFallback(clipName, fallback);
            }, watchdogDelay);
            intelligenceCoordinator.request(priority, prompt, drivingContext(), fallback, false, expiresInMs,
                    (id, text, online) -> runOnUiThread(() -> {
                        if (!delivered.compareAndSet(false, true)) return;
                        if (online) {
                            speakShort(text, clipName, fallback);
                        } else {
                            playOnlineTtsFallback(clipName, fallback);
                        }
                    }));
        } else if (clipName != null) {
            voicePlayer.announce(clipName, fallback);
        } else {
            voicePlayer.speak(fallback);
        }
    }

    private void playDrivingFallback(String clipName, String fallback) {
        onlineSpeechClient.stopPlayback();
        voicePlayer.interrupt();
        if (clipName != null) voicePlayer.announce(clipName, fallback);
        else voicePlayer.speak(fallback);
    }

    /** Uses online TTS for a deterministic fallback sentence before resorting to a packaged WAV. */
    private void playOnlineTtsFallback(String clipName, String fallback) {
        final AtomicBoolean finished = new AtomicBoolean(false);
        Runnable localFallback = () -> {
            if (!finished.compareAndSet(false, true)) return;
            playDrivingFallback(clipName, fallback);
            setStatus(clipName == null
                    ? "صدای آنلاین در دسترس نبود؛ راهنمای محلی پخش شد."
                    : "صدای آنلاین در دسترس نبود؛ هشدار WAV پخش شد.");
        };
        setStatus("\u0645\u062f\u0644 \u0628\u0647\u200c\u0645\u0648\u0642\u0639 \u0646\u0631\u0633\u06cc\u062f\u061b \u062f\u0631 \u062d\u0627\u0644 \u062f\u0631\u06cc\u0627\u0641\u062a \u0635\u062f\u0627\u06cc \u0622\u0646\u0644\u0627\u06cc\u0646...");
        voiceHandler.postDelayed(localFallback, 2500L);
        onlineSpeechClient.speak(fallback, new OnlineSpeechClient.SpeechCallback() {
            @Override public void onPlayed() { runOnUiThread(() -> {
                if (finished.compareAndSet(false, true)) setStatus("متن مسیر با "
                        + onlineSpeechClient.getLastTtsProvider() + " پخش شد.");
            }); }
            @Override public void onError() { runOnUiThread(localFallback); }
        });
    }

    private void playPreparedOrRequest(String key, DrivingIntelligenceCoordinator.Priority priority, String prompt,
                                       String fallback, long expiresInMs) {
        String prepared = intelligenceCoordinator.consumePrepared(key);
        if (prepared != null) speakShort(prepared);
        else requestIntelligence(priority, prompt, fallback, false, expiresInMs);
    }

    private void handleSmartEvent(String event, String facts) {
        if (!navigationEngine.isNavigating()) return;
        if ("speed".equals(event)) {
            requestIntelligence(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "رویداد ایمنی GPS: " + facts + " یک هشدار فارسی بسیار کوتاه و آرام برای کاهش سرعت بگو.",
                    "لطفاً سرعت خود را کم کنید.", false, 12_000L);
            return;
        }
        if ("slow".equals(event)) {
            requestIntelligence(DrivingIntelligenceCoordinator.Priority.DRIVING,
                    "رویداد رانندگی: " + facts + " یک هشدار کوتاه و بدون ادعای ترافیک زنده بگو.",
                    "حرکت مسیر کند است؛ با احتیاط ادامه دهید.", false, 20_000L);
            return;
        }
        if ("rest_prepare".equals(event)) {
            intelligenceCoordinator.prefetch("rest-reminder", DrivingIntelligenceCoordinator.Priority.DRIVING,
                    "حدود دو ساعت رانندگی پیوسته نزدیک است. یک یادآوری فارسی کوتاه، آرام و عملی برای استراحت در محل امن بگو.",
                    drivingContext(), 25 * 60_000L);
            return;
        }
        if ("rest".equals(event)) {
            playPreparedOrRequest("rest-reminder", DrivingIntelligenceCoordinator.Priority.DRIVING,
                    "یادآوری ایمنی: بیش از دو ساعت رانندگی پیوسته بدون توقف ده دقیقه‌ای ثبت شده است. یک هشدار فارسی کوتاه و عملی برای استراحت بگو.",
                    "حدود دو ساعت رانندگی کرده‌اید؛ در اولین محل امن کمی استراحت کنید.", 25_000L);
            return;
        }
        if ("fatigue".equals(event)) {
            requestIntelligence(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "هشدار ایمنی غیرپزشکی: " + facts + " در یک جمله کوتاه و آرام پیشنهاد توقف در محل امن بده؛ ادعای تشخیص پزشکی نکن.",
                    "رانندگی پیوسته طولانی شده است؛ در اولین محل امن توقف و استراحت کنید.", false, 25_000L);
            return;
        }
        if ("fuel_low_guess".equals(event)) {
            suggestFuelStop();
            return;
        }
        switch (event) {
            case "traffic_reroute":
                rerouteForTraffic();
                break;
            case "fuel_check":
                voicePlayer.speak("حدود نود دقیقه از شروع سفر گذشته است. اگر نیاز به سوخت‌گیری دارید، بگویید پمپ بنزین.");
                break;
            case "fatigue_offline":
                voicePlayer.speak("بیش از دو ساعت است در حال رانندگی هستید. بهتر است در اولین فرصت استراحت کنید. برای پیدا کردن نزدیک‌ترین استراحتگاه بگویید «استراحتگاه».");
                break;
            default:
                break;
        }
    }

    private void rerouteForTraffic() {
        if (activeDestination == null) return;
        setStatus("ترافیک پایدار؛ در حال بررسی مسیر جایگزین...");
        startNavigation(activeDestination);
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                "حرکت مسیر برای مدتی کند بوده است و مسیر جایگزین در حال بررسی است. یک پیام کوتاه و طبیعی بگو.",
                "alternative_route", "در حال بررسی مسیر جایگزین هستم.", 15_000L);
    }

    private void findNearbyForCompanion(String term, String facts) {
        Location location = locationTracker.getLastLocation();
        if (location == null) { voicePlayer.speak("مکان نزدیک بدون GPS قابل پیدا کردن نیست."); return; }
        placeSearchRepository.search(term, location.getLatitude(), location.getLongitude(), place -> runOnUiThread(() -> {
            Location found = new Location("poi");
            found.setLatitude(place.latitude);
            found.setLongitude(place.longitude);
            int meters = Math.round(location.distanceTo(found));
            voicePlayer.speak("نزدیک‌ترین " + term + ": " + place.name + "، حدود " + meters + " متر فاصله.");
        }), error -> runOnUiThread(() -> voicePlayer.speak("مکان نزدیک تأیید نشد.")));
    }

    /** Entry point for every "نزدیک‌ترین X کجاست؟" style voice command (FIND_PLACE). Looks up the
     *  nearest match and speaks a distance-based suggestion; the driver confirms with "بله"/"باشه"
     *  (CONFIRM_SUGGESTION) to actually start navigation, matching the requested dialogue style. */
    private void suggestNearbyPlace(PoiCategory category) {
        if (category == null) return;
        Location location = locationTracker.getLastLocation();
        if (location == null) { setStatus("برای پیدا کردن " + category.label + "، GPS باید آماده باشد."); return; }
        setStatus("در حال پیدا کردن " + category.label + " در اطراف...");
        boolean nightPriority = isLateNight()
                && (category == PoiCategory.HOSPITAL || category == PoiCategory.CLINIC || category == PoiCategory.PHARMACY);
        // Real opening hours aren't available from the search provider; biasing the query text
        // toward "شبانه روزی" (24-hour) listings is an honest approximation, not verified live data.
        String term = nightPriority ? category.searchTerm + " شبانه روزی" : category.searchTerm;
        placeSearchRepository.searchAll(term, location.getLatitude(), location.getLongitude(),
                places -> runOnUiThread(() -> announceNearbySuggestion(category, places, location, nightPriority)),
                error -> runOnUiThread(() -> { setStatus(error); voicePlayer.speak(category.label + " در اطراف پیدا نشد."); }));
    }

    private void announceNearbySuggestion(PoiCategory category, List<SavedPlace> places, Location origin, boolean nightPriority) {
        if (places == null || places.isEmpty()) {
            voicePlayer.speak(category.label + " نزدیکی پیدا نشد.");
            return;
        }
        List<SavedPlace> sorted = new ArrayList<>(places);
        sorted.sort(Comparator.comparingDouble(place ->
                distanceKm(origin.getLatitude(), origin.getLongitude(), place.latitude, place.longitude)));
        SavedPlace nearest = sorted.get(0);
        double km = distanceKm(origin.getLatitude(), origin.getLongitude(), nearest.latitude, nearest.longitude);
        int etaMinutes = Math.max(1, (int) Math.round(km / 40.0 * 60.0));
        pendingSuggestionPlace = nearest;
        pendingSuggestionCategory = category;
        String distancePhrase = km < 1d ? Math.round(km * 1000) + " متر" : String.format(Locale.US, "%.1f کیلومتر", km);
        String message;
        if (category == PoiCategory.RESTAURANT || category == PoiCategory.COFFEE_SHOP) {
            message = sorted.size() + " " + category.label + " در نزدیکی مسیر است. نزدیک‌ترین حدود "
                    + etaMinutes + " دقیقه دیگر است. مسیر عوض شود؟";
        } else {
            message = "نزدیک‌ترین " + category.label + " " + distancePhrase + " فاصله دارد. مسیر عوض شود؟";
        }
        if (nightPriority) message += " (بر اساس عنوان شبانه‌روزی ثبت‌شده؛ ساعت کاری واقعی تأیید نشده است.)";
        setStatus(message);
        voicePlayer.speak(message);
    }

    private void confirmPendingSuggestion() {
        if (pendingSuggestionPlace == null) { voicePlayer.speak("در حال حاضر پیشنهادی برای تأیید وجود ندارد."); return; }
        SavedPlace place = pendingSuggestionPlace;
        pendingSuggestionPlace = null;
        pendingSuggestionCategory = null;
        startNavigation(place);
    }

    private void declinePendingSuggestion() {
        if (pendingSuggestionPlace == null) return;
        pendingSuggestionPlace = null;
        pendingSuggestionCategory = null;
        voicePlayer.speak("باشه، مسیر تغییر نمی‌کند.");
    }

    private boolean isLateNight() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 23 || hour < 6;
    }

    private double distanceKm(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double value = Math.sin(latitudeDelta / 2d) * Math.sin(latitudeDelta / 2d)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(longitudeDelta / 2d) * Math.sin(longitudeDelta / 2d);
        return 6371d * 2d * Math.atan2(Math.sqrt(value), Math.sqrt(1d - value));
    }

    /** Picks the nearest place for a proactive (non-voice-triggered) suggestion, arms the pending
     *  confirmation just like a voice-triggered one, and returns a Persian clause to fold into the
     *  existing "rest"/"fatigue"/"fuel_low_guess" spoken messages. Empty string if none found. */
    private String nearestPlaceClause(List<SavedPlace> places, Location origin, PoiCategory category) {
        if (places == null || places.isEmpty()) return "";
        List<SavedPlace> sorted = new ArrayList<>(places);
        sorted.sort(Comparator.comparingDouble(place ->
                distanceKm(origin.getLatitude(), origin.getLongitude(), place.latitude, place.longitude)));
        SavedPlace nearest = sorted.get(0);
        double km = distanceKm(origin.getLatitude(), origin.getLongitude(), nearest.latitude, nearest.longitude);
        pendingSuggestionPlace = nearest;
        pendingSuggestionCategory = category;
        String distancePhrase = km < 1d ? Math.round(km * 1000) + " متر" : String.format(Locale.US, "%.1f کیلومتر", km);
        return " نزدیک‌ترین " + category.label + " (" + nearest.name + ") " + distancePhrase
                + " فاصله دارد؛ برای مسیریابی به آنجا بگویید بله.";
    }

    /** Proactive version of the "rest" smart-event: same 2-hour reminder as before, now with the
     *  nearest restaurant/rest option looked up and offered for confirmation. */
    private void suggestRestStop() {
        Location location = locationTracker.getLastLocation();
        String baseFallback = "حدود دو ساعت رانندگی کرده‌اید؛ در اولین محل امن کمی استراحت کنید.";
        if (location == null) {
            playPreparedOrRequest("rest-reminder", DrivingIntelligenceCoordinator.Priority.DRIVING,
                    "یادآوری ایمنی: بیش از دو ساعت رانندگی پیوسته بدون توقف ده دقیقه‌ای ثبت شده است. یک هشدار فارسی کوتاه و عملی برای استراحت بگو.",
                    baseFallback, 25_000L);
            return;
        }
        placeSearchRepository.searchAll(PoiCategory.RESTAURANT.searchTerm, location.getLatitude(), location.getLongitude(),
                places -> runOnUiThread(() -> {
                    String clause = nearestPlaceClause(places, location, PoiCategory.RESTAURANT);
                    requestIntelligence(DrivingIntelligenceCoordinator.Priority.DRIVING,
                            "یادآوری ایمنی: بیش از دو ساعت رانندگی پیوسته ثبت شده است." + clause
                                    + " یک هشدار فارسی کوتاه و عملی برای استراحت بگو و همین مکان پیشنهادی را هم در جمله بیاور.",
                            baseFallback + clause, false, 25_000L);
                }),
                error -> runOnUiThread(() -> playPreparedOrRequest("rest-reminder", DrivingIntelligenceCoordinator.Priority.DRIVING,
                        "یادآوری ایمنی: بیش از دو ساعت رانندگی پیوسته ثبت شده. یک هشدار کوتاه بگو.", baseFallback, 25_000L)));
    }

    /** Proactive version of the "fatigue" smart-event: same 3-hour safety warning as before, now
     *  paired with the nearest coffee shop for a concrete place to pull over. */
    private void suggestFatigueBreak() {
        Location location = locationTracker.getLastLocation();
        String baseFallback = "رانندگی پیوسته طولانی شده است؛ در اولین محل امن توقف و استراحت کنید.";
        if (location == null) {
            requestIntelligence(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "هشدار ایمنی غیرپزشکی: بیش از سه ساعت رانندگی پیوسته ثبت شده است. در یک جمله کوتاه و آرام پیشنهاد توقف در محل امن بده؛ ادعای تشخیص پزشکی نکن.",
                    baseFallback, false, 25_000L);
            return;
        }
        placeSearchRepository.searchAll(PoiCategory.COFFEE_SHOP.searchTerm, location.getLatitude(), location.getLongitude(),
                places -> runOnUiThread(() -> {
                    String clause = nearestPlaceClause(places, location, PoiCategory.COFFEE_SHOP);
                    requestIntelligence(DrivingIntelligenceCoordinator.Priority.SAFETY,
                            "هشدار ایمنی غیرپزشکی: بیش از سه ساعت رانندگی پیوسته ثبت شده است." + clause
                                    + " در یک جمله کوتاه و آرام پیشنهاد توقف بده و همین مکان را بگو؛ ادعای تشخیص پزشکی نکن.",
                            baseFallback + clause, false, 25_000L);
                }),
                error -> runOnUiThread(() -> requestIntelligence(DrivingIntelligenceCoordinator.Priority.SAFETY,
                        "هشدار ایمنی غیرپزشکی: بیش از سه ساعت رانندگی پیوسته ثبت شده است. در یک جمله کوتاه پیشنهاد توقف بده.",
                        baseFallback, false, 25_000L)));
    }

    /** Handles the SmartDriveCompanion "fuel_low_guess" event: an approximate distance-based
     *  reminder (see SmartDriveCompanion's FUEL_GUESS_DISTANCE_METERS), paired with the nearest
     *  real gas station. The driver resets the counter by saying "بنزین زدم". */
    private void suggestFuelStop() {
        Location location = locationTracker.getLastLocation();
        String baseFallback = "مسافت قابل توجهی رانندگی کرده‌اید و ممکن است سوخت کم باشد. اگر سوخت‌گیری کرده‌اید بگویید «بنزین زدم».";
        if (location == null) { voicePlayer.speak(baseFallback); return; }
        placeSearchRepository.searchAll(PoiCategory.FUEL.searchTerm, location.getLatitude(), location.getLongitude(),
                places -> runOnUiThread(() -> {
                    String clause = nearestPlaceClause(places, location, PoiCategory.FUEL);
                    requestIntelligence(DrivingIntelligenceCoordinator.Priority.DRIVING,
                            "یادآوری تقریبی: مسافت زیادی از آخرین سوخت‌گیری تأییدشده رانندگی شده است؛ این تشخیص واقعی سطح سوخت نیست."
                                    + clause + " یک یادآوری فارسی کوتاه و آرام بگو.",
                            baseFallback + clause, false, 25_000L);
                }),
                error -> runOnUiThread(() -> voicePlayer.speak(baseFallback)));
    }

    private void speakShort(String answer) {
        speakShort(answer, null, null);
    }

    /** Plays a generated response, with a known navigation clip as the reliable final fallback. */
    private void speakShort(String answer, String fallbackClip, String fallbackText) {
        String shortAnswer = answer == null ? "" : answer.trim();
        if (shortAnswer.length() > 190) shortAnswer = shortAnswer.substring(0, 190);
        setStatus("در حال دریافت صدای آنلاین...");
        final String finalAnswer = shortAnswer;
        voicePlayer.interrupt();
        onlineSpeechClient.stopPlayback();
        onlineSpeechClient.speak(finalAnswer, new OnlineSpeechClient.SpeechCallback() {
            @Override public void onPlayed() { runOnUiThread(() -> setStatus("پاسخ هوشمند با "
                    + onlineSpeechClient.getLastTtsProvider() + " پخش شد.")); }
            @Override public void onError() { runOnUiThread(() -> {
                if (fallbackClip != null) {
                    voicePlayer.announce(fallbackClip, fallbackText);
                    setStatus("\u0635\u062f\u0627\u06cc \u0622\u0646\u0644\u0627\u06cc\u0646 \u062f\u0631 \u062f\u0633\u062a\u0631\u0633 \u0646\u06cc\u0633\u062a\u061b \u0647\u0634\u062f\u0627\u0631 WAV \u067e\u062e\u0634 \u0634\u062f.");
                    return;
                }
                voicePlayer.speak(finalAnswer);
                setStatus("متن مدل با صدای محلی پخش شد.");
            }); }
        });
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

    private DrivingIntelligenceCoordinator.Mode readIntelligenceMode() {
        String saved = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
                .getString(KEY_INTELLIGENCE_MODE, DrivingIntelligenceCoordinator.Mode.ECONOMY.name());
        try { return DrivingIntelligenceCoordinator.Mode.valueOf(saved); }
        catch (Exception ignored) { return DrivingIntelligenceCoordinator.Mode.ECONOMY; }
    }

    private void showSettingsMenu() {
        String mode = readIntelligenceMode() == DrivingIntelligenceCoordinator.Mode.FULL ? "هوشمند کامل" : "هوشمند اقتصادی";
        new AlertDialog.Builder(this).setTitle("تنظیمات")
                .setItems(new String[]{"تنظیمات صدا", "هوشمندی رانندگی: " + mode}, (dialog, which) -> {
                    if (which == 0) cycleVolume(); else showIntelligenceModeDialog();
                }).show();
    }

    private void showIntelligenceModeDialog() {
        DrivingIntelligenceCoordinator.Mode current = readIntelligenceMode();
        String[] options = {"هوشمند اقتصادی (پیشنهادی)", "هوشمند کامل"};
        new AlertDialog.Builder(this).setTitle("هوشمندی رانندگی")
                .setSingleChoiceItems(options, current == DrivingIntelligenceCoordinator.Mode.FULL ? 1 : 0, (dialog, which) -> {
                    DrivingIntelligenceCoordinator.Mode selected = which == 1
                            ? DrivingIntelligenceCoordinator.Mode.FULL : DrivingIntelligenceCoordinator.Mode.ECONOMY;
                    selectIntelligenceMode(selected);
                    dialog.dismiss();
                }).setNegativeButton("انصراف", null).show();
    }

    private void maybeShowIntelligenceOnboarding() {
        if (isFinishing() || getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
                .getBoolean(KEY_INTELLIGENCE_ONBOARDING_SHOWN, false)) return;
        new AlertDialog.Builder(this)
                .setTitle("هوشمندی رانندگی")
                .setMessage("حالت اقتصادی\nمصرف اینترنت کم و راهنمایی فوری محلی.\n\n"
                        + "حالت هوشمند کامل\nخلاصه طبیعی مسیر، تحلیل آنلاین و پاسخ صوتی هوشمند؛ "
                        + "در صورت تأخیر اینترنت، راهنمای محلی ادامه پیدا می‌کند.")
                .setPositiveButton("هوشمند کامل", (dialog, which) -> {
                    selectIntelligenceMode(DrivingIntelligenceCoordinator.Mode.FULL);
                    markOnboardingShown();
                })
                .setNegativeButton("اقتصادی", (dialog, which) -> {
                    selectIntelligenceMode(DrivingIntelligenceCoordinator.Mode.ECONOMY);
                    markOnboardingShown();
                })
                .setCancelable(false)
                .show();
    }

    private void markOnboardingShown() {
        getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).edit()
                .putBoolean(KEY_INTELLIGENCE_ONBOARDING_SHOWN, true).apply();
    }

    private void selectIntelligenceMode(DrivingIntelligenceCoordinator.Mode selected) {
        getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).edit()
                .putString(KEY_INTELLIGENCE_MODE, selected.name()).apply();
        intelligenceCoordinator.setMode(selected);
        intelligenceCoordinator.cancelAll();
        writeAutomaticBackup();
        refreshIntelligenceButton();
        refreshAiStatus();
        setStatus(selected == DrivingIntelligenceCoordinator.Mode.FULL
                ? "حالت هوشمند کامل فعال شد." : "حالت هوشمند اقتصادی فعال شد.");
    }

    private void refreshIntelligenceButton() {
        if (intelligenceButton == null) return;
        intelligenceButton.setText(readIntelligenceMode() == DrivingIntelligenceCoordinator.Mode.FULL
                ? "هوشمندی رانندگی: کامل" : "هوشمندی رانندگی: اقتصادی");
    }

    private void refreshAiStatus() {
        if (aiStatusText == null) return;
        if (runtimeKeysLoading) {
            aiStatusText.setText("هوشمندی رانندگی در حال آماده‌سازی");
        } else if (isFullIntelligenceMode() && onlineSpeechClient.canUseOnlineTts()) {
            aiStatusText.setText("تحلیل هوشمند فعال");
        } else if (isFullIntelligenceMode()) {
            aiStatusText.setText("هوشمند کامل؛ TTS آنلاین در دسترس نیست");
        } else {
            aiStatusText.setText("حالت اقتصادی");
        }
    }

    private void cycleVolume() {
        final String[] choices = {"افزایش صدای راهنما", "کاهش صدای راهنما"};
        new AlertDialog.Builder(this).setTitle("تنظیمات صدا").setItems(choices, (d, which) -> {
            if (which == 0) { voicePlayer.increaseVolume(); voicePlayer.announce("voice_louder", "صدای راهنما بیشتر شد."); setStatus("صدای راهنما بیشتر شد."); }
            else { voicePlayer.decreaseVolume(); voicePlayer.announce("voice_lower", "صدای راهنما کمتر شد."); setStatus("صدای راهنما کمتر شد."); }
        }).show();
    }

    private void selectMainTab(int tab) {
        dashboardPage.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        savedPlacesPage.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        profilePage.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        ((Button) findViewById(R.id.tabDashboardButton)).setAlpha(tab == 0 ? 1f : 0.62f);
        ((Button) findViewById(R.id.tabSavedButton)).setAlpha(tab == 1 ? 1f : 0.62f);
        ((Button) findViewById(R.id.tabProfileButton)).setAlpha(tab == 2 ? 1f : 0.62f);
        ((Button) findViewById(R.id.tabMapButton)).setAlpha(0.62f);
        if (tab == 1) refreshSavedPlacesTab();
    }

    private void refreshSavedPlacesTab() {
        if (savedPlacesTabText == null) return;
        List<SavedPlace> places = placeStore.allPlaces();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < places.size(); i++) {
            SavedPlace place = places.get(i);
            text.append(i + 1).append(". ").append(place.name);
            if (place.address != null && !place.address.trim().isEmpty()) text.append("\n").append(place.address);
            text.append("\n\n");
        }
        savedPlacesTabText.setText(text.length() == 0 ? "هنوز مکانی ذخیره نشده است." : text.toString().trim());
        savedPlacesTabText.setOnClickListener(v -> choosePlace(new ArrayList<>(placeStore.allPlaces())));
    }

    private void showSubscriptionInfo() {
        new AlertDialog.Builder(this)
                .setTitle("اشتراک")
                .setMessage("مدیریت اشتراک در نسخه بعدی فعال می‌شود. امکانات فعلی برنامه بدون تغییر در دسترس هستند.")
                .setPositiveButton("متوجه شدم", null)
                .show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("درباره DriveMate AI")
                .setMessage("دستیار رانندگی فارسی با مسیریابی، راهنمای صوتی، نقشه، مکان‌های ذخیره‌شده و پشتیبان‌گیری محلی.")
                .setPositiveButton("بستن", null)
                .show();
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
        new AlertDialog.Builder(this)
                .setTitle(place.name)
                .setItems(new String[]{"شروع مسیریابی", "ویرایش نام", "حذف مکان"}, (dialog, action) -> {
                    if (action == 0) {
                        startNavigation(place);
                    } else if (action == 1) {
                        editSavedPlaceName(place);
                    } else {
                        confirmDeleteSavedPlace(place);
                    }
                })
                .show();
    }

    private void editSavedPlaceName(SavedPlace place) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(place.name);
        new AlertDialog.Builder(this)
                .setTitle("ویرایش نام مکان")
                .setView(input)
                .setPositiveButton("ذخیره", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    placeStore.upsert(new SavedPlace(name, place.kind, place.latitude, place.longitude,
                            place.address, System.currentTimeMillis(), place.favorite));
                    writeAutomaticBackup();
                    refreshList();
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void confirmDeleteSavedPlace(SavedPlace place) {
        new AlertDialog.Builder(this)
                .setTitle("حذف مکان")
                .setMessage("«" + place.name + "» از ذخیره‌ها حذف شود؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    placeStore.delete(place);
                    writeAutomaticBackup();
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
        refreshSavedPlacesTab();
    }

    private void scheduleTrafficCheck() {
        voiceHandler.removeCallbacks(trafficCheck);
        if (navigationEngine.isNavigating() && activeDestination != null) {
            voiceHandler.postDelayed(trafficCheck, TRAFFIC_CHECK_INTERVAL_MS);
        }
    }

    /**
     * Requests a fresh traffic-aware route at a bounded cadence. The current route is replaced
     * only when the returned ETA beats the elapsed-time-adjusted previous ETA by a useful margin.
     */
    private void checkTrafficAndMaybeReroute() {
        if (!navigationEngine.isNavigating() || activeDestination == null) return;
        Location location = locationTracker.getLastLocation();
        if (location == null) { scheduleTrafficCheck(); return; }
        final SavedPlace destination = activeDestination;
        final int priorEtaSeconds = lastTrafficEtaSeconds;
        final long priorEtaMeasuredAt = lastTrafficEtaMeasuredAt;
        routeRepository.getRoute(location.getLatitude(), location.getLongitude(), destination.latitude, destination.longitude,
                route -> runOnUiThread(() -> {
                    if (!navigationEngine.isNavigating() || activeDestination != destination) return;
                    long now = System.currentTimeMillis();
                    int elapsedSeconds = priorEtaMeasuredAt == 0L ? 0 : (int) ((now - priorEtaMeasuredAt) / 1000L);
                    int expectedRemaining = Math.max(0, priorEtaSeconds - elapsedSeconds);
                    int gainSeconds = expectedRemaining - route.durationSeconds;
                    boolean materiallyFaster = expectedRemaining >= 300
                            && route.durationSeconds > 0
                            && gainSeconds >= TRAFFIC_REROUTE_MIN_GAIN_SECONDS
                            && gainSeconds * 100 >= expectedRemaining * 12;
                    lastTrafficEtaSeconds = route.durationSeconds;
                    lastTrafficEtaMeasuredAt = now;
                    if (materiallyFaster) replaceRouteForTraffic(route, destination, gainSeconds);
                    else scheduleTrafficCheck();
                }), error -> runOnUiThread(this::scheduleTrafficCheck));
    }

    private void replaceRouteForTraffic(RouteResult route, SavedPlace destination, int gainSeconds) {
        activeRoute = route;
        navigationEngine.start(route, new NavigationEngine.Listener() {
            @Override public void onInstruction(RouteStep step) { runOnUiThread(() -> announceRouteStep(step)); }
            @Override public void onOffRoute() { runOnUiThread(() -> rerouteFromCurrentLocation()); }
            @Override public void onArrived() { runOnUiThread(() -> finishTrip(destination)); }
        });
        setStatus("مسیر با ترافیک به‌روزرسانی شد؛ حدود " + Math.max(1, gainSeconds / 60) + " دقیقه سریع‌تر است.");
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                "مسیر ترافیک‌محور به " + destination.name + " حدود " + Math.max(1, gainSeconds / 60) + " دقیقه زمان بهتری دارد. یک هشدار صوتی بسیار کوتاه و آرام بگو.",
                "alternative_route", "مسیر سریع‌تری پیدا شد.", 20_000L);
        scheduleTrafficCheck();
    }

    private void setStatus(String message) {
        android.util.Log.i("DriveMateStatus", message);
        statusText.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void rerouteFromCurrentLocation() {
        if (activeDestination == null || locationTracker.getLastLocation() == null) return;
        setStatus("از مسیر خارج شدید؛ در حال محاسبه مسیر جدید...");
        startNavigation(activeDestination);
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                "کاربر از مسیر خارج شده است. یک هشدار خیلی کوتاه و آرام برای ادامه مسیر بگو.",
                "route_recalculated", "از مسیر خارج شدید؛ در حال محاسبه مسیر جدید هستم.", 15_000L);
    }

    private boolean backgroundNavigationEnabled() {
        return getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).getBoolean("background_navigation", true);
    }

    private void toggleBackgroundNavigation() {
        boolean enabled = !backgroundNavigationEnabled();
        getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).edit().putBoolean("background_navigation", enabled).apply();
        writeAutomaticBackup();
        if (enabled && navigationEngine.isNavigating()) startBackgroundNavigation();
        else if (!enabled) stopBackgroundNavigation();
        refreshNotificationButton();
        setStatus(enabled ? "اعلان و ادامه مسیریابی در پس‌زمینه فعال شد." : "اعلان و ادامه مسیریابی در پس‌زمینه غیرفعال شد.");
    }

    private void refreshNotificationButton() {
        if (notificationButton != null) notificationButton.setText(backgroundNavigationEnabled() ? "اعلان مسیریابی: روشن" : "اعلان مسیریابی: خاموش");
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
        ++routeRequestSequence;
        navigationEngine.stop();
        smartCompanion.stop();
        intelligenceCoordinator.cancelAll();
        voiceHandler.removeCallbacks(trafficCheck);
        voiceHandler.removeCallbacks(tripAnalysisHide);
        activeDestination = null;
        tripStartedAt = 0L;
        activeTripDistanceMeters = 0;
        initialGuidanceHeldUntil = 0L;
        hideTripAnalysis();
        stopBackgroundNavigation();
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                "مسیریابی متوقف شده است. یک پیام فارسی کوتاه و طبیعی برای راننده بگو.",
                "stop_navigation", message, 12_000L);
        setStatus(message);
        activeRoute = null;
        if (tripStatsPanel != null) tripStatsPanel.setVisibility(View.GONE);
    }

    private void registerNavigationReceiver() {
        IntentFilter filter = new IntentFilter(NavigationForegroundService.ACTION_STOP_BROADCAST);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(navigationStopReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(navigationStopReceiver, filter);
    }

    private void announceRouteStep(RouteStep step) {
        if (System.currentTimeMillis() < initialGuidanceHeldUntil) {
            lastInstruction = "continue_route";
            lastInstructionText = step.instruction == null ? "" : step.instruction;
            return;
        }
        onlineSpeechClient.stopPlayback();
        String text = step.instruction == null || step.instruction.trim().isEmpty() ? "ادامه مسیر" : step.instruction;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("camera") || text.contains("دوربین")) {
            speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "داده مسیر درباره دوربین سرعت هشدار داده است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام بگو.",
                    "speed_camera", "دوربین سرعت در مسیر است.", 10_000L);
            smartCompanion.routeHazard("دوربین سرعت");
            setStatus(text);
            return;
        }
        if (lower.contains("speed bump") || text.contains("دست انداز") || text.contains("سرعت گیر")) {
            speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.SAFETY,
                    "داده مسیر درباره دست‌انداز هشدار داده است. یک هشدار فارسی بسیار کوتاه، طبیعی و آرام بگو.",
                    "speed_bump_warning", "دست انداز در مسیر است.", 10_000L);
            smartCompanion.routeHazard("دست انداز");
            setStatus(text);
            return;
        }
        if (lower.contains("left") || text.contains("چپ")) lastInstruction = "turn_left";
        else if (lower.contains("right") || text.contains("راست")) lastInstruction = "turn_right";
        else if (lower.contains("arriv") || text.contains("مقصد")) lastInstruction = "destination_arrived";
        else lastInstruction = "continue_route";
        lastInstructionText = text;
        speakDrivingEvent(DrivingIntelligenceCoordinator.Priority.DRIVING,
                "دستور مسیریابی فعلی این است: " + text + ". آن را در یک جمله فارسی کوتاه، طبیعی و مناسب رانندگی بیان کن.",
                lastInstruction, text, 10_000L);
        setStatus(text);
    }

    @Override protected void onDestroy() {
        voiceHandler.removeCallbacks(automaticStop);
        voiceHandler.removeCallbacks(trafficCheck);
        onlineSpeechClient.cancelRecording();
        localSpeechRecognizer.destroy();
        intelligenceCoordinator.shutdown();
        smartCompanion.stop();
        voicePlayer.shutdown();
        unregisterReceiver(navigationStopReceiver);
        navigationEngine.stop();
        stopBackgroundNavigation();
        locationTracker.stop();
        super.onDestroy();
    }
}
