from pathlib import Path

path = Path('app/src/main/java/ai/drivemate/MapActivity.java')
s = path.read_text(encoding='utf-8')

s = s.replace('    private final NavigationEngine navigationEngine = new NavigationEngine();\n', '')
s = s.replace('if (navigationMode && navigationEngine.isNavigating() && destination != null)', 'if (navigationMode && destination != null)')
s = s.replace('if (navigationMode && navigationEngine.isNavigating())', 'if (navigationMode && destination != null)')
s = s.replace('        navigationEngine.onLocation(accepted);\n', '')

# MapActivity never starts its own live engine.
s = s.replace('        navigationEngine.start(route, this, current, new RoutePoint(destination.latitude, destination.longitude));\n',
              '        // MainActivity owns the single live NavigationEngine; MapActivity only renders the route.\n        displayedStepIndex = 0;\n')

# Route-only initial visual instruction.
old = '''        if (!navigationEngine.announceCurrentInstruction()) {\n            turnInstructionText.setText("به سمت مقصد حرکت کنید");\n            turnArrowText.setText("↑");\n            renderLaneGuidance(null);\n        }\n'''
new = '''        displayedStepIndex = 0;\n        if (selectedRoute != null && selectedRoute.steps != null && !selectedRoute.steps.isEmpty()) {\n            RouteStep first = selectedRoute.steps.get(0);\n            turnInstructionText.setText(first.instruction == null || first.instruction.trim().isEmpty()\n                    ? "به سمت مقصد حرکت کنید" : first.instruction);\n            turnArrowText.setText(arrowForInstruction(first.instruction));\n            renderLaneGuidance(first.laneGuidance);\n        } else {\n            turnInstructionText.setText("به سمت مقصد حرکت کنید");\n            turnArrowText.setText("↑");\n            renderLaneGuidance(null);\n        }\n'''
if old in s: s = s.replace(old, new, 1)

old = '''        RouteStep step = navigationEngine.currentStep();\n        if (step == null) return;\n'''
new = '''        if (selectedRoute == null || selectedRoute.steps == null || selectedRoute.steps.isEmpty()) return;\n        if (displayedStepIndex < 0) displayedStepIndex = 0;\n        if (displayedStepIndex >= selectedRoute.steps.size()) displayedStepIndex = selectedRoute.steps.size() - 1;\n        while (displayedStepIndex < selectedRoute.steps.size() - 1) {\n            RouteStep currentStep = selectedRoute.steps.get(displayedStepIndex);\n            Location currentTarget = new Location("route");\n            currentTarget.setLatitude(currentStep.latitude);\n            currentTarget.setLongitude(currentStep.longitude);\n            if (location.distanceTo(currentTarget) > 30f) break;\n            displayedStepIndex++;\n        }\n        RouteStep step = selectedRoute.steps.get(displayedStepIndex);\n'''
if old in s: s = s.replace(old, new, 1)

# The HUD needs the same live location already available to updateTurnBanner.
s = s.replace('        updateDrivingHud(metersToTurn);', '        updateDrivingHud(location, metersToTurn);')
s = s.replace('        int remainingMeters = navigationEngine.remainingMeters();',
              '        int remainingMeters = estimateRemainingRouteMeters(location);')
s = s.replace('private void updateDrivingHud(float metersToCurrentTarget)',
              'private void updateDrivingHud(Location location, float metersToCurrentTarget)')

anchor = '    private void updateDrivingHud(Location location, float metersToCurrentTarget) {'
if 'private int estimateRemainingRouteMeters(Location location)' not in s and anchor in s:
    helper = '''    private int estimateRemainingRouteMeters(Location location) {\n        if (selectedRoute == null || selectedRoute.steps == null || selectedRoute.steps.isEmpty()) return 0;\n        int start = Math.max(0, Math.min(displayedStepIndex, selectedRoute.steps.size() - 1));\n        Location previous = location;\n        double total = 0d;\n        for (int i = start; i < selectedRoute.steps.size(); i++) {\n            RouteStep step = selectedRoute.steps.get(i);\n            Location point = new Location("route");\n            point.setLatitude(step.latitude);\n            point.setLongitude(step.longitude);\n            total += previous.distanceTo(point);\n            previous = point;\n        }\n        if (destination != null) {\n            Location end = new Location("destination");\n            end.setLatitude(destination.latitude);\n            end.setLongitude(destination.longitude);\n            if (previous.distanceTo(end) > 5f) total += previous.distanceTo(end);\n        }\n        return (int) Math.max(0, Math.round(total));\n    }\n\n'''
    s = s.replace(anchor, helper + anchor, 1)

s = s.replace('''        RoutePoint snapped = navigationEngine.snappedRoutePosition();\n        if (navigationMode && snapped != null) {\n            return new LatLng(snapped.latitude, snapped.longitude);\n        }\n''', '')
s = s.replace('''        if (navigationEngine.isNavigating() && navigationEngine.snappedRoutePosition() != null) {\n            return navigationEngine.routeHeading();\n        }\n''', '')
s = s.replace('Math.min(navigationEngine.currentStepIndex(), selectedRoute.steps.size() - 1)',
              'Math.min(displayedStepIndex, selectedRoute.steps.size() - 1)')
s = s.replace('boolean wasNavigating = navigationMode && navigationEngine.isNavigating();\n        navigationEngine.stop();\n',
              'boolean wasNavigating = navigationMode && destination != null;\n')

if 'navigationEngine.' in s:
    remaining = [line.strip() for line in s.splitlines() if 'navigationEngine.' in line]
    raise SystemExit('navigationEngine references remain in MapActivity: ' + ' | '.join(remaining))
path.write_text(s, encoding='utf-8')
print('single navigation owner repair applied')
