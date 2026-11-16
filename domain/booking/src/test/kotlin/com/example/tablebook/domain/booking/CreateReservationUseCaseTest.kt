package com.example.tablebook.domain.booking

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateReservationUseCaseTest {
    private val now = Instant.parse("2026-11-16T15:00:00Z")
    private val start = now.plusSeconds(3600)
    private val draft = BookingDraft(BookingTable(1, 4), guestCount = 2, startsAt = start)
    private val requests = mutableListOf<Pair<BookingDraft, String>>()
    private val confirmed = CreateReservationResult.Confirmed(
        ConfirmedReservation(1, 1, 2, start, start.plusSeconds(7200), version = 1),
    )
    private val repository = object : BookingRepository {
        override suspend fun createReservation(draft: BookingDraft, idempotencyKey: String): CreateReservationResult {
            requests.add(draft to idempotencyKey)
            return if (requests.size == 1) CreateReservationResult.Failure.TEMPORARILY_UNAVAILABLE else confirmed
        }
    }
    private val create = CreateReservationUseCase(repository)

    @Test
    fun invalidFirstAttemptsDoNotReachTheRepository() = runBlocking {
        assertEquals(CreateReservationResult.Failure.INVALID_GUEST_COUNT, create(draft.copy(guestCount = 5), "key", now, false))
        assertEquals(CreateReservationResult.Failure.INVALID_START_TIME, create(draft, "key", start.plusSeconds(1), false))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun retryStillReachesTheRepositoryAfterTheStartTime() = runBlocking {
        val key = "reservation-attempt"
        assertEquals(CreateReservationResult.Failure.TEMPORARILY_UNAVAILABLE, create(draft, key, now, false))
        assertEquals(confirmed, create(draft, key, start.plusSeconds(1), true))
        assertEquals(listOf(draft to key, draft to key), requests)
    }
}
