# kotlinx-serialization: keep generated serializers of our @Serializable models.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class alarmclock.vanbaelen.dev.** {
    *** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class alarmclock.vanbaelen.dev.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class alarmclock.vanbaelen.dev.**$$serializer { *; }
-keepclassmembers class alarmclock.vanbaelen.dev.** {
    *** Companion;
}

# Room: entities and DAOs are generated; keep the database implementation constructor.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }

# WorkManager instantiates workers reflectively.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# OkHttp optional platform classes.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
