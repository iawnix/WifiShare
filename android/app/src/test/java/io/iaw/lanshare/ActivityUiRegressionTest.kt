package io.iaw.lanshare

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w360dp-h800dp-notnight-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityUiRegressionTest {
    private val activities = mutableListOf<ActivityController<out Activity>>()

    @After fun tearDown() {
        activities.asReversed().forEach { it.pause().stop().destroy() }
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test fun darkSettingsDiagnosticsUseDarkSurfaceEvenOnLightSystem() {
        val activity = settings(ThemeModeSetting.DARK).get()
        val button = activity.findViewById<TextView>(R.id.copyDiagnostics)
        val fill = (button.background as RippleDrawable).getDrawable(0) as GradientDrawable
        assertTrue(fill.colors!!.all { Color.red(it) < 100 && Color.green(it) < 100 && Color.blue(it) < 100 })
        assertEquals(AppTheme.palette(activity, ThemeModeSetting.DARK).text, button.currentTextColor)
        assertTrue(button.isClickable)
        assertTrue(button.hasOnClickListeners())
        val allText = texts(activity.findViewById(android.R.id.content))
        assertFalse(allText.any { it == "Widget appearance" || it == "小组件外观" })
        preview(activity, "settings-dark")
    }

    @Test fun lightSettingsDiagnosticsKeepReadableText() {
        val activity = settings(ThemeModeSetting.LIGHT).get()
        val button = activity.findViewById<TextView>(R.id.copyDiagnostics)
        val fill = (button.background as RippleDrawable).getDrawable(0) as GradientDrawable
        assertTrue(fill.colors!!.all { Color.red(it) > 220 })
        assertEquals(AppTheme.palette(activity, ThemeModeSetting.LIGHT).text, button.currentTextColor)
        preview(activity, "settings-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-night-mdpi")
    fun diagnosticsFollowSystemDarkTheme() {
        val activity = settings(ThemeModeSetting.SYSTEM).get()
        val button = activity.findViewById<TextView>(R.id.copyDiagnostics)
        val fill = (button.background as RippleDrawable).getDrawable(0) as GradientDrawable
        assertTrue(fill.colors!!.all { Color.red(it) < 100 })
        assertEquals(AppTheme.palette(activity, ThemeModeSetting.DARK).text, button.currentTextColor)
    }

    @Test fun homeNewServerToolbarBackClosesEditorWithoutSaving() {
        val activity = settings(directEditor = true).get()
        activity.findViewById<EditText>(R.id.serverNameInput).setText("Unsaved server")
        activity.findViewById<View>(R.id.backButton).performClick()
        assertTrue(activity.isFinishing)
        assertTrue(SettingsStore(activity).loadAll().isEmpty())
    }

    @Test fun homeNewServerSystemBackClosesEditor() {
        val activity = settings(directEditor = true).get()
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
    }

    @Test fun homeNewServerRetainsReturnDestinationAndDraftAfterRecreation() {
        val controller = settings(directEditor = true)
        controller.get().findViewById<EditText>(R.id.serverNameInput).setText("Draft server")
        controller.recreate()
        val activity = controller.get()
        assertEquals("Draft server", activity.findViewById<EditText>(R.id.serverNameInput).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.editorPanel).visibility)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
    }

    @Test fun settingsNewServerSystemBackReturnsToSettingsOverview() {
        val activity = settings().get()
        activity.findViewById<View>(R.id.settingsActionButton).performClick()
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(activity.isFinishing)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsOverviewPanel).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.editorPanel).visibility)
    }

    @Test fun settingsNewServerToolbarBackReturnsToSettingsOverview() {
        val activity = settings().get()
        activity.findViewById<View>(R.id.settingsActionButton).performClick()
        activity.findViewById<View>(R.id.backButton).performClick()
        assertFalse(activity.isFinishing)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsOverviewPanel).visibility)
    }

    @Test fun invalidSaveStaysInHomeLaunchedEditor() {
        val activity = settings(directEditor = true).get()
        activity.findViewById<View>(R.id.settingsActionButton).performClick()
        assertFalse(activity.isFinishing)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.editorPanel).visibility)
        assertEquals(activity.getString(R.string.invalid_config), activity.findViewById<TextView>(R.id.editorStatusText).text)
    }

    @Test fun settingsPreferenceSubpagesUseSameSystemBackNavigation() {
        val activity = settings().get()
        for (id in listOf(R.id.languageRow, R.id.themeRow)) {
            activity.findViewById<View>(id).performClick()
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(activity.isFinishing)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsOverviewPanel).visibility)
        }
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
    }

    @Test fun homeActionsAreCenteredIconsWithAccessibleLabelsAndFilePicker() {
        checkHome(ThemeModeSetting.DARK, "home-dark")
    }

    @Test fun homeActionsFitLightSmallPhone() {
        checkHome(ThemeModeSetting.LIGHT, "home-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-notnight-mdpi")
    fun narrowPhoneWithLargerFontKeepsControlsInsideScreen() {
        RuntimeEnvironment.setFontScale(1.3f)
        checkHome(ThemeModeSetting.DARK, "home-dark-small")
        val activity = settings(ThemeModeSetting.DARK).get()
        preview(activity, "settings-dark-small")
        val copy = activity.findViewById<TextView>(R.id.copyDiagnostics)
        assertTrue(copy.height >= AppTheme.dp(activity, 48))
        assertTrue(copy.layout.getEllipsisCount(0) == 0)
        val label = activity.findViewById<TextView>(R.id.saveLocationLabel)
        assertEquals(1, label.lineCount)
        assertEquals(label.text.length, label.layout.getLineEnd(0))
        assertTrue(label.height >= label.layout.height)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-notnight-mdpi")
    fun widerPhoneKeepsIconsCentered() {
        checkHome(ThemeModeSetting.LIGHT, "home-light-wide")
    }

    private fun checkHome(mode: ThemeModeSetting, name: String) {
        SettingsStore(RuntimeEnvironment.getApplication()).saveThemeMode(mode)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        activities += controller
        val activity = controller.get()
        preview(activity, name)
        val send = activity.findViewById<ImageButton>(R.id.selectFilesButton)
        val receive = activity.findViewById<ImageButton>(R.id.receiveButton)
        val dock = activity.findViewById<View>(R.id.homeActions)
        assertTrue(texts(dock).isEmpty())
        for (button in listOf(send, receive)) {
            assertEquals(ImageView.ScaleType.CENTER, button.scaleType)
            assertNotNull(button.drawable)
            assertTrue(button.contentDescription.isNotEmpty())
            assertFalse(button.tooltipText.isNullOrEmpty())
            assertTrue(button.height >= AppTheme.dp(activity, 48))
            assertTrue(button.width >= AppTheme.dp(activity, 48))
            assertEquals(button.paddingLeft, button.paddingRight)
            assertEquals(button.paddingTop, button.paddingBottom)
        }
        assertFalse(receive.isEnabled)
        send.performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(activity).nextStartedActivity.action)
    }

    private fun settings(
        mode: ThemeModeSetting = ThemeModeSetting.LIGHT,
        directEditor: Boolean = false,
    ): ActivityController<SettingsActivity> {
        val app = RuntimeEnvironment.getApplication()
        SettingsStore(app).saveThemeMode(mode)
        val intent = if (directEditor) SettingsActivity.createNewServerIntent(app) else Intent(app, SettingsActivity::class.java)
        return Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().visible().also { activities += it }
    }

    private fun texts(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(texts(view.getChildAt(index)))
    }

    private fun preview(activity: Activity, name: String) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val width = AppTheme.dp(activity, activity.resources.configuration.screenWidthDp)
        val height = AppTheme.dp(activity, activity.resources.configuration.screenHeightDp)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        root.viewTreeObserver.dispatchOnPreDraw()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val directory = File("build/ui-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("Preview should render visible content", bitmap.getPixel(20, 20) != Color.TRANSPARENT)
        bitmap.recycle()
    }
}
