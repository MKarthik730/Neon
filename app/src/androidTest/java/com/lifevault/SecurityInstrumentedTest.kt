package com.lifevault

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifevault.testing.FileVaultFs
import com.lifevault.vault.VaultSession
import com.lifevault.vault.crypto.Argon2Kdf
import com.lifevault.vault.crypto.VaultCrypto
import com.lifevault.vault.storage.VaultDir
import com.lifevault.vault.storage.VaultPath
import com.lifevault.vault.store.HeaderStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.Random

@RunWith(AndroidJUnit4::class)
class SecurityInstrumentedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun mainWindowIsFlagSecure() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                assertTrue("screenshots/recents must be blocked", a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
    }

    @Test
    fun appHasNoNetworkPermissionAndNoBackup() {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toSet()
        assertFalse(Manifest.permission.INTERNET in requested)
        assertFalse(Manifest.permission.ACCESS_NETWORK_STATE in requested)
        assertEquals(0, ctx.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun argon2CalibrationStaysInBoundsOnThisDevice() {
        val cost = Argon2Kdf.calibrate()
        assertTrue(cost.iterations in Argon2Kdf.MIN_ITERATIONS..Argon2Kdf.MAX_ITERATIONS)
        val t0 = System.nanoTime()
        Argon2Kdf.derive("passphrase".toByteArray(), Argon2Kdf.newParams(cost))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("unlock should stay well under 5 s (took $ms ms)", ms < 5_000)
    }

    @Test
    fun vaultRoundTripAndSeekingOnTheRealRuntime() = runBlocking {
        val dir = File(ctx.cacheDir, "vault-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val fs = FileVaultFs(dir).also { it.ensureLayout() }
            val created = VaultCrypto.create("device test passphrase", Argon2Kdf.calibrate())
            HeaderStore(fs).write(created.header)
            val session = VaultSession(fs, created.header, created.keys)
            session.settings.update { it.copy(attendance = it.attendance.copy(thresholdPercent = 80)) }

            val data = ByteArray(300_000).also { Random(1).nextBytes(it) }
            val id = session.blobs.newId()
            session.blobs.writeFrom(id, data.inputStream())
            session.blobs.openSeekable(id).use { ch ->
                ch.position(123_456)
                val buf = ByteBuffer.allocate(1000)
                while (buf.hasRemaining()) if (ch.read(buf) < 0) break
                assertArrayEquals(data.copyOfRange(123_456, 124_456), buf.array())
            }
            session.fs.writeBytes(VaultPath.of(VaultDir.TMP, "scratch"), byteArrayOf(1))
            session.close()
            assertTrue(fs.list(VaultDir.TMP).isEmpty())

            val keys = VaultCrypto.unlockWithPassphrase(HeaderStore(fs).read()!!, "device test passphrase")
            val again = VaultSession(fs, created.header, keys)
            assertEquals(80, again.settings.get().attendance.thresholdPercent)
            again.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
