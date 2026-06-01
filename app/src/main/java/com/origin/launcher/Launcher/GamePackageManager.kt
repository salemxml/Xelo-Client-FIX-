package com.origin.launcher.Launcher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import com.origin.launcher.versions.GameVersion
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.zip.ZipFile
import android.annotation.SuppressLint


class GamePackageManager private constructor(private val context: Context, private val version: GameVersion?) {

    private val packageContext: Context
    private val assetManager: AssetManager
    private val nativeLibDir: String
    private val applicationInfo: ApplicationInfo

    private val knownPackages = arrayOf(
        "com.mojang.minecraftpe",
        "com.mojang.minecraftpe.beta",
        "com.mojang.minecraftpe.preview"
    )

    private val requiredLibs = arrayOf(
        "libc++_shared.so",
        "libfmod.so",
        "libMediaDecoders_Android.so",
        "libminecraftpe.so",
    )

    private val optionalLibs = arrayOf(
        "libHttpClient.Android.so",
        "libpairipcore.so",
        "libPlayFabMultiplayer.so",
        "libmaesdk.so",
        "libmtbinloader2.so",
    )

    private val allExtractLibs: Array<String>
        get() = arrayOf(
            "libc++_shared.so",
            "libfmod.so",
            "libMediaDecoders_Android.so",
            "libHttpClient.Android.so",
            "libpairipcore.so",
            "libPlayFabMultiplayer.so",
            "libmaesdk.so",
            "libmtbinloader2.so",
            "libminecraftpe.so",
        )

    init {
        val packageName = detectGamePackage() ?: throw IllegalStateException("Minecraft not found")
        packageContext = context.createPackageContext(
            packageName,
            Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
        )

        if (version != null && !version.isInstalled) {
            applicationInfo = MinecraftLauncher(context).createFakeApplicationInfo(version, MinecraftLauncher.MC_PACKAGE_NAME)
            nativeLibDir = applicationInfo.nativeLibraryDir
        } else {
            applicationInfo = packageContext.applicationInfo
            nativeLibDir = resolveNativeLibDir()
        }

        extractLibraries()
        assetManager = createAssetManager()
        setupSecurityProvider()
    }

