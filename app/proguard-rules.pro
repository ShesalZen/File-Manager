# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep,allowoptimization class ai.onnxruntime.** { *; }
-keep,allowoptimization class org.libreoffice.kit.** { *; }
-keep,allowoptimization class org.apache.harmony.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**

# Apache POI & AWT
-dontwarn java.awt.**
-dontwarn org.apache.poi.**

# DocxWebRenderer print callbacks and JS interface
-keep class android.print.OpenLayoutResultCallback { *; }
-keep class android.print.OpenWriteResultCallback { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WorkManager Room DB
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# WorkManager recreates workers by their persisted class name. Keep worker class
# names and constructors stable across app updates while allowing implementation
# methods to be optimized.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# App-owned Gson models are persisted in preferences and JSON files. Preserve their
# classes, field names, and enum-constant names for upgrade compatibility, while
# allowing R8 to remove unused model methods and unrelated app code.
-keep,allowoptimization class com.zenlabs.filemanager.common.LogHolder { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.model.GithubRelease { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.model.GithubReleaseAsset { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.startup.StartupTabs { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.startup.StartupTab { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.startup.StartupTabType { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.startup.PlusButtonOverride { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.files.misc.DefaultOpeningMethods { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.files.misc.OpeningMethod { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.files.misc.FileSortingPrefs { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.files.misc.RecentOpenWithApps { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.files.misc.RecentOpenWithEntry { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.files.misc.ViewConfigs { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.files.misc.ViewType { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.files.service.FolderLockType { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.files.service.FolderLockEntry { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.files.service.VaultFileRecord { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.files.service.remote.NetworkConnectionModel { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.files.task.ScheduledFileTask { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.files.ui.BottomBarActionConfig { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.home.data.HomeLayout { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.home.data.HomeSectionConfig { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.main.tab.home.data.HomeSectionType { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.preferences.SaveLocationHistoryItem { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.preferences.misc.PreferenceItem { <fields>; }
-keep,allowoptimization class com.zenlabs.filemanager.screen.textEditor.holder.SymbolHolder { <fields>; }

-keep,allowoptimization class org.eclipse.tm4e.** { *; }
-keep,allowoptimization class org.joni.** { *; }
-keep class android.content.** { *; }
-keep,allowoptimization class com.android.apksig.** { *; }

-keepnames interface * { *; }

# Suppress warnings from missing classes in PDFBox and SSHJ/EdDSA
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
-dontwarn sun.security.x509.X509Key

# BouncyCastle Cryptographic Provider (required for SSHJ KeyPairGenerator and Ciphers)
-keep,allowoptimization class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# SSHJ & Crypto Dependencies
-keep,allowoptimization class net.schmizz.sshj.** { *; }
-dontwarn net.schmizz.sshj.**
-keep,allowoptimization class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-keep,allowoptimization class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**
-keep,allowoptimization class com.jcraft.jzlib.** { *; }
-dontwarn com.jcraft.jzlib.**
-dontwarn org.slf4j.**
-dontwarn javax.security.auth.kerberos.**

# jCIFS-NG
-keep,allowoptimization class jcifs.** { *; }
-dontwarn jcifs.**
-dontwarn javax.annotation.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Markwon
-dontwarn pl.droidsonroids.gif.GifDrawable
-dontwarn io.noties.markwon.**