# Settings fragments live in external DEX files. R8 cannot see their calls into these UI libraries.
-keep class androidx.fragment.app.** { *; }
-keep class androidx.appcompat.** { *; }
-keep class androidx.recyclerview.widget.** { *; }
-keep class com.google.android.material.** { *; }
