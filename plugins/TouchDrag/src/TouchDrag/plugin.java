package TouchDrag;

import plugin.Plugin;
import plugin.annotations.PluginMeta;
import plugin.api.API;
import rt4.Camera;
import rt4.GameShell;
import rt4.MiniMenu;
import rt4.Mouse;
import rt4.client;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/// Turns a touch into the press, hold and release the client needs before it will drag anything, and turns the camera.
/// The launcher cannot see what the client is drawing, so it reports every touch as a key press instead,
/// using codes above the end of the client's key map where the game ignores them. It reports a camera drag
/// as arrow keys, one per fixed lump of finger travel, and this owns those too so that nothing else moves the camera.
@PluginMeta(author = "Dave", description = "Turns touches into drags and finger travel into camera movement", version = 2.0)
public class plugin extends Plugin {

    private static final int KEY_TOUCH_DOWN = KeyEvent.VK_F13;
    private static final int KEY_TOUCH_TAP = KeyEvent.VK_F14;
    private static final int KEY_TOUCH_UP = KeyEvent.VK_F15;

    /// The client offers this somewhere in its menu whenever the cursor is over the world rather than an interface.
    private static final short WALK_HERE = 60;

    /// The client copies its cursor across and rebuilds its menu once a tick, so a touch is a tick behind until then.
    private static final int TICKS_BEFORE_READING = 2;

    private static final int LEFT_BUTTON = 1;
    private static final int RELEASED = 0;

    private static final String CMD_SMOOTHING = "::camerasmoothing";
    private static final String CMD_SMOOTHING_SHORT = "::cs";

    private static final String SETTINGS_FILE = "touchdrag.properties";
    private static final String KEY_SETTLE_MS = "settleMs";

    private static final int DEFAULT_SETTLE_MS = 300;
    private static final int MIN_SETTLE_MS = 0;
    private static final int MAX_SETTLE_MS = 600;

    /// What MobileClientBindings applies per arrow key. Matching it keeps the launcher's pan sensitivity slider honest.
    private static final double UNITS_PER_CAMERA_KEY = 15.0;

    private static final double YAW_UNITS_PER_TURN = 2048.0;

    /// The client holds the camera between looking level and looking down at the player.
    private static final double MIN_PITCH = 128.0;
    private static final double MAX_PITCH = 383.0;

    /// A jump this large is a cutscene or a script placing the camera, and it is followed rather than eased into.
    private static final double SNAP_UNITS = 150.0;

    /// Below this the remainder is worth less than the whole unit the client rounds to, so it is spent outright.
    private static final double SETTLE_UNITS = 0.05;

    /// A frame this long is the app coming back from the background, and easing across it would look like a slide.
    private static final long LONGEST_USEFUL_FRAME_MS = 100;

    private final List<KeyAdapter> otherKeyListeners = new ArrayList<>();
    private int knownListenerCount = -1;

    private boolean touchDown = false;
    private boolean decided = false;
    private boolean holdingButton = false;
    private int readTouchOnTick = 0;

    private boolean tapWaiting = false;
    private int tapOnTick = 0;
    private int releaseTapOnTick = 0;

    /// Arrow keys arrive on the launcher's input thread and the camera is moved on the game thread, so the two meet here.
    private final Object cameraLock = new Object();
    private double bankedYaw = 0.0;
    private double bankedPitch = 0.0;

    private final Follower yaw = new Follower();
    private final Follower pitch = new Follower();

    private int settleMs = DEFAULT_SETTLE_MS;
    private double writtenYaw;
    private double writtenPitch;
    private boolean adoptedTheCamera = false;

