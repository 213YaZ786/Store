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

    @Test
    fun `the notification is about the entry that runs, with its percent only while it downloads`() {
        val jobs = listOf(job("Beta", JobStep.QUEUED), job("Alpha", JobStep.DOWNLOADING, 0.376f), job("Done", JobStep.DONE))
        val text = InstallNotice.describe(jobs)!!
        assertEquals("Alpha", text.title)
        assertEquals("Downloading, 1 more waiting", text.text)
        assertEquals(37, text.percent)
        assertNull(InstallNotice.describe(listOf(job("Alpha", JobStep.INSTALLING)))!!.percent)
    }

    @Test
    fun `each step has its words and nothing active gives no notification`() {
        assertEquals("Checking", InstallNotice.describe(listOf(job("A", JobStep.CHECKING)))!!.text)
        assertEquals("Installing", InstallNotice.describe(listOf(job("A", JobStep.INSTALLING)))!!.text)
        assertEquals("Waiting for your confirmation", InstallNotice.describe(listOf(job("A", JobStep.CONFIRM)))!!.text)
        assertEquals("Waiting", InstallNotice.describe(listOf(job("A", JobStep.QUEUED)))!!.text)
        assertNull(InstallNotice.describe(listOf(job("A", JobStep.DONE), job("B", JobStep.FAILED))))
        assertNull(InstallNotice.describe(emptyList()))
    }
}
