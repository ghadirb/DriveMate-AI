from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(rel):
    return (ROOT / rel).read_text(encoding='utf-8')

def write(rel, text):
    (ROOT / rel).write_text(text, encoding='utf-8')

def replace_once(rel, old, new, label):
    s = read(rel)
    if old not in s:
        raise SystemExit(f'{label}: marker not found in {rel}')
    write(rel, s.replace(old, new, 1))
    print('patched', label)

# NavigationEngine: arrival needs two good samples, a slightly wider final radius,
# and one GPS update may advance across several already-passed maneuvers.
NAV = 'app/src/main/java/ai/drivemate/routing/NavigationEngine.java'
replace_once(NAV, 'private static final float FINAL_ARRIVAL_RADIUS_METERS = 55f;',
             'private static final float FINAL_ARRIVAL_RADIUS_METERS = 100f;', 'arrival radius')
replace_once(NAV,
'''        if (accuracyOkFor(location, MAX_ACCURACY_FOR_ARRIVAL_METERS) && metersToDestination < FINAL_ARRIVAL_RADIUS_METERS) {
            finalArrivalConfirmSamples++;
        } else {
            finalArrivalConfirmSamples = 0;
        }''',
'''        boolean destinationCloseEnough = metersToDestination <= FINAL_ARRIVAL_RADIUS_METERS
                || (routeProgress != null && routeProgress.onRoute
                && routeProgress.remainingMeters <= 120 && metersToDestination <= 140f);
        if (accuracyOkFor(location, MAX_ACCURACY_FOR_ARRIVAL_METERS) && destinationCloseEnough) {
            finalArrivalConfirmSamples++;
        } else {
            finalArrivalConfirmSamples = 0;
        }''', 'arrival proximity confirmation')
old = '''    private void advancePastPassedSteps(Location location, RouteProgressTracker.Snapshot routeProgress) {
        if (routeProgress == null || !routeProgress.onRoute || !accuracyOk(location)
                || nextStep >= route.steps.size() - 1 || nextStep >= stepProgressMeters.length) {
            passedStepConfirmSamples = 0;
            return;
        }
        RouteStep target = route.steps.get(nextStep);
        if (target.waypointOrdinal >= 0 || Double.isNaN(stepProgressMeters[nextStep])
                || routeProgress.progressMeters < stepProgressMeters[nextStep] + PASSED_STEP_BUFFER_METERS) {
            passedStepConfirmSamples = 0;
            return;
        }
        passedStepConfirmSamples++;
        if (passedStepConfirmSamples < STEP_ADVANCE_CONFIRM_SAMPLES) return;
        passedStepConfirmSamples = 0;
        // Advance one maneuver per accepted GPS update. A mocked or delayed location stream can
        // leap across several short city blocks; advancing them all in a loop skips every spoken
        // left/right/roundabout instruction and leaves only the final generic message.
        nextStep = Math.min(nextStep + 1, route.steps.size() - 1);
        currentInstructionAnnounced = false;
        advanceConfirmSamples = 0;
        updateTargetReference(location);
        if (instructionAnnouncementsEnabled) announceCurrentInstruction();
    }
'''
new = '''    private void advancePastPassedSteps(Location location, RouteProgressTracker.Snapshot routeProgress) {
        if (routeProgress == null || !routeProgress.onRoute || !accuracyOk(location)
                || nextStep >= route.steps.size() - 1 || nextStep >= stepProgressMeters.length) {
            passedStepConfirmSamples = 0;
            return;
        }
        int furthestNextStep = nextStep;
        for (int index = nextStep; index < route.steps.size() - 1; index++) {
            RouteStep step = route.steps.get(index);
            if (step.waypointOrdinal >= 0 || index >= stepProgressMeters.length
                    || Double.isNaN(stepProgressMeters[index])) break;
            if (routeProgress.progressMeters >= stepProgressMeters[index] + PASSED_STEP_BUFFER_METERS) {
                furthestNextStep = index + 1;
            } else {
                break;
            }
        }
        if (furthestNextStep <= nextStep) {
            passedStepConfirmSamples = 0;
            return;
        }
        passedStepConfirmSamples++;
        if (passedStepConfirmSamples < STEP_ADVANCE_CONFIRM_SAMPLES) return;
        passedStepConfirmSamples = 0;
        // Fake/delayed GPS can cross several maneuver endpoints at once. Synchronize to the first
        // maneuver that is still ahead, then announce that actionable maneuver immediately.
        nextStep = Math.min(furthestNextStep, route.steps.size() - 1);
        currentInstructionAnnounced = false;
        advanceConfirmSamples = 0;
        updateTargetReference(location);
        if (instructionAnnouncementsEnabled) announceCurrentInstruction();
    }
'''
replace_once(NAV, old, new, 'multi-maneuver GPS synchronization')
replace_once(NAV, 'float corridorMeters = Math.max(80f, location.getAccuracy() * 2.5f);',
             'float corridorMeters = Math.max(100f, location.getAccuracy() * 3.0f);', 'off-route corridor')

