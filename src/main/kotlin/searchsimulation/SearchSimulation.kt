package searchsimulation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class Cell(val row: Int, val col: Int)

@JvmInline
value class PilotId(val value: Int)

data class RobotId(val pilot: PilotId, val index: Int)

class KilledException(val robot: RobotId) : RuntimeException("robot $robot was killed")

sealed interface SearchResult {
    data class Found(val cell: Cell) : SearchResult
    data object AllPilotsDead : SearchResult
}

// Deaths that happen during the simulation, reported to the [Renderer] so they can be logged
sealed interface SimulationEvent {
    data class PilotDied(val pilot: PilotId, val robotsLost: Int) : SimulationEvent
    data class PilotOutOfRobots(val pilot: PilotId) : SimulationEvent
    data class RobotKilledPilot(val robot: RobotId, val robotsLost: Int) : SimulationEvent
    data class RobotDied(val robot: RobotId) : SimulationEvent
}

data class SimulationConfig(
    val pilotCount: Int = 4,
    val minRobots: Int = 1,
    val maxRobots: Int = 4,
    val minDigTime: Duration = 1_000.milliseconds,
    val maxDigTime: Duration = 2_000.milliseconds,
    val delayBetweenMovingTries: Duration = 20.milliseconds,
    val pilotKillInterval: Duration? = 1_600.milliseconds,
    val pilotKillChance: Double = 0.5,
    val robotKillChance: Double = 0.1,
    val robotDies: ((RobotId) -> Boolean)? = null,
    val supervisedPilotChance: Double = 0.8,
)