    private fun detectGamePackage(): String? {
        return knownPackages.firstOrNull { isPackageInstalled(it) }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun resolveNativeLibDir(): String {
        val appInfo = packageContext.applicationInfo
        return if (appInfo.splitPublicSourceDirs?.isNotEmpty() == true) {
            val cacheLibDir = File(context.cacheDir, "lib/${getDeviceAbi()}")
            cacheLibDir.mkdirs()
            cacheLibDir.absolutePath
        } else {
            appInfo.nativeLibraryDir
        }
    }

    private fun getDeviceAbi(): String {
        return Build.SUPPORTED_64_BIT_ABIS.firstOrNull {
            it.contains("arm64-v8a") || it.contains("x86_64")
        } ?: Build.SUPPORTED_32_BIT_ABIS.firstOrNull {
            it.contains("armeabi-v7a") || it.contains("x86")
        } ?: (Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a")
    }

    private fun getApkTimestamp(): Long {
        val primaryApk = if (version != null && !version.isInstalled) {
            File(applicationInfo.sourceDir)
        } else {
            File(packageContext.applicationInfo.sourceDir)
        }
        return if (primaryApk.exists()) primaryApk.lastModified() else 0L
    }

    private fun readStoredTimestamp(dir: File): Long {
        val f = File(dir, TIMESTAMP_FILE)
        return if (f.exists()) f.readText().trim().toLongOrNull() ?: 0L else 0L
    }

    private fun writeStoredTimestamp(dir: File, timestamp: Long) {
        try {
            File(dir, TIMESTAMP_FILE).writeText(timestamp.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write APK timestamp: ${e.message}")
        }
    }

    private fun clearExtractedLibs(dir: File) {
        allExtractLibs.forEach { lib ->
            val f = File(dir, lib)
            if (f.exists()) {
                if (f.delete()) {
                    Log.i(TAG, "Deleted stale library: $lib")
                } else {
                    Log.w(TAG, "Failed to delete stale library: $lib")
                }
            }
        }
    }

    private fun isManagedOutputDir(dir: File): Boolean {
        val cacheDirPath = context.cacheDir.canonicalPath
        return try {
            dir.canonicalPath.startsWith(cacheDirPath)
        } catch (e: Exception) {
            false
        }
    }

    private fun extractLibraries() {
        val outputDir = File(nativeLibDir)
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val isNonInstalled = version != null && !version.isInstalled
        val isManagedDir = isNonInstalled || isManagedOutputDir(outputDir)

        if (isManagedDir) {
            val apkTimestamp = getApkTimestamp()
            val storedTimestamp = readStoredTimestamp(outputDir)
            if (apkTimestamp > 0L && apkTimestamp != storedTimestamp) {
                Log.i(TAG, "APK changed (stored=$storedTimestamp, current=$apkTimestamp), clearing stale libraries in $outputDir")
                clearExtractedLibs(outputDir)
            }
        }

        if (isNonInstalled) {
            val apkPaths = mutableListOf<String>()
            val baseApk = File(applicationInfo.sourceDir)
            if (baseApk.exists()) {
                apkPaths.add(applicationInfo.sourceDir)
            } else {
                Log.w(TAG, "Base APK not found: ${applicationInfo.sourceDir}")
            }
            applicationInfo.splitSourceDirs?.forEach {
                if (File(it).exists()) {
                    apkPaths.add(it)
                } else {
                    Log.w(TAG, "Split APK not found: $it")
                }
            }
            apkPaths.forEach { extractFromApk(it, outputDir, getDeviceAbi()) }
            if (requiredLibs.any { !File(outputDir, it).exists() }) {
                Log.w(TAG, "Primary ABI ${getDeviceAbi()} libraries missing, trying fallback ABIs")
                val fallbackAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
                fallbackAbis.filter { it != getDeviceAbi() }.forEach { abi ->
                    apkPaths.forEach { extractFromApk(it, outputDir, abi) }
                }
            }
        } else {
            val appInfo = packageContext.applicationInfo
            if (File(appInfo.nativeLibraryDir).exists()) {
                copyFromNativeDir(appInfo.nativeLibraryDir, outputDir)
            }
            val apkPaths = mutableListOf<String>()
            appInfo.sourceDir?.let { apkPaths.add(it) }
            appInfo.splitPublicSourceDirs?.let { apkPaths.addAll(it) }
            apkPaths.forEach { extractFromApk(it, outputDir, getDeviceAbi()) }
        }
        verifyLibraries(outputDir)

        if (isManagedDir) {
            val apkTimestamp = getApkTimestamp()
            if (apkTimestamp > 0L) {
                writeStoredTimestamp(outputDir, apkTimestamp)
            }
        }
    }

    private fun copyFromNativeDir(sourceDir: String, destDir: File) {
        val source = File(sourceDir)
        if (!source.exists()) {
            Log.w(TAG, "Source native library directory does not exist: $sourceDir")
            return
        }

        allExtractLibs.forEach { lib ->
            val srcFile = File(source, lib)
            val dstFile = File(destDir, lib)
            if (srcFile.exists() && srcFile.length() > 0) {
                try {
                    srcFile.copyTo(dstFile, overwrite = true)
                    dstFile.setReadable(true)
                    dstFile.setExecutable(true)
                    logFileOperation("Copied", lib)
                } catch (e: Exception) {
                    logFileOperation("Failed to copy", lib, e = e)
                }
            }
        }
    }

    private fun extractFromApk(apkPath: String, outputDir: File, abi: String) {
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            Log.w(TAG, "APK file does not exist: $apkPath")
            return
        }
        if (!apkPath.contains("arm") && !apkPath.contains("x86")
            && !apkPath.contains("base.apk") && !apkPath.contains("split")) {
            return
        }

        try {
            ZipFile(apkPath).use { zip ->
                val abiPath = "lib/$abi"
                allExtractLibs.forEach { lib ->
                    val entry = zip.getEntry("$abiPath/$lib")
                    if (entry == null) {
                        return@forEach
                    }
                    val output = File(outputDir, lib)
                    if (output.exists() && output.length() > 0) {
                        return@forEach
                    }
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { out ->
                            input.copyTo(out)
                        }
                    }
                    output.setReadable(true)
                    output.setExecutable(true)
                    Log.d(TAG, "Extracted $lib from $apkPath ($abi)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract libraries from $apkPath: ${e.message}")
        }
    }

    private fun verifyLibraries(dir: File) {
        val missing = requiredLibs.filterNot {
            File(dir, it).let { f -> f.exists() && f.length() > 0 }
        }
        val presentOptional = optionalLibs.filter {
            File(dir, it).let { f -> f.exists() && f.length() > 0 }
        }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Missing required libraries in $dir: ${missing.joinToString()}")
            val suggestion = buildString {
                append("The following required libraries could not be extracted:\n\n")
                missing.forEach { append("  • $it\n") }
                append("\nSuggested fixes:\n")
                append("  1. Reinstall Minecraft from the Play Store\n")
                append("  2. Clear cache for both Minecraft and this app\n")
                append("  3. Ensure sufficient storage space is available")
            }
            throw MissingLibrariesException(missing, suggestion)
        } else {
            Log.i(TAG, "All required libraries verified in $dir")
        }
        if (presentOptional.isNotEmpty()) {
            Log.i(TAG, "Optional libraries found: ${presentOptional.joinToString()}")
        }
    }

    class MissingLibrariesException(
        val missingLibs: List<String>,
        val suggestion: String
    ) : RuntimeException(
        "Required libraries missing: ${missingLibs.joinToString()}\n\n$suggestion"
    )

    private fun logFileOperation(action: String, lib: String, extra: String? = null, e: Exception? = null) {
        val message = buildString {
            append("$action $lib")
            if (extra != null) append(" $extra")
            if (e != null) append(": ${e.message}")
        }
        if (e != null) Log.w(TAG, message) else Log.d(TAG, message)
    }

    private fun createAssetManager(): AssetManager {
        if (version == null || version.isInstalled) {
            Log.d(TAG, "Using packageContext.assets for installed version")
            return packageContext.assets
        }

        val paths = mutableListOf<String>()
        val baseApk = File(applicationInfo.sourceDir)
        if (baseApk.exists()) {
            paths.add(applicationInfo.sourceDir)
        } else {
            Log.w(TAG, "Base APK for assets not found: ${applicationInfo.sourceDir}")
        }
        applicationInfo.splitSourceDirs?.forEach {
            if (File(it).exists()) {
                paths.add(it)
                Log.d(TAG, "Adding split APK for assets: $it")
            } else {
                Log.w(TAG, "Split APK for assets not found: $it")
            }
        }
        paths.add(context.packageResourcePath)

        return try {
            @Suppress("DEPRECATION")
            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            paths.forEach { path ->
                try {
                    addAssetPath.invoke(assets, path)
                    Log.d(TAG, "Added asset path: $path")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add asset path $path: ${e.message}")
                }
            }
            assets
        } catch (t: Throwable) {
            Log.w(TAG, "Hidden AssetManager API failed, falling back to packageContext.assets: ${t.message}")
            packageContext.assets
        }
    }

    private fun setupSecurityProvider() {
        Log.d(TAG, "Setting up security provider...")
        try {
            java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
        } catch (e: Exception) {
            Log.w(TAG, "Conscrypt init failed: ${e.message}")
        }
    }

    fun loadLibrary(name: String): Boolean {
        val libFile = File(nativeLibDir, if (name.startsWith("lib")) name else "lib$name.so")
        return try {
            if (libFile.exists() && libFile.length() > 0) {
                System.load(libFile.absolutePath)
                Log.d(TAG, "Loaded $name from $nativeLibDir")
                true
            } else {
                try {
                    val systemName = name.removePrefix("lib").removeSuffix(".so")
                    System.loadLibrary(systemName)
                    Log.d(TAG, "Loaded $name via system loader (not found in $nativeLibDir)")
                    true
                } catch (t2: Throwable) {
                    Log.w(TAG, "Library $name not found in $nativeLibDir and not in system path")
                    false
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load $name: ${t.message}")
            false
        }
    }

    private val requiredLibNames: Set<String> by lazy {
        requiredLibs.map { it.removePrefix("lib").removeSuffix(".so") }.toSet()
    }

    fun loadAllLibraries(excludeLibs: Set<String> = emptySet()) {
        val failedRequired = mutableListOf<String>()
        allExtractLibs.forEach { lib ->
            val libName = lib.removePrefix("lib").removeSuffix(".so")
            if (excludeLibs.contains(libName) || excludeLibs.contains(lib)) {
                Log.d(TAG, "Skipping excluded library: $libName")
                return@forEach
            }
            if (!loadLibrary(libName)) {
                Log.w(TAG, "Could not load library: $libName")
                if (requiredLibNames.contains(libName)) {
                    failedRequired.add(lib)
                }
            }
        }
        if (failedRequired.isNotEmpty()) {
            throw LibraryLoadException(failedRequired)
        }
    }

    class LibraryLoadException(val failedLibs: List<String>) : RuntimeException(buildString {
        append("Failed to load required libraries:\n\n")
        failedLibs.forEach { append("  \u2022 $it\n") }
        append("\nSuggested fixes:\n")
        append("  1. Reinstall Minecraft from the Play Store\n")
        append("  2. Clear cache for both Minecraft and this app\n")
        append("  3. Ensure the device supports the required CPU architecture")
    })

    fun getAssets(): AssetManager = assetManager

    fun getPackageContext(): Context = packageContext

    fun getApplicationInfo(): ApplicationInfo = applicationInfo

    fun getVersionName(): String? {
        return try {
            context.packageManager.getPackageInfo(packageContext.packageName, 0).versionName
        } catch (e: Exception) {
            version?.versionCode
        }
    }

    companion object {
        private const val TAG = "GamePackageManager"
        private const val TIMESTAMP_FILE = ".apk_timestamp"

        @Volatile
        private var instance: GamePackageManager? = null

        @JvmStatic
        fun getInstance(context: Context, version: GameVersion? = null): GamePackageManager {
            return synchronized(this) {
                instance ?: GamePackageManager(context.applicationContext, version)
                    .also { instance = it }
            }
        }

        @JvmStatic
        fun clearInstance() {
            synchronized(this) { instance = null }
        }

        fun isInitialized() = instance != null
    }
}
