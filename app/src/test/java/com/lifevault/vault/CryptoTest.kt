package com.lifevault.vault

import com.lifevault.domain.model.Settings
import com.lifevault.testing.FileVaultFs
import com.lifevault.vault.crypto.Argon2Kdf
import com.lifevault.vault.crypto.KdfCost
import com.lifevault.vault.crypto.RecoveryCode
import com.lifevault.vault.crypto.VaultCrypto
import com.lifevault.vault.crypto.VaultKeys
import com.lifevault.vault.crypto.VaultLockedException
import com.lifevault.vault.crypto.WrongSecretException
import com.lifevault.vault.storage.VaultDir
import com.lifevault.vault.storage.VaultPath
import com.lifevault.vault.store.BlobStore
import com.lifevault.vault.store.CorruptFileException
import com.lifevault.vault.store.EncryptedStore
import com.lifevault.vault.store.HeaderStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.Random

class CryptoTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fastKdf = KdfCost(memoryKiB = 1024, iterations = 1)

    private fun newFs() = FileVaultFs(tmp.newFolder("vault")).also { it.ensureLayout() }

    @Test
    fun `argon2id is deterministic and salt sensitive`() {
        val p = Argon2Kdf.newParams(fastKdf)
        val a = Argon2Kdf.derive("correct horse".toByteArray(), p)
        val b = Argon2Kdf.derive("correct horse".toByteArray(), p)
        val c = Argon2Kdf.derive("correct horse".toByteArray(), Argon2Kdf.newParams(fastKdf))
        assertArrayEquals(a, b)
        assertFalse(a.contentEquals(c))
        assertEquals(32, a.size)
    }

    @Test
    fun `passphrases are normalised so composed and decomposed forms match`() {
        val composed = "café passphrase"
        val decomposed = "café passphrase"
        assertArrayEquals(Argon2Kdf.passphraseBytes(composed), Argon2Kdf.passphraseBytes(decomposed))
    }

    @Test
    fun `aead round trip through the store`() = runTest {
        val fs = newFs()
        val keys = VaultKeys.generate()
        val store = EncryptedStore(fs, keys)
        val s = Settings(attendance = Settings().attendance.copy(thresholdPercent = 80))
        store.write("settings", Settings.serializer(), s)
        assertEquals(s, store.read("settings", Settings.serializer()))
        assertNull(store.read("missing", Settings.serializer()))
    }

    @Test
    fun `modified ciphertext is detected`() = runTest {
        val fs = newFs()
        val keys = VaultKeys.generate()
        val store = EncryptedStore(fs, keys)
        store.write("settings", Settings.serializer(), Settings())
        val path = VaultPath.of(VaultDir.DATA, store.physicalName("settings"))
        fs.rawFlipByte(path, 30)
        try {
            store.read("settings", Settings.serializer())
            fail("tampered file must not decrypt")
        } catch (e: CorruptFileException) {
            // expected: no backup exists for a file written once
        }
    }

    @Test
    fun `files cannot be swapped between names`() = runTest {
        val fs = newFs()
        val keys = VaultKeys.generate()
        val store = EncryptedStore(fs, keys)
        store.write("a", Settings.serializer(), Settings())
        store.write("b", Settings.serializer(), Settings())
        val a = VaultPath.of(VaultDir.DATA, store.physicalName("a"))
        val b = VaultPath.of(VaultDir.DATA, store.physicalName("b"))
        fs.rawWrite(b, fs.readBytes(a))
        try {
            store.read("b", Settings.serializer())
            fail("ciphertext moved to another name must be rejected")
        } catch (e: CorruptFileException) {
        }
    }

    @Test
    fun `physical names are opaque and stable`() {
        val keys = VaultKeys.generate()
        val n = keys.nameFor("attendance-2026-09")
        assertEquals(n, keys.nameFor("attendance-2026-09"))
        assertNotEquals(n, keys.nameFor("attendance-2026-10"))
        assertTrue(n.matches(Regex("[0-9a-f]{40}")))
        assertNotEquals(n, VaultKeys.generate().nameFor("attendance-2026-09"))
    }

    @Test
    fun `wrong passphrase fails and right one unlocks the same keys`() {
        val created = VaultCrypto.create("my long passphrase", fastKdf)
        try {
            VaultCrypto.unlockWithPassphrase(created.header, "wrong passphrase")
            fail("wrong passphrase must fail")
        } catch (e: WrongSecretException) {
        }
        val keys = VaultCrypto.unlockWithPassphrase(created.header, "my long passphrase")
        assertEquals(created.keys.nameFor("x"), keys.nameFor("x"))
        val ct = created.keys.aead.encrypt("hello".toByteArray(), null)
        assertArrayEquals("hello".toByteArray(), keys.aead.decrypt(ct, null))
    }

    @Test
    fun `passphrase change keeps data readable and old passphrase stops working`() = runTest {
        val fs = newFs()
        val created = VaultCrypto.create("old passphrase!", fastKdf)
        HeaderStore(fs).write(created.header)
        EncryptedStore(fs, created.keys).write("settings", Settings.serializer(), Settings(vaultCreatedOn = java.time.LocalDate.of(2026, 9, 1)))

        val newHeader = VaultCrypto.rewrapPassphrase(created.header, created.keys, "new passphrase!", fastKdf)
        HeaderStore(fs).write(newHeader)
        val reread = HeaderStore(fs).read()!!
        assertEquals(newHeader, reread)
        assertEquals(created.header.recovery, reread.recovery)

        val keys = VaultCrypto.unlockWithPassphrase(reread, "new passphrase!")
        assertEquals(java.time.LocalDate.of(2026, 9, 1), EncryptedStore(fs, keys).read("settings", Settings.serializer())!!.vaultCreatedOn)
        try {
            VaultCrypto.unlockWithPassphrase(reread, "old passphrase!")
            fail("old passphrase must stop working")
        } catch (e: WrongSecretException) {
        }
    }

    @Test
    fun `recovery code unlocks, tolerates formatting, and rotation revokes the old one`() {
        val created = VaultCrypto.create("passphrase here", fastKdf)
        val code = created.recoveryCode
        assertTrue(code.matches(Regex("([0-9A-Z]{4}-){7}[0-9A-Z]{4}")))
        val messy = " " + code.lowercase().replace("-", " ") + " "
        val keys = VaultCrypto.unlockWithRecovery(created.header, messy)
        assertEquals(created.keys.nameFor("x"), keys.nameFor("x"))

        val (rotated, newCode) = VaultCrypto.rotateRecovery(created.header, keys, fastKdf)
        assertNotEquals(code, newCode)
        VaultCrypto.unlockWithRecovery(rotated, newCode)
        try {
            VaultCrypto.unlockWithRecovery(rotated, code)
            fail("old recovery code must be revoked")
        } catch (e: WrongSecretException) {
        }
        try {
            VaultCrypto.unlockWithRecovery(created.header, "not-a-code")
            fail("malformed code must fail")
        } catch (e: WrongSecretException) {
        }
    }

    @Test
    fun `recovery codes normalise ambiguous letters`() {
        val canonical = RecoveryCode.normalize("0000-1111-0000-1111-0000-1111-0000-1111")!!
        assertEquals(canonical, RecoveryCode.normalize("oooo-iiii-OOOO-llll-0000-1111-0000-1111"))
        assertNull(RecoveryCode.normalize("too short"))
        assertNull(RecoveryCode.normalize("UUUU-1111-0000-1111-0000-1111-0000-1111")) // U is not Crockford
    }

    @Test
    fun `tampered wrapped key is rejected`() {
        val created = VaultCrypto.create("passphrase here", fastKdf)
        val raw = java.util.Base64.getDecoder().decode(created.header.passphrase.wrappedKey)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        val bad = created.header.copy(passphrase = created.header.passphrase.copy(wrappedKey = java.util.Base64.getEncoder().encodeToString(raw)))
        try {
            VaultCrypto.unlockWithPassphrase(bad, "passphrase here")
            fail()
        } catch (e: WrongSecretException) {
        }
    }

    @Test
    fun `wrapped key is bound to the vault id`() {
        val created = VaultCrypto.create("passphrase here", fastKdf)
        val moved = created.header.copy(vaultId = "another-vault")
        try {
            VaultCrypto.unlockWithPassphrase(moved, "passphrase here")
            fail()
        } catch (e: WrongSecretException) {
        }
    }

    @Test
    fun `destroyed keys refuse to work`() {
        val keys = VaultKeys.generate()
        keys.destroy()
        assertTrue(keys.isDestroyed)
        try { keys.aead; fail() } catch (e: VaultLockedException) {}
        try { keys.nameFor("x"); fail() } catch (e: VaultLockedException) {}
        try { keys.exportMaterial(); fail() } catch (e: VaultLockedException) {}
    }

    @Test
    fun `key material round trips and is zeroed after import`() {
        val keys = VaultKeys.generate()
        val material = keys.exportMaterial()
        val copy = material.copyOf()
        val restored = VaultKeys.fromMaterial(material)
        assertTrue(material.all { it == 0.toByte() })
        assertEquals(keys.nameFor("n"), restored.nameFor("n"))
        copy.fill(0)
    }

    @Test
    fun `streaming blob round trip and random seeks`() {
        val fs = newFs()
        val blobs = BlobStore(fs, VaultKeys.generate())
        val data = ByteArray(1_000_003).also { Random(42).nextBytes(it) }
        val id = blobs.newId()
        assertEquals(data.size.toLong(), blobs.writeFrom(id, data.inputStream()))
        assertArrayEquals(data, blobs.openDecryptingInput(id).use { it.readBytes() })

        blobs.openSeekable(id).use { ch ->
            assertEquals(data.size.toLong(), ch.size())
            val rnd = Random(7)
            repeat(50) {
                val pos = rnd.nextInt(data.size - 5000).toLong()
                val len = 1 + rnd.nextInt(4999)
                ch.position(pos)
                val buf = ByteBuffer.allocate(len)
                while (buf.hasRemaining()) if (ch.read(buf) < 0) break
                assertArrayEquals("seek to $pos", data.copyOfRange(pos.toInt(), pos.toInt() + len), buf.array())
            }
        }
        // Plaintext never appears in the stored blob.
        val stored = fs.readBytes(VaultPath.of(VaultDir.MEDIA, id))
        assertFalse(indexOf(stored, data.copyOfRange(1000, 1032)) >= 0)
    }

    @Test
    fun `tampered media blob fails to decrypt`() {
        val fs = newFs()
        val blobs = BlobStore(fs, VaultKeys.generate())
        val id = blobs.newId()
        blobs.writeFrom(id, ByteArray(20_000) { it.toByte() }.inputStream())
        fs.rawFlipByte(VaultPath.of(VaultDir.MEDIA, id), 10_000)
        try {
            blobs.openDecryptingInput(id).use { it.readBytes() }
            fail("tampered blob must fail")
        } catch (e: java.io.IOException) {
        } catch (e: GeneralSecurityException) {
        }
    }

    @Test
    fun `thumbnails are encrypted and bound to their id`() {
        val fs = newFs()
        val blobs = BlobStore(fs, VaultKeys.generate())
        val jpeg = ByteArray(500) { (it * 7).toByte() }
        blobs.writeThumb("t1", jpeg)
        assertArrayEquals(jpeg, blobs.readThumb("t1"))
        fs.rawWrite(VaultPath.of(VaultDir.THUMBS, "t2"), fs.readBytes(VaultPath.of(VaultDir.THUMBS, "t1")))
        try { blobs.readThumb("t2"); fail() } catch (e: GeneralSecurityException) {}
    }

    @Test
    fun `secure delete removes blob and thumb`() {
        val fs = newFs()
        val blobs = BlobStore(fs, VaultKeys.generate())
        val id = blobs.newId()
        blobs.writeFrom(id, ByteArray(10_000).inputStream())
        blobs.writeThumb("th", ByteArray(10))
        blobs.delete(id, "th")
        assertFalse(blobs.exists(id))
        assertTrue(blobs.listThumbs().isEmpty())
    }

    @Test
    fun `calibration stays within bounds`() {
        var t = 0L
        val cost = Argon2Kdf.calibrate(targetMillis = 400, memoryKiB = 1024, clock = { t.also { t += 50_000_000 } })
        assertEquals(8, cost.iterations) // 400 ms / 50 ms per iteration
        var slow = 0L
        val min = Argon2Kdf.calibrate(targetMillis = 400, memoryKiB = 1024, clock = { slow.also { slow += 900_000_000 } })
        assertEquals(Argon2Kdf.MIN_ITERATIONS, min.iterations)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