class SearchSimulation(
    val size: Int,
    val treasure: Cell,
    val config: SimulationConfig = SimulationConfig(),
    private val random: Random = Random.Default,
    private val renderer: Renderer = NoOpRenderer,
) {

    private val allCells: List<Cell> = (0 until size).flatMap { r -> (0 until size).map { c -> Cell(r, c) } }

    internal val occupied = ConcurrentHashMap<Cell, Pilot>()
    internal val visitedBy = ConcurrentHashMap<Cell, Int>()
    internal val pilots = ConcurrentHashMap<PilotId, Pilot>()

    private val outcome = CompletableDeferred<SearchResult>()
    private val pilotCounter = AtomicInteger(0)

    internal fun currentlyOccupied(): Int = occupied.size
    internal fun livePilotIds(): List<PilotId> = pilots.keys.toList()
    internal fun liveRobotIds(): List<RobotId> = pilots.values.flatMap { it.robots.keys }
    internal fun killPilot(id: PilotId) { pilots[id]?.job?.cancel() }

    suspend fun play(): SearchResult = coroutineScope {
        val world = launch {
            supervisorScope {
                this.launch { pilotRandomDeath(this) }
                renderer.start(this, this@SearchSimulation)
                this.launch {
                    supervisorScope { repeat(config.pilotCount) { createPilot(this) } }
                    outcome.complete(SearchResult.AllPilotsDead)
                }
            }
        }
        val result = outcome.await()
        world.cancelAndJoin()
        renderer.finish(result, this@SearchSimulation)
        result
    }

    class Pilot(val id: PilotId, val colorIndex: Int, val robotCount: Int, val supervised: Boolean) {
        lateinit var job: Job
        val robots = ConcurrentHashMap<RobotId, Job>()
        val robotsLostWithPilot = AtomicInteger(0)
    }

    private fun createPilot(scope: CoroutineScope) {
        val id = PilotId(pilotCounter.getAndIncrement())
        val colorIndex = id.value % PILOT_COLORS.size
        val robotCount = random.nextInt(config.minRobots, config.maxRobots + 1)
        val supervised = random.nextDouble() < config.supervisedPilotChance
        val pilot = Pilot(id, colorIndex, robotCount, supervised)

        fun logDeath(event: SimulationEvent) {
            if (!outcome.isCompleted && scope.isActive) renderer.onEvent(event)
        }

        val job = scope.launch(CoroutineName("pilot-${id.value}") + pilotDeathHandler, start = CoroutineStart.LAZY) {
            try {
                pilotBody(pilot)
                logDeath(SimulationEvent.PilotOutOfRobots(id))
            } catch (e: KilledException) {
                logDeath(SimulationEvent.RobotKilledPilot(e.robot, pilot.robotsLostWithPilot.get()))
                throw e
            } catch (e: CancellationException) {
                logDeath(SimulationEvent.PilotDied(id, pilot.robotsLostWithPilot.get()))
                throw e
            }
        }
        pilot.job = job
        pilots[id] = pilot
        job.invokeOnCompletion { pilots.remove(id) }
        job.start()
    }

    private val pilotDeathHandler = CoroutineExceptionHandler { _, e ->
        if (e !is KilledException) throw e
    }

    private suspend fun pilotBody(pilot: Pilot) {
        if (pilot.supervised) supervisorScope { launchRobots(pilot) } else coroutineScope { launchRobots(pilot) }
    }

    private fun CoroutineScope.launchRobots(pilot: Pilot) {
        repeat(pilot.robotCount) { index ->
            val robotId = RobotId(pilot.id, index)
            val robotJob = launch(robotDeathHandler, start = CoroutineStart.LAZY) {
                try {
                    startRobot(pilot, robotId)
                } catch (e: CancellationException) {
                    if (!outcome.isCompleted) pilot.robotsLostWithPilot.incrementAndGet()
                    throw e
                }
            }
            pilot.robots[robotId] = robotJob
            robotJob.invokeOnCompletion { pilot.robots.remove(robotId) }
            robotJob.start()
        }
    }

    private val robotDeathHandler = CoroutineExceptionHandler { _, e ->
        if (e !is KilledException) throw e
        renderer.onEvent(SimulationEvent.RobotDied(e.robot))
    }

    private suspend fun startRobot(pilot: Pilot, robotId: RobotId) {
        var currentCell: Cell? = null

        fun releaseCurrentCell() {
            val cell = currentCell ?: return
            occupied.remove(cell)
            currentCell = null
        }
        try {
            while (!outcome.isCompleted) {
                val target = findPossibleNextCell()
                if (target == null) {
                    delay(config.delayBetweenMovingTries)
                    continue
                }
                if (occupied.putIfAbsent(target, pilot) != null) continue
                currentCell = target
                val digTime = randomDigTime()
                if (robotShouldDie(robotId)) {
                    delay(digTime / 2)
                    throw KilledException(robotId)
                }
                delay(digTime)

                if (target == treasure) outcome.complete(SearchResult.Found(target))
                markVisited(target, pilot.colorIndex)
                releaseCurrentCell()
            }
        } finally {
            releaseCurrentCell()
        }
    }

    private fun findPossibleNextCell(): Cell? {
        val free = allCells.filter { !visitedBy.containsKey(it) && !occupied.containsKey(it) }
        if (free.isEmpty()) return null
        return free[random.nextInt(free.size)]
    }

    private fun robotShouldDie(robotId: RobotId): Boolean =
        config.robotDies?.invoke(robotId) ?: (random.nextDouble() < config.robotKillChance)

    private fun randomDigTime(): Duration =
        random.nextLong(config.minDigTime.inWholeMilliseconds, config.maxDigTime.inWholeMilliseconds + 1).milliseconds

    private fun markVisited(cell: Cell, colorIndex: Int) {
        visitedBy[cell] = colorIndex
    }

    private suspend fun pilotRandomDeath(scope: CoroutineScope) {
        val interval = config.pilotKillInterval ?: return
        while (scope.isActive && !outcome.isCompleted) {
            delay(interval)
            if (random.nextDouble() >= config.pilotKillChance) continue
            val victims = pilots.values.toList()
            if (victims.isEmpty()) continue
            val victim = victims[random.nextInt(victims.size)]
            victim.job.cancel()
        }
    }

    companion object {
        // 256-colour ANSI foreground codes, one per pilot (wraps for many pilots)
        val PILOT_COLORS = intArrayOf(39, 208, 46, 201, 226, 51, 129, 196, 118, 99, 214, 45)
    }
}
