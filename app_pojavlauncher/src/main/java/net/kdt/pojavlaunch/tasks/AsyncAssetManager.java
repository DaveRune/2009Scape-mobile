package net.kdt.pojavlaunch.tasks;


import static net.kdt.pojavlaunch.utils.Architecture.archAsString;
import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.util.Log;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class AsyncAssetManager {

    private static final String PLUGIN_PATH = "plugins";
    private static final String PLUGIN_VERSION_FILE = "plugins.version";

    private AsyncAssetManager(){}

    /**
     * Attempt to install the java 8 runtime, if necessary
     * @param am App context
     */
    public static void unpackRuntime(AssetManager am) {
        /* Check if JRE is included */
        String rt_version = null;
        String current_rt_version = MultiRTUtils.__internal__readBinpackVersion("Internal");
        try {
            rt_version = Tools.read(am.open("components/jre/version"));
        } catch (IOException e) {
            Log.e("JREAuto", "JRE was not included on this APK.", e);
        }
        String exactJREName = MultiRTUtils.getExactJreName(8);
        if(current_rt_version == null && exactJREName != null && !exactJREName.equals("Internal")/*this clause is for when the internal runtime is goofed*/) return;
        if(rt_version == null) return;
        if(rt_version.equals(current_rt_version)) return;

        // Install the runtime in an async manner, hope for the best
        String finalRt_version = rt_version;
        sExecutorService.execute(() -> {

            try {
                MultiRTUtils.installRuntimeNamedBinpack(
                        am.open("components/jre/universal.tar.xz"),
                        am.open("components/jre/bin-" + archAsString(Tools.DEVICE_ARCHITECTURE) + ".tar.xz"),
                        "Internal", finalRt_version);
                MultiRTUtils.postPrepare("Internal");
            }catch (IOException e) {
                Log.e("JREAuto", "Internal JRE unpack failed", e);
            }
        });
    }

    /** Unpack single files, with no regard to version tracking */
    public static void unpackSingleFiles(Context ctx){
        ProgressLayout.setProgress(ProgressLayout.EXTRACT_SINGLE_FILES, 0);
        sExecutorService.execute(() -> {
            try {
                Tools.copyAssetFile(ctx, "options.txt", Tools.DIR_GAME_NEW, false);
                Tools.copyAssetFile(ctx, "default.json", Tools.CTRLMAP_PATH, false);
                Tools.copyAssetFile(ctx, "launcher_profiles.json", Tools.DIR_GAME_NEW, false);
            } catch (IOException e) {
                Log.e("AsyncAssetManager", "Failed to unpack critical components !");
            }
            ProgressLayout.clearProgress(ProgressLayout.EXTRACT_SINGLE_FILES);
        });
    }

    public static void unpackComponents(Context ctx){
        ProgressLayout.setProgress(ProgressLayout.EXTRACT_COMPONENTS, 0);
        sExecutorService.execute(() -> {
            try {
                unpackComponent(ctx, "caciocavallo", false);
                unpackComponent(ctx, "caciocavallo17", false);
                // Since the Java module system doesn't allow multiple JARs to declare the same module,
                // we repack them to a single file here
                unpackComponent(ctx, "lwjgl3", false);
                unpackComponent(ctx, "security", true);
                unpackClient(ctx);
                Tools.copyAssetFile(ctx,"config.json",Tools.DIR_DATA, false);

                // Unzip the plugins for use.
                extractAllPlugins(ctx);

            } catch (IOException e) {
                Log.e("AsyncAssetManager", "Failed o unpack components !",e );
            }
            ProgressLayout.clearProgress(ProgressLayout.EXTRACT_COMPONENTS);
        });
    }

    /**
     * Installs the shipped plugins whenever the app itself is installed, keeping a record of every file it
     * wrote and the fingerprint it wrote. That record is what lets an update replace its own files, delete the
     * ones it no longer ships, and leave alone both the files a plugin wrote for itself and any settings
     * something on the device has since changed.
     */
    private static void extractAllPlugins(Context ctx) throws IOException {
        File recordFile = new File(Tools.DIR_DATA, PLUGIN_VERSION_FILE);
        String shipped = describeThisInstall(ctx);
        List<String> record = readRecord(recordFile);

        if (!record.isEmpty() && shipped.equals(record.get(0))) {
            Log.i("UnpackPrep", "plugins: Plugins are up-to-date with the app, continuing...");
            return;
        }

        File pluginsDirectory = new File(Tools.DIR_DATA + "/plugins/");
        File disabledPluginsDirectory = new File(Tools.DIR_DATA + "/disabledPlugins/");
        if (!disabledPluginsDirectory.exists() && !disabledPluginsDirectory.mkdirs()) {
            Log.e("UnpackPrep", "plugins: Failed to create " + disabledPluginsDirectory.getPath());
            return;
        }

        String[] pluginFiles = ctx.getAssets().list(PLUGIN_PATH);
        if (pluginFiles == null) return;

        Log.i("UnpackPrep", "plugins: Installing the plugins shipped with " + shipped);
        Map<String, String> alreadyInstalled = readInstalledFiles(record);
        Map<String, String> nowInstalled = new LinkedHashMap<>();

        for (String pluginFile : pluginFiles) {
            String pluginName = pluginFile.substring(0, pluginFile.lastIndexOf('.'));
            boolean userDisabledIt = new File(disabledPluginsDirectory, pluginName).exists();

            Tools.copyAssetFile(ctx, PLUGIN_PATH + "/" + pluginFile, Tools.DIR_DATA, true);
            installPlugin(new File(Tools.DIR_DATA + "/" + pluginFile),
                    userDisabledIt ? disabledPluginsDirectory : pluginsDirectory,
                    alreadyInstalled, nowInstalled);
        }

        removeFilesNoLongerShipped(alreadyInstalled, nowInstalled, pluginsDirectory, disabledPluginsDirectory);
        writeRecord(recordFile, shipped, nowInstalled);
    }

    /**
     * The version alone would miss a rebuild of the same commit, which is most of what happens during
     * development, so the moment the app was installed decides it instead.
     */
    private static String describeThisInstall(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return BuildConfig.VERSION_NAME + " " + info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("The app cannot find its own package", e);
        }
    }

    /**
     * Writes out one plugin. A class file is always replaced, because the code is ours and a copy left on a
     * device by hand is how a build gets tested that nobody else will ever run. Anything else is left alone
     * once its fingerprint stops matching, because by then it holds something the plugin or the player put there.
     */
    private static void installPlugin(File pluginZip, File root, Map<String, String> alreadyInstalled,
                                      Map<String, String> nowInstalled) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(pluginZip)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File file = new File(root, entry.getName());
                File directory = entry.isDirectory() ? file : file.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Failed to create " + directory.getAbsolutePath());
                }
                if (entry.isDirectory()) continue;

                byte[] content = readEntry(zip);
                String installedFingerprint = alreadyInstalled.get(entry.getName());
                boolean isCode = entry.getName().endsWith(".class");

                if (!isCode && file.exists() && installedFingerprint != null
                        && !Tools.compareSHA1(file, installedFingerprint)) {
                    Log.i("UnpackPrep", "plugins: Keeping " + entry.getName() + ", it has been changed on this device");
                    nowInstalled.put(entry.getName(), installedFingerprint);
                    continue;
                }

                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(content);
                }
                nowInstalled.put(entry.getName(), fingerprint(content));
            }
        }
    }

    /**
     * Deletes the files the launcher installed last time and no longer ships, so a plugin dropped from an update
     * leaves the device rather than staying behind and breaking the game. Nothing else is touched, because
     * nothing else is in the record.
     */
    private static void removeFilesNoLongerShipped(Map<String, String> alreadyInstalled, Map<String, String> nowInstalled,
                                                   File pluginsDirectory, File disabledPluginsDirectory) {
        for (String path : alreadyInstalled.keySet()) {
            if (nowInstalled.containsKey(path)) continue;

            Log.i("UnpackPrep", "plugins: Removing " + path + ", which the launcher no longer ships");
            new File(pluginsDirectory, path).delete();
            new File(disabledPluginsDirectory, path).delete();
        }
        deleteEmptyPluginFolders(pluginsDirectory);
        deleteEmptyPluginFolders(disabledPluginsDirectory);
    }

    private static void deleteEmptyPluginFolders(File directory) {
        File[] folders = directory.listFiles(File::isDirectory);
        if (folders == null) return;

        for (File folder : folders) {
            String[] contents = folder.list();
            if (contents != null && contents.length == 0) folder.delete();
        }
    }

    private static List<String> readRecord(File recordFile) throws IOException {
        if (!recordFile.exists()) return Collections.emptyList();
        return Arrays.asList(Tools.read(recordFile.getAbsolutePath()).split("\n"));
    }

    /** @return the path of every file the launcher installed, against the fingerprint it had when written */
    private static Map<String, String> readInstalledFiles(List<String> record) {
        Map<String, String> installed = new LinkedHashMap<>();
        for (int line = 1; line < record.size(); line++) {
            int separator = record.get(line).indexOf(' ');
            if (separator < 1) continue;
            installed.put(record.get(line).substring(separator + 1), record.get(line).substring(0, separator));
        }
        return installed;
    }

    private static void writeRecord(File recordFile, String shipped, Map<String, String> installed) throws IOException {
        StringBuilder record = new StringBuilder(shipped);
        for (Map.Entry<String, String> file : installed.entrySet()) {
            record.append('\n').append(file.getValue()).append(' ').append(file.getKey());
        }
        Tools.write(recordFile.getAbsolutePath(), record.toString());
    }

    private static byte[] readEntry(InputStream stream) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) collected.write(buffer, 0, count);
        return collected.toByteArray();
    }

    private static String fingerprint(byte[] content) {
        return new String(Hex.encodeHex(DigestUtils.sha1(content)));
    }


    public static void extractPluginZip(File plugin) throws IOException {
        Tools.ZipTool.unzip(plugin, new File(Tools.DIR_DATA + "/plugins/"));
    }

    /**
     * The client jar is replaced whenever the launcher version changes, so an app update always
     * brings its own client with it. Without this an existing install keeps whichever client it
     * first unpacked, forever, and gets a new launcher driving an old client.
     */
    private static void unpackClient(Context ctx) throws IOException {
        File jar = new File(Tools.DIR_DATA, "rt4.jar");
        File versionFile = new File(Tools.DIR_DATA, "rt4.jar.version");
        String shipped = BuildConfig.VERSION_NAME;

        if (jar.exists() && versionFile.exists() && shipped.equals(Tools.read(versionFile.getAbsolutePath()))) {
            Log.i("UnpackPrep", "rt4.jar: Client is up-to-date with the launcher, continuing...");
            return;
        }

        Log.i("UnpackPrep", "rt4.jar: Unpacking the client shipped with " + shipped);
        Tools.copyAssetFile(ctx, "rt4.jar", Tools.DIR_DATA, true);
        Tools.write(versionFile.getAbsolutePath(), shipped);
    }

    private static void unpackComponent(Context ctx, String component, boolean privateDirectory) throws IOException {
        AssetManager am = ctx.getAssets();
        String rootDir = privateDirectory ? Tools.DIR_DATA : Tools.DIR_GAME_HOME;

        File versionFile = new File(rootDir + "/" + component + "/version");
        InputStream is = am.open("components/" + component + "/version");
        if(!versionFile.exists()) {
            if (versionFile.getParentFile().exists() && versionFile.getParentFile().isDirectory()) {
                FileUtils.deleteDirectory(versionFile.getParentFile());
            }
            versionFile.getParentFile().mkdir();

            Log.i("UnpackPrep", component + ": Pack was installed manually, or does not exist, unpacking new...");
            String[] fileList = am.list("components/" + component);
            for(String s : fileList) {
                Tools.copyAssetFile(ctx, "components/" + component + "/" + s, rootDir + "/" + component, true);
            }
        } else {
            FileInputStream fis = new FileInputStream(versionFile);
            String release1 = Tools.read(is);
            String release2 = Tools.read(fis);
            if (!release1.equals(release2)) {
                if (versionFile.getParentFile().exists() && versionFile.getParentFile().isDirectory()) {
                    FileUtils.deleteDirectory(versionFile.getParentFile());
                }
                versionFile.getParentFile().mkdir();

                String[] fileList = am.list("components/" + component);
                for (String fileName : fileList) {
                    Tools.copyAssetFile(ctx, "components/" + component + "/" + fileName, rootDir + "/" + component, true);
                }
            } else {
                Log.i("UnpackPrep", component + ": Pack is up-to-date with the launcher, continuing...");
            }
        }
    }
}
