package com.workouttracker.ui

import com.workouttracker.data.SetEntry

/**
 * One entry in a session's running order: an exercise done on its own, or a
 * superset of exercises done together. The session overview draws, drags and
 * moves these rather than exercises, which is what keeps a superset in one
 * piece -- there is no way to drag half of one.
 */
sealed interface Block {
    /** The exercises in it, in the order they are done. */
    val members: List<String>

    /** Unique within a session, for the list it is drawn in. */
    val key: String

    /** How it is named in a sentence: "Bench Press", or "Bench Press + Row". */
    val label: String get() = members.joinToString(" + ")

    data class Single(val exercise: String) : Block {
        override val members get() = listOf(exercise)
        override val key get() = exercise
    }

    data class Superset(val id: String, override val members: List<String>) : Block {
        // The first member too, because two runs of the same id -- which
        // nothing should produce, but a sync race could -- must still be two
        // distinct keys or the list drawing them crashes.
        override val key get() = "superset:$id:${members.first()}"
    }
}

/**
 * A session's exercises as blocks, in session order: consecutive exercises that
 * share a superset id form one block. An exercise's superset is the one on its
 * first set.
 */
fun blocksOf(sets: List<SetEntry>): List<Block> {
    val order = exerciseOrder(sets)
    val groupOf = order.associateWith { name -> sets.first { it.exercise == name }.supersetId }
    val blocks = mutableListOf<Block>()
    var run = mutableListOf<String>()
    var runGroup: String? = null

    fun close() {
        val group = runGroup
        when {
            run.isEmpty() -> Unit
            group == null || run.size == 1 -> run.forEach { blocks += Block.Single(it) }
            else -> blocks += Block.Superset(group, run.toList())
        }
        run = mutableListOf()
        runGroup = null
    }

    for (name in order) {
        val group = groupOf[name]
        if (group == null || group != runGroup) close()
        run += name
        runGroup = group
    }
    close()
    return blocks
}

/** The block [exercise] is in: its superset, or just itself. */
fun blockOf(sets: List<SetEntry>, exercise: String): Block =
    blocksOf(sets).firstOrNull { exercise in it.members } ?: Block.Single(exercise)

/** Expands a running order of blocks back into one of exercises. */
fun exercisesOf(blocks: List<Block>): List<String> = blocks.flatMap { it.members }

/**
 * Whether ticking off a set of [exercise] just finished a round of the superset
 * made of [members] -- which is when the rest starts. In a superset you go
 * straight from one exercise to the next, and rest once each has had its turn.
 *
 * A round is finished when nobody is behind: every other member has done at
 * least as many sets as [exercise] now has, or has none left to do. Counting
 * rather than looking at which member was ticked last means the order you do
 * them in does not matter, and a member with fewer sets planned than the
 * others stops holding up the rounds it is not in.
 */
fun completesRound(sets: List<SetEntry>, members: List<String>, exercise: String): Boolean {
    if (members.size < 2) return true
    fun done(name: String) = sets.count { it.exercise == name && it.completed }
    fun hasLeft(name: String) = sets.any { it.exercise == name && !it.completed }
    val round = done(exercise)
    return members.all { name -> name == exercise || done(name) >= round || !hasLeft(name) }
}

/**
 * How long a superset rests after each round: its own length if it has been
 * given one, otherwise the longest of its exercises' -- it rests once for all
 * of them, so as long as the one that needs it most. [memberRests] are the
 * exercises' own lengths, null for one on the [default].
 */
fun supersetRest(own: Int?, memberRests: List<Int?>, default: Int): Int =
    own ?: memberRests.maxOfOrNull { it ?: default } ?: default
