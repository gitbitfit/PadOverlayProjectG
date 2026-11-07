# Design notes

## Goal
Put the on-screen controls in black bars beside the game on ANY phone, not only
phones that have a built-in "free display" / aspect-ratio mode (e.g. RedMagic Game Space).

## Chosen method (implemented): forced freeform window
- Shizuku (shell) turns on freeform support and force-resizable activities:
  settings put global enable_freeform_support 1
  settings put global force_resizable_activities 1
- Launches the game: am start-activity --windowingMode 5 -n <component>
- Finds the task id in `dumpsys activity activities`, then: am task resize <id> L T R B
  (works only for freeform / multi-window tasks; newer builds take spaces, older ones commas,
  so both forms are tried).
- The overlay then puts the pods in the leftover bars (Window mode layout).
Top gap (current approach): the system does not let a task move above the screen, so shifting the
window up only pushed the bottom off-screen. Instead the window is shrunk so the picture fits
exactly under the blank strip; the launcher saves that content rect and the overlay covers the
strip (and the sides) in black.
(Earlier idea, did not work: move the task to a negative top.)
Top gap: a freeform window reserves a caption bar at the top (often empty on phones), so the
game's picture starts ~100px below the window top. launchWindowed() reads the caption inset
from `dumpsys window windows`, then moves the task up by that amount (negative top) so the
picture reaches the top edge. Details are written to the log file.
Stray taps: tapping the window behind the game can bring that app in front of it. The overlay
adds four black windows around the game window (Window mode, "Black surround") that swallow
touches there; overlay windows do not change the game's focus or stacking.
Limits: needs freeform support on the phone; game must already be closed; some games ignore
resizing or crash; the log is /sdcard/Download/padoverlay-log.txt.

## Alternative (kept for later): virtual display mirrored into the app
Use this if freeform turns out to be unreliable across phones.
1. Create a virtual display with the shape you want (e.g. 1920x1080):
   DisplayManager.createVirtualDisplay(name, w, h, dpi, surface, flags)
   flags: VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY.
2. The Surface comes from a SurfaceView in our own full-screen activity, drawn letterboxed
   in the middle, with the controls in the empty sides.
