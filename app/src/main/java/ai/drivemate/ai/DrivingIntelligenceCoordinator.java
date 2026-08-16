package ai.drivemate.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Serializes optional AI work so a delayed answer never interrupts an already-spoken local alert.
 * In economy mode local fallbacks are immediate. Full mode grants online generation a short budget.
 */
public class DrivingIntelligenceCoordinator {
    public enum Mode { ECONOMY, FULL }
    public enum Priority { SAFETY, DRIVING, CONVERSATION }
    public interface Listener { void onText(String requestId, String text, boolean online); }

    private enum State { PENDING, RUNNING, FALLBACK, CANCELLED, READY }
    private static final long FULL_MODE_SAFETY_WAIT_MS = 0L;
    private static final long FULL_MODE_DRIVING_WAIT_MS = 1_000L;
    private static final long FULL_MODE_STANDARD_WAIT_MS = 3000L;
    private static final long ECONOMY_ONLINE_COOLDOWN_MS = 45_000L; // 45-60s suggested
    private static final long ECONOMY_ONLINE_WAIT_MS = 2_500L;

    private final AiAssistant assistant;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final PriorityQueue<Request> queue = new PriorityQueue<>();
    private final Map<String, Request> requests = new HashMap<>();
    private final Map<String, Prepared> prepared = new HashMap<>();
    private Mode mode = Mode.ECONOMY;
    private boolean draining;
    private long lastEconomyOnlineCallAt;

    public DrivingIntelligenceCoordinator(AiAssistant assistant) { this.assistant = assistant; }

    public synchronized void setMode(Mode mode) { this.mode = mode == null ? Mode.ECONOMY : mode; }
    public synchronized Mode getMode() { return mode; }

    public String request(Priority priority, String prompt, String context, String fallback, boolean onlineInEconomy,
                          long expiresInMs, Listener listener) {
        final Request request = new Request(priority, prompt, context, fallback, expiresInMs, listener);
        synchronized (this) {
            boolean economyOnlineAllowed = false;
            if (mode == Mode.ECONOMY) {
                boolean allowOnline = onlineInEconomy
                        && (System.currentTimeMillis() - lastEconomyOnlineCallAt) >= ECONOMY_ONLINE_COOLDOWN_MS;
                if (!allowOnline) {
                    request.state = State.FALLBACK;
                    dispatch(request, fallback, false);
                    return request.id;
                }
                lastEconomyOnlineCallAt = System.currentTimeMillis();
                economyOnlineAllowed = true;
            }
            requests.put(request.id, request);
            queue.add(request);
            startDrainLocked();
            if (mode == Mode.FULL && fallback != null && !fallback.trim().isEmpty()) {
                long budget = waitBudget(request.priority);
                if (budget <= 0L && (request.priority == Priority.SAFETY || request.priority == Priority.DRIVING)) {
                    request.state = State.FALLBACK;
                    dispatch(request, fallback, false);
                } else {
                    timer.schedule(() -> fallbackAfterBudget(request.id), budget, TimeUnit.MILLISECONDS);
                }
            } else if (economyOnlineAllowed && fallback != null && !fallback.trim().isEmpty()) {
                timer.schedule(() -> fallbackAfterBudget(request.id), ECONOMY_ONLINE_WAIT_MS, TimeUnit.MILLISECONDS);
            }
        }
        return request.id;
    }

    /** Prepares a non-urgent response for an event that is likely to occur shortly. */
    public void prefetch(String key, Priority priority, String prompt, String context, long expiresInMs) {
        synchronized (this) {
            if (mode != Mode.FULL || key == null || key.trim().isEmpty()) return;
            Prepared current = prepared.get(key);
            if (current != null && current.expiresAt > System.currentTimeMillis()) return;
        }
        Request request = new Request(priority, prompt, context, null, expiresInMs, null);
        request.prefetchKey = key;
        synchronized (this) {
            requests.put(request.id, request);
            queue.add(request);
            startDrainLocked();
        }
    }

    public synchronized String consumePrepared(String key) {
        Prepared value = prepared.remove(key);
        if (value == null || value.expiresAt <= System.currentTimeMillis()) return null;
        return value.text;
    }

    public synchronized void cancel(String requestId) {
        Request request = requests.get(requestId);
        if (request != null) request.state = State.CANCELLED;
    }

    public synchronized void cancelAll() {
        for (Request request : requests.values()) request.state = State.CANCELLED;
        queue.clear();
        prepared.clear();
    }

    public void shutdown() {
        cancelAll();
        worker.shutdownNow();
        timer.shutdownNow();
    }

    private void fallbackAfterBudget(String id) {
        Request request;
        synchronized (this) {
            request = requests.get(id);
            if (request == null || request.state == State.CANCELLED || request.state == State.READY
                    || request.expiresAt <= System.currentTimeMillis()) return;
            request.state = State.FALLBACK;
        }
        dispatch(request, request.fallback, false);
    }

    private synchronized void startDrainLocked() {
        if (draining) return;
        draining = true;
        worker.execute(this::drain);
    }

    private void drain() {
        while (true) {
            Request request;
            synchronized (this) {
                request = queue.poll();
                if (request == null) { draining = false; return; }
                if (request.state == State.CANCELLED || request.state == State.FALLBACK || request.expiresAt <= System.currentTimeMillis()) {
                    request.state = State.CANCELLED;
                    continue;
                }
                request.state = State.RUNNING;
            }
            AiAssistant.AnswerResult answer = assistant.answerNowResult(request.prompt, request.context);
            synchronized (this) {
                if (request.state == State.CANCELLED || request.state == State.FALLBACK || request.expiresAt <= System.currentTimeMillis()) {
                    request.state = State.CANCELLED;
                    continue;
                }
                request.state = State.READY;
                if (request.prefetchKey != null) {
                    if (answer.online) prepared.put(request.prefetchKey, new Prepared(answer.text, request.expiresAt));
                    continue;
                }
            }
            dispatch(request, answer.text, answer.online);
        }
    }

    private long waitBudget(Priority priority) {
        switch (priority) {
            case SAFETY: return FULL_MODE_SAFETY_WAIT_MS;
            case DRIVING: return FULL_MODE_DRIVING_WAIT_MS;
            default: return FULL_MODE_STANDARD_WAIT_MS;
        }
    }

    private void dispatch(Request request, String text, boolean online) {
        if (request.listener != null && text != null && !text.trim().isEmpty()) request.listener.onText(request.id, text, online);
    }

    private static final class Request implements Comparable<Request> {
        final String id = UUID.randomUUID().toString();
        final Priority priority;
        final String prompt;
        final String context;
        final String fallback;
        final long createdAt = System.currentTimeMillis();
        final long expiresAt;
        final Listener listener;
        State state = State.PENDING;
        String prefetchKey;

        Request(Priority priority, String prompt, String context, String fallback, long expiresInMs, Listener listener) {
            this.priority = priority == null ? Priority.CONVERSATION : priority;
            this.prompt = prompt == null ? "" : prompt;
            this.context = context == null ? "" : context;
            this.fallback = fallback;
            this.listener = listener;
            this.expiresAt = createdAt + Math.max(1000L, expiresInMs);
        }

        @Override public int compareTo(Request other) {
            int priorityOrder = Integer.compare(priority.ordinal(), other.priority.ordinal());
            return priorityOrder != 0 ? priorityOrder : Long.compare(createdAt, other.createdAt);
        }
    }

    private static final class Prepared {
        final String text;
        final long expiresAt;
        Prepared(String text, long expiresAt) { this.text = text; this.expiresAt = expiresAt; }
    }
}
