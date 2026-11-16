package com.example.tablebook.domain.booking

import java.time.Instant
import java.time.temporal.ChronoUnit

data class BookingPlanAvailability(
    val hall: Hall,
    val plan: FloorPlan,
    val startsAt: Instant,
    val availableTables: Set<Long>,
    val error: BookingError? = null,
)

class LoadBookingPlanUseCase(private val repository: GuestBookingRepository) {
    suspend operator fun invoke(
        hall: Hall,
        cachedPlan: FloorPlan?,
        startsAt: Instant?,
        guestCount: Int,
    ): BookingPlanAvailability {
        val plan = cachedPlan ?: repository.floorPlan(hall.id)
        val start = startsAt ?: plan.serverTime.atZone(hall.zone)
            .truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant()
        return try {
            val available = repository.availability(hall.id, start, guestCount)
            BookingPlanAvailability(hall, plan, start, available)
        } catch (error: BookingException) {
            BookingPlanAvailability(hall, plan, start, emptySet(), error.error)
        }
    }
}
