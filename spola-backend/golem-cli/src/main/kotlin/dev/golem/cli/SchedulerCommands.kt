package dev.spola.cli

import dev.spola.scheduler.GolemJobStore
import dev.spola.scheduler.SqliteGolemJobStore
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Parameters
import java.util.concurrent.Callable

@Command(
    name = "scheduler",
    description = ["Manage scheduled Golem jobs"],
    subcommands = [
        SchedulerAddCommand::class,
        SchedulerListCommand::class,
        SchedulerRemoveCommand::class,
    ],
)
class SchedulerCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: GolemCli

    override fun call(): Int {
        CommandLine.usage(this, System.out)
        return 0
    }
}

@Command(name = "add", description = ["Add a scheduled job"])
class SchedulerAddCommand : Callable<Int> {
    @ParentCommand
    lateinit var schedulerCommand: SchedulerCommand

    @Option(names = ["--name"], required = true, description = ["Human-friendly job name"])
    lateinit var name: String

    @Option(names = ["--cron"], required = true, description = ["Five-field cron expression"])
    lateinit var cron: String

    @Option(names = ["--disabled"], description = ["Create the job disabled"])
    var disabled: Boolean = false

    @Parameters(index = "0", description = ["Goal to run"])
    lateinit var goal: String

    override fun call(): Int = runBlocking {
        withJobStore(schedulerCommand.root) { store ->
            val job = store.add(
                name = name,
                goal = goal,
                cronExpression = cron,
                enabled = !disabled,
            )
            println("Added job ${job.id}")
            println("  name: ${job.name}")
            println("  next run: ${job.nextRunAt}")
        }
        0
    }
}

@Command(name = "list", description = ["List scheduled jobs"])
class SchedulerListCommand : Callable<Int> {
    @ParentCommand
    lateinit var schedulerCommand: SchedulerCommand

    override fun call(): Int = runBlocking {
        withJobStore(schedulerCommand.root) { store ->
            val jobs = store.list()
            if (jobs.isEmpty()) {
                println("No scheduled jobs.")
            } else {
                jobs.forEach { job ->
                    println("${job.id} | ${job.name} | enabled=${job.enabled} | next=${job.nextRunAt} | cron=${job.cronExpression}")
                }
            }
        }
        0
    }
}

@Command(name = "remove", description = ["Remove a scheduled job"])
class SchedulerRemoveCommand : Callable<Int> {
    @ParentCommand
    lateinit var schedulerCommand: SchedulerCommand

    @Parameters(index = "0", description = ["Job id to remove"])
    lateinit var id: String

    override fun call(): Int = runBlocking {
        val removed = withJobStore(schedulerCommand.root) { store ->
            store.remove(id)
        }
        if (removed) {
            println("Removed job $id")
            0
        } else {
            System.err.println("Job not found: $id")
            1
        }
    }
}
