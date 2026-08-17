package ai.drivemate.routing;

import android.location.Location;
import android.util.Log;

import ai.drivemate.model.RouteResult;
import ai.drivemate.model.RoutePoint;
import ai.drivemate.model.RouteStep;

/** Keeps route progress separate from the activity so GPS updates can be handled consistently. */
public class NavigationEngine {
    private static final String TAG = "DriveMateNav";
    public interface Listener {
        void onInstruction(RouteStep step);
        void onOffRoute();
        void onArrived();
        /** Fired once when the next intermediate stop is close enough to announce. This is
         * separate from onWaypointReached(), so a driver can hear about a stop before arriving. */
        default void onWaypointApproaching(RouteStep step, int waypointOrdinal) { }
        /** Fired once, when the driver reaches an intermediate stop (see RouteStep.waypointOrdinal)
         *  rather than the final destination. Navigation continues on the same route afterward;
         *  this never stops the engine. Default no-op so existing Listener implementations compile
         *  and behave exactly as before unless they choose to override it. */
        default void onWaypointReached(RouteStep step, int waypointOrdinal) { }
        /** Fired after several accurate, on-route samples prove the driver is already beyond an
         * intermediate stop. The stop is removed from future reroutes instead of forcing a loop
         * back to a point the driver intentionally bypassed. */
        default void onWaypointSkipped(RouteStep step, int waypointOrdinal) { }
        /** Early heads-up cues for the upcoming maneuver, fired at most once each per maneuver in
         *  strict INITIAL -> APPROACHING order (see AnnouncementStage) as the live distance crosses
         *  speed-based thresholds - never on a fixed meter value, and never twice for the same
         *  maneuver even if GPS noise makes the reported distance briefly tick back up. The actual
         *  full instruction still arrives through onInstruction() once the driver is right at the
         *  maneuver; this is purely the earlier "در ۲۰۰ متر..." / "تا ۸۰ متر دیگر..." style warning.
         *  Default no-op so existing Listener implementations keep compiling untouched. */
        default void onInstructionStage(RouteStep step, AnnouncementStage stage, int metersRemaining) { }
    }

    /** INITIAL: first distant heads-up. APPROACHING: closer follow-up. Both go through
     *  onInstructionStage(); the maneuver's actual spoken instruction (onInstruction()) is the
     *  implicit third/final stage and reuses the engine's existing currentInstructionAnnounced gate. */
    public enum AnnouncementStage { INITIAL, APPROACHING }

