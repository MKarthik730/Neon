package com.lifevault.vault.storage

/** Any attempt to reach a file that is not inside the vault's fixed layout. */
class PathViolationException(message: String) : SecurityException(message)

/** The fixed vault layout. Nothing may be read or written anywhere else. */
enum class VaultDir(val dirName: String?) {
    ROOT(null),
    DATA("data"),
    MEDIA("media"),
    THUMBS("thumbs"),
    TMP(".tmp"),
    ;

    companion object {
        val subdirs = entries.filter { it.dirName != null }
        fun byName(name: String): VaultDir? = subdirs.firstOrNull { it.dirName == name }
    }
}

/**
 * A validated location inside the vault: one of the fixed directories plus a flat file name.
 * The only way to build one is through [of] / [parse], which reject traversal (`..`), separators,
 * absolute paths, hidden names, unknown directories and unexpected files in the vault root.
 */
class VaultPath private constructor(val dir: VaultDir, val name: String) {

    val relative: String get() = dir.dirName?.let { "$it/$name" } ?: name

    fun sibling(newName: String): VaultPath = of(dir, newName)

    override fun equals(other: Any?) = other is VaultPath && other.dir == dir && other.name == name
    override fun hashCode() = dir.hashCode() * 31 + name.hashCode()
    override fun toString() = relative

    companion object {
        const val HEADER = "vault.json"
        val ROOT_FILES = setOf(HEADER, "$HEADER.new", "$HEADER.bak")
        private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

        fun of(dir: VaultDir, name: String): VaultPath {
            validateName(name)
            if (dir == VaultDir.ROOT && name !in ROOT_FILES) {
                throw PathViolationException("Only vault.json may live in the vault root, not '$name'")
            }
            return VaultPath(dir, name)
        }

        /** Parses `data/abc` or `vault.json`. Anything else throws [PathViolationException]. */
        fun parse(relative: String): VaultPath {
            if (relative.isEmpty() || relative.startsWith("/") || relative.contains('\\') || relative.contains('\u0000')) {
                throw PathViolationException("Invalid vault path '$relative'")
            }
            val parts = relative.split('/')
            return when (parts.size) {
                1 -> of(VaultDir.ROOT, parts[0])
                2 -> of(VaultDir.byName(parts[0]) ?: throw PathViolationException("Unknown vault directory '${parts[0]}'"), parts[1])
                else -> throw PathViolationException("Nested paths are not allowed: '$relative'")
            }
        }

        fun validateName(name: String) {
            if (!NAME.matches(name) || name.contains("..")) {
                throw PathViolationException("Invalid file name '$name'")
            }
        }

        fun header() = of(VaultDir.ROOT, HEADER)
    }
}
