package ai.drivemate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

import ai.drivemate.ai.AiAssistant;
import ai.drivemate.ai.RuntimeKeys;
import ai.drivemate.location.DeviceLocationTracker;
import ai.drivemate.model.SavedPlace;
import ai.drivemate.routing.MapIrRoutingProvider;
import ai.drivemate.routing.NeshanRoutingProvider;
import ai.drivemate.routing.RouteRepository;
import ai.drivemate.storage.PlaceStore;
import ai.drivemate.voice.Command;
import ai.drivemate.voice.VoiceCommandParser;
import ai.drivemate.voice.VoiceGuidancePlayer;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 10;
    private static final int REQ_SPEECH = 20;

    private TextView statusText;
    private TextView listText;
    private PlaceStore placeStore;
    private VoiceGuidancePlayer voicePlayer;
    private DeviceLocationTracker locationTracker;
    private NeshanRoutingProvider neshanRoutingProvider;
    private MapIrRoutingProvider mapIrRoutingProvider;
    private RouteRepository routeRepository;
    private VoiceCommandParser commandParser;
    private AiAssistant aiAssistant;
    private RuntimeKeys runtimeKeys = new RuntimeKeys();
    private String lastInstruction = "start_navigation";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        listText = findViewById(R.id.listText);
        placeStore = new PlaceStore(this);
        voicePlayer = new VoiceGuidancePlayer(this);
        locationTracker = new DeviceLocationTracker(this);
        neshanRoutingProvider = new NeshanRoutingProvider(BuildConfig.NESHAN_API_KEY);
        mapIrRoutingProvider = new MapIrRoutingProvider(BuildConfig.MAPIR_API_KEY);
        routeRepository = new RouteRepository(neshanRoutingProvider, mapIrRoutingProvider);
        commandParser = new VoiceCommandParser();
        aiAssistant = new AiAssistant(BuildConfig.AI_API_KEY);

        wireButtons();
        requestCorePermissions();
        refreshList();
        voicePlayer.play("welcome");
        loadRuntimeKeys();
        promptEnableLocationIfNeeded();
    }

    private void wireButtons() {
        findViewById(R.id.voiceButton).setOnClickListener(v -> startVoiceInput());
        findViewById(R.id.saveButton).setOnClickListener(v -> promptSaveCurrentPlace());
        findViewById(R.id.homeButton).setOnClickListener(v -> navigateToKnownPlace("home"));
        findViewById(R.id.workButton).setOnClickListener(v -> navigateToKnownPlace("work"));
        findViewById(R.id.favoritesButton).setOnClickListener(v -> showPlaces(true));
        findViewById(R.id.recentButton).setOnClickListener(v -> showRecent());
        findViewById(R.id.settingsButton).setOnClickListener(v -> cycleVolume());
    }

    private void requestCorePermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
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

    private void startVoiceInput() {
        voicePlayer.play("listening");
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "فرمان رانندگی را بگویید");
        try {
            startActivityForResult(intent, REQ_SPEECH);
        } catch (Exception ex) {
            setStatus("تشخیص گفتار روی این دستگاه فعال نیست.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                handleVoiceText(results.get(0));
            }
        }
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
                } else {
                    voicePlayer.play("command_unknown");
                    askAi(text);
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
        SavedPlace place = new SavedPlace(
                (name == null || name.trim().isEmpty()) ? "مکان جدید" : name.trim(),
                kind,
                location.getLatitude(),
                location.getLongitude(),
                "آدرس تقریبی: " + String.format(Locale.US, "%.5f, %.5f", location.getLatitude(), location.getLongitude()),
                System.currentTimeMillis(),
                true
        );
        placeStore.upsert(place);
        voicePlayer.play("home".equals(kind) ? "home_saved" : "work".equals(kind) ? "work_saved" : "place_saved");
        setStatus(name + " ذخیره شد.");
        refreshList();
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
        Location origin = locationTracker.getLastLocation();
        if (origin == null) {
            setStatus("برای شروع مسیر، GPS باید آماده باشد.");
            voicePlayer.play("gps_lost");
            return;
        }
        setStatus("در حال دریافت مسیر به " + destination.name + "...");
        voicePlayer.play("searching_route");
        routeRepository.getRoute(origin.getLatitude(), origin.getLongitude(), destination.latitude, destination.longitude,
                route -> runOnUiThread(() -> {
                    placeStore.addRecent(destination);
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

    private void loadRuntimeKeys() {
        new Thread(() -> {
            runtimeKeys = RuntimeKeys.fetch(new String[]{
                    "https://abrehamrahi.ir/o/public/eUFcsXOX",
                    "https://gist.githubusercontent.com/ghadirb/626a804df3009e49045a2948dad89fe5/raw/c93c06d1b2f38c65ee30f092c134a89998326d12/keys.txt"
            }, BuildConfig.KEYS_DECRYPTION_SECRET);
            aiAssistant.setRuntimeKeys(runtimeKeys);
            neshanRoutingProvider.setApiKey(runtimeKeys.get("NESHAN_API_KEY"));
            mapIrRoutingProvider.setApiKey(runtimeKeys.get("MAPIR_API_KEY"));
            runOnUiThread(() -> setStatus(runtimeKeys.has("GAPGPT_API_KEY") || runtimeKeys.has("AI_API_KEY") ? "کلیدهای آنلاین فعال شدند." : "کلید آنلاین دریافت نشد؛ حالت آفلاین فعال است."));
        }).start();
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
        setStatus("در حال آماده کردن پاسخ هوشمند...");
        aiAssistant.answer(question, answer -> runOnUiThread(() -> setStatus(answer)));
    }

    private void cycleVolume() {
        voicePlayer.increaseVolume();
        setStatus("تنظیم صدا تغییر کرد.");
        voicePlayer.play("voice_louder");
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
}
