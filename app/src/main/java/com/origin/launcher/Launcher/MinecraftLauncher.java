package com.origin.launcher.Launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.origin.launcher.versions.GameVersion;
import com.origin.launcher.utils.FeatureSettings;
import com.origin.launcher.dialogs.LoadingDialog;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

public class MinecraftLauncher {
    private static final String TAG = "MinecraftLauncher";
    private final Context context;
    private GamePackageManager gameManager;
    public static final String MC_PACKAGE_NAME = "com.mojang.minecraftpe";
    private LoadingDialog loadingDialog;
    private LaunchErrorListener launchErrorListener;

    public interface LaunchErrorListener {
        void onLaunchError(String errorMessage);
    }

    public MinecraftLauncher(Context context) {
        this.context = context;
    }

    public void setLaunchErrorListener(LaunchErrorListener listener) {
        this.launchErrorListener = listener;
    }

    public static String abiToSystemLibDir(String abi) {
        if ("arm64-v8a".equals(abi)) return "arm64";
        if ("armeabi-v7a".equals(abi)) return "arm";
        return abi;
    }

    public ApplicationInfo createFakeApplicationInfo(GameVersion version, String packageName) {
        ApplicationInfo fakeInfo = new ApplicationInfo();
        File apkFile = new File(version.versionDir, "base.apk.xelo");
        fakeInfo.sourceDir = apkFile.getAbsolutePath();
        fakeInfo.publicSourceDir = fakeInfo.sourceDir;
        String systemAbi = abiToSystemLibDir(Build.SUPPORTED_ABIS[0]);
        File dstLibDir = new File(context.getDataDir(), "minecraft/" + version.directoryName + "/lib/" + systemAbi);
        fakeInfo.nativeLibraryDir = dstLibDir.getAbsolutePath();
        fakeInfo.packageName = packageName;
        fakeInfo.dataDir = version.versionDir.getAbsolutePath();

        File splitsFolder = new File(version.versionDir, "splits");
        if (splitsFolder.exists() && splitsFolder.isDirectory()) {
            File[] splits = splitsFolder.listFiles();
            if (splits != null) {
                ArrayList<String> splitPathList = new ArrayList<>();
                for (File f : splits) {
                    if (f.isFile() && f.getName().endsWith(".apk.xelo")) {
                        splitPathList.add(f.getAbsolutePath());
                    }
                }
                if (!splitPathList.isEmpty()) {
                    fakeInfo.splitSourceDirs = splitPathList.toArray(new String[0]);
                }
            }
        }
        return fakeInfo;
    }

    public void launch(Intent sourceIntent, GameVersion version) {
        Activity activity = (Activity) context;

        try {
            if (version == null) {
                Log.e(TAG, "No version selected");
                showLaunchErrorOnUi("No version selected");
                return;
            }

            activity.runOnUiThread(() -> {
                dismissLoading();
                loadingDialog = new LoadingDialog(activity);
                loadingDialog.show();
            });
                        new Thread(() -> {
                try {
                    gameManager = GamePackageManager.Companion.getInstance(context.getApplicationContext(), version);
                    fillIntentWithMcPath(sourceIntent, version);
                    launchMinecraftActivity(sourceIntent, version, false);
                } catch (Throwable e) {
                    Log.e(TAG, "Launch failed: " + e.getMessage(), e);
                    activity.runOnUiThread(() -> {
                        dismissLoading();
                        showLaunchErrorOnUi("Launch failed: " + e.getMessage());
                    });
                }
            }).start();
        } catch (Throwable e) {
            Log.e(TAG, "Launch failed: " + e.getMessage(), e);
            dismissLoading();
            showLaunchErrorOnUi("Launch failed: " + e.getMessage());
        }
    }

    private void fillIntentWithMcPath(Intent sourceIntent, GameVersion version) {
        if (FeatureSettings.getInstance().isVersionIsolationEnabled()) {
            sourceIntent.putExtra("MC_PATH", version.versionDir.getAbsolutePath());
            sourceIntent.putExtra("IS_INSTALLED", version.isInstalled);
        } else {
            sourceIntent.putExtra("MC_PATH", "");
            sourceIntent.putExtra("IS_INSTALLED", false);
        }
    }

    private void launchMinecraftActivity(Intent sourceIntent, GameVersion version, boolean modsEnabled) {
        Activity activity = (Activity) context;

        new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    sourceIntent.putExtra("DISABLE_SPLASH_SCREEN", true);
                }

