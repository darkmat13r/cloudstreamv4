/**
 * `:core:data` — persistence: today the storage key constants of `DataStore` and `DataStoreHelper`.
 *
 * ## FQN and facade freeze: these are published extension API
 *
 * Every declaration here keeps the `com.lagradost.cloudstream3.*` fully-qualified name it had in
 * `:app`, and every file keeps its name: `DataStore.kt` compiles to the facade `DataStoreKt` and
 * `DataStoreHelper.kt` to `DataStoreHelperKt`. `:app:makeJar` merges this module's `jvm` jar into
 * `app/build/classes.jar`, so a rename of a file or its package breaks every published extension.
 * Move files; never rename them or their package. When you move an extension-facing declaration
 * here, add a sentinel to `cloudstream.extension-api-jar` in the same change.
 *
 * ## `:app`'s `DataStore.kt` and `DataStoreHelper.kt` must never again declare top-level members
 *
 * Those `:app` files keep their names (`object DataStore`, `object DataStoreHelper` and their
 * classes are still there), so a top-level `fun`, `val` or `const` added to either one makes `:app`
 * emit a second `DataStoreKt` / `DataStoreHelperKt` facade. The build fails on it (D8's dex merge
 * reports the type as defined twice; `makeJar`'s `DuplicatesStrategy.FAIL` would too). Put a new
 * top-level member in this module's file of the same name, or in an object.
 *
 * ## Constants are inlined, the facade is not optional
 *
 * `const val` values are inlined into the extensions that use them at compile time, so a built
 * extension does not load these facades to read a key. The facade classes and their
 * `public static final String` fields stay part of the jar all the same: the compiler resolves the
 * constant from that field when an extension is built. Removing or renaming a constant breaks the
 * next build of every extension that uses it.
 *
 * ## Source sets and dependencies
 *
 * Set up by `cloudstream.kmp.extension-api` — see that plugin's KDoc.
 *
 * - `commonMain` — the constants are pure Kotlin `String`s. No dependencies.
 */
plugins {
    id("cloudstream.kmp.extension-api")
}
