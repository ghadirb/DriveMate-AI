from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
main = ROOT / "app/src/main/java/ai/drivemate/MainActivity.java"
service = ROOT / "app/src/main/java/ai/drivemate/NavigationForegroundService.java"

text = main.read_text(encoding="utf-8")

old = """    private static java.lang.ref.WeakReference<MainActivity> activeSessionOwner;\n"""
new = old + """    /** Strong owner while a foreground navigation service is active. This deliberately keeps the\n     * authoritative Activity instance alive while navigation is running so GPS/voice callbacks\n     * do not disappear when the task leaves the foreground. Cleared on a real navigation stop. */\n    private static MainActivity backgroundSessionOwner;\n"""
if "private static MainActivity backgroundSessionOwner;" not in text:
    if text.count(old) != 1: raise SystemExit("active owner anchor mismatch")
    text = text.replace(old, new, 1)

old = """    private void startBackgroundNavigation() {\n        if (!backgroundNavigationEnabled()) return;\n        Intent intent = new Intent(this, NavigationForegroundService.class);\n        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);\n    }\n\n    private void stopBackgroundNavigation() {\n        stopService(new Intent(this, NavigationForegroundService.class));\n    }\n"""
new = """    private void startBackgroundNavigation() {\n        if (!backgroundNavigationEnabled()) return;\n        backgroundSessionOwner = this;\n        Intent intent = new Intent(this, NavigationForegroundService.class);\n        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);\n    }\n\n    private void stopBackgroundNavigation() {\n        if (backgroundSessionOwner == this) backgroundSessionOwner = null;\n        stopService(new Intent(this, NavigationForegroundService.class));\n    }\n"""
if "backgroundSessionOwner = this;" not in text:
    if text.count(old) != 1: raise SystemExit("background service anchor mismatch")
    text = text.replace(old, new, 1)

needle = """        fetchRouteTrafficIncidents(route);\n        navigationEngine.start(route, new NavigationEngine.Listener() {\n"""
replacement = """        fetchRouteTrafficIncidents(route);\n        // Start the location foreground service while the Activity is visible. Android 12+\n        // restricts background starts for location FGS, so waiting until onDestroy is too late.\n        startBackgroundNavigation();\n        navigationEngine.start(route, new NavigationEngine.Listener() {\n"""
if text.count(needle) == 1 and "Start the location foreground service while the Activity is visible" not in text:
    text = text.replace(needle, replacement, 1)
elif text.count(needle) != 1 and "Start the location foreground service while the Activity is visible" not in text:
    raise SystemExit(f"navigation start anchor count={text.count(needle)}")

old = """        if (activeDestination == null) return;\n        resetGuidance(true);\n        TripRecord tripReport = buildTripRecord(destination, true);\n"""
new = """        if (activeDestination == null) return;\n        resetGuidance(true);\n        stopBackgroundNavigation();\n        TripRecord tripReport = buildTripRecord(destination, true);\n"""
if "stopBackgroundNavigation();\n        TripRecord tripReport = buildTripRecord(destination, true);" not in text:
    if text.count(old) != 1: raise SystemExit("finish trip anchor mismatch")
    text = text.replace(old, new, 1)

old = """        resetGuidance(true);\n        navigationEngine.stop();\n        activeDestination = null;\n"""
new = """        resetGuidance(true);\n        navigationEngine.stop();\n        stopBackgroundNavigation();\n        activeDestination = null;\n"""
if "navigationEngine.stop();\n        stopBackgroundNavigation();\n        activeDestination = null;" not in text:
    if text.count(old) != 1: raise SystemExit("map completion anchor mismatch")
    text = text.replace(old, new, 1)

main.write_text(text, encoding="utf-8")

s = service.read_text(encoding="utf-8")
if "return START_NOT_STICKY;\n    }\n\n    private Notification buildNotification" in s:
    s = s.replace("return START_NOT_STICKY;\n    }\n\n    private Notification buildNotification", "return START_STICKY;\n    }\n\n    private Notification buildNotification", 1)
service.write_text(s, encoding="utf-8")
