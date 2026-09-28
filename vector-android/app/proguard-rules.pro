# Vector — R8 rules.
#
# The app is mostly Compose and Kotlin, which R8 handles without help. The
# rules below cover the three places where something reflects, crosses a JNI
# boundary, or is deserialised by name — the cases R8 cannot see.

# ---- MapLibre GL -----------------------------------------------------------
#
# MapLibre's Java layer is called FROM native code. Obfuscating or stripping
# those classes produces a NoSuchMethodError at style load, not a build error,
# so this is a keep rather than a shrink.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**

# ---- RevenueCat ------------------------------------------------------------
#
# Purchases models are deserialised from the RevenueCat API by field name.
-keep class com.revenuecat.purchases.** { *; }
-dontwarn com.revenuecat.purchases.**

# ---- Google Play Services location ----------------------------------------
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**

# ---- Android Auto / Car App Library ---------------------------------------
#
# The host resolves the service and its screens by name from the manifest.
-keep class androidx.car.app.** { *; }
-keep class dev.vector.android.car.** { *; }
-dontwarn androidx.car.app.**

# ---- org.json --------------------------------------------------------------
#
# The bundled org.json shadows the platform one; keep it whole rather than
# letting R8 pick between two identically-named class trees.
-keep class org.json.** { *; }

# ---- Kotlin coroutines -----------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---- Keep line numbers so a crash report is still readable -----------------
#
# Vector ships no crash reporter, so the only person who will ever read a stack
# trace is whoever is holding the phone. Costs a little size; worth it.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
