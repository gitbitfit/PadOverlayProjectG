# PadOverlay

Touch gamepad overlay that injects controller input via Shizuku.

## Open and build
Android Studio / Code On the Go: open this folder, let Gradle sync, run on the phone.

## First-time setup (in the app)
1. Start Shizuku. 2. Tap 1 (overlay permission), 2 (Shizuku permission). 3. Tap 3 to start.

## MacroDroid (auto start/stop per app)
Action > Applications > Send Intent
- Intent type / Target: Broadcast Receiver
- Action: com.example.padoverlay.START   (or .STOP / .TOGGLE)
- Package: com.example.padoverlay
- Class: com.example.padoverlay.ControlReceiver

Triggers: Application Launched (game) -> START, Application Closed (game) -> STOP.

If the broadcast is blocked, use Target: Activity with class
com.example.padoverlay.ControlActivity and the same actions.

Also turn off battery optimization for PadOverlay (Android Settings > Apps).
Shizuku must be running (it needs restarting after a reboot).

## Game window (any phone)
In the app: pick the game, choose the window shape, tap "Launch game in window + start overlay".
Needs Shizuku, landscape, and the game fully closed. See NOTES.md for how it works and the
fallback idea (virtual display) if freeform does not work on a phone.

## Quick settings tile
Tap "Add quick setting tile" in the app (or edit Quick Settings and drag "Pad overlay" in).
The tile starts or stops the overlay using the current settings.

## Games grid
The top of the main screen is an editable grid. Tap a game to launch it with the settings below
(launch mode: Virtual display, Freeform window or Normal). Long-press for per-launch options or to
remove it. Tap + to add a game.

## Automatic game detection
On start the app scans the installed apps in the background and adds games to the grid: apps that
declare themselves as games, or that contain a known game engine library (Unity, Unreal, Godot,
Cocos, libGDX, SDL...). Games you remove are not added back automatically; "Find games now" brings
them back. Turn it off with "Auto-detect games".
