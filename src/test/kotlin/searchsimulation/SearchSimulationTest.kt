package searchsimulation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SearchSimulationTest {

    @Test
    fun `should find the treasure at a known cell`() = runTest {
        // given
        val simulation = newSimulation(size = 5, treasure = Cell(2, 3), seed = 1)

        // when
        val result = simulation.play()

        // then
        assertEquals(SearchResult.Found(Cell(2, 3)), result)
    }

    @Test
    fun `should dig concurrently with all the robots of a pilot`() = runTest {
        // given one pilot with four robots on a 2x2 grid: one dig per robot covers the whole grid
        val simulation = newSimulation(size = 2, treasure = Cell(1, 1), config = testConfig(pilotCount = 1, minRobots = 4, maxRobots = 4))

        // when
        val result = simulation.play()

        // then it took as long as a single dig
        assertEquals(SearchResult.Found(Cell(1, 1)), result)
        assertEquals(DIG_TIME, currentTime)
    }

    @Test
    fun `should search concurrently with all pilots`() = runTest {
        // given four pilots with one robot each on a 2x2 grid
        val simulation = newSimulation(size = 2, treasure = Cell(1, 1), config = testConfig(pilotCount = 4, minRobots = 1, maxRobots = 1))

        // when
        val result = simulation.play()

        // then it took as long as a single dig
        assertEquals(SearchResult.Found(Cell(1, 1)), result)
        assertEquals(DIG_TIME, currentTime)
    }

    @Test
    fun `should cancel all robots of a pilot when the pilot is killed`() = runTest {
        // given a running simulation with no automatic deaths, robots mid-dig
        val simulation = newSimulation(config = testConfig(pilotCount = 3))
        val result = simulation.startIn(backgroundScope)
        runCurrent()

        val victim = simulation.pilots.getValue(simulation.livePilotIds().first())
        val victimRobotJobs = victim.robots.values.toList()
        val otherRobotJobs = simulation.pilots.values.filter { it !== victim }.flatMap { it.robots.values }
        assertEquals(6, simulation.liveRobotIds().size)

        // when
        simulation.killPilot(victim.id)
        runCurrent()

        // then the pilot's job and every one of its robot jobs ended cancelled
        assertTrue(victim.job.isCancelled && victim.job.isCompleted)
        assertTrue(victimRobotJobs.all { it.isCancelled && it.isCompleted })
        assertFalse(victim.id in simulation.livePilotIds())
        assertEquals(6 - victimRobotJobs.size, simulation.liveRobotIds().size)

        // and the robots of the other pilots were not touched
        assertTrue(otherRobotJobs.all { it.isActive })

        // and the simulation still finishes (the surviving pilots keep searching)
        assertEquals(SearchResult.Found(FAR_CORNER), result.await())
    }

    @Test
    fun `should end with AllPilotsDead when every pilot is killed`() = runTest {
        // given
        val simulation = newSimulation(config = testConfig(pilotCount = 2), seed = 9)
        val result = simulation.startIn(backgroundScope)
        runCurrent()

        // when
        simulation.livePilotIds().forEach { simulation.killPilot(it) }

        // then the simulation ends at once without any digging
        assertEquals(SearchResult.AllPilotsDead, result.await())
        assertEquals(0, currentTime)
        assertEquals(0, simulation.currentlyOccupied())
        assertTrue(simulation.liveRobotIds().isEmpty())
    }

    @Test
    fun `should count a pilot as dead when all of its robots are dead`() = runTest {
        // given two pilots with one robot each, where the first pilot's only robot dies on its first dig
        val stranded = PilotId(0)
        val onlyRobot = RobotId(stranded, 0)
        val renderer = RecordingRenderer()
        val simulation = newSimulation(
            config = testConfig(pilotCount = 2, minRobots = 1, maxRobots = 1).copy(robotDies = { it == onlyRobot }),
            renderer = renderer,
        )
        val result = simulation.startIn(backgroundScope)

        // when its only robot dies, halfway through its first dig
        assertEquals(SimulationEvent.RobotDied(onlyRobot), renderer.awaitEvent())
        assertEquals(DIG_TIME / 2, currentTime)

        // then that pilot dies with it and the other one keeps searching
        assertEquals(SimulationEvent.PilotOutOfRobots(stranded), renderer.awaitEvent())
        assertEquals(listOf(PilotId(1)), simulation.livePilotIds())
        assertEquals(SearchResult.Found(FAR_CORNER), result.await())
    }

    @Test
    fun `should end with AllPilotsDead when the last robot of the last pilot dies`() = runTest {
        // given a single pilot whose single robot dies on its first dig
        val renderer = RecordingRenderer()
        val simulation = newSimulation(
            config = testConfig(pilotCount = 1, minRobots = 1, maxRobots = 1).copy(robotDies = { true }),
            renderer = renderer,
        )

        // when
        val result = simulation.play()

        // then the simulation ends the moment that robot dies, instead of waiting forever for a search nobody is doing
        assertEquals(SearchResult.AllPilotsDead, result)
        assertEquals(DIG_TIME / 2, currentTime)

        // and the last deaths were logged before the simulation ended
        val robot = RobotId(PilotId(0), 0)
        assertEquals(listOf(SimulationEvent.RobotDied(robot), SimulationEvent.PilotOutOfRobots(PilotId(0))), renderer.events)
    }

    @Test
    fun `should kill a robot on some digs but not others when the kill chance is below one`() = runTest {
        // given 10 robots, each with a 80% chance of dying during any dig
        val renderer = RecordingRenderer()
        val config = testConfig(pilotCount = 2, minRobots = 5, maxRobots = 5).copy(robotKillChance = 0.8)
        val simulation = newSimulation(size = 10, treasure = Cell(9, 9), config = config, renderer = renderer, seed = 8)
        simulation.startIn(backgroundScope)

        // when every robot is past the middle of its first dig
        advanceTimeBy(DIG_TIME / 2 + 1)

        // then some of those 10 first digs killed their robot and some did not
        val robotDeaths = renderer.events.count { it is SimulationEvent.RobotDied }
        assertTrue(robotDeaths in 1..9, "robotDeaths=$robotDeaths")
        assertEquals(10 - robotDeaths, simulation.liveRobotIds().size)
    }

    @Test
    fun `should not affect sibling robots when one robot dies from the environment`() = runTest {
        // given a supervisorScope pilot with exactly two robots, the first of which dies on its first dig
        val victimId = RobotId(PilotId(0), 0)
        val renderer = RecordingRenderer()
        val simulation = newSimulation(
            config = testConfig(pilotCount = 2, minRobots = 2, maxRobots = 2).copy(robotDies = { it == victimId }),
            renderer = renderer,
        )
        val result = simulation.startIn(backgroundScope)
        runCurrent()
        val pilot = simulation.pilots.getValue(victimId.pilot)
        val (victim, sibling) = pilot.robots.entries.sortedBy { it.key.index }.map { it.value }

        // when that robot dies halfway through its dig
        assertEquals(SimulationEvent.RobotDied(victimId), renderer.awaitEvent())
        runCurrent()

        // then only that robot's job failed
        assertTrue(victim.isCancelled && victim.isCompleted)
        assertTrue(sibling.isActive)
        assertTrue(pilot.job.isActive)

        assertEquals(SearchResult.Found(FAR_CORNER), result.await())
        assertEquals(listOf<SimulationEvent>(SimulationEvent.RobotDied(victimId)), renderer.events)
    }

    @Test
    fun `should take down the pilot and all its robots when one robot dies in a coroutineScope pilot`() = runTest {
        // given two coroutineScope pilots with three robots each, where one robot of the first dies on its first dig
        val victim = RobotId(PilotId(0), 0)
        val renderer = RecordingRenderer()
        val simulation = newSimulation(
            config = testConfig(pilotCount = 2, minRobots = 3, maxRobots = 3)
                .copy(supervisedPilotChance = 0.0, robotDies = { it == victim }),
            renderer = renderer,
        )
        val result = simulation.startIn(backgroundScope)
        runCurrent()
        val (pilot, otherPilot) = simulation.pilots.values.sortedBy { it.id.value }
        val robotJobs = pilot.robots.values.toList()

        // when that robot dies halfway through its dig
        assertEquals(SimulationEvent.RobotKilledPilot(victim, robotsLost = 2), renderer.awaitEvent())
        runCurrent()

        // then every sibling was cancelled and the pilot failed
        assertTrue(robotJobs.all { it.isCancelled && it.isCompleted })
        assertTrue(pilot.job.isCancelled && pilot.job.isCompleted)
        assertFalse(pilot.id in simulation.livePilotIds())

        // and the other pilots keep searching
        assertTrue(otherPilot.job.isActive)
        assertEquals(3, simulation.liveRobotIds().size)

        assertEquals(SearchResult.Found(FAR_CORNER), result.await())
        assertEquals(1, renderer.events.size)
    }

    @Test
    fun `should cancel everything when the simulation scope is cancelled`() = runTest {
        // given a running simulation, robots mid-dig
        val simulation = newSimulation(seed = 11)
        val job = launch { simulation.play() }
        runCurrent()
        assertEquals(6, simulation.currentlyOccupied())

        // when the whole simulation is cancelled
        job.cancel()
        runCurrent()

        // then nothing is left running or occupied
        assertTrue(job.isCancelled)
        assertEquals(0, simulation.currentlyOccupied())
        assertTrue(simulation.liveRobotIds().isEmpty())
        assertTrue(simulation.livePilotIds().isEmpty())
    }

    @Test
    fun `should log a death event for a killed robot and a killed pilot, but not for the simulation ending`() = runTest {
        // given two pilots with two robots each, where one robot of the second pilot dies on its first dig
        val pilot = PilotId(0)
        val loneRobot = RobotId(PilotId(1), 0)
        val renderer = RecordingRenderer()
        val simulation = newSimulation(
            config = testConfig(pilotCount = 2, minRobots = 2, maxRobots = 2).copy(robotDies = { it == loneRobot }),
            renderer = renderer,
        )
        val result = simulation.startIn(backgroundScope)

        // when that robot dies on its own, then a whole pilot is killed
        assertEquals(SimulationEvent.RobotDied(loneRobot), renderer.awaitEvent())
        simulation.killPilot(pilot)

        // then each death is logged once; the pilot's robots are counted in its event, not logged one by one
        assertEquals(SimulationEvent.PilotDied(pilot, robotsLost = 2), renderer.awaitEvent())

        // and the simulation ending cancels everything without logging more deaths
        assertEquals(SearchResult.Found(FAR_CORNER), result.await())
        assertEquals(2, renderer.events.size)
    }

    @Test
    fun `should dig each cell for a random time between minDigTime and maxDigTime`() = runTest {
        // given a single robot on a 1x1 grid, so each simulation is exactly one dig
        val config = testConfig(pilotCount = 1, minRobots = 1, maxRobots = 1)
            .copy(minDigTime = 1_000.milliseconds, maxDigTime = 2_000.milliseconds)

        // when five simulations are played with different seeds
        val digTimes = (1..5).map { seed ->
            val start = currentTime
            newSimulation(size = 1, treasure = Cell(0, 0), config = config, seed = seed).play()
            currentTime - start
        }

        // then every dig took between min and max, and they were not all the same length
        assertTrue(digTimes.all { it in 1_000L..2_000L }, "digTimes=$digTimes")
        assertTrue(digTimes.toSet().size > 1, "digTimes=$digTimes")
    }


    @Test
    fun `should finish cleanly`() = runBlocking(Dispatchers.Default) {
        // given a crowded grid running on many real threads (Random.Default is thread-safe)
        val treasure = Cell(5, 5)
        val simulation = SearchSimulation(
            size = 6,
            treasure = treasure,
            config = SimulationConfig(
                pilotCount = 8, minRobots = 3, maxRobots = 5,
                minDigTime = 1.milliseconds, maxDigTime = 1.milliseconds, delayBetweenMovingTries = 1.milliseconds,
                pilotKillInterval = null, robotKillChance = 0.0,
            ),
            random = Random.Default,
        )

        // when
        val result = withContext(Dispatchers.Default) { simulation.play() }

        // then the treasure is found and every claimed cell was released
        assertEquals(SearchResult.Found(treasure), result)
        assertEquals(0, simulation.currentlyOccupied())
    }

    /** No random deaths, every dig lasts exactly [DIG_TIME], every pilot is supervised. */
    private fun testConfig(pilotCount: Int = 3, minRobots: Int = 2, maxRobots: Int = 2) = SimulationConfig(
        pilotCount = pilotCount,
        minRobots = minRobots,
        maxRobots = maxRobots,
        minDigTime = DIG_TIME.milliseconds, maxDigTime = DIG_TIME.milliseconds,
        delayBetweenMovingTries = 10.milliseconds,
        pilotKillInterval = null,
        robotKillChance = 0.0,
        supervisedPilotChance = 1.0,
    )

    private fun newSimulation(
        size: Int = 8,
        treasure: Cell = FAR_CORNER,
        config: SimulationConfig = testConfig(),
        renderer: Renderer = NoOpRenderer,
        seed: Int = 5,
    ) = SearchSimulation(size = size, treasure = treasure, config = config, random = Random(seed), renderer = renderer)

    /** Plays in [scope] (the test's backgroundScope), so a test that never awaits the result doesn't leak the simulation. */
    private fun SearchSimulation.startIn(scope: CoroutineScope): Deferred<SearchResult> = scope.async { play() }

    /** A fake renderer that records deaths and lets a test suspend until the next one is logged. */
    private class RecordingRenderer : Renderer {
        val events = CopyOnWriteArrayList<SimulationEvent>()
        private val pending = Channel<SimulationEvent>(Channel.UNLIMITED)

        override fun start(scope: CoroutineScope, simulation: SearchSimulation) = Unit
        override fun finish(result: SearchResult, simulation: SearchSimulation) = Unit

        override fun onEvent(event: SimulationEvent) {
            events.add(event)
            pending.trySend(event)
        }

        suspend fun awaitEvent(): SimulationEvent = pending.receive()
    }

    private companion object {
        const val DIG_TIME = 100L
        val FAR_CORNER = Cell(7, 7)
    }
}
