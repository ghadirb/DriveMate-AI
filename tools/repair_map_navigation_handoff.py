from pathlib import Path

path = Path('app/src/main/java/ai/drivemate/MapActivity.java')
s = path.read_text(encoding='utf-8')

old = '''    private void returnToMainTab(String tab) {\n        Log.i("DriveMateSession", "Leaving navigation map for tab=" + tab\n                + "; main navigation remains active=" + navigationMode);\n        Intent result = new Intent();\n        result.putExtra(RESULT_MAIN_TAB, tab);\n        setResult(RESULT_OK, result);\n        finish();\n    }\n'''
new = '''    private void returnToMainTab(String tab) {\n        Log.i("DriveMateSession", "Leaving navigation map for tab=" + tab\n                + "; main navigation remains active=" + navigationMode);\n        Intent result = new Intent();\n        // MainActivity checks RESULT_MAIN_TAB before destination extras. Therefore a live navigation\n        // handoff must NOT include RESULT_MAIN_TAB, otherwise MainActivity would return early and\n        // never restart its authoritative navigation/voice session. A normal non-navigation map\n        // exit still returns the requested tab as before.\n        if (navigationMode && navigationEngine.isNavigating() && destination != null) {\n            result.putExtra(RESULT_LATITUDE, destination.latitude);\n            result.putExtra(RESULT_LONGITUDE, destination.longitude);\n            result.putExtra(RESULT_NAME, destination.name);\n            result.putExtra(RESULT_ADDRESS, destination.address);\n            result.putExtra(RESULT_ROUTE_INDEX, Math.max(0, navigationRouteIndex));\n            result.putStringArrayListExtra(RESULT_WAYPOINTS, encodeWaypoints());\n        } else {\n            result.putExtra(RESULT_MAIN_TAB, tab);\n        }\n        setResult(RESULT_OK, result);\n        finish();\n    }\n\n    @Override\n    public void onBackPressed() {\n        // The Android back gesture/button must use the same handoff as the in-app map exit.\n        // Explicitly stopping navigation remains a separate user action.\n        if (navigationMode && navigationEngine.isNavigating() && destination != null) {\n            returnToMainTab("dashboard");\n            return;\n        }\n        super.onBackPressed();\n    }\n'''
if old in s:
    s = s.replace(old, new, 1)
elif 'MainActivity checks RESULT_MAIN_TAB before destination extras' not in s:
    raise SystemExit('returnToMainTab anchor not found and repair is not already applied')
path.write_text(s, encoding='utf-8')
print('map navigation handoff repair applied')