    private RouteResult route;
    private int nextStep;
    private long lastOffRouteCallbackAt;
    private Listener listener;
    /** The actual requested final coordinate. Provider maneuver endpoints can be several streets
     * away from it, so arrival must never depend solely on the final maneuver's coordinate. */
    private RoutePoint finalDestination;
    private Location targetReference;
    private float targetDistanceAtReference;
    private int offRouteSamples;
    private long lastInstructionAt;
    private boolean currentInstructionAnnounced;
    /** Which of INITIAL/APPROACHING has already fired for the current maneuver - strictly
     *  monotonic (0=none, 1=INITIAL fired, 2=APPROACHING fired too) so GPS jitter bouncing the
     *  reported distance up and down can never re-fire a stage or fire them out of order. Reset to
     *  0 everywhere currentInstructionAnnounced is reset (new maneuver, waypoint advance/skip,
     *  fresh start). */
    private int announceStageReached;
    /** Smoothed, plausibility-checked driving speed used only for announcement *timing* (distance
     *  thresholds below) - never for route logic. Seeded with a conservative ~30km/h so the very
     *  first maneuver of a trip (before any real GPS speed sample exists) still gets reasonable
     *  lead time instead of the degenerate 0 m/s a fresh Location often reports. */
    private float lastValidSpeedMps = 8.3f;
    private Location lastTimingLocation;
    private static final float MIN_TIMING_SPEED_MPS = 2.8f;   // ~10km/h floor: stopped/crawling traffic must not collapse distances to ~0
    private static final float MAX_TIMING_SPEED_MPS = 36f;    // ~130km/h ceiling: guards a GPS speed spike from inflating distances unrealistically
    /** A single bad speed sample (multipath spike, brief loss of fix) must not be trusted outright -
     *  same spirit as the location filter's own acceleration sanity check, scoped to just this
     *  timing calculation: a jump larger than this from the last accepted speed is ignored. */
    private static final float MAX_PLAUSIBLE_SPEED_JUMP_MPS = 15f;
    private int announcedWaypointIndex = -1;
    private boolean instructionAnnouncementsEnabled = true;
    private final RouteProgressTracker progressTracker = new RouteProgressTracker();
    private double[] stepProgressMeters = new double[0];
    private static final long MIN_MS_BETWEEN_INSTRUCTIONS = 1800L;
    private static final float MAX_ACCURACY_FOR_ADVANCE_METERS = 60f;
    /** Deliberately looser than the maneuver-advance accuracy gate above: arrival is checked
     *  against a much larger 55m radius already, and a driver who has actually reached the
     *  destination but is getting a degraded fix (underground/multi-level parking, dense urban
     *  canyon, covered driveway - exactly where trips often end) must still be able to arrive
     *  rather than sit there indefinitely hearing "continue on route" because no fix ever came in
     *  under the tighter 60m bar used for in-route maneuver advancement. */
    private static final float MAX_ACCURACY_FOR_ARRIVAL_METERS = 100f;
    /** Minimum gap between onOffRoute() callbacks. Time-based rather than a one-shot latch that
     *  only clears on the next maneuver or a fresh start(): a one-shot latch can permanently lock
     *  up if the caller ever declines to act on a callback (e.g. its own reroute throttle), since
     *  nothing would ever clear it again for the rest of the trip. A cooldown always re-arms. */
    private static final long MIN_MS_BETWEEN_OFFROUTE_CALLBACKS = 3_000L;
    /** Consecutive confirming fixes required before trusting a maneuver has actually been reached
     *  (see onLocation). */
    private static final int STEP_ADVANCE_CONFIRM_SAMPLES = 2;
    private int advanceConfirmSamples;
    private int passedStepConfirmSamples;
    private int skippedWaypointConfirmSamples;
    private int finalArrivalConfirmSamples;
    private static final int FINAL_ARRIVAL_CONFIRM_SAMPLES = 2;
    private static final int WAYPOINT_SKIP_CONFIRM_SAMPLES = 2;
    private static final double PASSED_STEP_BUFFER_METERS = 35d;
    private static final double SKIPPED_WAYPOINT_BUFFER_METERS = 180d;
    private static final float GEOGRAPHIC_WAYPOINT_SKIP_ADVANTAGE_METERS = 180f;
    /** Modestly wider than the maneuver-advance radius: this is a one-shot check (no multi-sample
     *  confirmation, since a parked/stopped driver may only ever produce one fix inside it), so it
     *  needs its own buffer against GPS noise rather than sharing the tighter per-maneuver radius. */
    private static final float FINAL_ARRIVAL_RADIUS_METERS = 80f;
    private static final float FINAL_ARRIVAL_ROUTE_RADIUS_METERS = 140f;

    public void start(RouteResult route, Listener listener) {
        start(route, listener, null);
    }

    /**
     * Route providers expose maneuver end points, not the route polyline. A maneuver that ends
     * hundreds of meters ahead must never be mistaken for an off-route position at trip start.
     */
    public void start(RouteResult route, Listener listener, Location currentLocation) {
        start(route, listener, currentLocation, null);
    }

    /** Starts navigation with the exact final destination requested by the user. */
    public void start(RouteResult route, Listener listener, Location currentLocation, RoutePoint finalDestination) {
        start(route, listener, currentLocation, finalDestination, 0);
    }

