package com.workouttracker.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Putting exercises in a superset, taking them out, and what the rest of the
 * session's editing does to one. A superset is its members' sets sharing an
 * id and sitting together, so both halves are asserted on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SupersetTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private var clock = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = WorkoutRepository(db, syncTrigger = {}, now = { ++clock })
    }

    @After
    fun tearDown() = db.close()

    private suspend fun setsOf(workoutId: String) = db.workoutDao().setsOf(workoutId)

    private suspend fun orderOf(workoutId: String) = setsOf(workoutId).map { it.exercise }.distinct()

    private suspend fun groupOf(workoutId: String, exercise: String) =
        setsOf(workoutId).filter { it.exercise == exercise }.map { it.supersetId }.distinct().single()

    private suspend fun session(
        vararg exercises: String,
        date: LocalDate = LocalDate.of(2026, 9, 1),
    ): String {
        val id = repository.createWorkout("Push", date)
        for (exercise in exercises) {
            repository.addSet(id, exercise)
            repository.addSet(id, exercise)
        }
        return id
    }

    @Test
    fun `two exercises superset together and sit side by side`() = runTest {
        val id = session("Bench", "Squat", "Row")

        repository.supersetWith(id, "Bench", "Row")

        val group = groupOf(id, "Bench")
        assertNotNull(group)
        assertEquals(group, groupOf(id, "Row"))
        assertNull(groupOf(id, "Squat"))
        // The partner comes to the one whose menu it was picked from.
        assertEquals(listOf("Bench", "Row", "Squat"), orderOf(id))
    }

    @Test
    fun `an exercise joining a superset goes on the end of it`() = runTest {
        val id = session("Curl", "Bench", "Row", "Pushdown")
        repository.supersetWith(id, "Bench", "Row")

        // Picked from the curl's menu, but the curl joins the superset rather
        // than the superset coming to it.
        repository.supersetWith(id, "Curl", "Bench")

        assertEquals(listOf("Bench", "Row", "Curl", "Pushdown"), orderOf(id))
        assertEquals(groupOf(id, "Bench"), groupOf(id, "Curl"))
    }

    @Test
    fun `a new set of a superset member is in the superset`() = runTest {
        val id = session("Bench", "Row")
        repository.supersetWith(id, "Bench", "Row")

        repository.addSet(id, "Row")

        assertEquals(3, setsOf(id).count { it.exercise == "Row" })
        assertNotNull(groupOf(id, "Row"))
    }

    @Test
    fun `splitting leaves the exercises where they are`() = runTest {
        val id = session("Bench", "Squat", "Row")
        repository.supersetWith(id, "Bench", "Row")

        repository.splitSuperset(id, groupOf(id, "Bench")!!)

        assertTrue(setsOf(id).all { it.supersetId == null })
        assertEquals(listOf("Bench", "Row", "Squat"), orderOf(id))
    }

    @Test
    fun `removing one of two members ends the superset`() = runTest {
        val id = session("Bench", "Row")
        repository.supersetWith(id, "Bench", "Row")

        repository.deleteExercise(id, "Row")

        assertNull(groupOf(id, "Bench"))
    }

    @Test
    fun `deleting a member's last set ends the superset too`() = runTest {
        val id = repository.createWorkout("Push", LocalDate.of(2026, 9, 1))
        repository.addSet(id, "Bench")
        repository.addSet(id, "Row")
        repository.supersetWith(id, "Bench", "Row")

        repository.deleteSet(setsOf(id).single { it.exercise == "Row" }.id)

        assertNull(groupOf(id, "Bench"))
    }

    @Test
    fun `moving to another superset leaves the old one if it was the last partner`() = runTest {
        val id = session("A", "B", "C", "D")
        repository.supersetWith(id, "A", "B")
        repository.supersetWith(id, "C", "D")

        repository.supersetWith(id, "B", "C")

        assertNull(groupOf(id, "A"))
        assertEquals(groupOf(id, "C"), groupOf(id, "B"))
        assertEquals(groupOf(id, "C"), groupOf(id, "D"))
        assertEquals(listOf("A", "C", "D", "B"), orderOf(id))
    }

    @Test
    fun `a copied session keeps its supersets under new ids`() = runTest {
        val source = session("Bench", "Row", "Squat")
        repository.supersetWith(source, "Bench", "Row")
        val target = repository.createWorkout("Push again", LocalDate.of(2026, 9, 8))

        repository.copySession(target, source)

        val copied = groupOf(target, "Bench")
        assertNotNull(copied)
        assertEquals(copied, groupOf(target, "Row"))
        assertNull(groupOf(target, "Squat"))
        assertNotEquals(groupOf(source, "Bench"), copied)
    }

    @Test
    fun `copying last time's sets keeps this session's superset`() = runTest {
        val earlier = session("Row", date = LocalDate.of(2026, 8, 25))
        val id = session("Bench", "Row")
        repository.supersetWith(id, "Bench", "Row")
        val group = groupOf(id, "Row")

        repository.copyLastSession(id, "Row")

        assertEquals(group, groupOf(id, "Row"))
        assertNull(groupOf(earlier, "Row"))
    }

    @Test
    fun `a superset survives the trip through a backup`() = runTest {
        val id = session("Bench", "Row")
        repository.supersetWith(id, "Bench", "Row")
        val group = groupOf(id, "Bench")

        val snapshot = repository.snapshot()
        assertTrue(snapshot.sets.all { it.supersetId == group })
    }

    @Test
    fun `a superset's rest is kept for its exercises, whichever order they are in`() = runTest {
        repository.setRestSeconds(supersetRestKey(listOf("Barbell Row", "Barbell Bench Press")), 120)

        // Paired again the other way round, in another session.
        assertEquals(
            120,
            repository.restSecondsFor(supersetRestKey(listOf("Barbell Bench Press", "Barbell Row"))),
        )
        // And not the exercises' own rests, which apply when each is done alone.
        assertNull(repository.restSecondsFor("Barbell Bench Press"))
    }

    @Test
    fun `a superset's rest is listed under its exercises' names`() = runTest {
        repository.setRestSeconds(supersetRestKey(listOf("Barbell Row", "Barbell Bench Press")), 120)

        val listed = repository.observeRestOverrides().first().single()
        assertEquals("Barbell Bench Press + Barbell Row (superset)", listed.name)
        assertEquals(120, listed.seconds)
    }
}
