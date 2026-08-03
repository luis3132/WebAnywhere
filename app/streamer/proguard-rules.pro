# Ktor resolves engines and plugins reflectively via ServiceLoader.
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**

# SLF4J is an optional transitive of Ktor; we ship no binding.
-dontwarn org.slf4j.**

# Ktor/kotlinx reference JVM-only APIs that do not exist on Android. They are
# never reached at runtime, but R8 needs to be told so.
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn org.osgi.**
