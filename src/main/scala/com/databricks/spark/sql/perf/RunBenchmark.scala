/*
 * Copyright 2015 Databricks Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.databricks.spark.sql.perf

import java.io.File
import java.net.InetAddress

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

import scala.util.Try

case class RunConfig(
    benchmarkName: String = null,
    outputDir: String = new File("performance").toURI.toString,
    format: Option[String] = None,
    dataset: Option[String] = None,
    path: Option[String] = None,
    filter: Option[String] = None,
    iterations: Int = 1,
    baseline: Option[Long] = None)

/**
 * Runs a benchmark locally and prints the results to the screen.
 */
object RunBenchmark {
  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[RunConfig]("spark-sql-perf") {
      head("spark-sql-perf", BuildInfo.version)
      opt[String]('b', "benchmark")
        .action { (x, c) => c.copy(benchmarkName = x) }
        .text("the name of the benchmark to run")
        .required()
      opt[String]("format")
        .action((x, c) => c.copy(format = Some(x)))
        .text("Format to use to read data (table is a hive table and not a data source)")
      opt[String]('d', "dataset")
        .action((x, c) => c.copy(dataset = Some(x)))
        .text("dataset to find data in")
      opt[String]('p', "path")
        .action((x, c) => c.copy(path = Some(x)))
        .text("HCFS directory holding data")
      opt[String]('f', "filter")
        .action((x, c) => c.copy(filter = Some(x)))
        .text("a filter on the name of the queries to run")
      opt[Int]('i', "iterations")
        .action((x, c) => c.copy(iterations = x))
        .text("the number of iterations to run")
      opt[String]('o', "output")
        .action((x, c) => c.copy(outputDir = x))
        .text("HCFS directory containing the output report")
      opt[Long]('c', "compare")
          .action((x, c) => c.copy(baseline = Some(x)))
          .text("the timestamp of the baseline experiment to compare with")
      help("help")
        .text("prints this usage text")
    }

    parser.parse(args, RunConfig()) match {
      case Some(config) =>
        run(config)
      case None =>
        System.exit(1)
    }
  }

  def run(config: RunConfig): Unit = {
    val conf = new SparkConf()
      .setAppName(getClass.getName)

    val sparkSession = SparkSession.builder.config(conf).getOrCreate()
    val sc = sparkSession.sparkContext
    val sqlContext = sparkSession.sqlContext
    import sqlContext.implicits._

    sqlContext.setConf("spark.sql.perf.results", config.outputDir)

    Map(
      BenchmarkConfiguration.DATASET_KEY -> config.dataset,
      BenchmarkConfiguration.FORMAT_KEY -> config.format,
      BenchmarkConfiguration.PATH_KEY -> config.path)
      .foreach {
        case (k, Some(v)) =>
          sqlContext.setConf(k, v)
          case (_, None) => // nothing to set
      }
    val benchmark = Try {
      Class.forName(config.benchmarkName)
          .newInstance()
          .asInstanceOf[Benchmark]
    } getOrElse {
      Class.forName("com.databricks.spark.sql.perf." + config.benchmarkName)
          .newInstance()
          .asInstanceOf[Benchmark]
    }

    println("== SETUP ==")
    benchmark.setup()

    val allQueries = config.filter.map { f =>
      benchmark.allQueries.filter(_.name contains f)
    } getOrElse {
      benchmark.allQueries
    }

    println("== QUERY LIST ==")
    allQueries.foreach(println)

    val experiment = benchmark.runExperiment(
      executionsToRun = allQueries,
      iterations = config.iterations,
      tags = Map(
        "runtype" -> "local",
        "host" -> InetAddress.getLocalHost().getHostName()))

    println("== STARTING EXPERIMENT ==")
    experiment.waitForFinish(1000 * 60 * 30)

    sqlContext.setConf("spark.sql.shuffle.partitions", "1")
      
    val toShow = experiment.getCurrentRuns()
        .withColumn("result", explode($"results"))
        .select("result.*")
        .groupBy("name")
        .agg(
          min($"executionTime") as 'minTimeMs,
          max($"executionTime") as 'maxTimeMs,
          avg($"executionTime") as 'avgTimeMs,
          stddev($"executionTime") as 'stdDev,
          (stddev($"executionTime") / avg($"executionTime") * 100) as 'stdDevPercent)
        .orderBy("name")
        
    println("Showing at most 100 query results now")
    toShow.show(100)
      
    println(s"""Results: sqlContext.read.json("${experiment.resultPath}")""")

    config.baseline.foreach { baseTimestamp =>
      val baselineTime = when($"timestamp" === baseTimestamp, $"executionTime").otherwise(null)
      val thisRunTime = when($"timestamp" === experiment.timestamp, $"executionTime").otherwise(null)

      val data = sqlContext.read.json(benchmark.resultsLocation)
          .coalesce(1)
          .where(s"timestamp IN ($baseTimestamp, ${experiment.timestamp})")
          .withColumn("result", explode($"results"))
          .select("timestamp", "result.*")
          .groupBy("name")
          .agg(
            avg(baselineTime) as 'baselineTimeMs,
            avg(thisRunTime) as 'thisRunTimeMs,
            stddev(baselineTime) as 'stddev)
          .withColumn(
            "percentChange", ($"baselineTimeMs" - $"thisRunTimeMs") / $"baselineTimeMs" * 100)
          .filter('thisRunTimeMs.isNotNull)

      data.show(truncate = false)
    }
  }
}