    /** Restores a shared navigation session at the authoritative step already reached elsewhere. */
    public void start(RouteResult route, Listener listener, Location currentLocation,
                      RoutePoint finalDestination, int initialStepIndex) {
        this.route = route;
        this.listener = listener;
        this.finalDestination = finalDestination;
        this.nextStep = route == null || route.steps.isEmpty() ? 0
                : Math.max(0, Math.min(initialStepIndex, route.steps.size() - 1));
        if (initialStepIndex == 0) {
            this.nextStep = firstActionableStepIndex(route, this.nextStep);
        }
        this.lastOffRouteCallbackAt = 0L;
        this.offRouteSamples = 0;
        this.advanceConfirmSamples = 0;
        this.passedStepConfirmSamples = 0;
        this.skippedWaypointConfirmSamples = 0;
        this.finalArrivalConfirmSamples = 0;
        this.lastInstructionAt = 0L;
        this.lastValidSpeedMps = 8.3f;
        this.lastTimingLocation = currentLocation == null ? null : new Location(currentLocation);
        this.currentInstructionAnnounced = false;
        this.announceStageReached = 0;
        this.announcedWaypointIndex = -1;
        this.instructionAnnouncementsEnabled = true;
        progressTracker.reset(route, currentLocation);
        buildStepProgress();
        updateTargetReference(currentLocation);
        if (route != null && !route.steps.isEmpty()) {
            Log.i(TAG, "start step=" + nextStep + " steps=" + route.steps.size()
                    + " instruction=" + route.steps.get(nextStep).instruction);
        }
    }

    public void stop() {
        route = null;
        listener = null;
        finalDestination = null;
        targetReference = null;
        offRouteSamples = 0;
        advanceConfirmSamples = 0;
        passedStepConfirmSamples = 0;
        skippedWaypointConfirmSamples = 0;
        finalArrivalConfirmSamples = 0;
        lastTimingLocation = null;
        currentInstructionAnnounced = false;
        announceStageReached = 0;
        announcedWaypointIndex = -1;
        instructionAnnouncementsEnabled = true;
        stepProgressMeters = new double[0];
        progressTracker.clear();
    }
    public boolean isNavigating() { return route != null; }

    /** Current maneuver target, or null when no route is active. Lets a UI show live progress
     *  toward the same step this engine is tracking, without duplicating its step logic. */
    public RouteStep currentStep() {
        if (route == null || route.steps.isEmpty()) return null;
        return route.steps.get(Math.min(nextStep, route.steps.size() - 1));
    }

    public int currentStepIndex() { return nextStep; }

    /** Last monotonic route segment accepted by RouteProgressTracker. Unlike a global nearest-point
     * search this remains stable on loops, parallel roads and temporary off-route deviations. */
    public int currentRouteSegmentIndex() {
        RouteProgressTracker.Snapshot snapshot = progressTracker.current();
        return snapshot == null ? -1 : snapshot.segmentIndex;
    }

    public int remainingMeters() {
        RouteProgressTracker.Snapshot progress = progressTracker.current();
        return progress == null ? route == null ? 0 : route.distanceMeters : progress.remainingMeters;
    }

    public RoutePoint snappedRoutePosition() {
        RouteProgressTracker.Snapshot progress = progressTracker.current();
        return progress == null || !progress.onRoute ? null : progress.snappedPoint;
    }

    public float routeHeading() {
        RouteProgressTracker.Snapshot progress = progressTracker.current();
        return progress == null ? 0f : progress.headingDegrees;
    }

    public boolean isPointAhead(double latitude, double longitude, double minimumAheadMeters,
                                double maximumAheadMeters, float maximumLateralMeters) {
        return progressTracker.isPointAhead(latitude, longitude, minimumAheadMeters,
                maximumAheadMeters, maximumLateralMeters);
    }