                sourceIntent.setClass(context, MinecraftActivity.class);
                ApplicationInfo mcInfo = version.isInstalled ?
                        gameManager.getPackageContext().getApplicationInfo() :
                        createFakeApplicationInfo(version, MC_PACKAGE_NAME);
                sourceIntent.putExtra("MC_SRC", mcInfo.sourceDir);
                if (mcInfo.splitSourceDirs != null) {
                    sourceIntent.putExtra("MC_SPLIT_SRC", new ArrayList<>(Arrays.asList(mcInfo.splitSourceDirs)));
                }
                sourceIntent.putExtra("MODS_ENABLED", modsEnabled);
                sourceIntent.putExtra("MINECRAFT_VERSION", version.versionCode);
                sourceIntent.putExtra("MINECRAFT_VERSION_DIR", version.directoryName);
                if (shouldLoadHttpClient(version)) {
                    throwIfRequiredLibMissing(gameManager.loadLibrary("c++_shared"), "libc++_shared.so");
                    if (gameManager.loadLibrary("HttpClient.Android")) {
                        Log.d(TAG, "Loaded Minecraft's libHttpClient.Android.so");
                    } else {
                        Log.w(TAG, "HttpClient.Android not found in extracted libs");
                    }
                }

                if (shouldLoadMaesdk(version)) {
                    java.util.Set<String> excludeLibs = new java.util.HashSet<>();
                    if (shouldLoadHttpClient(version)) {
                        excludeLibs.add("c++_shared");
                        excludeLibs.add("HttpClient.Android");
                    }
                    if (!shouldLoadPlayFab(version)) {
                        excludeLibs.add("PlayFabMultiplayer");
                    }
                    gameManager.loadAllLibraries(excludeLibs);
                } else {
                    if (!shouldLoadHttpClient(version)) {
                        throwIfRequiredLibMissing(gameManager.loadLibrary("c++_shared"), "libc++_shared.so");
                    }
                    throwIfRequiredLibMissing(gameManager.loadLibrary("fmod"), "libfmod.so");
                    throwIfRequiredLibMissing(gameManager.loadLibrary("MediaDecoders_Android"), "libMediaDecoders_Android.so");
                    throwIfRequiredLibMissing(gameManager.loadLibrary("minecraftpe"), "libminecraftpe.so");
                    gameManager.loadLibrary("mtbinloader2"); // optional
                }

                activity.runOnUiThread(() -> {
                    dismissLoading();
                    activity.startActivity(sourceIntent);
                });
            } catch (Throwable e) {
                Log.e(TAG, "Failed to launch Minecraft activity: " + e.getMessage(), e);
                activity.runOnUiThread(() -> {
                    dismissLoading();
                    showLaunchErrorOnUi("Failed to launch Minecraft activity: " + e.getMessage());
                });
            }
        }).start();
    }

    private boolean shouldLoadMaesdk(GameVersion version) {
        if (version == null || version.versionCode == null) {
            return false;
        }
        String versionCode = version.versionCode;
        String targetVersion = versionCode.contains("beta") ? "1.21.110.22" : "1.21.110";
        return isVersionAtLeast(versionCode, targetVersion);
    }

    private boolean shouldLoadHttpClient(GameVersion version) {
        if (version == null || version.versionCode == null) {
            return false;
        }
        String versionCode = version.versionCode;
        String targetVersion = versionCode.contains("beta") ? "1.21.130.20" : "1.21.130";
        return isVersionAtLeast(versionCode, targetVersion);
    }

private boolean shouldLoadPlayFab(GameVersion version) {
        if (version == null || version.versionCode == null) {
            return false;
        }
        String versionCode = version.versionCode;
        String targetVersion = versionCode.contains("beta") ? "1.21.130.20" : "1.21.130";
        return isVersionAtLeast(versionCode, targetVersion);
    }

    private boolean isVersionAtLeast(String currentVersion, String targetVersion) {
        try {
            String[] current = currentVersion.replaceAll("[^0-9.]", "").split("\\.");
            String[] target = targetVersion.split("\\.");

            int maxLength = Math.max(current.length, target.length);

            for (int i = 0; i < maxLength; i++) {
                int currentPart = i < current.length ? Integer.parseInt(current[i]) : 0;
                int targetPart = i < target.length ? Integer.parseInt(target[i]) : 0;

                if (currentPart > targetPart) return true;
                if (currentPart < targetPart) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void throwIfRequiredLibMissing(boolean loaded, String libName) {
        if (!loaded) {
            throw new RuntimeException(
                "Failed to load required library: " + libName +
                "\n\nSuggested fixes:" +
                "\n  1. Reinstall Minecraft from the Play Store" +
                "\n  2. Clear cache for both Minecraft and this app" +
                "\n  3. Ensure sufficient storage space is available"
            );
        }
    }

    private void dismissLoading() {
        try {
            if (loadingDialog != null && loadingDialog.isShowing()) {
                loadingDialog.dismiss();
            }
        } catch (Exception ignored) {
        } finally {
            loadingDialog = null;
        }
    }

    private void showLaunchErrorOnUi(String message) {
        if (launchErrorListener != null) {
            launchErrorListener.onLaunchError(message);
        }
        Activity activity = (Activity) context;
        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity)
                        .setTitle("Game Failed to Start")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            } catch (Exception e) {
                Log.e(TAG, "Failed to show error dialog: " + e.getMessage());
            }
        });
    }
}