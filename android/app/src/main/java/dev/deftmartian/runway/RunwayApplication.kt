package dev.deftmartian.runway

import android.app.Application
import dev.deftmartian.runway.data.LocalActivityReviewRepository
import dev.deftmartian.runway.data.LocalConsequenceDecisionRepository
import dev.deftmartian.runway.data.LocalDataManagementRepository
import dev.deftmartian.runway.data.LocalPlanLifecycleRepository
import dev.deftmartian.runway.data.LocalPlanSetupRepository
import dev.deftmartian.runway.data.LocalPrivacyRepository
import dev.deftmartian.runway.data.LocalProfileRepository
import dev.deftmartian.runway.data.LocalSurfaceRepository
import dev.deftmartian.runway.data.LocalTrainingMutationRepository
import dev.deftmartian.runway.data.LocalTrainingContextRepository
import dev.deftmartian.runway.data.LocalWorkoutChangeRepository
import dev.deftmartian.runway.data.RoomLocalSurfaceLedgerReader
import dev.deftmartian.runway.data.RoomLocalWorkoutChangeStore
import dev.deftmartian.runway.data.RunwayLedgerDatabase
import dev.deftmartian.runway.data.healthconnect.LocalHealthConnectRepository
import dev.deftmartian.runway.data.importing.LocalGpxImportRepository

class RunwayApplication : Application() {
    val services: RunwayServices by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RunwayServices(this)
    }
}

class RunwayServices(application: Application) {
    private val database: RunwayLedgerDatabase = RunwayLedgerDatabase.create(application)
    internal val importSources = AndroidImportSourceController(application)
    val planSetup = LocalPlanSetupRepository(database)
    val profile = LocalProfileRepository(database)
    val privacy = LocalPrivacyRepository(database)
    val activityReview = LocalActivityReviewRepository(database)
    val trainingMutations = LocalTrainingMutationRepository(database)
    val trainingContext = LocalTrainingContextRepository(database)
    val workoutChanges = LocalWorkoutChangeRepository(RoomLocalWorkoutChangeStore(database))
    val consequenceDecisions = LocalConsequenceDecisionRepository(database)
    val planLifecycle = LocalPlanLifecycleRepository(database)
    val gpxImports = LocalGpxImportRepository(database)
    val healthConnect = LocalHealthConnectRepository(database)
    val dataManagement = LocalDataManagementRepository(database)
    val surfaces = LocalSurfaceRepository(
        RoomLocalSurfaceLedgerReader(
            database = database,
            versionName = BuildConfig.VERSION_NAME,
            buildRevision = BuildConfig.SOURCE_COMMIT,
        ),
    )
}

internal val android.content.Context.runwayServices: RunwayServices
    get() = (applicationContext as RunwayApplication).services
