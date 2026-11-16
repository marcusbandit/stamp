# commons-compress refers to optional codecs (zstd, xz, brotli) that Stamp never ships or calls:
# the Google Photos import reads plain zip entries only.
-dontwarn com.github.luben.zstd.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**

# WorkManager (the hourly sample) opens its Room database by reflection on the generated class's
# no-argument constructor, which R8 otherwise removes: the release build crashed on launch.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
