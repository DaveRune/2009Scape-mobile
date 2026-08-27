package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_DISABLE_SWAP_HAND;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_INSET_X;
import static net.kdt.pojavlaunch.utils.MCOptionUtils.getMcScale;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;
import static org.lwjgl.glfw.CallbackBridge.windowHeight;
import static org.lwjgl.glfw.CallbackBridge.windowWidth;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.gamepad.Gamepad;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.EfficientAndroidLWJGLKeycode;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.utils.MCOptionUtils;
import net.kdt.pojavlaunch.utils.MathUtils;

import org.lwjgl.glfw.CallbackBridge;

import fr.spse.gamepad_remapper.RemapperManager;
import fr.spse.gamepad_remapper.RemapperView;

/**
 * Class dealing with showing minecraft surface and taking inputs to dispatch them to minecraft
 */
public class GLFWGLSurface extends View implements GrabListener {
    /* Gamepad object for gamepad inputs, instantiated on need */
    private Gamepad mGamepad = null;

    float startX = 0;
    float startY = 0;
    private float mZoomTravel = 0;
    private final float mZoomStep = Math.max(1f, Tools.dpToPx(ZOOM_STEP_DP) / LauncherPreferences.PREF_ZOOM_SENSITIVITY);
    private float mPanTravelX = 0;
    private float mPanTravelY = 0;
    private int mPanPointerId = -1;
    private final float mPanStep = Math.max(1f, Tools.dpToPx(PAN_STEP_DP) / LauncherPreferences.PREF_CAMERA_PAN_SENSITIVITY);
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector longPressDetector;

    /* The RemapperView.Builder object allows you to set which buttons to remap */
    private final RemapperManager mInputManager = new RemapperManager(getContext(), new RemapperView.Builder(null)
            .remapA(true)
            .remapB(true)
            .remapX(true)
            .remapY(true)
            .remapDpad(true)
            .remapLeftJoystick(true)
            .remapRightJoystick(true)
            .remapStart(true)
            .remapSelect(true)
            .remapLeftShoulder(true)
            .remapRightShoulder(true)
            .remapLeftTrigger(true)
            .remapRightTrigger(true));

    /* Resolution scaler option, allow downsizing a window */
    private final float mScaleFactor = LauncherPreferences.PREF_SCALE_FACTOR/100f;
    /* The client is told its window is this much smaller than the surface, so it draws its interface bigger */
    private final float mUiScale = Math.max(1f, LauncherPreferences.PREF_UI_SCALE);
    /* Touch positions are sent in the client's window space, not the surface's */
    private final float mTouchScale = mScaleFactor / mUiScale;
    /* Sensitivity, adjusted according to screen size */
    private final double mSensitivityFactor = (1.4 * (1080f/ Tools.getDisplayMetrics((Activity) getContext()).heightPixels));
    /* Use to detect simple and double taps */
    private final TapDetector mSingleTapDetector = new TapDetector(1, TapDetector.DETECTION_METHOD_BOTH);
    private final TapDetector mDoubleTapDetector = new TapDetector(2, TapDetector.DETECTION_METHOD_DOWN);
    /* MC GUI scale, listened by MCOptionUtils */
    private int mGuiScale;
    @SuppressWarnings("FieldCanBeLocal") // it can't, otherwise the weak reference will disappear
    private final MCOptionUtils.MCOptionListener mGuiScaleListener = () -> mGuiScale = getMcScale();
    /* Surface ready listener, used by the activity to launch minecraft */
    SurfaceReadyListener mSurfaceReadyListener = null;
    final Object mSurfaceReadyListenerLock = new Object();
    /* View holding the surface, either a SurfaceView or a TextureView */
    View mSurface;

