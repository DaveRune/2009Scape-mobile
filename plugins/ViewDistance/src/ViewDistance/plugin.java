package ViewDistance;

import plugin.Plugin;
import plugin.annotations.PluginMeta;
import plugin.api.API;
import org.lwjgl.opengl.GL11;
import rt4.FogManager;
import rt4.GlobalConfig;
import rt4.GlRenderer;
import rt4.SceneGraph;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

@PluginMeta(author = "Dave", description = "Draws more of the world before it is culled, and can turn the fog off", version = 6.0)
public class plugin extends Plugin {

    private static final String CMD_DISTANCE = "::viewdistance";
    private static final String CMD_DISTANCE_SHORT = "::vd";
    private static final String ARG_FOG = "fog";

    private static final String SETTINGS_FILE = "viewdistance.properties";
    private static final String KEY_TILES = "tiles";
    private static final String KEY_FOG = "fog";

    private static final int STOCK_TILES = 28;
    private static final int DEFAULT_TILES = 48;
    private static final float STOCK_FADE = 256f;
    private static final int MIN_TILES = 8;
    // Every map access inside SceneGraph is clamped to the 104 tile map, so a cull radius that spans it is safe.
    private static final int MAX_TILES = 103;

    private int wantedTiles = DEFAULT_TILES;
    private boolean fogOn = true;

    private int fogRefreshKey = 0;
    private boolean fogNeedsRefresh = false;
    private boolean fogNeedsRestoring = false;

    @Override
    public void Init() {
        Properties settings = readSettings();
        wantedTiles = clampTiles(readInt(settings, KEY_TILES, DEFAULT_TILES));
        fogOn = readBoolean(settings, KEY_FOG, true);
        fogNeedsRestoring = fogOn;
        apply();
    }

    @Override
    public void Draw(long elapsed) {
        if (!fogNeedsRefresh) return;
        reissueFog();
        fogNeedsRefresh = false;
    }

    @Override
    public void LateDraw(long elapsed) {
        if (!API.IsHD()) return;

        if (fogNeedsRestoring) {
            GlRenderer.setFogEnabled(false);
            GlRenderer.setFogEnabled(true);
            fogNeedsRestoring = false;
            return;
        }

        if (fogOn) return;
        // GlRenderer only calls glEnable when its own flag changes, so holding that flag on keeps the scene draw from undoing this.
        GlRenderer.setFogEnabled(true);
        GL11.glDisable(GL11.GL_FOG);
    }

    @Override
    public void ProcessCommand(String command, String[] args) {
        if (!CMD_DISTANCE.equalsIgnoreCase(command) && !CMD_DISTANCE_SHORT.equalsIgnoreCase(command)) return;

        if (args.length == 0) {
            API.SendMessage("View distance " + wantedTiles + " tiles, fog " + (fogOn ? "on" : "off") + ". " + CMD_DISTANCE_SHORT + " " + MIN_TILES + " to " + MAX_TILES + ", or " + CMD_DISTANCE_SHORT + " " + ARG_FOG + ".");
            return;
        }

        if (ARG_FOG.equalsIgnoreCase(args[0])) {
            fogOn = !fogOn;
            fogNeedsRestoring = fogOn;
            store(KEY_FOG, Boolean.toString(fogOn));
            apply();
            API.SendMessage(fogOn ? "Fog on." : "Fog off.");
            return;
        }

        int tiles;
        try {
            tiles = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            API.SendMessage("View distance must be a number of tiles, or " + ARG_FOG + ".");
            return;
        }

        if (tiles < MIN_TILES || tiles > MAX_TILES) {
            API.SendMessage("View distance must be " + MIN_TILES + " to " + MAX_TILES + ".");
            return;
        }

        wantedTiles = tiles;
        store(KEY_TILES, Integer.toString(tiles));
        apply();
        API.SendMessage("View distance " + tiles + " tiles.");
    }

    private void apply() {
        GlobalConfig.TILE_DISTANCE = wantedTiles;
        GlobalConfig.VIEW_FADE_DISTANCE = wantedTiles / (float) STOCK_TILES * STOCK_FADE;
        GlobalConfig.VIEW_DISTANCE = farPlaneTiles() * 128;
        SceneGraph.visibility = wantedTiles;
        fogNeedsRefresh = true;
    }

    private int farPlaneTiles() {
        if (fogOn) return wantedTiles;
        // With no fog to hide it the square's corners are visible, and a corner sits at the radius times root two.
        return Math.round(wantedTiles * 1.4143f);
    }

    private void reissueFog() {
        fogRefreshKey = fogRefreshKey == 0 ? 1 : 0;
        FogManager.setFogParams(currentFogColour(), fogRefreshKey);
    }

    private int currentFogColour() {
        float[] colour = FogManager.fogColor;
        return (toByte(colour[0]) << 16) | (toByte(colour[1]) << 8) | toByte(colour[2]);
    }

    private static int toByte(float value) {
        int scaled = (int) (value * 255f);
        if (scaled < 0) return 0;
        return Math.min(scaled, 255);
    }

    private static int clampTiles(int tiles) {
        if (tiles < MIN_TILES) return MIN_TILES;
        return Math.min(tiles, MAX_TILES);
    }

    private static File settingsFile() {
        String dir = System.getProperty("pluginDir");
        return dir == null ? new File(SETTINGS_FILE) : new File(dir, SETTINGS_FILE);
    }

    private static Properties readSettings() {
        Properties settings = new Properties();
        File file = settingsFile();
        if (!file.exists()) return settings;
        try (FileInputStream in = new FileInputStream(file)) {
            settings.load(in);
        } catch (Exception e) {
            System.out.println("ViewDistance: could not read " + file + ", " + e);
        }
        return settings;
    }

    private static void writeSettings(Properties settings) {
        File file = settingsFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            settings.store(out, "ViewDistance plugin settings");
        } catch (Exception e) {
            System.out.println("ViewDistance: could not write " + file + ", " + e);
        }
    }

    private static void store(String key, String value) {
        Properties settings = readSettings();
        settings.setProperty(key, value);
        writeSettings(settings);
    }

    private static boolean readBoolean(Properties settings, String key, boolean fallback) {
        return Boolean.parseBoolean(settings.getProperty(key, Boolean.toString(fallback)));
    }

    private static int readInt(Properties settings, String key, int fallback) {
        try {
            return Integer.parseInt(settings.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
