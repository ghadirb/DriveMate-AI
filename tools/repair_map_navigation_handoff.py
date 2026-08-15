from pathlib import Path

path = Path('app/src/main/java/ai/drivemate/MapActivity.java')
s = path.read_text(encoding='utf-8')
original = s

# MapActivity must be a renderer/handoff screen only. MainActivity owns the single live
# NavigationEngine, GPS session and voice queue. A second engine here was the main source of
# background/session loss, duplicate callbacks and missing announcements when leaving the map.
s = s.replace(
    'public class MapActivity extends Activity implements LocationListener, NavigationEngine.Listener {',
    'public class MapActivity extends Activity implements LocationListener {',
    1,
)
s = s.replace('    private final NavigationEngine navigationEngine = new NavigationEngine();\n', '', 1)
if '    private int displayedStepIndex;\n' not in s:
    s = s.replace('    private boolean tripCompletionShown;\n',
                  '    private boolean tripCompletionShown;\n    private int displayedStepIndex;\n', 1)

# Leaving the map must always hand the active destination/waypoints back; it must not depend on
# a second local engine's isNavigating() state.
s = s.replace(
    'if (navigationMode && navigationEngine.isNavigating() && destination != null)',
    'if (navigationMode && destination != null)',
)

# Do not start a second engine when the route is displayed or redrawn.
old = '''        navigationEngine.start(route, this, current, new RoutePoint(destination.latitude, destination.longitude));\n        // Guarded: refreshNavigationRouteFrom/recalculateActiveRoute also call this on every\n        // reroute, which must not reset the true trip start time/origin/distance-so-far - only a\n        // genuinely new trip (mapNavigationStartedAt == 0) initializes these.\n'''
new = '''        // MainActivity owns the single live NavigationEngine. This Activity only renders the\n        // selected route and follows the shared location feed; starting another engine here caused\n        // duplicate GPS/session state, competing callbacks, missing voice announcements and\n        // navigation stopping when this map Activity was closed.\n        displayedStepIndex = 0;\n        // Guarded: rerendering/rerouting the map must not reset the true trip start time.\n'''
if old not in s:
    raise SystemExit('startTurnByTurn anchor not found; refusing unsafe patch')
s = s.replace(old, new, 1)

# The first visible instruction is rendered from the selected route. Voice remains owned by MainActivity.
old = '''        turnExpandIcon.setText("▾");\n        if (!navigationEngine.announceCurrentInstruction()) {\n            turnInstructionText.setText("به سمت مقصد حرکت کنید");\n            turnArrowText.setText("↑");\n            renderLaneGuidance(null);\n        }\n'''
new = '''        turnExpandIcon.setText("▾");\n        displayedStepIndex = 0;\n        if (selectedRoute != null && selectedRoute.steps != null && !selectedRoute.steps.isEmpty()) {\n            RouteStep first = selectedRoute.steps.get(0);\n            turnInstructionText.setText(first.instruction == null || first.instruction.trim().isEmpty()\n                    ? "به سمت مقصد حرکت کنید" : first.instruction);\n            turnArrowText.setText(arrowForInstruction(first.instruction));\n            renderLaneGuidance(first.laneGuidance);\n        } else {\n            turnInstructionText.setText("به سمت مقصد حرکت کنید");\n            turnArrowText.setText("↑");\n            renderLaneGuidance(null);\n        }\n'''
if old not in s:
    raise SystemExit('initial instruction anchor not found; refusing unsafe patch')
s = s.replace(old, new, 1)

# Replace engine-dependent current-step lookup with a small deterministic UI cursor over route steps.
old = '''        RouteStep step = navigationEngine.currentStep();\n        if (step == null) return;\n'''
new = '''        if (selectedRoute == null || selectedRoute.steps == null || selectedRoute.steps.isEmpty()) return;\n        if (displayedStepIndex < 0) displayedStepIndex = 0;\n        if (displayedStepIndex >= selectedRoute.steps.size()) displayedStepIndex = selectedRoute.steps.size() - 1;\n        while (displayedStepIndex < selectedRoute.steps.size() - 1) {\n            RouteStep currentStep = selectedRoute.steps.get(displayedStepIndex);\n            Location currentTarget = new Location("route");\n            currentTarget.setLatitude(currentStep.latitude);\n            currentTarget.setLongitude(currentStep.longitude);\n            if (location.distanceTo(currentTarget) > 30f) break;\n            displayedStepIndex++;\n        }\n        RouteStep step = selectedRoute.steps.get(displayedStepIndex);\n'''
if old not in s:
    raise SystemExit('current-step anchor not found; refusing unsafe patch')
s = s.replace(old, new, 1)
s = s.replace('        int remainingMeters = navigationEngine.remainingMeters();\n',
              '        int remainingMeters = estimateRemainingRouteMeters(location);\n', 1)

anchor = '    private void updateDrivingHud(float metersToCurrentTarget) {'
helper = '''    private int estimateRemainingRouteMeters(Location location) {\n        if (selectedRoute == null || selectedRoute.steps == null || selectedRoute.steps.isEmpty()) return 0;\n        int start = Math.max(0, Math.min(displayedStepIndex, selectedRoute.steps.size() - 1));\n        Location previous = location;\n        double total = 0d;\n        for (int i = start; i < selectedRoute.steps.size(); i++) {\n            RouteStep step = selectedRoute.steps.get(i);\n            Location point = new Location("route");\n            point.setLatitude(step.latitude);\n            point.setLongitude(step.longitude);\n            total += previous.distanceTo(point);\n            previous = point;\n        }\n        if (destination != null) {\n            Location end = new Location("destination");\n            end.setLatitude(destination.latitude);\n            end.setLongitude(destination.longitude);\n            if (previous.distanceTo(end) > 5f) total += previous.distanceTo(end);\n        }\n        return (int) Math.max(0, Math.round(total));\n    }\n\n'''
if anchor not in s:
    raise SystemExit('HUD anchor not found; refusing unsafe patch')
s = s.replace(anchor, helper + anchor, 1)

if 'navigationEngine.' in s:
    raise SystemExit('navigationEngine references remain in MapActivity; refusing partial ownership repair')
if s == original:
    raise SystemExit('patch made no changes')
path.write_text(s, encoding='utf-8')
print('single navigation owner repair applied')