    /* List of hotbarKeys, used when clicking on the hotbar */
    private static final int[] HOTBAR_KEYS = {
            LwjglGlfwKeycode.GLFW_KEY_1, LwjglGlfwKeycode.GLFW_KEY_2,   LwjglGlfwKeycode.GLFW_KEY_3,
            LwjglGlfwKeycode.GLFW_KEY_4, LwjglGlfwKeycode.GLFW_KEY_5,   LwjglGlfwKeycode.GLFW_KEY_6,
            LwjglGlfwKeycode.GLFW_KEY_7, LwjglGlfwKeycode.GLFW_KEY_8, LwjglGlfwKeycode.GLFW_KEY_9};
    /* Last hotbar button (0-9) registered */
    private int mLastHotbarKey = -1;
    /* Events can start with only a move instead of an pointerDown due to mouse passthrough */
    private boolean mShouldBeDown = false;
    /* When fingers are really near to each other, it tends to either swap or remove a pointer ! */
    private int mLastPointerCount = 0;
    /* Previous MotionEvent position, not scale */
    private float mPrevX, mPrevY;
    private int mStylusButton = -1;
    /* PointerID used for the moving camera */
    private int mCurrentPointerID = -1000;
    /* Initial first pointer positions non-scaled, used to test touch sloppiness */
    private float mInitialX, mInitialY;
    /* Last first pointer positions non-scaled, used to scroll distance */
    private float mScrollLastInitialX, mScrollLastInitialY;
    /* How much distance a finger has to go for touch sloppiness to be disabled */
    public static final int FINGER_STILL_THRESHOLD = (int) Tools.dpToPx(9);
    /* Finger travel that earns one camera rotation step at the default sensitivity */
    private static final float PAN_STEP_DP = 5f;
    /* Stops a pointer jump turning into a spin */
    private static final int MAX_PAN_STEPS_PER_EVENT = 24;
    /* Pinch distance that earns one zoom step at the default sensitivity */
    private static final float ZOOM_STEP_DP = 16f;
    /* Stops a pinch jump running the zoom away */
    private static final int MAX_ZOOM_STEPS_PER_EVENT = 8;
    /* How much distance a finger has to go to scroll */
    public static final int FINGER_SCROLL_THRESHOLD = (int) Tools.dpToPx(6);
    /* Whether the button was triggered, used by the handler */
    private static boolean triggeredLeftMouseButton = false;
    /* Handle hotbar throw button and mouse mining button */
    public static final int MSG_LEFT_MOUSE_BUTTON_CHECK = 1028;
    public static final int MSG_DROP_ITEM_BUTTON_CHECK = 1029;
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        public void handleMessage(Message msg) {
            if(msg.what == MSG_LEFT_MOUSE_BUTTON_CHECK) {
                if (LauncherPreferences.PREF_DISABLE_GESTURES) return;
                float x = CallbackBridge.mouseX;
                float y = CallbackBridge.mouseY;
                if (CallbackBridge.isGrabbing() &&
                        MathUtils.dist(x, y, mInitialX, mInitialY) < FINGER_STILL_THRESHOLD) {
                    triggeredLeftMouseButton = true;
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
                }
                return;
            }
            if(msg.what == MSG_DROP_ITEM_BUTTON_CHECK) {
                if(CallbackBridge.isGrabbing()){
                    mHandler.sendEmptyMessageDelayed(MSG_DROP_ITEM_BUTTON_CHECK, 600);
                }
            }
        }
    };



    public GLFWGLSurface(Context context) {
        this(context, null);
    }

    public GLFWGLSurface(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        setFocusable(true);

        MCOptionUtils.addMCOptionListener(mGuiScaleListener);
    }

    /** Initialize the view and all its settings */
    @SuppressLint("ClickableViewAccessibility")
    public void start(){
        System.out.println("Hello.. I can see the inset it: "+PREF_INSET_X);
        scaleGestureDetector = new ScaleGestureDetector(this.getContext(), new ScaleListener());
        if(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE){
            SurfaceView surfaceView = new SurfaceView(getContext());
            mSurface = surfaceView;

            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                private boolean isCalled = false;
                @Override
                public void surfaceCreated(@NonNull SurfaceHolder holder) {
                    if(isCalled) {
                        JREUtils.setupBridgeWindow(surfaceView.getHolder().getSurface());
                        return;
                    }
                    isCalled = true;

                    realStart(surfaceView.getHolder().getSurface());
                }

                @Override
                public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                    refreshSize();
                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder holder) {}
            });

            ((ViewGroup)getParent()).addView(surfaceView);
        }else{
            TextureView textureView = new TextureView(getContext());
            textureView.setOpaque(true);
            textureView.setAlpha(1.0f);
            mSurface = textureView;

            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                private boolean isCalled = false;
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    Surface tSurface = new Surface(surface);
                    if(isCalled) {
                        JREUtils.setupBridgeWindow(tSurface);
                        return;
                    }
                    isCalled = true;

                    realStart(tSurface);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    refreshSize();
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            });

            longPressDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public void onLongPress(MotionEvent e) {
                    super.onLongPress(e);
                    CallbackBridge.putMouseEventWithCoords(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, CallbackBridge.mouseX, CallbackBridge.mouseY);
                }
            });

            ((ViewGroup)getParent()).addView(textureView);
        }
    }


    /**
     * The touch event for both grabbed an non-grabbed mouse state on the touch screen
     * Does not cover the virtual mouse touchpad
     */
    @Override
    @SuppressWarnings("accessibility")
    public boolean onTouchEvent(MotionEvent e) {
        scaleGestureDetector.onTouchEvent(e);
        longPressDetector.onTouchEvent(e);
        // Kinda need to send this back to the layout
        if(((ControlLayout)getParent()).getModifiable()) return false;

        // Looking for a mouse to handle, won't have an effect if no mouse exists.
        for (int i = 0; i < e.getPointerCount(); i++) {
            if(e.getToolType(i) != MotionEvent.TOOL_TYPE_MOUSE && e.getToolType(i) != MotionEvent.TOOL_TYPE_STYLUS ) continue;

            // Mouse found
            if(CallbackBridge.isGrabbing()) return false;
            CallbackBridge.sendCursorPos(   e.getX(i) * mTouchScale, e.getY(i) * mTouchScale);
            if(e.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS) sendStylusButton(e);
            return true; //mouse event handled successfully
        }

        // System.out.println("Pre touch, isTouchInHotbar=" + Boolean.toString(isTouchInHotbar) + ", action=" + MotionEvent.actionToString(e.getActionMasked()));

        //Getting scaled position from the event
        /* Tells if a double tap happened [MOUSE GRAB ONLY]. Doesn't tell where though. */
        if(!CallbackBridge.isGrabbing()) {
            CallbackBridge.mouseX = (e.getX() * mTouchScale);
            CallbackBridge.mouseY = (e.getY() * mTouchScale);
            //One android click = one MC click
            if(mSingleTapDetector.onTouchEvent(e)){ //
                CallbackBridge.putMouseEventWithCoords(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, CallbackBridge.mouseX, CallbackBridge.mouseY);
                return true;
            }
        }

        // Check double tap state, used for the hotbar
        boolean hasDoubleTapped = mDoubleTapDetector.onTouchEvent(e);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                int panIndex = mPanPointerId == -1 ? -1 : e.findPointerIndex(mPanPointerId);
                if(panIndex != -1) {
                    float dx = e.getX(panIndex) - startX;
                    float dy = e.getY(panIndex) - startY;

                    if(scaleGestureDetector.isInProgress()) {
                        // Follow the finger without turning, so lifting one does not arrive as one huge delta
                        mPanTravelX = 0;
                        mPanTravelY = 0;
                    } else {
                        try {
                            panCamera(dx, dy);
                        } catch (InterruptedException ex) {
                            throw new RuntimeException(ex);
                        }
                    }

                    startX = e.getX(panIndex);
                    startY = e.getY(panIndex);
                }


                int pointerCount = e.getPointerCount();

                // In-menu interactions
                if(!CallbackBridge.isGrabbing()){

                    // Touch hover
                    if(pointerCount == 1){
                        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                        mPrevX =  e.getX();
                        mPrevY =  e.getY();
                        break;
                    }

                    // Scrolling feature
                    if(LauncherPreferences.PREF_DISABLE_GESTURES) break;
                    // The pointer count can never be 0, and it is not 1, therefore it is >= 2
                    int hScroll =  ((int) (e.getX() - mScrollLastInitialX)) / FINGER_SCROLL_THRESHOLD;
                    int vScroll = ((int) (e.getY() - mScrollLastInitialY)) / FINGER_SCROLL_THRESHOLD;

                    if(vScroll != 0 || hScroll != 0){
                        CallbackBridge.sendScroll(hScroll, vScroll);
                        mScrollLastInitialX = e.getX();
                        mScrollLastInitialY = e.getY();
                    }
                    break;
                }

                // Camera movement
                int pointerIndex = e.findPointerIndex(mCurrentPointerID);
                int hudKeyHandled = handleGuiBar((int)e.getX(), (int) e.getY());
                // Start movement, due to new pointer or loss of pointer
                if (pointerIndex == -1 || mLastPointerCount != pointerCount || !mShouldBeDown) {
                    if(hudKeyHandled != -1) break; //No pointer attribution on hotbar

                    mShouldBeDown = true;
                    mCurrentPointerID = e.getPointerId(0);
                    mPrevX = e.getX();
                    mPrevY = e.getY();
                    break;
                }
                // Continue movement as usual
                if(hudKeyHandled == -1){ //No camera on hotbar
                    CallbackBridge.mouseX += (e.getX(pointerIndex) - mPrevX) * mSensitivityFactor;
                    CallbackBridge.mouseY += (e.getY(pointerIndex) - mPrevY) * mSensitivityFactor;
                }

                mPrevX = e.getX(pointerIndex);
                mPrevY = e.getY(pointerIndex);

                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                break;

            case MotionEvent.ACTION_DOWN: // 0
                startX = e.getX();
                startY = e.getY();
                mPanPointerId = e.getPointerId(0);
                mPanTravelX = 0;
                mPanTravelY = 0;
                hudKeyHandled = handleGuiBar((int)e.getX(), (int) e.getY());
                boolean isTouchInHotbar = hudKeyHandled != -1;
                if (isTouchInHotbar) {
                    sendKeyPress(hudKeyHandled);
                    if(hasDoubleTapped && hudKeyHandled == mLastHotbarKey && !PREF_DISABLE_SWAP_HAND){
                        //Prevent double tapping Event on two different slots
                        sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_F);
                    }

                    mHandler.sendEmptyMessageDelayed(MSG_DROP_ITEM_BUTTON_CHECK, 350);
                    CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                    mLastHotbarKey = hudKeyHandled;
                    break;
                }

                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                mPrevX =  e.getX();
                mPrevY =  e.getY();

                if (CallbackBridge.isGrabbing()) {
                    mCurrentPointerID = e.getPointerId(0);
                    // It cause hold left mouse while moving camera
                    mInitialX = CallbackBridge.mouseX;
                    mInitialY = CallbackBridge.mouseY;
                    mHandler.sendEmptyMessageDelayed(MSG_LEFT_MOUSE_BUTTON_CHECK, LauncherPreferences.PREF_LONGPRESS_TRIGGER);
                }
                mLastHotbarKey = hudKeyHandled;
                break;

            case MotionEvent.ACTION_POINTER_UP: // 6
                if(e.getPointerId(e.getActionIndex()) == mPanPointerId) {
                    mPanPointerId = -1;
                    mPanTravelX = 0;
                    mPanTravelY = 0;
                }
                break;

            case MotionEvent.ACTION_UP: // 1
                // End of drag, reset the start position
                startX = 0;
                startY = 0;
                mPanPointerId = -1;
                mPanTravelX = 0;
                mPanTravelY = 0;
                break;
            case MotionEvent.ACTION_CANCEL: // 3
                mShouldBeDown = false;
                mCurrentPointerID = -1;
                mPanPointerId = -1;
                mPanTravelX = 0;
                mPanTravelY = 0;

                hudKeyHandled = handleGuiBar((int)e.getX(), (int) e.getY());
                isTouchInHotbar = hudKeyHandled != -1;
                // We only treat in world events
                if (!CallbackBridge.isGrabbing()) break;

                // Stop the dropping of items
                sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_Q, 0, false);
                mHandler.removeMessages(MSG_DROP_ITEM_BUTTON_CHECK);

                // Remove the mouse left button
                if(triggeredLeftMouseButton){
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                    triggeredLeftMouseButton = false;
                    break;
                }
                mHandler.removeMessages(MSG_LEFT_MOUSE_BUTTON_CHECK);

                // In case of a short click, just send a quick right click
                if(!LauncherPreferences.PREF_DISABLE_GESTURES &&
                        MathUtils.dist(mInitialX, mInitialY, CallbackBridge.mouseX, CallbackBridge.mouseY) < FINGER_STILL_THRESHOLD){
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, true);
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, false);
                }
                break;
        }

        // Actualise the pointer count
        mLastPointerCount = e.getPointerCount();
        longPressDetector.onTouchEvent(e);

        return true;
    }

    private void panCamera(float dx, float dy) throws InterruptedException {
        mPanTravelX += dx;
        mPanTravelY += dy;

        int horizontalSteps = (int) (mPanTravelX / mPanStep);
        if(horizontalSteps != 0) {
            mPanTravelX -= horizontalSteps * mPanStep;
            sendPanKey(horizontalSteps > 0 ? AWTInputEvent.VK_RIGHT : AWTInputEvent.VK_LEFT, Math.abs(horizontalSteps));
        }

        int verticalSteps = (int) (mPanTravelY / mPanStep);
        if(verticalSteps != 0) {
            mPanTravelY -= verticalSteps * mPanStep;
            boolean upwards = LauncherPreferences.PREF_CAMERA_PAN_INVERT_Y != (verticalSteps > 0);
            sendPanKey(upwards ? AWTInputEvent.VK_UP : AWTInputEvent.VK_DOWN, Math.abs(verticalSteps));
        }
    }

    private void sendPanKey(int keycode, int repeats) {
        for(int i = Math.min(repeats, MAX_PAN_STEPS_PER_EVENT); i > 0; i--) {
            AWTInputBridge.sendKey((char) keycode, keycode);
        }
    }

    /**
     * The event for mouse/joystick movements
     */
    @SuppressLint("NewApi")
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int mouseCursorIndex = -1;

        if(Gamepad.isGamepadEvent(event)){
            if(mGamepad == null){
                mGamepad = new Gamepad(this, event.getDevice());
            }

            mInputManager.handleMotionEventInput(getContext(), event, mGamepad);
            return true;
        }

        for(int i = 0; i < event.getPointerCount(); i++) {
            if(event.getToolType(i) != MotionEvent.TOOL_TYPE_MOUSE && event.getToolType(i) != MotionEvent.TOOL_TYPE_STYLUS ) continue;
            // Mouse found
            mouseCursorIndex = i;
            break;
        }
        if(mouseCursorIndex == -1) return false; // we cant consoom that, theres no mice!

        // Make sure we grabbed the mouse if necessary
        updateGrabState(CallbackBridge.isGrabbing());
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
                CallbackBridge.mouseX = (event.getX(mouseCursorIndex) * mTouchScale);
                CallbackBridge.mouseY = (event.getY(mouseCursorIndex) * mTouchScale);
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                return true;
            case MotionEvent.ACTION_SCROLL:
                CallbackBridge.sendScroll((double) event.getAxisValue(MotionEvent.AXIS_HSCROLL), (double) event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
                return sendMouseButtonUnconverted(event.getActionButton(),true);
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return sendMouseButtonUnconverted(event.getActionButton(),false);
            default:
                return false;
        }
    }

    //TODO MOVE THIS SOMEWHERE ELSE
    /** The input event for mouse with a captured pointer */
    @RequiresApi(26)
    @Override
    public boolean dispatchCapturedPointerEvent(MotionEvent e) {
        CallbackBridge.mouseX = (e.getX() * mTouchScale);
        CallbackBridge.mouseY = (e.getY() * mTouchScale);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
                return sendMouseButtonUnconverted(e.getActionButton(), true);
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return sendMouseButtonUnconverted(e.getActionButton(), false);
            case MotionEvent.ACTION_SCROLL:
                CallbackBridge.sendScroll(e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL));
                return true;
            default:
                return false;
        }
    }

    /** The event for keyboard/ gamepad button inputs */
    public boolean processKeyEvent(KeyEvent event) {
        //Toast.makeText(this, event.toString(),Toast.LENGTH_SHORT).show();
        //Toast.makeText(this, event.getDevice().toString(), Toast.LENGTH_SHORT).show();

        //Filtering useless events by order of probability
        int eventKeycode = event.getKeyCode();
        if(eventKeycode == KeyEvent.KEYCODE_UNKNOWN) return true;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_UP) return false;
        if(event.getRepeatCount() != 0) return true;
        if(event.getAction() == KeyEvent.ACTION_MULTIPLE) return true;

        //Sometimes, key events comes from SOME keys of the software keyboard
        //Even weirder, is is unknown why a key or another is selected to trigger a keyEvent
        if((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) == KeyEvent.FLAG_SOFT_KEYBOARD){
            if(eventKeycode == KeyEvent.KEYCODE_ENTER) return true; //We already listen to it.
            return true;
        }

        //Sometimes, key events may come from the mouse
        if(event.getDevice() != null
                && ( (event.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                ||   (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)  ){

            if(eventKeycode == KeyEvent.KEYCODE_BACK){
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, event.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }
        }

        if(Gamepad.isGamepadEvent(event)){
            if(mGamepad == null){
                mGamepad = new Gamepad(this, event.getDevice());
            }

            mInputManager.handleKeyEventInput(getContext(), event, mGamepad);
            return true;
        }

        int index = EfficientAndroidLWJGLKeycode.getIndexByKey(eventKeycode);
        if(EfficientAndroidLWJGLKeycode.containsIndex(index)) {
            EfficientAndroidLWJGLKeycode.execKey(event, index);
            return true;
        }

        // Some events will be generated an infinite number of times when no consumed
        return (event.getFlags() & KeyEvent.FLAG_FALLBACK) == KeyEvent.FLAG_FALLBACK;
    }

    private void sendStylusButton(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mStylusButton = (e.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                        ? LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT
                        : LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
                sendMouseButton(mStylusButton, true);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if(mStylusButton == -1) break;
                sendMouseButton(mStylusButton, false);
                mStylusButton = -1;
                break;
        }
    }

    /** Convert the mouse button, then send it
     * @return Whether the event was processed
     */
    public static boolean sendMouseButtonUnconverted(int button, boolean status) {
        int glfwButton = -256;
        switch (button) {
            case MotionEvent.BUTTON_PRIMARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
                break;
            case MotionEvent.BUTTON_TERTIARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE;
                break;
            case MotionEvent.BUTTON_SECONDARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT;
                break;
        }
        if(glfwButton == -256) return false;
        sendMouseButton(glfwButton, status);
        return true;
    }


    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            mZoomTravel += detector.getCurrentSpan() - detector.getPreviousSpan();

            int steps = (int) (mZoomTravel / mZoomStep);
            if(steps == 0) return true;
            mZoomTravel -= steps * mZoomStep;

            int keycode = steps > 0 ? AWTInputEvent.VK_F3 : AWTInputEvent.VK_F4;
            for(int i = Math.min(Math.abs(steps), MAX_ZOOM_STEPS_PER_EVENT); i > 0; i--) {
                AWTInputBridge.sendKey((char) keycode, keycode);
            }
            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            mZoomTravel = 0;
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            mZoomTravel = 0;
        }
    }

    /** @return the hotbar key, given the position. -1 if no key are pressed */
    public int handleGuiBar(int x, int y) {
        if (!CallbackBridge.isGrabbing()) return -1;

        int barHeight = mcscale(20);
        int barY = CallbackBridge.physicalHeight - barHeight;
        if(y < barY) return -1;

        int barWidth = mcscale(180);
        int barX = (CallbackBridge.physicalWidth / 2) - (barWidth / 2);
        if(x < barX || x >= barX + barWidth) return -1;

        return HOTBAR_KEYS[(int) net.kdt.pojavlaunch.utils.MathUtils.map(x, barX, barX + barWidth, 0, 9)];
    }

    /** Return the size, given the UI scale size */
    private int mcscale(int input) {
        return (int)((mGuiScale * input)/ mScaleFactor);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        refreshSize();
    }

    /** Called when the size need to be set at any point during the surface lifecycle **/
    public void refreshSize(){
        int surfaceWidth = getWidth();
        int surfaceHeight = getHeight();
        if(surfaceWidth == 0 || surfaceHeight == 0){
            surfaceWidth = (int) (Tools.currentDisplayMetrics.widthPixels - (PREF_INSET_X*2));
            surfaceHeight = (int) (Tools.currentDisplayMetrics.heightPixels - (PREF_INSET_X*2));
        }

        int renderWidth = Tools.getDisplayFriendlyRes(surfaceWidth, mScaleFactor);
        int renderHeight = Tools.getDisplayFriendlyRes(surfaceHeight, mScaleFactor);
        windowWidth = Tools.getDisplayFriendlyRes(renderWidth, 1f / mUiScale);
        windowHeight = Tools.getDisplayFriendlyRes(renderHeight, 1f / mUiScale);
        if(mSurface == null){
            Log.w("MGLSurface", "Attempt to refresh size on null surface");
            return;
        }
        if(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE){
            SurfaceView view = (SurfaceView) mSurface;
            if(view.getHolder() != null){
                view.getHolder().setFixedSize(renderWidth, renderHeight);
            }
        }else{
            TextureView view = (TextureView)mSurface;
            if(view.getSurfaceTexture() != null){
                view.getSurfaceTexture().setDefaultBufferSize(renderWidth, renderHeight);
            }
        }

        CallbackBridge.sendUpdateWindowSize(windowWidth, windowHeight);

    }

    private void realStart(Surface surface){
        // Initial size set
        refreshSize();
        getMcScale();

        JREUtils.setupBridgeWindow(surface);

        new Thread(() -> {
            try {
                // Wait until the listener is attached
                synchronized(mSurfaceReadyListenerLock) {
                    if(mSurfaceReadyListener == null) mSurfaceReadyListenerLock.wait();
                }

                mSurfaceReadyListener.isReady();
            } catch (Throwable e) {
                Tools.showError(getContext(), e, true);
            }
        }, "JVM Main thread").start();
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        post(()->updateGrabState(isGrabbing));
    }

    private void updateGrabState(boolean isGrabbing) {
        if(!MainActivity.isAndroid8OrHigher()) return;

        boolean hasPointerCapture = hasPointerCapture();
        if(isGrabbing){
            if(!hasPointerCapture) {
                requestFocus();
                requestPointerCapture();
            }
            return;
        }

        if(hasPointerCapture) {
            releasePointerCapture();
            clearFocus();
        }
    }

    public int dpToPx(int dp) {
        return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
    }

    /** A small interface called when the listener is ready for the first time */
    public interface SurfaceReadyListener {
        void isReady();
    }

    public void setSurfaceReadyListener(SurfaceReadyListener listener){
        synchronized (mSurfaceReadyListenerLock) {
            mSurfaceReadyListener = listener;
            mSurfaceReadyListenerLock.notifyAll();
        }
    }
}