3. Launch the game onto that display through Shizuku:
   am start-activity --display <displayId> -n <component>
   (As far as I know scrcpy's "new display" mode does the same thing.)
4. Touch: inject MotionEvents with the virtual display's id and coordinates in its own space.
   Keys / gamepad go to the focused display, so routing needs testing.
Pros: works without freeform; any shape and resolution; game is unmodified.
Cons: extra GPU copy (small); some games refuse secondary displays; focus and gamepad routing
are the risky parts; more code than the freeform method.

## Rejected
- Repackaging / patching games: breaks signatures (updates, licence checks, cloud saves),
  can get accounts banned by anti-cheat, needs a different patch per engine.
- Virtual machine: heavy and slow.
- `wm size` trick: gives bars, but they lie outside the logical display, so overlay windows
  cannot be placed in them. (Not tested.)

## Virtual display experiment (in progress)
"Test virtual display (experimental)" runs, on the Shizuku side, roughly what scrcpy's --new-display
does: build a DisplayManager with a fake shell Context, createVirtualDisplay with the extra
flags (trusted, own display group, own focus on newer Android), then
`am start-activity --display <id> -n <game>` and check which display the game's task landed on.
Result text is shown under the Launch button and logged ("vdtest:" lines).
If it passes, next steps: a MirrorActivity with a SurfaceView passed to the helper as the display's
surface; forward touch (MotionEvent with the display id) and gamepad events; controls in the bars.

## Virtual display mode (built after the test passed on a NX809J, Android 16)
MirrorActivity shows the game's virtual display in a SurfaceView sized to the chosen window shape;
the helper (Shizuku side) creates the display on that surface and starts the game on it.
- Touch on the picture -> helper injects touch with the display id (same size, 1:1).
- Gamepad: the HID pad's events reach MirrorActivity (focused), which forwards keys and axes to
  the game's display with the same device id.
- Touch-mapping markers use picture coordinates in this mode.
- Overlay X closes the overlay and the mirror (game is destroyed with the display).

## Lag / sync work on virtual display mode
- Pods used to go: pod -> HID report (`hid` tool) -> kernel -> MirrorActivity -> helper -> game.
  Now, while a virtual display is active and our pad device id is known, the helper injects keys and
  sticks straight onto the game's display using that device id (one hop, one ordered stream).
  Physical controllers are still forwarded by MirrorActivity.
- Button minimum hold is 30 ms in this mode (70 ms otherwise).
- "Picture quality" (100/75/50 %) shrinks the virtual display and the SurfaceView buffer; touch
  coordinates are scaled back. Helps if the phone is GPU-bound.
- The virtual display is probably capped at 60 fps and adds a compositing step (not fixed).

## Update: input fix + refresh rate
- The straight-to-game input ("Fast input") stopped the on-screen controls working for the user, so it is
  now OFF by default (setting in the app). The proven path is used: pod -> HID pad -> MirrorActivity
  -> helper -> game. With Fast input on, the first few injected keys are logged ("direct key dev=...").
- Refresh rate: on Android 14+ the virtual display is created through VirtualDisplayConfig with
  setRequestedRefreshRate(max of the screen's modes); the mirror window also asks for that rate.
  If the phone refuses, the log says "high refresh rate not available" and it stays at 60 Hz.

## Brightness on the virtual display
The mirror screen is an ordinary app, so it does not get the phone's game-mode brightness/colour
enhancements, and the game itself may fall back to SDR on a virtual display. Two options:
"Screen brightness" (window brightness override) and "Picture boost" (SurfaceControl colour
transform, hidden API, may be refused).

## Controls off + narrower shapes (for a real controller)
- "On-screen controls: Off" hides every pod except a small HIDE/SHOW button, and removes the virtual
  pad (profile NONE) so a real controller is the only pad the game sees. HIDE/SHOW toggles it live.
- New window shapes 18:9, 19:9, 19.5:9, 20:9 give black side bars to hide the camera area and curved
  corners. The RedMagic 11 Pro screen is about 2688x1216 (about 19.9:9), so 18:9 leaves roughly 128 px
  of black on each side.

## Black screen after a notification (virtual display mode)
Likely causes handled: (1) when the mirror screen went away, the display's surface was set to null,
which turns the virtual display off and can leave it black after returning. Now the helper switches
to a throw-away ImageReader surface instead, so the display and game keep running, and the real
surface is handed back on resume / focus. (2) The mirror screen no longer restarts on a dark-mode,
font or language change (extra configChanges), which would have closed the game.

## Slow phone / keyboard stopped (reported once)
Cause unknown. By design only ONE hidden display exists at a time (a new launch releases the old one),
but a crash can leave a display or `hid` pad helper behind until the Shizuku helper is stopped.
Possible contributors: game + mirror at a high refresh rate and resolution; focus switching between
displays (the display now reports "own focus on/off"); developer settings we switch on
(force_resizable_activities, enable_freeform_support). Added: Clean up button (releases the display,
stops pad helpers, switches those settings off, kills the helper process), Check button (lists displays,
helper processes and settings), force_resizable_activities is restored when the virtual display ends,
and the default refresh rate is back to 60 Hz.

## Back button exit + 18.5:9
The HIDE/SHOW button was removed. With "On-screen controls: Off" no overlay pods are created at all;
on the mirror screen the system Back button/gesture shows an "Exit the game?" confirmation (a real
controller's own Back/B button is forwarded to the game and does not trigger it). 18.5:9 was added at
the end of the shape list (so saved choices keep their meaning); the picker lists shapes sorted by width.

## Touch on virtual display (Wuthering Waves ignored it)
Touches used to be rebuilt from scratch (device id 0, full-size contact). Now the default is "Exact copy":
the real MotionEvent is parceled to the helper and injected onto the game's display unchanged (real
device id, pressure, size, history), scaled if Picture quality < 100%. "Simple" keeps the old method.
The helper logs the first few forwarded events ("vd touch ..."). If a game still ignores touch it may
reject injected input on purpose (anti-cheat); then use a real controller or Touch mapping instead.

## Refresh rate: what the status line means
Android docs: a virtual display with no requested rate refreshes at the physical display's rate, so
"Max" now asks for nothing (the display follows the screen, e.g. 144 Hz) and the mirror window asks
the screen for its top rate. 90 / 60 request that rate through VirtualDisplayConfig (API 34+), trying
any createVirtualDisplay overload that takes the config. The old "60 Hz requested" text meant only
"no rate was requested". "Check displays and helpers" now shows the display's refresh rate.

## Edge lines
Thin gradient lines (red -> violet -> cyan) sit just OUTSIDE the left and right edges of the picture, in the
black area, so they never cover game content. Setting "Edge lines": Off / Thin 2dp / Normal 4dp / Thick 6dp.
Virtual display mode: drawn by MirrorActivity. Freeform window mode: drawn by the overlay. Not drawn in
"Normal" launch mode (Game Space bars), because the exact picture edge is not known there.

## Controls too small (screenshot)
Cause: in window mode the pods are shrunk to fit narrow side bars, and Save wrote that shrunken size back
as the user's size, so later launches stayed small. Now: the automatic fit-to-bars size is never saved;
size comes from (1) the "Controls size" setting (Small .6 / Medium .8 / Large 1.0 / Extra large 1.25),
else (2) the size saved with - / + in edit mode ("scale2"), else (3) automatic. Fixed sizes may reach over
the picture edge on narrow bars. Changing the setting clears saved pod positions so they are re-spaced.

## "Controls do nothing" (Normal / Game Space launch)
Most likely cause: Shizuku was not running (it must be started again after every phone restart), so the
overlay had no helper to send input through; the buttons still looked normal. Now: pods are covered with a
red "NO SHIZUKU" mark until the helper connects, a toast explains it, the overlay retries every 2 s and
connects by itself once Shizuku is started (or asks you to allow Shizuku if permission is missing).
"Check displays and helpers" also says whether the virtual pad is running.

## Edge line colour
"Edge line color": Gradient (default red/violet/cyan), 9 preset solid colours, or Custom (R/G/B sliders with
a live preview). Stored as edge_mode (0 gradient, 1 solid) and edge_color (ARGB) in the "ui" prefs;
EdgeStyle.colors() in Bridge.kt is used by both MirrorActivity and the overlay.

## Bar background (fills the area outside the picture)
Setting "Bar background": Black / Gradient (8 presets or two custom colours) / Image from the gallery
(persistable URI, scaled and centre-cropped to the screen with ImageDecoder, EXIF-aware on Android 9+).
Virtual display mode: it is the MirrorActivity background, the picture sits on top. Freeform mode: the four
black "surround" windows each show their slice of the same full-screen picture (SliceView). Not available in
"Normal" (Game Space) launch mode - those bars belong to the system. Edge lines can be switched Off if
the background is enough.