# map.ir: machine-readable roundabout fields and the road name must win over generic turn text.
MIR = 'app/src/main/java/ai/drivemate/routing/MapIrRoutingProvider.java'
replace_once(MIR,
'''        android.util.Log.d("DriveMateManeuver", "type=" + type + " modifier=" + modifier
                + " exit=" + maneuver.optInt("exit", maneuver.optInt("roundaboutExitNumber", -1))
                + " road=" + road);
        if (type.isEmpty()) return explicit.isEmpty() ? "در مسیر ادامه دهید" : explicit;''',
'''        int roundaboutExit = extractRoundaboutExit(maneuver);
        String junctionType = maneuver.optString("junctionType", "").toLowerCase(java.util.Locale.ROOT);
        String maneuverCode = maneuver.optString("maneuver", "").toLowerCase(java.util.Locale.ROOT);
        boolean roundabout = type.contains("roundabout") || type.contains("rotary")
                || junctionType.contains("roundabout") || junctionType.contains("rotary")
                || maneuverCode.contains("roundabout") || maneuverCode.contains("rotary")
                || (road.contains("میدان") && ("turn".equals(type) || "end of road".equals(type) || "fork".equals(type)));
        android.util.Log.d("DriveMateManeuver", "type=" + type + " modifier=" + modifier
                + " junctionType=" + junctionType + " maneuver=" + maneuverCode
                + " exit=" + roundaboutExit + " road=" + road);
        if (roundabout) {
            if (roundaboutExit > 0) return "وارد میدان شوید و از خروجی " + persianDigits(roundaboutExit) + " خارج شوید";
            return road.isEmpty() ? "وارد میدان شوید" : "در " + road + " وارد میدان شوید";
        }
        if (type.isEmpty()) return explicit.isEmpty() ? "در مسیر ادامه دهید" : explicit;''', 'map.ir roundabout precedence')
replace_once(MIR, '    private String turnInstruction(String modifier, boolean endOfRoad) {',
'''    private int extractRoundaboutExit(JSONObject maneuver) {
        String[] keys = {"exit", "roundaboutExitNumber", "exitNumber", "roundabout_exit", "roundaboutExit"};
        for (String key : keys) {
            if (!maneuver.has(key)) continue;
            int value = maneuver.optInt(key, 0);
            if (value > 0 && value <= 20) return value;
        }
        JSONObject nested = maneuver.optJSONObject("maneuver");
        if (nested != null) {
            for (String key : keys) {
                if (!nested.has(key)) continue;
                int value = nested.optInt(key, 0);
                if (value > 0 && value <= 20) return value;
            }
        }
        return 0;
    }

    private String turnInstruction(String modifier, boolean endOfRoad) {''', 'map.ir roundabout exit extraction')

# Neshan: preserve any hidden maneuver/exit fields and prevent a roundabout from becoming TURN_RIGHT.
NESH = 'app/src/main/java/ai/drivemate/routing/NeshanRoutingProvider.java'
replace_once(NESH,
'''                    steps.add(new RouteStep(latitude, longitude, step.optString("instruction"),
                            stepDistance == null ? 0 : stepDistance.optInt("value"), parseLaneGuidance(step)));''',
'''                    steps.add(new RouteStep(latitude, longitude, persianInstruction(step),
                            stepDistance == null ? 0 : stepDistance.optInt("value"), parseLaneGuidance(step)));''', 'Neshan instruction normalization')
