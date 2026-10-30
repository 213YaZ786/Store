package com.yaz.store.data.install

import com.yaz.store.data.catalog.StoreEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The service decisions and the notification's words, without Android. The plan is Files' FileJobPlan (YAZ/Files at HEAD,
// data/files/FileJobPlan.kt and its FileJobPlanTest), itself Material Files' FileJobService (https://github.com/zhanghai/MaterialFiles):
// start for the first active entry, keep while some remain, stop when none is left.
class InstallServicePlanTest {

    private fun job(label: String, step: JobStep, progress: Float = 0f) = InstallJob(
        StoreEntry("r", "v1", "com.yaz.$label", label, "", 1, "1", 31, "$label.apk", null), handOver = false, step = step, progress = progress
    )

    @Test
    fun `the service starts for the first entry, is kept, and stops with the last`() {
        assertEquals(ServiceStep.START, InstallServicePlan.next(1, serviceUp = false))
        assertEquals(ServiceStep.KEEP, InstallServicePlan.next(3, serviceUp = true))
        assertEquals(ServiceStep.STOP, InstallServicePlan.next(0, serviceUp = true))
        assertEquals(ServiceStep.KEEP, InstallServicePlan.next(0, serviceUp = false))
    }

    // The notification's cases follow GrapheneOS' PackageDownloadFgService.createNotification (MIT, https://github.com/GrapheneOS/AppStore,
    // app/src/main/java/app/grapheneos/apps/PackageDownloadFgService.kt): one notification for all the downloads, the names joined by ", ".
    @Test
    fun `several downloads make one notification, the apps counted and named, the percent their mean`() {
        val jobs = listOf(
            job("Beta", JobStep.DOWNLOADING, 0.25f), job("Alpha", JobStep.DOWNLOADING, 0.5f), job("Gamma", JobStep.QUEUED), job("Done", JobStep.DONE)
        )
        val text = InstallNotice.describe(jobs)!!
        assertEquals("Downloading 3 apps", text.title)
        assertEquals("Beta, Alpha, Gamma", text.text)
        assertEquals(25, text.percent)
    }

    @Test
    fun `one download is named in the title`() {
        val text = InstallNotice.describe(listOf(job("Alpha", JobStep.DOWNLOADING, 0.376f)))!!
        assertEquals("Downloading Alpha", text.title)
        assertEquals("", text.text)
        assertEquals(37, text.percent)
        assertEquals("Checking Alpha", InstallNotice.describe(listOf(job("Alpha", JobStep.CHECKING, 1f)))!!.title)
        assertEquals("Checking 2 apps", InstallNotice.describe(listOf(job("A", JobStep.CHECKING, 1f), job("B", JobStep.CHECKING, 1f)))!!.title)
    }

    @Test
    fun `the app installing is the title, the others still downloading are the text`() {
        val jobs = listOf(job("Beta", JobStep.DOWNLOADING, 0.5f), job("Alpha", JobStep.INSTALLING), job("Gamma", JobStep.CHECKING, 1f))
        val text = InstallNotice.describe(jobs)!!
        assertEquals("Installing Alpha", text.title)
        assertEquals("Beta, Gamma", text.text)
        assertEquals(75, text.percent)
        assertNull(InstallNotice.describe(listOf(job("Alpha", JobStep.INSTALLING)))!!.percent)
        assertEquals("Waiting for your confirmation", InstallNotice.describe(listOf(job("A", JobStep.CONFIRM)))!!.text)
        assertEquals("Installing A", InstallNotice.describe(listOf(job("A", JobStep.CONFIRM)))!!.title)
    }

    @Test
    fun `cancel targets the app installing, else the first downloading, and nothing active gives no notification`() {
        val jobs = listOf(job("Beta", JobStep.DOWNLOADING), job("Alpha", JobStep.INSTALLING))
        assertEquals("Alpha", InstallNotice.current(jobs)!!.entry.label)
        assertEquals("Beta", InstallNotice.current(listOf(job("Beta", JobStep.DOWNLOADING), job("Gamma", JobStep.CHECKING)))!!.entry.label)
        assertNull(InstallNotice.current(listOf(job("A", JobStep.DONE))))
        assertEquals("Downloading A", InstallNotice.describe(listOf(job("A", JobStep.QUEUED)))!!.title)
        assertNull(InstallNotice.describe(listOf(job("A", JobStep.DONE), job("B", JobStep.FAILED))))
        assertNull(InstallNotice.describe(emptyList()))
    }
}
