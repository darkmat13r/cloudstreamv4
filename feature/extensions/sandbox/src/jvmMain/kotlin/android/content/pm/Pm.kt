package android.content.pm

/**
 * Empty, and `Context` has no `getPackageManager()`, so a plugin can name this and cannot get one.
 * Listing installed packages is refused outright by `ExtensionScanner`.
 */
abstract class PackageManager
