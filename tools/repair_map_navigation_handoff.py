from pathlib import Path

path = Path('app/src/main/java/ai/drivemate/MapActivity.java')
s = path.read_text(encoding='utf-8')

old = '''    private void returnToMainTab(String tab) {\n        Log.i("DriveMateSession", "Leaving navigation map for tab=" + tab\n                + "; main navigation remains active=" + navigationMode);\n        Intent result = new Intent();\n        result.putExtra(RESULT_MAIN_TAB, tab);\n        setResult(RESULT_OK, result);\n        finish();\n    }\n'''
new = '''    private void returnToMainTab(String tab) {\n        Log.i("DriveMateSession", "Leaving navigation map for tab=" + tab\n                + "; main navigation remains active=" + navigationMode);\n        Intent result = new Intent();\n        result.putExtra(RESULT_MAIN_TAB, tab);\n        // If this screen was the component that started the live route, hand the active\n        // destination back to MainActivity before finishing. Otherwise MainActivity receives only\n        // RESULT_MAIN_TAB and has no route to resume, so leaving the map silently kills the trip.\n        // MainActivity will become the single authoritative navigation/voice owner from here.\n        if (navigationMode && navigationEngine.isNavigating() && destination != null) {\n            result.putExtra(RESULT_LATITUDE, destination.latitude);\n            result.putExtra(RESULT_LONGITUDE, destination.longitude);\n            result.putExtra(RESULT_NAME, destination.name);\n            result.putExtra(RESULT_ADDRESS, destination.address);\n            result.putExtra(RESULT_ROUTE_INDEX, Math.max(0, navigationRouteIndex));\n            result.putStringArrayListExtra(RESULT_WAYPOINTS, encodeWaypoints());\n        }\n        setResult(RESULT_OK, result);\n        finish();\n    }\n\n    @Override\n    public void onBackPressed() {\n        // The Android back gesture/button must use the same handoff as the in-app map exit.\n        // Explicitly stopping navigation remains a separate user action.\n        if (navigationMode && navigationEngine.isNavigating() && destination != null) {\n            returnToMainTab("dashboard");\n            return;\n        }\n        super.onBackPressed();\n    }\n'''
if old not in s:
    raise SystemExit('returnToMainTab anchor not found')
s = s.replace(old, new, 1)
path.write_text(s, encoding='utf-8')
print('map navigation handoff repair applied')
