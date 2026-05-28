package com.mobiledivecontrol.app

import java.nio.file.Path

fun main(args: Array<String>) {
    val runner = ScenarioScriptRunner()
    val scenarioPath = if (args.isNotEmpty()) {
        Path.of(args[0])
    } else {
        Path.of("scenarios", "smoke.scenario")
    }

    val result = runner.runFile(scenarioPath)
    println(result.render())
}
