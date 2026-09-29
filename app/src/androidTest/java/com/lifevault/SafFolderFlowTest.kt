package com.lifevault

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * End-to-end: pick a new folder with the system folder picker (Storage Access Framework), create a vault
 * in it, confirm the recovery code, reach the Today screen, lock, and unlock again.
 *
 * Drives the real DocumentsUI with UI Automator, so it depends on the picker's labels (written against the
 * Google/AOSP DocumentsUI on API 33+). Run it on a clean install: it starts from the welcome screen.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class SafFolderFlowTest {
    private lateinit var device: UiDevice
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val timeout = 20_000L

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    private fun text(s: String): BySelector = By.text(Pattern.compile("(?i)\\s*" + Pattern.quote(s) + "\\s*"))
    private fun await(sel: BySelector, ms: Long = timeout): UiObject2 {
        device.wait(Until.hasObject(sel), ms)
        return device.findObject(sel) ?: scrollTo(sel) ?: throw AssertionError("Not on screen: $sel")
    }

    /** Scrolls the app's scrollable content until [sel] appears (e.g. a button below the keyboard). */
    private fun scrollTo(sel: BySelector): UiObject2? {
        repeat(5) {
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.8f)
            device.waitForIdle()
            device.findObject(sel)?.let { return it }
        }
        return null
    }

    private fun typeInto(index: Int, value: String) {
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), timeout)
        device.findObjects(By.clazz("android.widget.EditText"))[index].text = value
    }

    @Test
    fun chooseFolderCreateVaultLockAndUnlock() {
        await(text("Choose vault folder")).click()

        // System folder picker: Documents -> new folder -> use it -> allow.
        await(text("Documents")).click()
        await(By.desc("New folder")).click()
        val name = "LifeVaultTest" + (System.currentTimeMillis() % 100000)
        await(By.clazz("android.widget.EditText")).text = name
        await(text("OK")).click()
        await(text(name))
        await(text("Use this folder")).click()
        await(text("Allow")).click()

        // Back in LifeVault.
        await(text("Create your vault"))
        val passphrase = "instrumented test passphrase"
        typeInto(0, passphrase)
        typeInto(1, passphrase)
        await(text("Create vault")).click()

        val code = await(By.text(Pattern.compile("([0-9A-Z]{4}-){7}[0-9A-Z]{4}")), 60_000).text
        await(text("I have saved my recovery code")).click()
        typeInto(0, code.takeLast(4))
        await(text("Continue")).click()

        await(text("Today"))
        await(By.desc("Lock now")).click()
        await(text("Vault locked"))
        typeInto(0, passphrase)
        await(text("Unlock")).click()
        assertNotNull(await(By.desc("Lock now"), 30_000))
    }
}
