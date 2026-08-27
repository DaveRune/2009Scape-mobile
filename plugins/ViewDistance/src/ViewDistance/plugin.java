package ViewDistance;

import plugin.Plugin;
import plugin.annotations.PluginMeta;
import plugin.api.API;
import rt4.FogManager;
import rt4.GameShell;
import rt4.GlobalConfig;
import rt4.SceneGraph;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

@PluginMeta(author = "Dave", description = "Draws more of the world before it is culled and fogged out", version = 3.2)
public class plugin extends Plugin {

    private static final String CMD_DISTANCE = "::viewdistance";
    private static final String CMD_DISTANCE_SHORT = "::vd";
    private static final String CMD_UI = "::uiscale";
    private static final String CMD_UI_SHORT = "::uis";

    private static final String SETTINGS_FILE = "viewdistance.properties";
    private static final String KEY_TILES = "tiles";
    private static final String KEY_UI_SCALE = "uiscale";

    private static final int STOCK_TILES = 28;
    private static final int DEFAULT_TILES = 48;
    private static final float STOCK_FADE = 256f;
    private static final int MIN_TILES = 8;
    private static final int MAX_TILES = 51;

    private static final double STOCK_UI_SCALE = 1.0d;
    private static final double MIN_UI_SCALE = 0.5d;
    private static final double MAX_UI_SCALE = 4.0d;
    private static final long UI_TRIAL_MILLIS = 15000L;

    private int wantedTiles = DEFAULT_TILES;
    private double savedUiScale = STOCK_UI_SCALE;
    private double trialUiScale = STOCK_UI_SCALE;
    private long uiTrialEndsAt = 0L;

    private int fogRefreshKey = 0;
    private boolean fogNeedsRefresh = false;

    @Override
    public void Init() {
        Properties settings = readSettings();
        wantedTiles = clampTiles(readInt(settings, KEY_TILES, DEFAULT_TILES));
        savedUiScale = clampScale(readDouble(settings, KEY_UI_SCALE, STOCK_UI_SCALE));

        applyDistance(wantedTiles);
        GameShell.canvasScale = savedUiScale;
    }

    @Override
    public void Draw(long elapsed) {
        if (uiTrialEndsAt != 0L && System.currentTimeMillis() > uiTrialEndsAt) {
            uiTrialEndsAt = 0L;
            GameShell.canvasScale = savedUiScale;
            API.SendMessage("UI scale reverted to " + savedUiScale + ".");
        }

        if (!fogNeedsRefresh) return;
        reissueFog();
        fogNeedsRefresh = false;
    }

    @Override
    public void ProcessCommand(String command, String[] args) {
        if (CMD_DISTANCE.equalsIgnoreCase(command) || CMD_DISTANCE_SHORT.equalsIgnoreCase(command)) {
            handleDistance(args);
        } else if (CMD_UI.equalsIgnoreCase(command) || CMD_UI_SHORT.equalsIgnoreCase(command)) {
            handleUiScale(args);
        }
    }

    private void handleDistance(String[] args) {
        if (args.length == 0) {
            API.SendMessage("View distance " + SceneGraph.visibility + ", set to " + wantedTiles + ".");
            return;
        }

        int tiles;
        try {
            tiles = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            API.SendMessage("View distance must be a number. Default " + DEFAULT_TILES + ".");
            return;
        }

        if (tiles < MIN_TILES || tiles > MAX_TILES) {
            API.SendMessage("View distance must be " + MIN_TILES + " to " + MAX_TILES + ".");
            return;
        }

        wantedTiles = tiles;
        store(KEY_TILES, Integer.toString(tiles));
        applyDistance(tiles);
        API.SendMessage("View distance " + tiles + ". Relog to extend the ground.");
    }

    private void handleUiScale(String[] args) {
        if (args.length == 0) {
            API.SendMessage("UI scale " + GameShell.canvasScale + ", saved " + savedUiScale + ".");
            return;
        }

        if ("keep".equalsIgnoreCase(args[0])) {
            if (uiTrialEndsAt == 0L) {
                API.SendMessage("Nothing to keep.");
                return;
            }
            uiTrialEndsAt = 0L;
            savedUiScale = trialUiScale;
            store(KEY_UI_SCALE, Double.toString(savedUiScale));
            API.SendMessage("UI scale " + savedUiScale + " kept.");
            return;
        }

        double scale;
        try {
            scale = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            API.SendMessage("UI scale must be a number, or 'keep'.");
            return;
        }

        if (scale < MIN_UI_SCALE || scale > MAX_UI_SCALE) {
            API.SendMessage("UI scale must be " + MIN_UI_SCALE + " to " + MAX_UI_SCALE + ".");
            return;
        }

        trialUiScale = scale;
        GameShell.canvasScale = scale;
        uiTrialEndsAt = System.currentTimeMillis() + UI_TRIAL_MILLIS;
        API.SendMessage("Trying " + scale + ". Type ::uis keep within 15s.");
    }

    private void applyDistance(int tiles) {
        GlobalConfig.TILE_DISTANCE = tiles;
        GlobalConfig.VIEW_DISTANCE = tiles * 128;
        GlobalConfig.VIEW_FADE_DISTANCE = tiles / (float) STOCK_TILES * STOCK_FADE;
        fogNeedsRefresh = true;
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

    private static double clampScale(double scale) {
        if (scale < MIN_UI_SCALE) return MIN_UI_SCALE;
        return Math.min(scale, MAX_UI_SCALE);
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

    private static int readInt(Properties settings, String key, int fallback) {
        try {
            return Integer.parseInt(settings.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double readDouble(Properties settings, String key, double fallback) {
        try {
            return Double.parseDouble(settings.getProperty(key, Double.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
