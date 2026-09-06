package KeyboardRoom;

import plugin.Plugin;
import plugin.annotations.PluginMeta;
import rt4.ClientProt;
import rt4.Component;
import rt4.DisplayMode;
import rt4.GameShell;
import rt4.GlRenderer;
import rt4.InterfaceList;
import rt4.Protocol;
import rt4.client;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/// Keeps the chat box in sight by taking the height the on-screen keyboard covers off the client's window.
/// The launcher runs in its own virtual machine and cannot call in here, so it writes that height into a
/// file beside the plugins folder, in the client's own window pixels, and this reads it.
@PluginMeta(author = "Dave", description = "Keeps the chat box above the on-screen keyboard", version = 1.0)
public class plugin extends Plugin {

    private static final String HEIGHT_FILE = "../keyboard_height";

    private static final int HEIGHT_BUFFER_BYTES = 16;

    /// Below this the interface has nowhere to lay itself out, whatever the launcher asks for.
    private static final int SMALLEST_WINDOW = 64;

    /// The fixed modes hold the interface at 765 by 503 wherever the window goes, so only the resizable ones follow.
    private static final int FIRST_RESIZABLE_MODE = 2;

    /// Roughly one look at the file per frame, since a frame draws a few hundred components.
    private static final int COMPONENTS_BETWEEN_POLLS = 250;

    private static final int NO_INTERFACE_OPEN = -1;

    private static final int GAME_STATE_LOADING = 25;
    private static final int GAME_STATE_IN_GAME = 30;

    private File heightFile;
    private long lastModified = -1;
    private int fullHeight = 0;
    private int coveredHeight = 0;
    private int componentsUntilPoll = 0;

    @Override
    public void Init() {
        heightFile = new File(System.getProperty("pluginDir"), HEIGHT_FILE);
    }

    @Override
    public void Draw(long delta) {
        poll();
    }

    /// Draw only reaches a plugin from inside the game interface, so nothing arrives at all while the world map is up.
    @Override
    public void ComponentDraw(int interfaceId, Component component, int x, int y) {
        if (componentsUntilPoll-- > 0) return;
        componentsUntilPoll = COMPONENTS_BETWEEN_POLLS;
        poll();
    }

    private void poll() {
        if (DisplayMode.getWindowMode() < FIRST_RESIZABLE_MODE) return;
        if (fullHeight == 0) fullHeight = GameShell.canvasHeight;

        long stamp = heightFile.lastModified();
        if (stamp == lastModified) return;
        lastModified = stamp;

        int covered = readCoveredHeight();
        if (covered != coveredHeight) apply(covered);
    }

    private int readCoveredHeight() {
        try (FileInputStream in = new FileInputStream(heightFile)) {
            byte[] buffer = new byte[HEIGHT_BUFFER_BYTES];
            int length = in.read(buffer);
            return Integer.parseInt(new String(buffer, 0, length, StandardCharsets.UTF_8).trim());
        } catch (Exception exception) {
            System.out.println("KeyboardRoom: could not read '" + heightFile + "', '" + exception + "'.");
            return coveredHeight;
        }
    }

    private void apply(int covered) {
        int height = Math.max(SMALLEST_WINDOW, fullHeight - covered);

        // Sets only the height the interface lays out against, leaving the picture full size so it sits at the top.
        GlRenderer.setCanvasSize(GameShell.canvasWidth, height);
        GameShell.canvas.setSize(GameShell.canvasWidth, height);
        relayout();

        coveredHeight = covered;
    }

    /// The run the client makes when its display mode changes, which is the only window resize it knows about.
    private void relayout() {
        if (InterfaceList.topLevelInterface != NO_INTERFACE_OPEN) InterfaceList.method3712(true);

        // Marks every open interface as needing a redraw, which is what stops the old size flickering through.
        for (int index = 0; index < InterfaceList.aBooleanArray100.length; index++) {
            InterfaceList.aBooleanArray100[index] = true;
        }
        GameShell.fullRedraw = true;

        boolean serverIsListening = client.gameState == GAME_STATE_IN_GAME || client.gameState == GAME_STATE_LOADING;
        if (Protocol.socket != null && serverIsListening) ClientProt.sendWindowDetails();
    }
}
