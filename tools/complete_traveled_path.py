from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAP = ROOT / "app/src/main/java/ai/drivemate/MapActivity.java"
MAIN = ROOT / "app/src/main/java/ai/drivemate/MainActivity.java"
POLY = ROOT / "app/src/main/java/ai/drivemate/map/Polyline.java"


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"anchor not found: {label}")
    return text.replace(old, new, 1)


def add_once(text, anchor, addition, label):
    if addition.strip() in text:
        return text
    if anchor not in text:
        raise SystemExit(f"anchor not found: {label}")
    return text.replace(anchor, anchor + addition, 1)

# 1) Give Polyline an explicit style so the traveled track is visually distinct from the planned route.
text = POLY.read_text(encoding="utf-8")
old = '''    private final List<LatLng> points;\n    private final boolean primary;\n    private org.osmdroid.views.overlay.Polyline overlay;\n\n    public Polyline(List<LatLng> points, boolean primary) {\n        this.points = points == null ? new ArrayList<>() : new ArrayList<>(points);\n        this.primary = primary;\n    }'''
new = '''    private final List<LatLng> points;\n    private final boolean primary;\n    private final Integer customColor;\n    private final Float customWidth;\n    private org.osmdroid.views.overlay.Polyline overlay;\n\n    public Polyline(List<LatLng> points, boolean primary) {\n        this(points, primary, null, null);\n    }\n\n    /** Explicit style for overlays such as the actual traveled track. */\n    public Polyline(List<LatLng> points, int color, float width) {\n        this(points, false, color, width);\n    }\n\n    private Polyline(List<LatLng> points, boolean primary, Integer customColor, Float customWidth) {\n        this.points = points == null ? new ArrayList<>() : new ArrayList<>(points);\n        this.primary = primary;\n        this.customColor = customColor;\n        this.customWidth = customWidth;\n    }'''
text = replace_once(text, old, new, "Polyline constructors")
text = replace_once(text,
    '            overlay.getOutlinePaint().setColor(primary ? Color.rgb(0, 94, 255) : Color.rgb(90, 90, 90));\n            overlay.getOutlinePaint().setStrokeWidth(primary ? 20f : 9f);',
    '            overlay.getOutlinePaint().setColor(customColor != null ? customColor : (primary ? Color.rgb(0, 94, 255) : Color.rgb(90, 90, 90)));\n            overlay.getOutlinePaint().setStrokeWidth(customWidth != null ? customWidth : (primary ? 20f : 9f));',
    "Polyline style application")
POLY.write_text(text, encoding="utf-8")

# 2) MapActivity: keep a compact GPS trail and draw it live during navigation.
text = MAP.read_text(encoding="utf-8")
text = replace_once(text,
    '    private float tripTraveledDistanceMeters;\n    private Location lastTripAccumLocation;',
    '''    private float tripTraveledDistanceMeters;\n    private Location lastTripAccumLocation;\n    /** GPS samples actually driven during the current navigation session. */\n    private final List<RoutePoint> activeTraveledPath = new ArrayList<>();\n    private Polyline traveledPathPolyline;\n    private long lastTraveledPathRenderAt;''',
    "MapActivity traveled-path fields")

text = replace_once(text,
    '''            tripTraveledDistanceMeters = 0f;\n            lastTripAccumLocation = null;''',
    '''            tripTraveledDistanceMeters = 0f;\n            lastTripAccumLocation = null;\n            activeTraveledPath.clear();\n            lastTraveledPathRenderAt = 0L;\n            clearTraveledPathOverlay();''',
    "MapActivity new-trip reset")

