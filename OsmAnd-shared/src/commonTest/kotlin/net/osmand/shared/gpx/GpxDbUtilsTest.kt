package net.osmand.shared.gpx

import net.osmand.shared.gpx.GpxTrackAnalysis.Companion.ANALYSIS_VERSION
import net.osmand.shared.io.KFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpxDbUtilsTest {

	@Test
	fun dataVersionKeepsSchemaVersionAboveAnalysisVersion() {
		val current = GpxDbUtils.createDataVersion(ANALYSIS_VERSION)
		assertEquals((GpxDatabase.DB_VERSION shl 10) + ANALYSIS_VERSION, current)
		assertEquals(ANALYSIS_VERSION, GpxDbUtils.getAnalysisVersion(current))
		// a row written by 5.3 (schema 34) carries the same analysis version
		assertEquals(ANALYSIS_VERSION, GpxDbUtils.getAnalysisVersion((34 shl 10) + ANALYSIS_VERSION))
		// the DATA_VERSION reset that GpxDbHelper uses to force a recalculation
		assertEquals(0, GpxDbUtils.getAnalysisVersion(0))
	}

	@Test
	fun schemaBumpAloneDoesNotRequireAnalysis() {
		// the root path has no parent, so the item is built without the platform context
		val item = GpxDataItem(KFile("/")).apply {
			setAnalysis(GpxTrackAnalysis().apply { wptCategoryNames = "" })
			setParameter(GpxParameter.FILE_CREATION_TIME, 1L)
		}
		val current = GpxDbUtils.createDataVersion(ANALYSIS_VERSION)

		item.setParameter(GpxParameter.DATA_VERSION, current)
		assertFalse(GpxDbUtils.isAnalyseNeeded(item))
		// the same row written under the previous schema: the analysis is current, nothing to read
		item.setParameter(GpxParameter.DATA_VERSION, current - (1 shl 10))
		assertFalse(GpxDbUtils.isAnalyseNeeded(item))
		// an older analysis is read again whatever the schema, and so is the DATA_VERSION reset
		item.setParameter(GpxParameter.DATA_VERSION, current - 1)
		assertTrue(GpxDbUtils.isAnalyseNeeded(item))
		item.setParameter(GpxParameter.DATA_VERSION, 0)
		assertTrue(GpxDbUtils.isAnalyseNeeded(item))
	}
}
