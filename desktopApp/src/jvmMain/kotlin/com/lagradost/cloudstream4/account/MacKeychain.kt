package com.lagradost.cloudstream4.account

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * The Mac's Keychain through Security.framework, over JNA 5.19.1 (`KMP-95f`; the owner approved JNA for this,
 * 2026-11-08). Only a signed build makes one (`AccountStorage.forBuild`); nothing else, a test included, reaches it.
 *
 * - **Scoped to this app** (the PM, 2026-11-09): the data-protection keychain (`kSecUseDataProtectionKeychain`), whose
 *   items belong to an access group. The app's entitlements name one group, `<Team ID>.com.lagradost.cloudstream4`, and
 *   an item goes in the first group named, so another app, or this one unsigned, cannot read it. An unsigned process
 *   is refused (`errSecMissingEntitlement`), which reads as [VaultRead.Failed]: it never falls back to anything.
 * - **This Mac only, and only while it is unlocked** (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`): never in a
 *   backup or iCloud Keychain, as `DS-25` keeps sign-ins out of every backup.
 * - **Nothing is logged at the default log level.** Not the item's name, its value, or the status code: a failure is
 *   only false or Failed.
 *
 * Generic-password items, under [service], each named by its account. Every call is short and blocks the caller.
 */
internal class MacKeychain(private val service: String = SERVICE) : SecretVault {
    override fun read(name: String): VaultRead = runCatching {
        val api = Api.instance ?: return VaultRead.Failed
        api.withQuery(service, name, returnData = true) { query ->
            val result = PointerByReference()
            when (api.security.SecItemCopyMatching(query, result)) {
                ERR_SUCCESS -> {
                    val data = result.value ?: return@withQuery VaultRead.Failed
                    try {
                        api.dataBytes(data)?.let(VaultRead::Found) ?: VaultRead.Failed
                    } finally {
                        api.cf.CFRelease(data)
                    }
                }
                ERR_ITEM_NOT_FOUND -> VaultRead.Absent
                else -> VaultRead.Failed
            }
        }
    }.getOrDefault(VaultRead.Failed)

    override fun write(name: String, value: ByteArray): Boolean = runCatching {
        if (value.size > MAX_BYTES) return false
        val api = Api.instance ?: return false
        val data = api.cf.CFDataCreate(null, value, NativeLong(value.size.toLong())) ?: return false
        try {
            api.withQuery(service, name, returnData = false) { query ->
                val change = api.dictionary(listOf(api.kSecValueData to data)) ?: return@withQuery false
                val updated = try {
                    api.security.SecItemUpdate(query, change)
                } finally {
                    api.cf.CFRelease(change)
                }
                when (updated) {
                    ERR_SUCCESS -> true
                    ERR_ITEM_NOT_FOUND -> api.add(service, name, data)
                    else -> false
                }
            }
        } finally {
            api.cf.CFRelease(data)
        }
    }.getOrDefault(false)

    override fun delete(name: String): Boolean = runCatching {
        val api = Api.instance ?: return false
        api.withQuery(service, name, returnData = false) { query ->
            when (api.security.SecItemDelete(query)) {
                ERR_SUCCESS, ERR_ITEM_NOT_FOUND -> true
                else -> false
            }
        }
    }.getOrDefault(false)

    override fun toString(): String = "MacKeychain(<hidden>)"

    @Suppress("FunctionName")
    private interface CoreFoundation : Library {
        fun CFStringCreateWithBytes(alloc: Pointer?, bytes: ByteArray, numBytes: NativeLong, encoding: Int, isExternal: Byte): Pointer?
        fun CFDataCreate(alloc: Pointer?, bytes: ByteArray, length: NativeLong): Pointer?
        fun CFDataGetLength(data: Pointer): NativeLong
        fun CFDataGetBytePtr(data: Pointer): Pointer?
        fun CFGetTypeID(cf: Pointer): NativeLong
        fun CFDataGetTypeID(): NativeLong
        fun CFDictionaryCreate(
            alloc: Pointer?,
            keys: Array<Pointer>,
            values: Array<Pointer>,
            count: NativeLong,
            keyCallBacks: Pointer,
            valueCallBacks: Pointer,
        ): Pointer?
        fun CFRelease(cf: Pointer)
    }

    @Suppress("FunctionName")
    private interface Security : Library {
        fun SecItemAdd(attributes: Pointer, result: Pointer?): Int
        fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int
        fun SecItemUpdate(query: Pointer, attributesToUpdate: Pointer): Int
        fun SecItemDelete(query: Pointer): Int
    }

    /** The two frameworks and the constants read from them, loaded once, on a Mac only. */
    private class Api private constructor() {
        val cf: CoreFoundation = Native.load("CoreFoundation", CoreFoundation::class.java)
        val security: Security = Native.load("Security", Security::class.java)
        private val cfLibrary = NativeLibrary.getInstance("CoreFoundation")
        private val secLibrary = NativeLibrary.getInstance("Security")

        /** A `CFStringRef` (or other CF object) exported by Security.framework. */
        private fun sec(name: String): Pointer = checkNotNull(secLibrary.getGlobalVariableAddress(name).getPointer(0))

        val kSecClass = sec("kSecClass")
        val kSecClassGenericPassword = sec("kSecClassGenericPassword")
        val kSecAttrService = sec("kSecAttrService")
        val kSecAttrAccount = sec("kSecAttrAccount")
        val kSecAttrAccessible = sec("kSecAttrAccessible")
        val kSecAttrAccessibleWhenUnlockedThisDeviceOnly = sec("kSecAttrAccessibleWhenUnlockedThisDeviceOnly")
        val kSecUseDataProtectionKeychain = sec("kSecUseDataProtectionKeychain")
        val kSecValueData = sec("kSecValueData")
        val kSecReturnData = sec("kSecReturnData")
        val kSecMatchLimit = sec("kSecMatchLimit")
        val kSecMatchLimitOne = sec("kSecMatchLimitOne")
        private val kCFBooleanTrue: Pointer = checkNotNull(cfLibrary.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0))

        /** The callback structs themselves, by address, not dereferenced. */
        private val keyCallBacks: Pointer = cfLibrary.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks")
        private val valueCallBacks: Pointer = cfLibrary.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks")

        fun string(text: String): Pointer? {
            val bytes = text.toByteArray(Charsets.UTF_8)
            return cf.CFStringCreateWithBytes(null, bytes, NativeLong(bytes.size.toLong()), UTF8, 0)
        }

        /** A dictionary that retains its keys and values; the caller releases it. */
        fun dictionary(entries: List<Pair<Pointer, Pointer>>): Pointer? = cf.CFDictionaryCreate(
            null,
            entries.map { it.first }.toTypedArray(),
            entries.map { it.second }.toTypedArray(),
            NativeLong(entries.size.toLong()),
            keyCallBacks,
            valueCallBacks,
        )

        /** Runs [block] with the query for one item: its class, [service], [name], and the data-protection keychain. */
        fun <T> withQuery(service: String, name: String, returnData: Boolean, block: (Pointer) -> T): T {
            val serviceRef = checkNotNull(string(service))
            val nameRef = string(name)
            try {
                checkNotNull(nameRef)
                val entries = mutableListOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to serviceRef,
                    kSecAttrAccount to nameRef,
                    kSecUseDataProtectionKeychain to kCFBooleanTrue,
                )
                if (returnData) {
                    entries += kSecReturnData to kCFBooleanTrue
                    entries += kSecMatchLimit to kSecMatchLimitOne
                }
                val query = checkNotNull(dictionary(entries))
                try {
                    return block(query)
                } finally {
                    cf.CFRelease(query)
                }
            } finally {
                nameRef?.let(cf::CFRelease)
                cf.CFRelease(serviceRef)
            }
        }

        /** A new item: this Mac only, while unlocked. */
        fun add(service: String, name: String, data: Pointer): Boolean {
            val serviceRef = string(service) ?: return false
            val nameRef = string(name)
            try {
                nameRef ?: return false
                val item = dictionary(
                    listOf(
                        kSecClass to kSecClassGenericPassword,
                        kSecAttrService to serviceRef,
                        kSecAttrAccount to nameRef,
                        kSecUseDataProtectionKeychain to kCFBooleanTrue,
                        kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                        kSecValueData to data,
                    ),
                ) ?: return false
                try {
                    return security.SecItemAdd(item, null) == ERR_SUCCESS
                } finally {
                    cf.CFRelease(item)
                }
            } finally {
                nameRef?.let(cf::CFRelease)
                cf.CFRelease(serviceRef)
            }
        }

        /** The bytes of a `CFData`, or null when it is not one or is larger than [MAX_BYTES]. */
        fun dataBytes(data: Pointer): ByteArray? {
            if (cf.CFGetTypeID(data) != cf.CFDataGetTypeID()) return null
            val length = cf.CFDataGetLength(data).toLong()
            if (length !in 0..MAX_BYTES.toLong()) return null
            if (length == 0L) return ByteArray(0)
            return cf.CFDataGetBytePtr(data)?.getByteArray(0, length.toInt())
        }

        companion object {
            /** `kCFStringEncodingUTF8`. */
            private const val UTF8 = 0x08000100

            /** Null off a Mac, or when the frameworks cannot be loaded: every call is then a failure. */
            val instance: Api? by lazy {
                if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) null else runCatching { Api() }.getOrNull()
            }
        }
    }

    internal companion object {
        /** The items' service: the app's bundle id, which the packaged app is signed with. */
        const val SERVICE = "com.lagradost.cloudstream4"

        /** The largest secret kept or read back: as the PINs file's limit. */
        const val MAX_BYTES = 64 * 1024

        private const val ERR_SUCCESS = 0
        private const val ERR_ITEM_NOT_FOUND = -25300
    }
}
