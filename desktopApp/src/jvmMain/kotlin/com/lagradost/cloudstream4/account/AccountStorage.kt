package com.lagradost.cloudstream4.account

import java.io.File

/**
 * Where a build keeps its PINs and sign-ins (`KMP-95f`). Which one is decided once, at build time, by [forBuild]'s
 * `signed`: the app passes `DesktopSecrets.signedBuild`, a constant `generateDesktopSecrets` writes from the signing
 * properties, so nothing at runtime (an env var, a file, a flag) can switch a signed build to the unsigned stores.
 *
 * - **Signed:** the Keychain for both ([MacKeychain]). There is no path from here to [InMemorySignInStore] or to D1's
 *   PIN file as a store: a Keychain that fails means no sign-in, and a locked profile (the PM, 2026-11-09).
 * - **Unsigned** (`./gradlew run`, a test): `KMP-95d`'s and `95c`'s stores, unchanged. Sign-ins in memory until quit, and
 *   the window says so; the PINs in D1's owner-only file. The Keychain is never touched: an unsigned process has no
 *   access group, and would only be refused.
 */
internal class AccountStorage private constructor(val pins: DesktopPins, val signIns: SignInStore) {
    override fun toString(): String = "AccountStorage(<hidden>)"

    companion object {
        /**
         * @param vault made only when [signed]; a test passes a fake, the app the Keychain.
         * @param directory where the PIN-attempts file lives, and D1's PIN file on an unsigned build.
         */
        fun forBuild(
            signed: Boolean,
            directory: File,
            onSaveFailed: (Throwable) -> Unit = {},
            clock: () -> Long = { System.nanoTime() / 1_000_000L },
            vault: () -> SecretVault = { MacKeychain() },
        ): AccountStorage = if (signed) {
            val keychain = vault()
            AccountStorage(
                DesktopPins(directory, clock = clock, onSaveFailed = onSaveFailed, vault = keychain),
                KeychainSignInStore(keychain, onSaveFailed),
            )
        } else {
            AccountStorage(DesktopPins(directory, clock = clock, onSaveFailed = onSaveFailed), InMemorySignInStore())
        }
    }
}
