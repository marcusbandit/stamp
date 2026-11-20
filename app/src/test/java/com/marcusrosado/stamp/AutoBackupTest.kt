package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.AutoBackup
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AutoBackupTest {
    @Test fun oneFileADayNamedSoTheySortByDate() {
        assertEquals("stamp-auto-backup-2026-03-07.json", AutoBackup.fileName(LocalDate.of(2026, 3, 7)))
    }

    @Test fun onlyTheOldestOfStampsOwnFilesGo() {
        val own = (1..8).map { AutoBackup.fileName(LocalDate.of(2026, 1, it)) }
        val theirs = listOf("stamp-backup-2026-01-01.json", "holiday.jpg", "stamp-auto-backup-notes.json")
        val gone = AutoBackup.stale((own + theirs).shuffled(java.util.Random(4)), keep = 5)
        assertEquals(own.take(3).toSet(), gone.toSet())
        assertEquals(emptyList<String>(), AutoBackup.stale(own.take(5), keep = 5))
    }

    @Test fun aChangeSoonAfterABackupWaitsForTheDayNotTheWeek() {
        val day = AutoBackup.DAY_MS
        val now = 10 * day
        // Last backup an hour ago: the next one when the day is up, 23 hours from now.
        assertEquals(day - 3_600_000L, AutoBackup.delayAfterChange(now, now - 3_600_000L))
        // A day old or never: soon, once the edits settle.
        assertEquals(15 * 60_000L, AutoBackup.delayAfterChange(now, now - 2 * day))
        assertEquals(15 * 60_000L, AutoBackup.delayAfterChange(now, 0L))
    }
}
