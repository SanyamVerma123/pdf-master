package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.PdfEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("OmniPDF", appName)
  }

  @Test
  fun `formatFileSize formats correctly`() {
    assertEquals("500 B", PdfEngine.formatFileSize(500))
    assertEquals("1.0 KB", PdfEngine.formatFileSize(1024))
    assertEquals("1.5 MB", PdfEngine.formatFileSize((1.5 * 1024 * 1024).toLong()))
  }

  @Test
  fun `viewModel starts in ToolSelection screen and transitions to workbench`() {
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.viewmodel.PdfConverterViewModel(app)

    assertEquals(com.example.ui.viewmodel.AppScreen.ToolSelection, vm.currentScreen.value)

    // Open Merge PDF tool
    vm.openTool(com.example.data.model.ConversionType.MERGE_PDF)
    assertEquals(
      com.example.ui.viewmodel.AppScreen.ToolWorkbench(com.example.data.model.ConversionType.MERGE_PDF),
      vm.currentScreen.value
    )
    assertEquals(com.example.data.model.ConversionType.MERGE_PDF, vm.activeTool.value)

    // Navigate back returns to ToolSelection
    vm.navigateBack()
    assertEquals(com.example.ui.viewmodel.AppScreen.ToolSelection, vm.currentScreen.value)

    // Navigate to drawer sections
    vm.navigateTo(com.example.ui.viewmodel.AppScreen.MyPdfs)
    assertEquals(com.example.ui.viewmodel.AppScreen.MyPdfs, vm.currentScreen.value)

    vm.navigateTo(com.example.ui.viewmodel.AppScreen.MyLibrary)
    assertEquals(com.example.ui.viewmodel.AppScreen.MyLibrary, vm.currentScreen.value)

    vm.navigateTo(com.example.ui.viewmodel.AppScreen.Settings)
    assertEquals(com.example.ui.viewmodel.AppScreen.Settings, vm.currentScreen.value)

    vm.navigateTo(com.example.ui.viewmodel.AppScreen.Account)
    assertEquals(com.example.ui.viewmodel.AppScreen.Account, vm.currentScreen.value)
  }

  @Test
  fun `applyTemplate populates text composer and navigates to Text to PDF workbench`() {
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.ui.viewmodel.PdfConverterViewModel(app)

    val templateTitle = "Invoice #101"
    val templateContent = "Billed to Acme Corp"
    vm.applyTemplate(templateTitle, templateContent)

    assertEquals(templateTitle, vm.textTitle.value)
    assertEquals(templateContent, vm.textContent.value)
    assertEquals(com.example.data.model.ConversionType.TEXT_TO_PDF, vm.activeTool.value)
    assertEquals(
      com.example.ui.viewmodel.AppScreen.ToolWorkbench(com.example.data.model.ConversionType.TEXT_TO_PDF),
      vm.currentScreen.value
    )
  }

  @Test
  fun `exportSingleBitmap creates PNG photo file with correct png extension`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
    val file = PdfEngine.exportSingleBitmap(context, bitmap, "sample_invoice", 1)

    org.junit.Assert.assertTrue(file.exists())
    org.junit.Assert.assertTrue(file.name.endsWith(".png"))
    org.junit.Assert.assertEquals("png", file.extension.lowercase())
  }
}
