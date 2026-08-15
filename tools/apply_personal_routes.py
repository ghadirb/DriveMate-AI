from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/ai/drivemate/MainActivity.java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"


def replace_method(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"method signature not found: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"opening brace not found: {signature}")
    depth = 0
    in_string = False
    escaped = False
    in_line_comment = False
    in_block_comment = False
    i = brace
    while i < len(text):
        c = text[i]
        n = text[i + 1] if i + 1 < len(text) else ""
        if in_line_comment:
            if c == "\n": in_line_comment = False
        elif in_block_comment:
            if c == "*" and n == "/": in_block_comment = False; i += 1
        elif in_string:
            if escaped: escaped = False
            elif c == "\\": escaped = True
            elif c == '"': in_string = False
        else:
            if c == '"': in_string = True
            elif c == "/" and n == "/": in_line_comment = True; i += 1
            elif c == "/" and n == "*": in_block_comment = True; i += 1
            elif c == "{": depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return text[:start] + replacement + text[i + 1:]
        i += 1
    raise SystemExit(f"unclosed method: {signature}")


def add_once(text: str, needle: str, addition: str, where: str = "after") -> str:
    if addition.strip() in text:
        return text
    pos = text.find(needle)
    if pos < 0:
        raise SystemExit(f"needle not found: {needle}")
    if where == "after":
        pos += len(needle)
    return text[:pos] + addition + text[pos:]


text = JAVA.read_text(encoding="utf-8")
text = add_once(text,
    "import ai.drivemate.model.TripRecord;",
    "\nimport ai.drivemate.model.PersonalRoute;",
)

text = add_once(text,
    "        handleSharedIntent(getIntent());",
    "\n        handlePersonalRouteIntent(getIntent());",
)

text = add_once(text,
    "        if (ACTION_VOICE_FROM_NOTIFICATION.equals(intent.getAction())) voiceHandler.postDelayed(this::toggleVoiceInput, 350L);",
    "\n        handlePersonalRouteIntent(intent);",
)

text = text.replace(
    "                runtimeKeysLoading = false;",
    "                runtimeKeysLoading = false;\n                handlePersonalRouteIntent(getIntent());",
    1,
)

old_history = """    private void renderTripHistory() {\n        tripHistoryContent.removeAllViews();"""
new_history = """    private void renderTripHistory() {\n        tripHistoryContent.removeAllViews();\n        Button personalRoutes = new Button(this);\n        personalRoutes.setText(\"مسیرهای شخصی و نقاط اجباری\");\n        personalRoutes.setAllCaps(false);\n        personalRoutes.setOnClickListener(v -> startActivity(new Intent(this, PersonalRouteActivity.class)));\n        tripHistoryContent.addView(personalRoutes, new LinearLayout.LayoutParams(\n                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));"""
if old_history not in text:
    raise SystemExit("renderTripHistory anchor not found")
text = text.replace(old_history, new_history, 1)

show_detail = '''    private void showTripDetail(TripRecord record, boolean allowDelete) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("جزئیات سفر")
                .setMessage(tripDetailText(record))
                .setPositiveButton("نمایش مسیر روی نقشه", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(this, TripMapActivity.class);
                        intent.putExtra(TripMapActivity.EXTRA_TRIP_JSON, record.toJson().toString());
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "امکان باز کردن مسیر این سفر وجود ندارد.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("بستن", null);
        if (allowDelete) {
            builder.setNegativeButton("حذف", (dialog, which) -> {
                tripStore.remove(record.startedAt);
                writeAutomaticBackup();
                renderTripHistory();
            });
        }
        builder.show();
    }'''
text = replace_method(text, "    private void showTripDetail(TripRecord record, boolean allowDelete)", show_detail)

marker = "    private void handleSharedIntent(Intent intent) {"
helper = '''    private void handlePersonalRouteIntent(Intent intent) {
        if (intent == null || !PersonalRouteActivity.ACTION_START_PERSONAL_ROUTE.equals(intent.getAction())) return;
        if (runtimeKeysLoading) {
            voiceHandler.postDelayed(() -> handlePersonalRouteIntent(getIntent()), 700L);
            return;
        }
        String json = intent.getStringExtra(PersonalRouteActivity.EXTRA_PERSONAL_ROUTE_JSON);
        intent.setAction(null);
        if (json == null || json.trim().isEmpty()) return;
        try {
            PersonalRoute route = PersonalRoute.fromJson(new org.json.JSONObject(json));
            if (route.points.size() < 2) return;
            ai.drivemate.model.RoutePoint last = route.points.get(route.points.size() - 1);
            SavedPlace destination = new SavedPlace(route.name, "personal_route",
                    last.latitude, last.longitude, "مسیر شخصی", System.currentTimeMillis(), false);
            ArrayList<RoutePoint> mandatory = new ArrayList<>();
            for (int i = 0; i < route.points.size() - 1; i++) mandatory.add(route.points.get(i));
            startNavigation(destination, mandatory);
            setStatus("مسیریابی مسیر شخصی «" + route.name + "» با نقاط اجباری آغاز شد.");
        } catch (Exception e) {
            Toast.makeText(this, "مسیر شخصی قابل خواندن نیست.", Toast.LENGTH_SHORT).show();
        }
    }

'''
text = add_once(text, marker, helper, where="before")
JAVA.write_text(text, encoding="utf-8")

manifest = MANIFEST.read_text(encoding="utf-8")
activity_xml = '''        <activity android:name=".PersonalRouteActivity" android:exported="false" />\n        <activity android:name=".TripMapActivity" android:exported="false" />\n'''
if ".PersonalRouteActivity" not in manifest:
    marker = "</application>"
    manifest = manifest.replace(marker, activity_xml + marker, 1)
MANIFEST.write_text(manifest, encoding="utf-8")
print("Personal route and trip map integration applied")