text = replace_once(text,
    '''    private static final float MANUAL_STOP_COMPLETION_RADIUS_METERS = 200f;\n\n    private void stopNavigationFromMap() {''',
    '''    private static final float MANUAL_STOP_COMPLETION_RADIUS_METERS = 200f;\n\n    private void recordActiveTraveledPath(Location location) {\n        if (!navigationMode || location == null || !navigationEngine.isNavigating()) return;\n        if (activeTraveledPath.isEmpty()) {\n            activeTraveledPath.add(new RoutePoint(location.getLatitude(), location.getLongitude()));\n        } else {\n            RoutePoint last = activeTraveledPath.get(activeTraveledPath.size() - 1);\n            Location lastLocation = new Location("traveled_path");\n            lastLocation.setLatitude(last.latitude);\n            lastLocation.setLongitude(last.longitude);\n            if (lastLocation.distanceTo(location) < 8f) return;\n            activeTraveledPath.add(new RoutePoint(location.getLatitude(), location.getLongitude()));\n            // Keep memory and drawing cost bounded just like TripRecord.traveledPath.\n            while (activeTraveledPath.size() > 240) activeTraveledPath.remove(0);\n        }\n        long now = System.currentTimeMillis();\n        if (now - lastTraveledPathRenderAt >= 1000L || activeTraveledPath.size() == 1) {\n            renderTraveledPath();\n            lastTraveledPathRenderAt = now;\n        }\n    }\n\n    private void renderTraveledPath() {\n        if (map == null || !map.isReadyForOverlays() || activeTraveledPath.size() < 2) return;\n        clearTraveledPathOverlay();\n        ArrayList<LatLng> points = new ArrayList<>();\n        for (RoutePoint point : activeTraveledPath) points.add(new LatLng(point.latitude, point.longitude));\n        // Green/teal is intentionally different from the planned blue route.\n        traveledPathPolyline = new Polyline(points, android.graphics.Color.rgb(0, 150, 110), 14f);\n        map.addPolyline(traveledPathPolyline);\n    }\n\n    private void clearTraveledPathOverlay() {\n        if (map != null && traveledPathPolyline != null && map.isReadyForOverlays()) {\n            map.removePolyline(traveledPathPolyline);\n        }\n        traveledPathPolyline = null;\n    }\n\n    private void stopNavigationFromMap() {''',
    "MapActivity traveled-path methods")

text = replace_once(text,
    '''        if (navigationMode && navigationEngine.isNavigating()) {\n            if (lastTripAccumLocation != null) tripTraveledDistanceMeters += lastTripAccumLocation.distanceTo(accepted);\n            lastTripAccumLocation = new Location(accepted);\n        }''',
    '''        if (navigationMode && navigationEngine.isNavigating()) {\n            if (lastTripAccumLocation != null) tripTraveledDistanceMeters += lastTripAccumLocation.distanceTo(accepted);\n            lastTripAccumLocation = new Location(accepted);\n        }''',
    "MapActivity location distance anchor")
# Add trail recording inside the UI callback after current marker update; it is safe on the main thread.
text = replace_once(text,
    '''            showCurrentMarker();\n            if (selectedRoute != null) updateRoadSpeedLimit(accepted.getLatitude(), accepted.getLongitude());''',
    '''            showCurrentMarker();\n            if (navigationMode && navigationEngine.isNavigating()) recordActiveTraveledPath(accepted);\n            if (selectedRoute != null) updateRoadSpeedLimit(accepted.getLatitude(), accepted.getLongitude());''',
    "MapActivity live trail recording")

# Make the trail survive map redraws/reroutes and clear only when the trip ends.
text = replace_once(text,
    '''        if (routePolyline != null) {\n            map.removePolyline(routePolyline);\n            routePolyline = null;\n        }''',
    '''        if (routePolyline != null) {\n            map.removePolyline(routePolyline);\n            routePolyline = null;\n        }\n        // drawAllRoutes() is also called after reroutes; restore the actual driven trail after\n        // replacing planned-route overlays so it never disappears when the route is recalculated.\n        renderTraveledPath();''',
    "MapActivity route redraw preservation")

text = replace_once(text,
    '''        resetTripTracking();\n        Intent result = new Intent();''',
    '''        clearTraveledPathOverlay();\n        activeTraveledPath.clear();\n        resetTripTracking();\n        Intent result = new Intent();''',
    "MapActivity trip end cleanup")
MAP.write_text(text, encoding="utf-8")

# 3) MainActivity: make the map button obvious in the trip history instead of hiding it in a dialog.
text = MAIN.read_text(encoding="utf-8")
old = '''            body.addView(destination);\n            body.addView(metadata);\n            card.addView(body);'''
new = '''            body.addView(destination);\n            body.addView(metadata);\n            Button mapTrip = new Button(this);\n            mapTrip.setText("نمایش مسیر این سفر روی نقشه");\n            mapTrip.setAllCaps(false);\n            mapTrip.setOnClickListener(v -> {\n                try {\n                    Intent intent = new Intent(this, TripMapActivity.class);\n                    intent.putExtra(TripMapActivity.EXTRA_TRIP_JSON, record.toJson().toString());\n                    startActivity(intent);\n                } catch (Exception e) {\n                    Toast.makeText(this, "امکان نمایش مسیر این سفر وجود ندارد.", Toast.LENGTH_SHORT).show();\n                }\n            });\n            body.addView(mapTrip, new LinearLayout.LayoutParams(\n                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));\n            card.addView(body);'''
text = replace_once(text, old, new, "visible trip map button")
MAIN.write_text(text, encoding="utf-8")

print("Completed visible live traveled path and visible trip-map access")
