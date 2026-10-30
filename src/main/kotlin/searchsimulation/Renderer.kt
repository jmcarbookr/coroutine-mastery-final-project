package searchsimulation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How the simulation shows itself. Tests use [NoOpRenderer]; the terminal demo uses [AnsiRenderer]. */
interface Renderer {
    fun start(scope: CoroutineScope, simulation: SearchSimulation)
    fun finish(result: SearchResult, simulation: SearchSimulation)

    /** Called from pilot and robot coroutines, possibly on any thread: must be thread-safe and must not suspend. */
    fun onEvent(event: SimulationEvent)
}

object NoOpRenderer : Renderer {
    override fun start(scope: CoroutineScope, simulation: SearchSimulation) = Unit
    override fun finish(result: SearchResult, simulation: SearchSimulation) = Unit
    override fun onEvent(event: SimulationEvent) = Unit
}

/**
 * Draws the grid to the terminal using ANSI escape codes:
 *   x  a robot of a supervisorScope pilot, in its pilot's colour
 *   v  a robot of a coroutineScope pilot (one death takes down the pilot and all its robots)
 *   o  a dug cell, in the colour of the pilot who dug it (no robot may enter it again)
 *   T  the treasure (hidden once a robot stands on it)
 *   .  an untouched cell
 *
 * The map is redrawn once every [frame]. Deaths are kept in a log of the last [logLines]
 * events, shown under the map.
 */
class AnsiRenderer(private val frame: Duration = 1.seconds, private val logLines: Int = 5) : Renderer {

    private val log = ArrayDeque<String>() // guarded by itself: events arrive from several threads
    private var startMark = TimeSource.Monotonic.markNow()

    override fun start(scope: CoroutineScope, simulation: SearchSimulation) {
        startMark = TimeSource.Monotonic.markNow()
        print(CLEAR_SCREEN)
        scope.launch {
            while (isActive) {
                draw(simulation)
                delay(frame)
            }
        }
    }

    override fun onEvent(event: SimulationEvent) {
        val text = when (event) {
            is SimulationEvent.PilotDied -> "pilot P${event.pilot.value} died, taking ${event.robotsLost} robot(s) with it"
            is SimulationEvent.PilotOutOfRobots -> "pilot P${event.pilot.value} died: all of its robots are dead"
            is SimulationEvent.RobotKilledPilot -> with(event.robot) {
                "linked robot P${pilot.value}#$index died; it took down pilot P${pilot.value}" +
                    " and its ${event.robotsLost} other robot(s)"
            }
            is SimulationEvent.RobotDied -> "robot P${event.robot.pilot.value}#${event.robot.index} died"
        }
        val seconds = startMark.elapsedNow().inWholeMilliseconds / 1000.0
        synchronized(log) {
            log.addLast(dim("[%6.1fs]".format(seconds)) + " " + fg(196, "✝") + " " + text)
            while (log.size > logLines) log.removeFirst()
        }
    }

    override fun finish(result: SearchResult, simulation: SearchSimulation) {
        draw(simulation)
        val message = when (result) {
            is SearchResult.Found -> fg(226, "Treasure found at (${result.cell.row}, ${result.cell.col})!")
            SearchResult.AllPilotsDead -> fg(196, "All pilots are dead. The treasure was never found.")
        }
        println("\n$message")
    }

    private fun draw(simulation: SearchSimulation) {
        val sb = StringBuilder(HOME)
        for (r in 0 until simulation.size) {
            for (c in 0 until simulation.size) {
                val cell = Cell(r, c)
                val occupant = simulation.occupied[cell]
                val dugBy = simulation.visitedBy[cell]
                val glyph = when {
                    occupant != null ->
                        fg(SearchSimulation.PILOT_COLORS[occupant.colorIndex], if (occupant.supervised) " x" else " v")
                    dugBy != null -> dim(fg(SearchSimulation.PILOT_COLORS[dugBy], " o"))
                    cell == simulation.treasure -> bold(fg(226, " T"))
                    else -> dim(" .")
                }
                sb.append(glyph)
            }
            sb.append(CLEAR_LINE).append('\n')
        }
        sb.append(CLEAR_LINE).append('\n')
        sb.append("pilots: ")
        for (id in simulation.livePilotIds().sortedBy { it.value }) {
            val pilot = simulation.pilots[id] ?: continue
            sb.append(fg(SearchSimulation.PILOT_COLORS[pilot.colorIndex], "P${id.value}(${pilot.robots.size}) "))
        }
        sb.append(CLEAR_LINE).append('\n')
        sb.append(CLEAR_LINE).append('\n')
        sb.append(dim("events:")).append(CLEAR_LINE)
        for (line in synchronized(log) { log.toList() }) sb.append('\n').append(line).append(CLEAR_LINE)
        sb.append('\n') // consoles that ignore HOME (e.g. IntelliJ's) start the next frame on its own line
        print(sb)
        System.out.flush()
    }

    private fun fg(code: Int, s: String) = "\u001b[38;5;${code}m$s\u001b[0m"
    private fun dim(s: String) = "\u001b[2m$s\u001b[0m"
    private fun bold(s: String) = "\u001b[1m$s\u001b[0m"

    private companion object {
        const val CLEAR_SCREEN = "\u001b[2J"
        const val HOME = "\u001b[H"
        const val CLEAR_LINE = "\u001b[K"
    }
}
