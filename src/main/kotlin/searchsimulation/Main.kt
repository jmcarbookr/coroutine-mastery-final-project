package searchsimulation

import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Terminal demo. Run with `./gradlew run` (or from your IDE).
 *
 * Watch pilots (each its own colour) spread robots (x or v) across the grid, leaving dug
 * cells (o) behind. Pilots and robots die at random; the search keeps going until a
 * robot digs the treasure (T) or every pilot is dead.
 */
fun main(): Unit = runBlocking {
    val size = 10
    val rng = Random(System.nanoTime())
    val treasure = Cell(rng.nextInt(size), rng.nextInt(size))

    val simulation = SearchSimulation(
        size = size,
        treasure = treasure,
        config = SimulationConfig(
            pilotCount = 4,
            minRobots = 3,
            maxRobots = 5,
            minDigTime = 1_000.milliseconds,
            maxDigTime = 2_000.milliseconds,
            delayBetweenMovingTries = 20.milliseconds,
            pilotKillInterval = 1_800.milliseconds,
            pilotKillChance = 0.5,
            robotKillChance = 0.1,
            supervisedPilotChance = 0.65,
        ),
        random = rng,
        renderer = AnsiRenderer(),
    )

    simulation.play()
}
