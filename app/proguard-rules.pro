# Tarumi release R8 rules — minify + obfuscate (no -dontobfuscate).

-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# Kotlin null-check noise
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void checkExpressionValueIsNotNull(...);
	public static void checkNotNullExpressionValue(...);
	public static void checkReturnedValueIsNotNull(...);
	public static void checkFieldIsNotNull(...);
	public static void checkParameterIsNotNull(...);
	public static void checkNotNullParameter(...);
}

# ---- Android / general ----
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exception*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Application / Activities / Services / Receivers / Providers
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends androidx.fragment.app.Fragment

# Parcelable
-keep class * implements android.os.Parcelable {
	public static final ** CREATOR;
}

# Enums
-keepclassmembers enum * {
	public static **[] values();
	public static ** valueOf(java.lang.String);
}

# ---- Hilt / Dagger ----
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclasseswithmembers class * {
	@dagger.* <methods>;
}
-keep class **_HiltModules { *; }
-keep class **_HiltModules$* { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory

# ---- ViewBinding / DataBinding ----
-keep class * implements androidx.viewbinding.ViewBinding {
	public static * inflate(...);
	public static * bind(...);
}

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---- Kotlin serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
	kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.koitharu.kotatsu.**$$serializer { *; }
-keepclassmembers class org.koitharu.kotatsu.** {
	*** Companion;
}
-keepclasseswithmembers class org.koitharu.kotatsu.** {
	kotlinx.serialization.KSerializer serializer(...);
}

# ---- Parsers (reflection / KSP factory) — must stay intact ----
-keep class org.koitharu.kotatsu.parsers.** { *; }
-keepclassmembers class org.koitharu.kotatsu.parsers.** { *; }
-dontwarn org.koitharu.kotatsu.parsers.**
-dontwarn org.koitharu.kotatsu.parsers.site.mangareader.id.KomikIndo

# ---- OkHttp / Okio ----
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.j2objc.annotations.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---- Coil ----
-dontwarn coil3.PlatformContext
-keep class coil3.** { *; }

# ---- WorkManager ----
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
	public <init>(android.content.Context,androidx.work.WorkerParameters);
}

# ---- MediaPipe / GenAI ----
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**

# ---- ACRA ----
-keep class org.acra.** { *; }
-keep class org.acra.security.NoKeyStoreFactory { *; }
-keep class org.acra.config.DefaultRetryPolicy { *; }
-keep class org.acra.attachment.DefaultAttachmentProvider { *; }
-keep class org.acra.sender.JobSenderService

# ---- Jsoup ----
-keep class org.jsoup.** { *; }
-keep class org.jsoup.parser.Tag
-keep class org.jsoup.internal.StringUtil

# ---- App-specific reflection / settings fragments ----
-keep class org.koitharu.kotatsu.settings.NotificationSettingsLegacyFragment
-keep class org.koitharu.kotatsu.settings.about.changelog.ChangelogFragment
-keep class org.koitharu.kotatsu.backups.ui.periodical.PeriodicalBackupSettingsFragment { *; }
-keep class org.koitharu.kotatsu.core.exceptions.** { *; }
-keep class org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy { *; }

# Native methods
-keepclasseswithmembernames class * {
	native <methods>;
}

# R8 full mode extras
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn javax.lang.model.**
-dontwarn com.google.j2objc.**
