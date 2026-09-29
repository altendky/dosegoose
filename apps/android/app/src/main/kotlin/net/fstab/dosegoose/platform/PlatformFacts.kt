package net.fstab.dosegoose.platform

import android.app.KeyguardManager
import android.content.Context
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class PlatformMoment(
    val epochSeconds: Long,
    val localDate: LocalDate,
    val zoneId: String,
    val utcOffsetSeconds: Int,
)

data class ScheduledMoment(
    val epochSeconds: Long,
    val zoneId: String,
    val utcOffsetSeconds: Int,
)

interface PlatformTimeSource {
    fun capture(): PlatformMoment

    fun resolve(
        date: LocalDate,
        hour: Int,
        minute: Int,
        zoneId: String,
    ): ScheduledMoment

    fun format(epochSeconds: Long, zoneId: String): String
}

class SystemPlatformTimeSource(
    private val clock: Clock = Clock.systemDefaultZone(),
) : PlatformTimeSource {
    override fun capture(): PlatformMoment {
        val now = ZonedDateTime.now(clock)
        return PlatformMoment(
            epochSeconds = now.toEpochSecond(),
            localDate = now.toLocalDate(),
            zoneId = now.zone.id,
            utcOffsetSeconds = now.offset.totalSeconds,
        )
    }

    override fun resolve(
        date: LocalDate,
        hour: Int,
        minute: Int,
        zoneId: String,
    ): ScheduledMoment {
        val resolved = date.atTime(LocalTime.of(hour, minute)).atZone(ZoneId.of(zoneId))
        return ScheduledMoment(
            epochSeconds = resolved.toEpochSecond(),
            zoneId = resolved.zone.id,
            utcOffsetSeconds = resolved.offset.totalSeconds,
        )
    }

    override fun format(epochSeconds: Long, zoneId: String): String = DISPLAY_FORMATTER.format(
        Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.of(zoneId)),
    )

    private companion object {
        val DISPLAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
    }
}

fun occurrenceId(scheduleId: String, localDate: LocalDate): String = UUID.nameUUIDFromBytes(
    "dosegoose:v1:once-daily:$scheduleId:$localDate".toByteArray(StandardCharsets.UTF_8),
).toString()

data class ForegroundAuthorizationFacts(
    val appIsForeground: Boolean,
    val deviceIsUnlocked: Boolean,
)

interface ForegroundAuthorizationProvider {
    fun capture(appIsForeground: Boolean): ForegroundAuthorizationFacts
}

class AndroidForegroundAuthorizationProvider(context: Context) :
    ForegroundAuthorizationProvider {
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)

    override fun capture(appIsForeground: Boolean): ForegroundAuthorizationFacts =
        ForegroundAuthorizationFacts(
            appIsForeground = appIsForeground,
            deviceIsUnlocked = !keyguardManager.isDeviceLocked,
        )
}