replace_once(NESH,
'''    /** The documented Neshan response does not currently expose maxspeed. This parses only an
     * explicit numeric field if a future response adds one; no value is derived from ETA or road type. */''',
'''    /** Normalize roundabout metadata before the generic provider instruction reaches voice guidance. */
    private String persianInstruction(JSONObject step) {
        String instruction = step.optString("instruction", "").trim();
        JSONObject maneuver = step.optJSONObject("maneuver");
        String type = step.optString("type", "").toLowerCase(java.util.Locale.ROOT);
        String junctionType = step.optString("junctionType", "").toLowerCase(java.util.Locale.ROOT);
        String maneuverType = step.optString("maneuverType", "").toLowerCase(java.util.Locale.ROOT);
        if (maneuver != null) {
            if (type.isEmpty()) type = maneuver.optString("type", "").toLowerCase(java.util.Locale.ROOT);
            if (junctionType.isEmpty()) junctionType = maneuver.optString("junctionType", "").toLowerCase(java.util.Locale.ROOT);
            if (maneuverType.isEmpty()) maneuverType = maneuver.optString("maneuver", "").toLowerCase(java.util.Locale.ROOT);
        }
        String road = step.optString("name", step.optString("streetName", "")).trim();
        boolean roundabout = type.contains("roundabout") || type.contains("rotary")
                || junctionType.contains("roundabout") || junctionType.contains("rotary")
                || maneuverType.contains("roundabout") || maneuverType.contains("rotary")
                || (road.contains("میدان") && (instruction.contains("راست") || instruction.contains("چپ")
                || instruction.toLowerCase(java.util.Locale.ROOT).contains("right")
                || instruction.toLowerCase(java.util.Locale.ROOT).contains("left")));
        if (!roundabout) return instruction;
        int exit = extractRoundaboutExit(step, maneuver);
        if (exit > 0) return "وارد میدان شوید و از خروجی " + persianDigits(exit) + " خارج شوید";
        return road.isEmpty() ? "وارد میدان شوید" : "در " + road + " وارد میدان شوید";
    }

    private int extractRoundaboutExit(JSONObject step, JSONObject maneuver) {
        String[] keys = {"exit", "roundaboutExitNumber", "exitNumber", "roundabout_exit", "roundaboutExit"};
        for (String key : keys) {
            int value = step.optInt(key, 0);
            if (value > 0 && value <= 20) return value;
            if (maneuver != null) {
                value = maneuver.optInt(key, 0);
                if (value > 0 && value <= 20) return value;
            }
        }
        return 0;
    }

    private static String persianDigits(int value) {
        String latin = String.valueOf(value);
        StringBuilder result = new StringBuilder(latin.length());
        for (int index = 0; index < latin.length(); index++) {
            char character = latin.charAt(index);
            result.append(character >= '0' && character <= '9'
                    ? (char) ('۰' + character - '0') : character);
        }
        return result.toString();
    }

    /** The documented Neshan response does not currently expose maxspeed. This parses only an
     * explicit numeric field if a future response adds one; no value is derived from ETA or road type. */''', 'Neshan roundabout helpers')

# TomTom: junctionType and explicit exit number win over generic turn code.
TOM = 'app/src/main/java/ai/drivemate/routing/TomTomRoutingProvider.java'
replace_once(TOM,
'''        String text;
        if (maneuver.contains("ARRIVE")) return "به مقصد می‌رسید";''',
'''        String junctionType = instruction.optString("junctionType", "").toUpperCase(java.util.Locale.US);
        int explicitRoundaboutExit = instruction.optInt("roundaboutExitNumber", 0);
        boolean roundabout = maneuver.contains("ROUNDABOUT") || junctionType.contains("ROUNDABOUT")
                || explicitRoundaboutExit > 0;
        String text;
        if (maneuver.contains("ARRIVE")) return "به مقصد می‌رسید";''', 'TomTom roundabout metadata')
replace_once(TOM,
'''        else if (maneuver.contains("ROUNDABOUT")) {
            int exit = instruction.optInt("roundaboutExitNumber", 0);
            text = exit > 0 ? "وارد میدان شوید و از خروجی " + persianDigits(exit) + " خارج شوید" : "وارد میدان شوید";
        } else if (maneuver.contains("LEFT"))''',
'''        else if (roundabout) {
            int exit = explicitRoundaboutExit;
            text = exit > 0 ? "وارد میدان شوید و از خروجی " + persianDigits(exit) + " خارج شوید" : "وارد میدان شوید";
        } else if (maneuver.contains("LEFT"))''', 'TomTom roundabout precedence')