    public void onLocation(Location location) {
        if (route == null || listener == null) return;
        if (route.steps.isEmpty()) return;
        RouteProgressTracker.Snapshot routeProgress = progressTracker.update(location);
        // Checked independently of nextStep: sequential step advancement (below) requires getting
        // close to each intermediate maneuver point in order, which never happens if the traveled
        // path diverges from the routed streets (a pedestrian shortcut through an alley/park, a
        // driver cutting through a lot). Without this, arrival could never fire in that case - the
        // engine would keep waiting to reach maneuver points on a path that was never taken, even
        // while standing at the actual destination.
        RouteStep destinationStep = route.steps.get(route.steps.size() - 1);
        float metersToDestination = location.distanceTo(finalDestination == null
                ? asLocation(destinationStep) : asLocation(finalDestination));
        float metersToRouteEnd = route.geometry == null || route.geometry.isEmpty()
                ? Float.MAX_VALUE : location.distanceTo(asLocation(route.geometry.get(route.geometry.size() - 1)));
        // Arrival is a final-state decision: never finish a trip merely because the driver entered a broad destination radius.
        boolean destinationCloseEnough = metersToDestination <= 35f
                || (routeProgress != null && routeProgress.onRoute
                && routeProgress.remainingMeters <= 20 && metersToDestination <= 45f)
                || (routeProgress != null && routeProgress.onRoute
                && routeProgress.remainingMeters <= 15 && metersToRouteEnd <= 35f
                && metersToDestination <= 55f);
        if (accuracyOkFor(location, MAX_ACCURACY_FOR_ARRIVAL_METERS) && destinationCloseEnough) {
            finalArrivalConfirmSamples++;
        } else {
            finalArrivalConfirmSamples = 0;
        }
        if (finalArrivalConfirmSamples >= FINAL_ARRIVAL_CONFIRM_SAMPLES) {
            Log.i(TAG, "arrival confirmed distance=" + Math.round(metersToDestination)
                    + " routeRemaining=" + Math.round(routeProgress == null ? -1 : routeProgress.remainingMeters)
                    + " routeEndDistance=" + Math.round(metersToRouteEnd)
                    + " accuracy=" + (location.hasAccuracy() ? Math.round(location.getAccuracy()) : -1));
            Listener callback = listener;
            stop();
            callback.onArrived();
            return;
        }
        int nextWaypointIndex = nextWaypointIndex();
        if (nextWaypointIndex >= 0) {
            RouteStep waypoint = route.steps.get(nextWaypointIndex);
            float metersToWaypoint = location.distanceTo(asLocation(waypoint));
            // Announce before the skip test so a delayed/fake GPS jump cannot silently consume the stop.
            float waypointSpeed = smoothedSpeedMps(location);
            float waypointLeadSeconds = waypointSpeed >= 22f ? 10f : 8f;
            float waypointAnnounceDistance = Math.max(120f, Math.min(320f,
                    Math.max(waypoint.distanceMeters * 0.65f, waypointSpeed * waypointLeadSeconds)));
            if (instructionAnnouncementsEnabled && announcedWaypointIndex != nextWaypointIndex
                    && metersToWaypoint <= waypointAnnounceDistance) {
                announcedWaypointIndex = nextWaypointIndex;
                Log.i(TAG, "waypoint approaching ordinal=" + waypoint.waypointOrdinal
                        + " distance=" + Math.round(metersToWaypoint)
                        + " announceDistance=" + Math.round(waypointAnnounceDistance)
                        + " speedMps=" + String.format(java.util.Locale.US, "%.1f", waypointSpeed));
                listener.onWaypointApproaching(waypoint, waypoint.waypointOrdinal);
            }
            if (waypointWasSkipped(location, routeProgress, nextWaypointIndex, metersToWaypoint)) {
                skipWaypoint(nextWaypointIndex, location, waypoint);
                return;
            }// A shortcut can reach a stop without ever touching all of the provider's maneuver
            // points. Advance directly past that stop so navigation continues to the next one.
            if (accuracyOk(location) && metersToWaypoint <= FINAL_ARRIVAL_RADIUS_METERS) {
                advancePastWaypoint(nextWaypointIndex, location, waypoint);
                return;
            }
        } else {
            skippedWaypointConfirmSamples = 0;
        }
        advancePastPassedSteps(location, routeProgress);
        RouteStep target = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
        float meters = location.distanceTo(asLocation(target));
        // Announce before reaching the maneuver. A no-map experience needs the next action in
        // advance, while route progression still waits until the maneuver endpoint is reached.
        boolean accuracyOk = accuracyOk(location);
        if (instructionAnnouncementsEnabled && accuracyOk) evaluateInstructionCascade(location, target, meters);

        float reachedDistance = Math.max(28f, Math.min(65f, target.distanceMeters * 0.15f));
        if (accuracyOk && meters <= reachedDistance) {
            // A single close fix is not enough to trust: GPS multipath in narrow alleys can put a
            // momentarily-jumped reading within range of a maneuver the driver has not actually
            // reached, advancing past the true next step and announcing its (wrong) direction
            // instead. Two consecutive confirming fixes (roughly a second apart) are required
            // before the advance is trusted.
            advanceConfirmSamples++;
            if (advanceConfirmSamples < STEP_ADVANCE_CONFIRM_SAMPLES) return;
            advanceConfirmSamples = 0;
            RouteStep justReached = target;
            if (justReached.waypointOrdinal >= 0) {
                advancePastWaypoint(nextStep, location, justReached);
                return;
            }
            // For a short maneuver step, reachedDistance (which triggers this advance) can be
            // numerically closer than finalMeters (which triggers the voice instruction in
            // evaluateInstructionCascade) - the driver could physically reach/pass the maneuver
            // before its own announcement threshold was ever crossed, silently skipping the
            // instruction entirely (e.g. a roundabout with a short entry segment: shown on the
            // banner because that reads the step directly, but never spoken). This is the last
            // chance to say it before moving on to the next maneuver.
            if (instructionAnnouncementsEnabled && !currentInstructionAnnounced && hasActionableCurrentInstruction()) {
                currentInstructionAnnounced = true;
                lastInstructionAt = System.currentTimeMillis();
                listener.onInstruction(justReached);
            }
            nextStep = Math.min(nextStep + 1, route.steps.size() - 1);
            currentInstructionAnnounced = false;
            announceStageReached = 0;
            updateTargetReference(location);
            RouteStep next = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
            float nextDistance = location.distanceTo(asLocation(next));
            if (instructionAnnouncementsEnabled) evaluateInstructionCascade(location, next, nextDistance);
            return;
        }
        advanceConfirmSamples = 0;
        long now = System.currentTimeMillis();
        if (isReliablyOffRoute(location, meters, routeProgress)
                && now - lastOffRouteCallbackAt >= MIN_MS_BETWEEN_OFFROUTE_CALLBACKS) {
            lastOffRouteCallbackAt = now;
            Log.i(TAG, "off-route confirmed routeDistance=" + Math.round(routeProgress == null ? -1 : routeProgress.distanceToRouteMeters)
                    + " targetDistance=" + Math.round(meters) + " accuracy=" + Math.round(location.getAccuracy())
                    + " samples=" + offRouteSamples);
            listener.onOffRoute();
        }
    }

