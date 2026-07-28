package ai.drivemate.ai;

import android.location.Location;

import java.util.HashMap;
import java.util.Map;

/**
 * Generates conservative, observable driving events purely from on-device GPS signal — no OBD
 * fuel sensor, no paid live-traffic feed, no LLM calls. Keeps proactive safety alerts cheap and
 * free to run continuously: heavy braking/slow-speed acts as an honest proxy for traffic, and
 * elapsed driving time is used for fatigue reminders. Anything that truly needs live traffic or
 * fuel-level data is reported as unavailable rather than guessed.
 */
public class SmartDriveCompanion {
    public interface Listener { void onSmartEvent(String event, String facts); }

    /** No fuel sensor exists, so this is a rough distance-based reminder only, not a real fuel
     *  gauge: after roughly this much driving in one session (reset by resetFuelDistance(), which
     *  the driver triggers by saying they refueled) a low-fuel suggestion fires once. */
    private static final double FUEL_GUESS_DISTANCE_METERS = 380_000d;
    private static final long FUEL_GUESS_COOLDOWN_MS = 3 * 60 * 60_000L;

    private final Listener listener;
    private final Map<String, Long> lastEvents = new HashMap<>();
    private boolean active;
    private long startedAt;
    private long slowSince;
    private long stoppedSince;
    private long continuousDrivingSince;
    private Location previousFuelLocation;
    private double fuelDistanceMeters;

    public SmartDriveCompanion(Listener listener) { this.listener = listener; }

    public void start() {
        active = true;
        startedAt = System.currentTimeMillis();
        slowSince = 0L;
        stoppedSince = 0L;
        continuousDrivingSince = startedAt;
        previousFuelLocation = null;
        fuelDistanceMeters = 0d;
        lastEvents.clear();
    }

    public void stop() { active = false; slowSince = 0L; stoppedSince = 0L; }

    /** Called when the driver says they refueled (e.g. "بنزین زدم"); restarts the distance count. */
    public void resetFuelDistance() {
        fuelDistanceMeters = 0d;
        previousFuelLocation = null;
        lastEvents.remove("fuel_low_guess");
    }

    public void onLocation(Location location) {
        if (!active || location == null) return;
        long now = System.currentTimeMillis();
        if (previousFuelLocation != null) fuelDistanceMeters += previousFuelLocation.distanceTo(location);
        previousFuelLocation = location;
        if (fuelDistanceMeters >= FUEL_GUESS_DISTANCE_METERS && allow("fuel_low_guess", now, FUEL_GUESS_COOLDOWN_MS)) {
            listener.onSmartEvent("fuel_low_guess", "حدود " + Math.round(fuelDistanceMeters / 1000d)
                    + " کیلومتر رانندگی بدون سوخت‌گیری تأییدشده ثبت شده است؛ این فقط یک برآورد تقریبی مسافت است، نه سطح واقعی سوخت.");
        }
        float speedKmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;

        // Traffic crawl is not a rest. Only a near-full stop sustained for ten minutes resets
        // the continuous-driving timer used for non-medical fatigue reminders.
        if (location.hasSpeed() && speedKmh <= 2f) {
            if (stoppedSince == 0L) stoppedSince = now;
            if (now - stoppedSince >= 10 * 60_000L) continuousDrivingSince = now;
        } else {
            stoppedSince = 0L;
        }

        if (location.hasSpeed() && speedKmh >= 110f && allow("speed", now, 8 * 60_000L)) {
            listener.onSmartEvent("speed", "سرعت ثبت‌شدهٔ GPS حدود " + Math.round(speedKmh) + " کیلومتر بر ساعت است.");
        }

        if (location.hasSpeed() && speedKmh <= 8f) {
            if (slowSince == 0L) slowSince = now;
            long slowMinutes = (now - slowSince) / 60_000L;
            // Short sustained slowdown: likely traffic — just announce it.
            if (slowMinutes >= 3 && allow("slow", now, 10 * 60_000L)) {
                listener.onSmartEvent("slow", "GPS بیش از سه دقیقه حرکت بسیار کند را ثبت کرده است؛ ترافیک زنده تأیید نشده اما احتمال آن بالاست.");
            }
        } else {
            slowSince = 0L;
        }

        if (now - startedAt >= 90 * 60_000L && allow("fuel_check", now, 60 * 60_000L)) {
            listener.onSmartEvent("fuel_check", "حدود نود دقیقه از شروع سفر گذشته است. سطح سوخت خودرو در دسترس برنامه نیست.");
        }
        if (now - continuousDrivingSince >= 105 * 60_000L && allow("rest_prepare", now, 90 * 60_000L)) {
            listener.onSmartEvent("rest_prepare", "زمان یادآوری استراحت نزدیک است.");
        }
        if (now - continuousDrivingSince >= 2 * 60 * 60_000L && allow("rest", now, 90 * 60_000L)) {
            listener.onSmartEvent("rest", "حدود دو ساعت رانندگی پیوسته بدون توقف ده دقیقه‌ای ثبت شده است.");
        }
        if (now - continuousDrivingSince >= 3 * 60 * 60_000L && allow("fatigue", now, 2 * 60 * 60_000L)) {
            listener.onSmartEvent("fatigue", "بیش از سه ساعت رانندگی پیوسته بدون توقف ده دقیقه‌ای ثبت شده است. این تشخیص پزشکی نیست؛ فقط یادآوری ایمنی است.");
        }
    }

    public void routeHazard(String kind) {
        if (active && allow("hazard_" + kind, System.currentTimeMillis(), 3 * 60_000L)) {
            listener.onSmartEvent("hazard", "هشدار ثبت‌شده در دادهٔ مسیر: " + kind + ".");
        }
    }

    private boolean allow(String key, long now, long cooldown) {
        Long last = lastEvents.get(key);
        if (last != null && now - last < cooldown) return false;
        lastEvents.put(key, now);
        return true;
    }
}
