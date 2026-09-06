package net.kdt.pojavlaunch.customcontrols.keyboard;

import android.app.Activity;
import android.util.Log;
import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import net.kdt.pojavlaunch.GLFWGLSurface;
import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Publishes how much of the game the on-screen keyboard covers, in the client's own window pixels.
 * The game runs in its own virtual machine and nothing here can call into it, so the figure goes into
 * a file beside the plugins folder, which the KeyboardRoom plugin reads and lays the interface out to.
 */
public class KeyboardRoom {
    private static final String TAG = "KeyboardRoom";
    private static final String FILE_NAME = "keyboard_height";

    private final View mDecorView;
    private final GLFWGLSurface mGameView;
    private final File mFile;
    private int mLastPublished = -1;

    public KeyboardRoom(Activity activity, GLFWGLSurface gameView) {
        mDecorView = activity.getWindow().getDecorView();
        mGameView = gameView;
        mFile = new File(Tools.DIR_DATA, FILE_NAME);
    }

    public void start() {
        // A height left behind by the last session would shrink the window before the keyboard is ever opened.
        publish(0);
        ViewCompat.setOnApplyWindowInsetsListener(mDecorView, (view, insets) -> {
            publish(measureCoveredHeight(insets));
            return ViewCompat.onApplyWindowInsets(view, insets);
        });
        ViewCompat.requestApplyInsets(mDecorView);
    }

    /** Measured off the view, not the inset, because the game stops short of the navigation bar the keyboard covers */
    private int measureCoveredHeight(WindowInsetsCompat insets) {
        int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        if (keyboard == 0) return 0;

        int[] location = new int[2];
        mDecorView.getLocationOnScreen(location);
        int keyboardTop = location[1] + mDecorView.getHeight() - keyboard;

        mGameView.getLocationOnScreen(location);
        int gameBottom = location[1] + mGameView.getHeight();

        return mGameView.toWindowPixels(Math.max(0, gameBottom - keyboardTop));
    }

    private void publish(int windowPixels) {
        if (windowPixels == mLastPublished) return;
        try (FileOutputStream out = new FileOutputStream(mFile)) {
            out.write(Integer.toString(windowPixels).getBytes(StandardCharsets.UTF_8));
            mLastPublished = windowPixels;
        } catch (IOException exception) {
            Log.w(TAG, "Could not write '" + mFile + "'.", exception);
        }
    }
}