    /** True when the current step is a maneuver rather than a destination-only fallback step. */
    public boolean hasActionableCurrentInstruction() {
        if (route == null || route.steps.isEmpty()) return false;
        RouteStep step = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
        if (step.waypointOrdinal >= 0) return false;
        String instruction = step.instruction == null ? "" : step.instruction;
        String lower = instruction.toLowerCase(java.util.Locale.ROOT);
        return !(lower.contains("arriv")
                || instruction.contains("\u0645\u0642\u0635\u062f")
                || instruction.contains("\u0631\u0633\u06cc\u062f"));
    }

    private static int firstActionableStepIndex(RouteResult route, int startingIndex) {
        if (route == null || route.steps == null) return startingIndex;
        for (int index = startingIndex; index < route.steps.size(); index++) {
            RouteStep step = route.steps.get(index);
            if (step.waypointOrdinal >= 0) continue;
            String instruction = step.instruction == null ? "" : step.instruction.trim();
            String lower = instruction.toLowerCase(java.util.Locale.ROOT);
            boolean arrival = lower.contains("arriv") || instruction.contains("\u0645\u0642\u0635\u062f")
                    || instruction.contains("\u0631\u0633\u06cc\u062f");
            boolean genericStart = lower.contains("depart") || lower.contains("continue")
                    || instruction.contains("\u0628\u0647 \u0633\u0645\u062a \u0645\u0642\u0635\u062f \u062d\u0631\u06a9\u062a")
                    || instruction.contains("\u062f\u0631 \u0645\u0633\u06cc\u0631 \u0627\u062f\u0627\u0645\u0647");
            if (!arrival && !genericStart) return index;
        }
        return startingIndex;
    }

    /** Announces the first actionable provider instruction as soon as a route is ready. */
    public boolean announceCurrentInstruction() {
        if (!instructionAnnouncementsEnabled || route == null || listener == null || route.steps.isEmpty() || currentInstructionAnnounced
                || !hasActionableCurrentInstruction()) return false;
        currentInstructionAnnounced = true;
        lastInstructionAt = System.currentTimeMillis();
        RouteStep step = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
        Log.i(TAG, "announce step=" + nextStep + " instruction=" + step.instruction);
        listener.onInstruction(step);
        return true;
    }

