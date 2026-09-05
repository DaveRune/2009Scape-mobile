package TouchDrag;

import plugin.Plugin;
import plugin.annotations.PluginMeta;
import plugin.api.API;
import rt4.GameShell;
import rt4.MiniMenu;
import rt4.Mouse;
import rt4.client;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/// Turns a touch into the press, hold and release the client needs before it will drag anything.
/// The launcher cannot see what the client is drawing, so it reports every touch as a key press instead,
/// using codes above the end of the client's key map where the game ignores them. This also owns the
/// camera keys, so that dragging an interface cannot turn the world at the same time.
@PluginMeta(author = "Dave", description = "Lets a finger drag scroll bars and inventory items", version = 1.0)
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

    private final List<KeyAdapter> otherKeyListeners = new ArrayList<>();
    private int knownListenerCount = -1;

    private boolean touchDown = false;
    private boolean decided = false;
    private boolean holdingButton = false;
    private int readTouchOnTick = 0;

    private boolean tapWaiting = false;
    private int tapOnTick = 0;
    private int releaseTapOnTick = 0;

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
            if (swallowsCameraKeys() && isCameraKey(event.getKeyCode())) return;
            for (KeyAdapter other : otherKeyListeners) other.keyPressed(event);
        }

        @Override
        public void keyReleased(KeyEvent event) {
            if (isTouchKey(event.getKeyCode())) return;
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
}
