package net.fstab.dosegoose.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.fstab.dosegoose.app.AppState
import net.fstab.dosegoose.app.Medication
import net.fstab.dosegoose.data.local.MedicationDao
import net.fstab.dosegoose.data.local.toDomain

interface MedicationRepository {
    val medications: Flow<List<Medication>>

    suspend fun initialize()

    suspend fun upsert(medication: Medication, effectiveFromEpochSeconds: Long)

    suspend fun setEnabled(
        medicationId: String,
        enabled: Boolean,
        effectiveFromEpochSeconds: Long,
    )
}

class RoomMedicationRepository(
    private val dao: MedicationDao,
) : MedicationRepository {
    override val medications: Flow<List<Medication>> =
        dao.observeMedications().map { stored -> stored.map { it.toDomain() } }

    override suspend fun initialize() {
        dao.initializeIfNeeded(AppState.initial().medications)
    }

    override suspend fun upsert(medication: Medication, effectiveFromEpochSeconds: Long) {
        dao.upsertMedication(medication, effectiveFromEpochSeconds)
    }

    override suspend fun setEnabled(
        medicationId: String,
        enabled: Boolean,
        effectiveFromEpochSeconds: Long,
    ) {
        check(dao.updateEnabled(medicationId, enabled) == 1) {
            "Medication $medicationId does not exist"
        }
        if (enabled) {
            check(dao.updateScheduleEffectiveFrom(medicationId, effectiveFromEpochSeconds) == 1) {
                "Medication $medicationId has no schedule"
            }
        }
    }
}
