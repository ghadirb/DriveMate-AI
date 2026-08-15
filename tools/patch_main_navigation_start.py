from pathlib import Path
p = Path('app/src/main/java/ai/drivemate/MainActivity.java')
s = p.read_text(encoding='utf-8')
needle = '''            activeSessionOwner = new java.lang.ref.WeakReference<>(MainActivity.this);\n            activeDestination = destination;\n            activeRoute = route;\n            RouteCache.store(route, destination.latitude, destination.longitude);\n            activeWaypoints = new ArrayList<>(requestedWaypoints);\n'''
replacement = '''            activeSessionOwner = new java.lang.ref.WeakReference<>(MainActivity.this);\n            activeDestination = destination;\n            activeRoute = route;\n            RouteCache.store(route, destination.latitude, destination.longitude);\n            activeWaypoints = new ArrayList<>(requestedWaypoints);\n            // Start the location foreground service while the Activity is visible. Android 12+\n            // restricts background starts for location FGS, so waiting until onDestroy is too late.\n            startBackgroundNavigation();\n'''
if replacement in s:
    raise SystemExit(0)
if s.count(needle) != 1:
    raise SystemExit(f'expected one main-session anchor, found {s.count(needle)}')
p.write_text(s.replace(needle, replacement, 1), encoding='utf-8')