    /** Prevents the first maneuver from being consumed while the trip-start summary is playing. */
    public void setInstructionAnnouncementsEnabled(boolean enabled) {
        instructionAnnouncementsEnabled = enabled;
    }

    /** Replaces the old single fixed-fraction announce distance with three speed/reaction-time
     *  based thresholds (INITIAL -> APPROACHING -> the maneuver's real instruction as the implicit
     *  final stage), each fired at most once per maneuver via the strictly-monotonic
     *  announceStageReached counter - GPS jitter bouncing the reported distance up and down can
     *  never re-fire or reorder a stage, and a driver sitting still (traffic light, jam) simply
     *  never crosses a threshold rather than having anything reset. */
    private void evaluateInstructionCascade(Location location, RouteStep target, float meters) {
        if (target == null || target.distanceMeters <= 0) return;
        float speed = smoothedSpeedMps(location);
        float reactionSeconds = reactionSecondsFor(target, speed);
        float stepLen = Math.max(50f, target.distanceMeters);
        // "اکنون": right at the maneuver - still speed-scaled (faster approach needs a slightly
        // earlier cue even here) but capped well under the step length.
        float finalMeters = Math.min(stepLen * 0.4f, clamp(30f, 90f, speed * reactionSeconds * 0.35f));
        // "تا X متر دیگر": a closer follow-up reminder.
        float approachMeters = Math.min(stepLen * 0.7f, Math.max(finalMeters + 20f,
                clamp(70f, 180f, speed * reactionSeconds * 0.75f)));
        // "در X متر": the first distant heads-up - this is where speed matters most, since it's the
        // one a fast highway approach most needs pulled earlier.
        float initialMeters = Math.min(stepLen * 0.98f, Math.max(approachMeters + 50f,
                clamp(140f, 600f, speed * reactionSeconds * 2.0f)));

        if (announceStageReached < 1 && meters <= initialMeters) {
            announceStageReached = 1;
            listener.onInstructionStage(target, AnnouncementStage.INITIAL, Math.round(meters));
        }
        if (announceStageReached < 2 && meters <= approachMeters) {
            announceStageReached = 2;
            listener.onInstructionStage(target, AnnouncementStage.APPROACHING, Math.round(meters));
        }
        boolean cooldownOk = System.currentTimeMillis() - lastInstructionAt >= MIN_MS_BETWEEN_INSTRUCTIONS;
        if (!currentInstructionAnnounced && cooldownOk && meters <= finalMeters) {
            announceCurrentInstruction();
        }
    }

    /** Smoothed, plausibility-checked speed used only for the timing calculation above - never for
     *  route/off-route logic, which relies on the already-filtered Location itself. A raw sample
     *  more than MAX_PLAUSIBLE_SPEED_JUMP_MPS away from the last accepted value (multipath spike,
     *  a fix reacquired after a brief loss) is ignored outright so one bad sample cannot suddenly
     *  collapse or balloon every announcement distance for this tick. */
    private float smoothedSpeedMps(Location location) {
        float raw = location.hasSpeed() ? location.getSpeed() : -1f;
        if (raw < 0f && lastTimingLocation != null) {
            long elapsedMs = location.getElapsedRealtimeNanos() > 0L && lastTimingLocation.getElapsedRealtimeNanos() > 0L
                    ? (location.getElapsedRealtimeNanos() - lastTimingLocation.getElapsedRealtimeNanos()) / 1_000_000L
                    : location.getTime() - lastTimingLocation.getTime();
            if (elapsedMs > 0L) raw = lastTimingLocation.distanceTo(location) / Math.max(0.5f, elapsedMs / 1000f);
        }
        if (raw >= 0f && raw <= MAX_TIMING_SPEED_MPS) {
            // Smooth legitimate acceleration/deceleration instead of rejecting large jumps.
            // Rejecting them kept the old speed and could make a warning arrive too late.
            float bounded = Math.max(0f, Math.min(MAX_TIMING_SPEED_MPS, raw));
            lastValidSpeedMps = lastValidSpeedMps * 0.65f + bounded * 0.35f;
        }
        lastTimingLocation = new Location(location);
        return Math.max(MIN_TIMING_SPEED_MPS, Math.min(MAX_TIMING_SPEED_MPS, lastValidSpeedMps));
    }