# MainActivity: classify roundabouts before generic left/right; ordinary navigation must not preempt speech.
MAIN = 'app/src/main/java/ai/drivemate/MainActivity.java'
replace_once(MAIN,
'''        if (lower.contains("left") || text.contains("چپ")) lastInstruction = "turn_left";
        else if (lower.contains("right") || text.contains("راست")) lastInstruction = "turn_right";
        else if (lower.contains("arriv") || text.contains("مقصد")) lastInstruction = "destination_arrived";
        else if (lower.contains("uturn") || lower.contains("u-turn") || text.contains("دور بزنید")) lastInstruction = "make_u_turn";
        else if (text.contains("میدان") || lower.contains("roundabout")) {''',
'''        if (text.contains("میدان") || lower.contains("roundabout") || lower.contains("rotary")) {
            int exitNumber = extractExitNumber(text);
            lastInstruction = exitNumber >= 1 && exitNumber <= 3 ? "roundabout_exit_" + exitNumber : "roundabout_custom";
        } else if (lower.contains("left") || text.contains("چپ")) lastInstruction = "turn_left";
        else if (lower.contains("right") || text.contains("راست")) lastInstruction = "turn_right";
        else if (lower.contains("arriv") || text.contains("مقصد")) lastInstruction = "destination_arrived";
        else if (lower.contains("uturn") || lower.contains("u-turn") || text.contains("دور بزنید")) lastInstruction = "make_u_turn";
        else {''', 'MainActivity roundabout classification')
replace_once(MAIN,
'''        voicePlayer.interrupt();
        onlineSpeechClient.stopPlayback();
        if (isFullIntelligenceMode()) {''',
'''        if (priority == DrivingIntelligenceCoordinator.Priority.SAFETY) {
            voicePlayer.interrupt();
            onlineSpeechClient.stopPlayback();
        }
        if (isFullIntelligenceMode()) {''', 'ordinary navigation non-interruption')
replace_once(MAIN,
'    private DrivingAnnouncement pendingDrivingAnnouncement;\n',
'    private final ArrayDeque<DrivingAnnouncement> pendingDrivingAnnouncements = new ArrayDeque<>();\n', 'pending announcement queue')
replace_once(MAIN,
'''        pendingDrivingAnnouncement = null;
        intelligenceCoordinator.cancelAll();''',
'''        pendingDrivingAnnouncements.clear();
        intelligenceCoordinator.cancelAll();''', 'guidance reset queue')
replace_once(MAIN,
'''        if (safetyAnnouncementPlaying || !safetyAnnouncementQueue.isEmpty()) {
            pendingDrivingAnnouncement = announcement;
            return;
        }''',
'''        if (safetyAnnouncementPlaying || !safetyAnnouncementQueue.isEmpty()) {
            pendingDrivingAnnouncements.addLast(announcement);
            return;
        }''', 'safety pending queue')
replace_once(MAIN,
'''        if (announcement == null) {
            DrivingAnnouncement pending = pendingDrivingAnnouncement;
            pendingDrivingAnnouncement = null;
            if (pending != null && pending.expiresAt >= System.currentTimeMillis()) {
                playDrivingAnnouncement(pending);
            }
            return;
        }''',
'''        if (announcement == null) {
            long now = System.currentTimeMillis();
            while (!pendingDrivingAnnouncements.isEmpty()) {
                DrivingAnnouncement pending = pendingDrivingAnnouncements.pollFirst();
                if (pending != null && pending.expiresAt >= now) playDrivingAnnouncement(pending);
            }
            return;
        }''', 'drain all pending navigation announcements')

# OnlineSpeechClient: serialize generated speech; only stopPlayback() is a hard preemption path.
ONLINE = 'app/src/main/java/ai/drivemate/ai/OnlineSpeechClient.java'
replace_once(ONLINE, 'import java.util.UUID;\n', 'import java.util.UUID;\nimport java.util.ArrayDeque;\n', 'online queue import')
replace_once(ONLINE,
'''    private volatile String transcriptionHint = "";
    /** Bumped by stopPlayback()''',
'''    private volatile String transcriptionHint = "";
    private final ArrayDeque<SpeechRequest> speechQueue = new ArrayDeque<>();
    private boolean speechSynthesisInFlight;
    /** Bumped by stopPlayback()''', 'online queue fields')

