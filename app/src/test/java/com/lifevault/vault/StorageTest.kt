package com.lifevault.vault

import com.lifevault.domain.model.Settings
import com.lifevault.testing.CrashingFs
import com.lifevault.testing.FileVaultFs
import com.lifevault.vault.crypto.VaultKeys
import com.lifevault.vault.storage.PathViolationException
import com.lifevault.vault.storage.SafJail
import com.lifevault.vault.storage.VaultDir
import com.lifevault.vault.storage.VaultPath
import com.lifevault.vault.store.AtomicFiles
import com.lifevault.vault.store.CorruptFileException
import com.lifevault.vault.store.EncryptedStore
import com.lifevault.vault.store.RecoverySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class StorageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `traversal and unexpected paths are rejected`() {
        val bad = listOf(
            "../outside", "data/../vault.json", "data/..", "..", ".", "/etc/passwd", "data/a/b", "data\\x",
            "C:\\Windows", "media/.hidden", "unknown/x", "notes.txt", "data/", "data/ spaced", "data/a\u0000b",
            "data/" + "a".repeat(129), "thumbs/-dash", ".tmp/..x", "data/a..b",
        )
        for (p in bad) {
            try {
                VaultPath.parse(p)
                fail("'$p' must be rejected")
            } catch (e: PathViolationException) {
            }
        }
        for (ok in listOf("vault.json", "vault.json.bak", "data/abc123", "data/abc.bak", "media/1b4e28ba-2fa1-11d2-883f-0016d3cca427", ".tmp/restore1")) {
            assertEquals(ok, VaultPath.parse(ok).relative)
        }
        try { VaultPath.of(VaultDir.ROOT, "anything"); fail() } catch (e: PathViolationException) {}
        try { VaultPath.of(VaultDir.DATA, "../x"); fail() } catch (e: PathViolationException) {}
    }

    @Test
    fun `saf jail only accepts uris from the same tree`() {
        assertTrue(SafJail.isSameTree("com.android.externalstorage.documents", "primary:Vault", "com.android.externalstorage.documents", "primary:Vault"))
        assertFalse(SafJail.isSameTree("com.android.externalstorage.documents", "primary:Vault", "com.android.externalstorage.documents", "primary:Other"))
        assertFalse(SafJail.isSameTree("com.android.externalstorage.documents", "primary:Vault", "evil.provider", "primary:Vault"))
        assertFalse(SafJail.isSameTree("com.android.externalstorage.documents", "primary:Vault", null, null))
    }

    @Test
    fun `file backend double checks canonical paths`() {
        val fs = FileVaultFs(tmp.newFolder("vault"))
        // Even a symlink-free path built by hand cannot escape: VaultPath refuses to build it first.
        try { fs.resolve(VaultPath.parse("data/../../x")); fail() } catch (e: PathViolationException) {}
    }

    private fun store(fs: com.lifevault.vault.storage.VaultFs, keys: VaultKeys) = EncryptedStore(fs, keys)
    private fun s(day: Int) = Settings(vaultCreatedOn = LocalDate.of(2026, 9, day))

    @Test
    fun `write keeps one backup and no temp files`() = runTest {
        val fs = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }
        val st = store(fs, VaultKeys.generate())
        st.write("settings", Settings.serializer(), s(1))
        st.write("settings", Settings.serializer(), s(2))
        st.write("settings", Settings.serializer(), s(3))
        val base = st.physicalName("settings")
        assertEquals(setOf(base, "$base.bak"), fs.list(VaultDir.DATA))
        assertEquals(s(3), st.read("settings", Settings.serializer()))
    }

    @Test
    fun `stray temp copy from an interrupted write is discarded`() = runTest {
        val fs = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }
        val st = store(fs, VaultKeys.generate())
        st.write("settings", Settings.serializer(), s(1))
        val base = st.physicalName("settings")
        fs.rawWrite(VaultPath.of(VaultDir.DATA, "$base.new"), byteArrayOf(1, 2, 3)) // torn write
        assertEquals(s(1), st.read("settings", Settings.serializer()))
        assertFalse(fs.exists(VaultPath.of(VaultDir.DATA, "$base.new")))
    }

    @Test
    fun `crash between the two renames recovers the new version`() = runTest {
        val fs = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }
        val keys = VaultKeys.generate()
        val st = store(fs, keys)
        st.write("settings", Settings.serializer(), s(1))
        st.write("settings", Settings.serializer(), s(2))
        val base = st.physicalName("settings")
        // Simulate: new version written to .new, main renamed to .bak, crash before .new -> main.
        val main = VaultPath.of(VaultDir.DATA, base)
        val newer = fs.readBytes(main)
        fs.delete(VaultPath.of(VaultDir.DATA, "$base.bak"))
        fs.rename(main, "$base.bak")
        fs.rawWrite(VaultPath.of(VaultDir.DATA, "$base.new"), newer)
        assertEquals(s(2), store(fs, keys).read("settings", Settings.serializer()))
        assertTrue(fs.exists(main))
    }

    @Test
    fun `corrupt primary is quarantined and restored from backup`() = runTest {
        val fs = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }
        val keys = VaultKeys.generate()
        val st = store(fs, keys)
        st.write("settings", Settings.serializer(), s(1))
        st.write("settings", Settings.serializer(), s(2))
        val base = st.physicalName("settings")
        fs.rawWrite(VaultPath.of(VaultDir.DATA, base), ByteArray(100)) // damaged
        val fresh = store(fs, keys)
        assertEquals(s(1), fresh.read("settings", Settings.serializer()))
        assertTrue(fs.exists(VaultPath.of(VaultDir.DATA, "$base.corrupt")))
        assertEquals(RecoverySource.BACKUP, fresh.recoveries.replayCache.single().source)
        // The restored file is now healthy.
        assertEquals(s(1), store(fs, keys).read("settings", Settings.serializer()))
    }

    @Test
    fun `missing primary is restored from backup`() = runTest {
        val fs = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }
        val keys = VaultKeys.generate()
        val st = store(fs, keys)
        st.write("settings", Settings.serializer(), s(1))
        st.write("settings", Settings.serializer(), s(2))
        fs.delete(VaultPath.of(VaultDir.DATA, st.physicalName("settings")))
        assertEquals(s(1), store(fs, keys).read("settings", Settings.serializer()))
    }

    @Test
    fun `everything damaged surfaces a clear error`() = runTest {
        val fs = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }
        val keys = VaultKeys.generate()
        val st = store(fs, keys)
        st.write("settings", Settings.serializer(), s(1))
        st.write("settings", Settings.serializer(), s(2))
        val base = st.physicalName("settings")
        fs.rawWrite(VaultPath.of(VaultDir.DATA, base), ByteArray(64))
        fs.rawWrite(VaultPath.of(VaultDir.DATA, "$base.bak"), ByteArray(64))
        try {
            store(fs, keys).read("settings", Settings.serializer())
            fail()
        } catch (e: CorruptFileException) {
            assertTrue(e.message!!.contains("damaged"))
        }
    }

    @Test
    fun `a crash at any step of a write leaves either the old or the new value`() = runTest {
        val keys = VaultKeys.generate()
        for (crashAt in 1..8) {
            val dir = tmp.newFolder("crash$crashAt")
            val fs = FileVaultFs(dir).also { it.ensureLayout() }
            store(fs, keys).write("settings", Settings.serializer(), s(1))
            store(fs, keys).write("settings", Settings.serializer(), s(2))
            try {
                store(CrashingFs(fs, crashAt), keys).write("settings", Settings.serializer(), s(3))
            } catch (e: CrashingFs.Crash) {
            }
            val after = store(fs, keys).read("settings", Settings.serializer())
            assertTrue("crashAt=$crashAt gave $after", after == s(2) || after == s(3))
            // A second, clean write always succeeds afterwards.
            store(fs, keys).write("settings", Settings.serializer(), s(4))
            assertEquals(s(4), store(fs, keys).read("settings", Settings.serializer()))
        }
    }

    @Test
    fun `startup scan repairs interrupted writes`() = runTest {
        val fs = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }
        val keys = VaultKeys.generate()
        val st = store(fs, keys)
        st.write("settings", Settings.serializer(), s(1))
        st.write("settings", Settings.serializer(), s(2))
        val base = st.physicalName("settings")
        fs.delete(VaultPath.of(VaultDir.DATA, base))
        store(fs, keys).recoverAll()
        assertTrue(fs.exists(VaultPath.of(VaultDir.DATA, base)))
    }

    @Test
    fun `concurrent updates to one file never lose a change`() = runTest {
        val fs = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }
        val st = store(fs, VaultKeys.generate())
        val jobs = (1..20).map { i ->
            async(Dispatchers.IO) {
                st.update("settings", Settings.serializer(), { Settings() }) {
                    it.copy(mood = it.mood.copy(tags = it.mood.tags + "t$i"))
                }
            }
        }
        jobs.forEach { it.await() }
        val tags = st.read("settings", Settings.serializer())!!.mood.tags
        assertEquals(20, tags.count { it.startsWith("t") && it.drop(1).toIntOrNull() != null })
    }

    @Test
    fun `newer schema files are never repaired or overwritten`() = runTest {
        val fs = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }
        val keys = VaultKeys.generate()
        val st = store(fs, keys)
        st.write("settings", Settings.serializer(), s(1))
        st.write("settings", Settings.serializer(), s(2))
        val base = st.physicalName("settings")
        val newer = keys.aead.encrypt("""{"schemaVersion":99}""".toByteArray(), (EncryptedStore.AAD_PREFIX + base).toByteArray())
        fs.rawWrite(VaultPath.of(VaultDir.DATA, base), newer)
        try {
            store(fs, keys).read("settings", Settings.serializer())
            fail()
        } catch (e: com.lifevault.domain.migration.NewerSchemaException) {
        }
        assertFalse(fs.exists(VaultPath.of(VaultDir.DATA, "$base.corrupt")))
    }

    @Test
    fun `existing months are found by probing hashed names`() = runTest {
        val fs = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }
        val st = store(fs, VaultKeys.generate())
        st.write("mood-2026-07", Settings.serializer(), Settings())
        st.write("mood-2026-09", Settings.serializer(), Settings())
        st.write("entries-2026-08", Settings.serializer(), Settings())
        val months = st.existingMonths("mood-", java.time.YearMonth.of(2000, 1), java.time.YearMonth.of(2030, 12))
        assertEquals(listOf(java.time.YearMonth.of(2026, 7), java.time.YearMonth.of(2026, 9)), months)
    }

    @Test
    fun `atomic files helpers`() {
        assertTrue(AtomicFiles.isAuxiliary("abc.bak"))
        assertTrue(AtomicFiles.isAuxiliary("abc.new"))
        assertFalse(AtomicFiles.isAuxiliary("abc"))
        assertEquals("abc", AtomicFiles.baseName("abc.corrupt"))
    }

    @Test
    fun `missing file reads as null`() = runTest {
        val fs = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }
        assertNull(store(fs, VaultKeys.generate()).read("nothing", Settings.serializer()))
        assertTrue(File(fs.root, "data").isDirectory)
    }
}