    /** Reaction time (seconds) appropriate to the maneuver, longer for anything riskier than a
     *  normal turn, and longer again at highway speed regardless of maneuver type - matching the
     *  same classification MainActivity's voice layer already uses for these Persian instructions. */
    private static float reactionSecondsFor(RouteStep step, float speedMps) {
        String text = step.instruction == null ? "" : step.instruction;
        float base = 7f;
        if (text.contains("دور بزنید")) base = 9f;
        else if (text.contains("میدان")) base = 10f;
        else if (text.contains("تند")) base = 7f;
        if (speedMps >= 22f) base += 4f;
        return base;
    }

    private static float clamp(float min, float max, float value) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isReliablyOffRoute(Location location, float targetDistance,
                                       RouteProgressTracker.Snapshot routeProgress) {
        if (targetReference == null) return false;
        if (!location.hasAccuracy() || location.getAccuracy() > 50f) return false;

        // Off-route detection must also work in the final streets of a trip. The previous
        // last-step guard disabled rerouting exactly when a driver could turn wrong near the
        // destination. Use route geometry as the primary signal and confirm with two good fixes.
        float routeDistance = routeProgress == null ? distanceToRoute(location) : routeProgress.distanceToRouteMeters;
        float movedFromReference = location.distanceTo(targetReference);
        float corridorMeters = Math.max(35f, Math.min(70f, location.getAccuracy() * 2.5f));
        if (routeDistance > corridorMeters && movedFromReference >= 20f) {
            offRouteSamples++;
            return offRouteSamples >= 2;
        }
        if (routeDistance <= corridorMeters * 0.65f) offRouteSamples = 0;

        // Without a route polyline, a far maneuver end point cannot be used for off-route detection.
        if (targetDistanceAtReference > 160f) return false;
        boolean movingAway = movedFromReference >= 80f
                && targetDistance > Math.max(180f, targetDistanceAtReference + 100f);
        offRouteSamples = movingAway ? offRouteSamples + 1 : 0;
        return offRouteSamples >= 2;
    }

    private boolean accuracyOk(Location location) {
        return accuracyOkFor(location, MAX_ACCURACY_FOR_ADVANCE_METERS);
    }

    private boolean accuracyOkFor(Location location, float maxAccuracyMeters) {
        return !location.hasAccuracy() || location.getAccuracy() <= maxAccuracyMeters;
    }

    private int nextWaypointIndex() {
        if (route == null || route.steps == null) return -1;
        for (int index = Math.max(0, nextStep); index < route.steps.size(); index++) {
            if (route.steps.get(index).waypointOrdinal >= 0) return index;
        }
        return -1;
    }

    private void advancePastWaypoint(int waypointIndex, Location location, RouteStep waypoint) {
        nextStep = Math.min(waypointIndex + 1, route.steps.size() - 1);
        currentInstructionAnnounced = false;
        skippedWaypointConfirmSamples = 0;
        announceStageReached = 0;
        updateTargetReference(location);
        listener.onWaypointReached(waypoint, waypoint.waypointOrdinal);
        RouteStep next = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
        float nextDistance = location.distanceTo(asLocation(next));
        if (instructionAnnouncementsEnabled) evaluateInstructionCascade(location, next, nextDistance);
    }

    private void skipWaypoint(int waypointIndex, Location location, RouteStep waypoint) {
        nextStep = Math.min(waypointIndex + 1, route.steps.size() - 1);
        currentInstructionAnnounced = false;
        skippedWaypointConfirmSamples = 0;
        announcedWaypointIndex = -1;
        announceStageReached = 0;
        updateTargetReference(location);
        listener.onWaypointSkipped(waypoint, waypoint.waypointOrdinal);
        RouteStep next = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
        float nextDistance = location.distanceTo(asLocation(next));
        if (instructionAnnouncementsEnabled) evaluateInstructionCascade(location, next, nextDistance);
    }

