package dev.deftmartian.runway

import dev.deftmartian.runway.domain.PrescriptionKind
import dev.deftmartian.runway.domain.PrescriptionSegment
import dev.deftmartian.runway.domain.RunWalkBlock
import dev.deftmartian.runway.domain.SegmentKind
import dev.deftmartian.runway.domain.TimedIntervalStructure
import dev.deftmartian.runway.domain.WorkoutProposal
import dev.deftmartian.runway.domain.WorkoutType
import java.time.LocalDate

internal fun String?.toWorkoutTypeOrNull(): WorkoutType? =
    when (this) {
        "easy" -> WorkoutType.EASY
        "long" -> WorkoutType.LONG
        "recovery" -> WorkoutType.RECOVERY
        "rest" -> WorkoutType.REST
        "race" -> WorkoutType.RACE
        else -> null
    }

internal fun String?.toPrescriptionKindOrNull(): PrescriptionKind? =
    when (this) {
        "distance" -> PrescriptionKind.DISTANCE
        "timed" -> PrescriptionKind.TIMED
        "rest" -> PrescriptionKind.REST
        else -> null
    }

internal fun WorkoutMutation.toWorkoutProposal(weekId: String): WorkoutProposal =
    WorkoutProposal(
        weekId = weekId,
        scheduledDate = LocalDate.parse(scheduledDate),
        type = type,
        prescriptionKind = prescriptionKind,
        targetDistanceMeters = targetDistanceMeters,
        targetDurationSeconds = targetDurationSeconds,
        intervalStructure = intervalStructure?.toDomain(),
        intensity = intensity,
        purpose = purpose,
        reason = userReason,
    )

private fun TimedIntervalStructureDto.toDomain() =
    TimedIntervalStructure(
        warmupSeconds = warmupSeconds ?: 0,
        cooldownSeconds = cooldownSeconds ?: 0,
        blocks =
            blocks.map { block ->
                RunWalkBlock(
                    repetitions = requireNotNull(block.repetitions),
                    segments =
                        block.segments.map { segment ->
                            PrescriptionSegment(
                                kind =
                                    when (segment.kind) {
                                        "run" -> SegmentKind.RUN
                                        "walk" -> SegmentKind.WALK
                                        else ->
                                            throw IllegalArgumentException(
                                                "Timed workout segment kind is not supported.",
                                            )
                                    },
                                durationSeconds = requireNotNull(segment.durationSeconds),
                            )
                        },
                )
            },
    )
