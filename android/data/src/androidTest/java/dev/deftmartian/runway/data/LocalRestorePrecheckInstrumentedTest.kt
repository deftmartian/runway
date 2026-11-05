package dev.deftmartian.runway.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The pre-check answers before restore disconnects import sources or touches the installed ledger. */
@RunWith(AndroidJUnit4::class)
class LocalRestorePrecheckInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: RunwayLedgerDatabase
    private val files = mutableListOf<File>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, RunwayLedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        files.forEach(File::delete)
        context.deleteDatabase(BACKUP_NAME)
    }

    @Test
    fun aFileThatIsNotABackupIsRejectedBeforeAnythingIsDisconnected() = runBlocking {
        val notABackup = File(context.cacheDir, "not-a-backup.gpx").also(files::add)
        notABackup.writeText("<gpx></gpx>")

        val problem = LocalDataManagementRepository(database).restoreProblem(context, Uri.fromFile(notABackup))

        assertNotNull(problem)
    }

    @Test
    fun aCurrentSchemaBackupPassesThePrecheck() = runBlocking {
        context.deleteDatabase(BACKUP_NAME)
        // A clean close checkpoints the WAL, leaving a complete single-file database.
        RunwayLedgerDatabase.builder(context, BACKUP_NAME).build().apply {
            openHelper.writableDatabase
            close()
        }
        val backup = context.getDatabasePath(BACKUP_NAME)

        val problem = LocalDataManagementRepository(database).restoreProblem(context, Uri.fromFile(backup))

        assertNull(problem)
    }

    private companion object {
        const val BACKUP_NAME = "runway-precheck-backup.db"
    }
}