    private boolean waypointWasSkipped(Location location, RouteProgressTracker.Snapshot routeProgress,
                                       int waypointIndex, float metersToWaypoint) {
        if (!accuracyOkFor(location, 75f)
                || waypointIndex >= stepProgressMeters.length
                || Double.isNaN(stepProgressMeters[waypointIndex])) {
            skippedWaypointConfirmSamples = 0;
            return false;
        }
        boolean passedOnRoute = routeProgress != null && routeProgress.onRoute
                && routeProgress.progressMeters >= stepProgressMeters[waypointIndex] + SKIPPED_WAYPOINT_BUFFER_METERS;
        boolean isCloserToFinalDestination = finalDestination != null
                && location.distanceTo(asLocation(finalDestination))
                + GEOGRAPHIC_WAYPOINT_SKIP_ADVANTAGE_METERS < metersToWaypoint;
        boolean clearlyPast = passedOnRoute || isCloserToFinalDestination;
        boolean didNotReachStop = metersToWaypoint > FINAL_ARRIVAL_RADIUS_METERS * 1.5f;
        if (!clearlyPast || !didNotReachStop) {
            skippedWaypointConfirmSamples = 0;
            return false;
        }
        skippedWaypointConfirmSamples++;
        return skippedWaypointConfirmSamples >= WAYPOINT_SKIP_CONFIRM_SAMPLES;
    }

    private void advancePastPassedSteps(Location location, RouteProgressTracker.Snapshot routeProgress) {
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
        announceStageReached = 0;
        advanceConfirmSamples = 0;
        updateTargetReference(location);
        if (instructionAnnouncementsEnabled) announceCurrentInstruction();
    }

    private void buildStepProgress() {
        if (route == null || route.steps == null || route.steps.isEmpty()) {
            stepProgressMeters = new double[0];
            return;
        }
        stepProgressMeters = new double[route.steps.size()];
        double minimumProgress = 0d;
        for (int index = 0; index < route.steps.size(); index++) {
            RouteStep step = route.steps.get(index);
            double progress = progressTracker.progressAt(step.latitude, step.longitude, minimumProgress);
            stepProgressMeters[index] = progress;
            if (!Double.isNaN(progress)) minimumProgress = progress;
        }
    }

    private float distanceToRoute(Location location) {
        if (route == null || route.geometry == null || route.geometry.isEmpty()) return Float.MAX_VALUE;
        if (route.geometry.size() == 1) return location.distanceTo(asLocation(route.geometry.get(0)));
        double latitudeRadians = Math.toRadians(location.getLatitude());
        double metersPerLatitude = 111_320d;
        double metersPerLongitude = Math.max(1d, metersPerLatitude * Math.cos(latitudeRadians));
        double nearestSquared = Double.MAX_VALUE;
        for (int i = 1; i < route.geometry.size(); i++) {
            RoutePoint first = route.geometry.get(i - 1);
            RoutePoint second = route.geometry.get(i);
            double ax = (first.longitude - location.getLongitude()) * metersPerLongitude;
            double ay = (first.latitude - location.getLatitude()) * metersPerLatitude;
            double bx = (second.longitude - location.getLongitude()) * metersPerLongitude;
            double by = (second.latitude - location.getLatitude()) * metersPerLatitude;
            double dx = bx - ax;
            double dy = by - ay;
            double lengthSquared = dx * dx + dy * dy;
            double fraction = lengthSquared == 0d ? 0d : Math.max(0d, Math.min(1d, -(ax * dx + ay * dy) / lengthSquared));
            double px = ax + fraction * dx;
            double py = ay + fraction * dy;
            nearestSquared = Math.min(nearestSquared, px * px + py * py);
        }
        return (float) Math.sqrt(nearestSquared);
    }

    private Location asLocation(RoutePoint point) {
        Location location = new Location("route");
        location.setLatitude(point.latitude);
        location.setLongitude(point.longitude);
        return location;
    }

    private void updateTargetReference(Location location) {
        offRouteSamples = 0;
        if (location == null || route == null || route.steps.isEmpty()) {
            targetReference = null;
            targetDistanceAtReference = Float.MAX_VALUE;
            return;
        }
        targetReference = new Location(location);
        RouteStep target = route.steps.get(Math.min(nextStep, route.steps.size() - 1));
        targetDistanceAtReference = location.distanceTo(asLocation(target));
    }

    private Location asLocation(RouteStep step) {
        Location location = new Location("route");
        location.setLatitude(step.latitude);
        location.setLongitude(step.longitude);
        return location;
    }

}