    private final KeyAdapter listener = new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent event) {
            switch (event.getKeyCode()) {
                case KEY_TOUCH_DOWN:
                    onTouchDown();
                    return;
                case KEY_TOUCH_TAP:
                    onTouchLifted(true);
                    return;
                case KEY_TOUCH_UP:
                    onTouchLifted(false);
                    return;
            }
            if (isCameraKey(event.getKeyCode())) {
                if (!swallowsCameraKeys()) bankCameraKey(event.getKeyCode());
                return;
            }
            for (KeyAdapter other : otherKeyListeners) other.keyPressed(event);
        }

        @Override
        public void keyReleased(KeyEvent event) {
            if (isTouchKey(event.getKeyCode()) || isCameraKey(event.getKeyCode())) return;
            for (KeyAdapter other : otherKeyListeners) other.keyReleased(event);
        }

        @Override
        public void keyTyped(KeyEvent event) {
            // A typed event carries no key code, so a reported touch can only be recognised by its character
            if (isTouchKey(event.getKeyCode()) || isTouchKey(event.getKeyChar())) return;
            for (KeyAdapter other : otherKeyListeners) other.keyTyped(event);
        }
    };

    @Override
    public void Init() {
        takeOverTheKeyboard();
        settleMs = clampSettle(readInt(readSettings(), KEY_SETTLE_MS, DEFAULT_SETTLE_MS));
    }

    /// Stands in front of every other keyboard listener, so a camera key can be stopped rather than undone later.
    private void takeOverTheKeyboard() {
        otherKeyListeners.clear();
        for (KeyAdapter existing : new ArrayList<>(API.registeredKeyListeners)) {
            if (existing == listener) continue;
            GameShell.canvas.removeKeyListener(existing);
            otherKeyListeners.add(existing);
        }
        GameShell.canvas.removeKeyListener(listener);
        API.registeredKeyListeners.remove(listener);
        API.AddKeyboardListener(listener);
        knownListenerCount = API.registeredKeyListeners.size();
    }

    private static boolean isTouchKey(int keycode) {
        return keycode == KEY_TOUCH_DOWN || keycode == KEY_TOUCH_TAP || keycode == KEY_TOUCH_UP;
    }

    private static boolean isCameraKey(int keycode) {
        return keycode == KeyEvent.VK_LEFT || keycode == KeyEvent.VK_RIGHT
                || keycode == KeyEvent.VK_UP || keycode == KeyEvent.VK_DOWN;
    }

    private boolean swallowsCameraKeys() {
        return holdingButton || (touchDown && !decided);
    }

    private void bankCameraKey(int keycode) {
        synchronized (cameraLock) {
            if (keycode == KeyEvent.VK_RIGHT) bankedYaw += UNITS_PER_CAMERA_KEY;
            if (keycode == KeyEvent.VK_LEFT) bankedYaw -= UNITS_PER_CAMERA_KEY;
            if (keycode == KeyEvent.VK_UP) bankedPitch += UNITS_PER_CAMERA_KEY;
            if (keycode == KeyEvent.VK_DOWN) bankedPitch -= UNITS_PER_CAMERA_KEY;
        }
    }

    private void onTouchDown() {
        touchDown = true;
        decided = false;
        readTouchOnTick = client.loop + TICKS_BEFORE_READING;
        releaseButton();
    }

    private void onTouchLifted(boolean wasTap) {
        touchDown = false;
        decided = true;

        if (holdingButton) {
            releaseButton();
            return;
        }

        if (wasTap) {
            tapWaiting = true;
            tapOnTick = client.loop + TICKS_BEFORE_READING;
        }
    }

    @Override
    public void LateDraw(long elapsed) {
        if (API.registeredKeyListeners.size() != knownListenerCount) takeOverTheKeyboard();

        if (holdingButton) Mouse.eventAction = LEFT_BUTTON;

        if (releaseTapOnTick != 0 && client.loop >= releaseTapOnTick) {
            Mouse.eventAction = RELEASED;
            releaseTapOnTick = 0;
        }

        if (touchDown && !decided && client.loop >= readTouchOnTick) decideWhatTheFingerLandedOn();
        if (tapWaiting && client.loop >= tapOnTick) tap();

        moveTheCamera(elapsed);
    }

    private void moveTheCamera(long elapsed) {
        double arrivedYaw;
        double arrivedPitch;
        synchronized (cameraLock) {
            arrivedYaw = bankedYaw;
            arrivedPitch = bankedPitch;
            bankedYaw = 0.0;
            bankedPitch = 0.0;
        }

        double movedElsewhereYaw = shortestTurn(Camera.yawTarget - writtenYaw);
        double movedElsewherePitch = Camera.pitchTarget - writtenPitch;
        if (!adoptedTheCamera
                || Math.abs(movedElsewhereYaw) >= SNAP_UNITS
                || Math.abs(movedElsewherePitch) >= SNAP_UNITS) {
            adoptWhereTheGamePutTheCamera();
            return;
        }

        arrivedYaw += movedElsewhereYaw;
        arrivedPitch += movedElsewherePitch;

        double stepYaw;
        double stepPitch;
        if (settleMs == MIN_SETTLE_MS || elapsed <= 0) {
            stepYaw = arrivedYaw;
            stepPitch = arrivedPitch;
        } else {
            double fraction = fractionToSpend(Math.min(elapsed, LONGEST_USEFUL_FRAME_MS));
            stepYaw = yaw.letOut(arrivedYaw, fraction);
            stepPitch = pitch.letOut(arrivedPitch, fraction);
        }

        writtenYaw = wrapYaw(writtenYaw + stepYaw);
        writtenPitch = clampPitch(writtenPitch + stepPitch);
        Camera.yawTarget = writtenYaw;
        Camera.pitchTarget = writtenPitch;
    }

    private void adoptWhereTheGamePutTheCamera() {
        writtenYaw = Camera.yawTarget;
        writtenPitch = Camera.pitchTarget;
        yaw.forget();
        pitch.forget();
        adoptedTheCamera = true;
    }

    @Override
    public void ProcessCommand(String command, String[] args) {
        if (!CMD_SMOOTHING.equalsIgnoreCase(command) && !CMD_SMOOTHING_SHORT.equalsIgnoreCase(command)) return;

        if (args.length == 0) {
            API.SendMessage(describeSettings());
            return;
        }

        int wanted;
        try {
            wanted = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            API.SendMessage("Use ::cs <milliseconds>, 0 for none.");
            return;
        }

        if (wanted < MIN_SETTLE_MS || wanted > MAX_SETTLE_MS) {
            API.SendMessage("Camera smoothing must be " + MIN_SETTLE_MS + " to " + MAX_SETTLE_MS + ".");
            return;
        }

        settleMs = wanted;
        store(KEY_SETTLE_MS, Integer.toString(wanted));
        yaw.forget();
        pitch.forget();
        API.SendMessage(describeSettings());
    }

    private String describeSettings() {
        return settleMs == MIN_SETTLE_MS ? "Camera smoothing off." : "Camera smoothing " + settleMs + "ms.";
    }

    /// Exponential easing off the real frame time, and half the settle each because the two stages run in series.
    private double fractionToSpend(long elapsed) {
        return 1.0 - Math.exp(-2.0 * (double) elapsed / (double) settleMs);
    }

    private void pressLeftButton() {
        Mouse.eventMouseDownX = Mouse.eventMouseX;
        Mouse.eventMouseDownY = Mouse.eventMouseY;
        Mouse.eventTime = System.currentTimeMillis();
        Mouse.eventButton = LEFT_BUTTON;
        Mouse.eventAction = LEFT_BUTTON;
    }

    private void releaseButton() {
        if (holdingButton) Mouse.eventAction = RELEASED;
        holdingButton = false;
    }

    /// Reading the top entry alone would treat a drag that started on an npc as an attack, so the whole menu counts.
    private void decideWhatTheFingerLandedOn() {
        if (MiniMenu.size <= 0) return;

        decided = true;
        if (menuOffersWalkHere()) return;

        holdingButton = true;
        pressLeftButton();
    }

    private boolean menuOffersWalkHere() {
        for (int entry = 0; entry < MiniMenu.size; entry++) {
            if (MiniMenu.actions[entry] == WALK_HERE) return true;
        }
        return false;
    }

    private void tap() {
        tapWaiting = false;
        pressLeftButton();
        releaseTapOnTick = client.loop + TICKS_BEFORE_READING;
    }

    private static double shortestTurn(double units) {
        double wrapped = wrapYaw(units);
        return wrapped > YAW_UNITS_PER_TURN / 2.0 ? wrapped - YAW_UNITS_PER_TURN : wrapped;
    }

    private static double wrapYaw(double units) {
        return ((units % YAW_UNITS_PER_TURN) + YAW_UNITS_PER_TURN) % YAW_UNITS_PER_TURN;
    }

    private static double clampPitch(double angle) {
        if (angle < MIN_PITCH) return MIN_PITCH;
        return Math.min(angle, MAX_PITCH);
    }

    private static int clampSettle(int milliseconds) {
        if (milliseconds < MIN_SETTLE_MS) return MIN_SETTLE_MS;
        return Math.min(milliseconds, MAX_SETTLE_MS);
    }

    /// Holds back one axis of camera movement and lets it out through two easing stages in series.
    /// The second stage carries a speed rather than a position, so the camera's pace changes gradually
    /// however unevenly the arrow keys arrive.
    private static final class Follower {

        private double waiting;
        private double moving;

        private double letOut(double arrived, double fraction) {
            waiting += arrived;
            double handedOn = waiting * fraction;
            waiting -= handedOn;
            moving += handedOn;

            double out = moving * fraction;
            moving -= out;

            if (Math.abs(waiting) + Math.abs(moving) > SETTLE_UNITS) return out;
            out += waiting + moving;
            forget();
            return out;
        }

        private void forget() {
            waiting = 0.0;
            moving = 0.0;
        }
    }

    private static File inPluginDir(String name) {
        String dir = System.getProperty("pluginDir");
        return dir == null ? new File(name) : new File(dir, name);
    }

    private static Properties readSettings() {
        Properties settings = new Properties();
        File file = inPluginDir(SETTINGS_FILE);
        if (!file.exists()) return settings;
        try (FileInputStream in = new FileInputStream(file)) {
            settings.load(in);
        } catch (Exception e) {
            System.out.println("TouchDrag: could not read " + file + ", " + e);
        }
        return settings;
    }

    private static void store(String key, String value) {
        Properties settings = readSettings();
        settings.setProperty(key, value);
        File file = inPluginDir(SETTINGS_FILE);
        try (FileOutputStream out = new FileOutputStream(file)) {
            settings.store(out, "TouchDrag plugin settings");
        } catch (Exception e) {
            System.out.println("TouchDrag: could not write " + file + ", " + e);
        }
    }

    private static int readInt(Properties settings, String key, int fallback) {
        try {
            return Integer.parseInt(settings.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
