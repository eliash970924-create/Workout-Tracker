package com.workouttracker.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Notes on an exercise in a session: written, cleared, carried by the sync,
 * and found again from the next session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExerciseNoteTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private var clock = 1_000L
    private var changes = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = WorkoutRepository(db, syncTrigger = { changes++ }, now = { ++clock })
    }

    @After
    fun tearDown() = db.close()

    private suspend fun session(day: Int = 1, exercises: List<String> = listOf("Leg Press")): String {
        val id = repository.createWorkout("Legs", LocalDate.of(2026, 9, day))
        for (exercise in exercises) repository.addSet(id, exercise)
        return id
    }

    @Test
    fun `a note is kept per exercise, apart from the session's own`() = runTest {
        val id = session(1, listOf("Leg Press", "Leg Curl"))

        repository.setExerciseNote(id, "Leg Press", "Seat at 4")

        assertEquals("Seat at 4", repository.observeExerciseNote(id, "Leg Press").first())
        assertEquals("", repository.observeExerciseNote(id, "Leg Curl").first())
        assertEquals("", db.workoutDao().findWorkout(id)?.notes)
    }

    @Test
    fun `clearing a note tombstones it, so the clearing syncs`() = runTest {
        val id = session()
        repository.setExerciseNote(id, "Leg Press", "Seat at 4")

        repository.setExerciseNote(id, "Leg Press", "  ")

        assertEquals("", repository.observeExerciseNote(id, "Leg Press").first())
        assertTrue(repository.snapshot().exerciseNotes.single().deleted)
    }

    @Test
    fun `typing the same note again writes nothing`() = runTest {
        val id = session()
        repository.setExerciseNote(id, "Leg Press", "Seat at 4")
        val before = changes

        repository.setExerciseNote(id, "Leg Press", "Seat at 4")
        repository.setExerciseNote(id, "Leg Curl", "")

        assertEquals(before, changes)
    }

    @Test
    fun `the note from last time is found from the next session`() = runTest {
        val earlier = session(1)
        repository.setExerciseNote(earlier, "Leg Press", "Seat at 4, go up next time")
        session(8)

        val notes = repository.observeNotesOf("Leg Press").first()

        assertEquals(mapOf(earlier to "Seat at 4, go up next time"), notes)
    }

    @Test
    fun `a deleted session's notes are gone with it`() = runTest {
        val id = session()
        repository.setExerciseNote(id, "Leg Press", "Seat at 4")

        repository.deleteWorkout(id)

        assertTrue(repository.observeNotesOf("Leg Press").first().isEmpty())
    }

    @Test
    fun `removing the exercise from the session removes its note`() = runTest {
        val id = session(1, listOf("Leg Press", "Leg Curl"))
        repository.setExerciseNote(id, "Leg Press", "Seat at 4")

        repository.deleteExercise(id, "Leg Press")

        assertEquals("", repository.observeExerciseNote(id, "Leg Press").first())
    }

    @Test
    fun `notes merge by last write`() = runTest {
        val id = session()
        repository.setExerciseNote(id, "Leg Press", "Seat at 4")
        val snapshot = repository.snapshot()
        val remote = snapshot.exerciseNotes.single()

        // The other device edited it later.
        repository.merge(
            snapshot.copy(
                exerciseNotes = listOf(remote.copy(text = "Seat at 5", updatedAt = remote.updatedAt + 10)),
            ),
        )
        assertEquals("Seat at 5", repository.observeExerciseNote(id, "Leg Press").first())

        // An older copy does not undo that.
        repository.merge(snapshot)
        assertEquals("Seat at 5", repository.observeExerciseNote(id, "Leg Press").first())
    }
}