s = read(ONLINE)
a = s.index('    public void speak(String text) {')
b = s.index('    private File synthesizeGeminiTts(', a)
new_speak = '''    public void speak(String text) {
        speak(text, null);
    }

    /** Serializes online TTS requests so successive navigation messages cannot cut each other off. */
    public synchronized void speak(String text, SpeechCallback callback) {
        if (gapKey() == null || text == null || text.trim().isEmpty()) {
            if (callback != null) callback.onError();
            return;
        }
        speechQueue.addLast(new SpeechRequest(text, callback));
        drainSpeechQueue();
    }

    private synchronized void drainSpeechQueue() {
        if (speechSynthesisInFlight || speechQueue.isEmpty()) return;
        final SpeechRequest request = speechQueue.peekFirst();
        final int requestGeneration = playGeneration;
        speechSynthesisInFlight = true;
        new Thread(() -> {
            try {
                File output;
                String key = gapKey();
                try {
                    output = synthesizeOpenAiTts(request.text, key, "gpt-4o-mini-tts", "answer-gpt4o-mini-tts.mp3");
                    lastTtsProvider = "GapGPT gpt-4o-mini-tts";
                } catch (Exception gptError) {
                    try {
                        output = synthesizeGeminiTts(request.text, key, true);
                        lastTtsProvider = "Gemini TTS";
                    } catch (Exception documentedError) {
                        try {
                            output = synthesizeGeminiTts(request.text, key, false);
                            lastTtsProvider = "Gemini TTS";
                        } catch (Exception compatibleError) {
                            output = synthesizeTts1(request.text, key);
                            lastTtsProvider = "GapGPT tts-1";
                        }
                    }
                }
                final File playableOutput = output;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    synchronized (OnlineSpeechClient.this) {
                        if (speechQueue.peekFirst() != request) return;
                        if (requestGeneration != playGeneration) {
                            speechQueue.removeFirst();
                            speechSynthesisInFlight = false;
                            if (request.callback != null) request.callback.onError();
                            drainSpeechQueue();
                            return;
                        }
                        speechQueue.removeFirst();
                        boolean played = play(playableOutput);
                        speechSynthesisInFlight = false;
                        if (request.callback != null) {
                            if (played) request.callback.onPlayed(); else request.callback.onError();
                        }
                        if (!played) drainSpeechQueue();
                    }
                });
            } catch (Exception error) {
                Log.e(TAG, "All online TTS providers failed", error);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    synchronized (OnlineSpeechClient.this) {
                        if (speechQueue.peekFirst() != request) return;
                        speechQueue.removeFirst();
                        speechSynthesisInFlight = false;
                        if (request.callback != null) request.callback.onError();
                        drainSpeechQueue();
                    }
                });
            }
        }).start();
    }

'''
write(ONLINE, s[:a] + new_speak + s[b:])
replace_once(ONLINE,
'''    private boolean play(File file) {
        stopPlayback();
        player = new MediaPlayer();
        try { player.setDataSource(file.getAbsolutePath()); player.prepare(); player.start(); return true; }
        catch (Exception ignored) { player.release(); player = null; return false; }
    }''',
'''    private boolean play(File file) {
        player = new MediaPlayer();
        try {
            player.setDataSource(file.getAbsolutePath());
            player.prepare();
            player.setOnCompletionListener(completed -> {
                synchronized (OnlineSpeechClient.this) {
                    try { completed.release(); } catch (Exception ignored) { }
                    if (player == completed) player = null;
                    drainSpeechQueue();
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                synchronized (OnlineSpeechClient.this) {
                    try { mp.release(); } catch (Exception ignored) { }
                    if (player == mp) player = null;
                    speechSynthesisInFlight = false;
                    drainSpeechQueue();
                }
                return true;
            });
            player.start();
            return true;
        } catch (Exception ignored) {
            try { player.release(); } catch (Exception ignoredToo) { }
            player = null;
            return false;
        }
    }''', 'online serialized playback')
replace_once(ONLINE,
'''    public void stopPlayback() {
        playGeneration++;
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) { }
            player.release();
            player = null;
        }
    }''',
'''    public synchronized void stopPlayback() {
        playGeneration++;
        speechQueue.clear();
        speechSynthesisInFlight = false;
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }''', 'online hard stop')
replace_once(ONLINE,
'''    private void releaseRecorder() {''',
'''    private static final class SpeechRequest {
        final String text;
        final SpeechCallback callback;
        SpeechRequest(String text, SpeechCallback callback) {
            this.text = text;
            this.callback = callback;
        }
    }

    private void releaseRecorder() {''', 'online request model')

print('all navigation fixes applied')
