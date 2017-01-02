/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.csv

import java.nio.charset.{Charset, StandardCharsets}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileStatus
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapred.TextInputFormat
import org.apache.hadoop.mapreduce._

import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Dataset, Encoders, SparkSession}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.util.CompressionCodecs
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.datasources.text.TextFileFormat
import org.apache.spark.sql.functions.{length, trim}
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.util.SerializableConfiguration

/**
 * Provides access to CSV data from pure SQL statements.
 */
class CSVFileFormat extends TextBasedFileFormat with DataSourceRegister {

  override def shortName(): String = "csv"

  override def toString: String = "CSV"

  override def hashCode(): Int = getClass.hashCode()

  override def equals(other: Any): Boolean = other.isInstanceOf[CSVFileFormat]

  override def inferSchema(
      sparkSession: SparkSession,
      options: Map[String, String],
      files: Seq[FileStatus]): Option[StructType] = {
    require(files.nonEmpty, "Cannot infer schema from an empty set of files")

    val csvOptions = new CSVOptions(options)
    val paths = files.map(_.getPath.toString)
    val lines: Dataset[String] = readText(sparkSession, csvOptions, paths)
    val firstLine: String = findFirstLine(csvOptions, lines)
    val firstRow = new CsvReader(csvOptions).parseLine(firstLine)
    val caseSensitive = sparkSession.sessionState.conf.caseSensitiveAnalysis
    val header = makeSafeHeader(firstRow, csvOptions, caseSensitive)

    val parsedRdd: RDD[Array[String]] = CSVRelation.univocityTokenizer(
      lines,
      firstLine = if (csvOptions.headerFlag) firstLine else null,
      params = csvOptions)
    val schema = if (csvOptions.inferSchemaFlag) {
      CSVInferSchema.infer(parsedRdd, header, csvOptions)
    } else {
      // By default fields are assumed to be StringType
      val schemaFields = header.map { fieldName =>
        StructField(fieldName, StringType, nullable = true)
      }
      StructType(schemaFields)
    }
    Some(schema)
  }

  /**
   * Generates a header from the given row which is null-safe and duplicate-safe.
   */
  private def makeSafeHeader(
      row: Array[String],
      options: CSVOptions,
      caseSensitive: Boolean): Array[String] = {
    if (options.headerFlag) {
      val duplicates = {
        val headerNames = row.filter(_ != null)
          .map(name => if (caseSensitive) name else name.toLowerCase)
        headerNames.diff(headerNames.distinct).distinct
      }

      row.zipWithIndex.map { case (value, index) =>
        if (value == null || value.isEmpty || value == options.nullValue) {
          // When there are empty strings or the values set in `nullValue`, put the
          // index as the suffix.
          s"_c$index"
        } else if (!caseSensitive && duplicates.contains(value.toLowerCase)) {
          // When there are case-insensitive duplicates, put the index as the suffix.
          s"$value$index"
        } else if (duplicates.contains(value)) {
          // When there are duplicates, put the index as the suffix.
          s"$value$index"
        } else {
          value
        }
      }
    } else {
      row.zipWithIndex.map { case (_, index) =>
        // Uses default column names, "_c#" where # is its position of fields
        // when header option is disabled.
        s"_c$index"
      }
    }
  }

  override def prepareWrite(
      sparkSession: SparkSession,
      job: Job,
      options: Map[String, String],
      dataSchema: StructType): OutputWriterFactory = {
    verifySchema(dataSchema)
    val conf = job.getConfiguration
    val csvOptions = new CSVOptions(options)
    csvOptions.compressionCodec.foreach { codec =>
      CompressionCodecs.setCodecConfiguration(conf, codec)
    }

    new CSVOutputWriterFactory(csvOptions)
  }

  override def buildReader(
      sparkSession: SparkSession,
      dataSchema: StructType,
      partitionSchema: StructType,
      requiredSchema: StructType,
      filters: Seq[Filter],
      options: Map[String, String],
      hadoopConf: Configuration): (PartitionedFile) => Iterator[InternalRow] = {
    val csvOptions = new CSVOptions(options)
    val commentPrefix = csvOptions.comment.toString
    val headers = requiredSchema.fields.map(_.name)

    val broadcastedHadoopConf =
      sparkSession.sparkContext.broadcast(new SerializableConfiguration(hadoopConf))

    (file: PartitionedFile) => {
      val lineIterator = {
        val conf = broadcastedHadoopConf.value.value
        val linesReader = new HadoopFileLinesReader(file, conf)
        Option(TaskContext.get()).foreach(_.addTaskCompletionListener(_ => linesReader.close()))
        linesReader.map { line =>
          new String(line.getBytes, 0, line.getLength, csvOptions.charset)
        }
      }

      CSVRelation.dropHeaderLine(file, lineIterator, csvOptions)

      val csvParser = new CsvReader(csvOptions)
      val tokenizedIterator = lineIterator.filter { line =>
        line.trim.nonEmpty && !line.startsWith(commentPrefix)
      }.map { line =>
        csvParser.parseLine(line)
      }
      val parser = CSVRelation.csvParser(dataSchema, requiredSchema.fieldNames, csvOptions)
      var numMalformedRecords = 0
      tokenizedIterator.flatMap { recordTokens =>
        val row = parser(recordTokens, numMalformedRecords)
        if (row.isEmpty) {
          numMalformedRecords += 1
        }
        row
      }
    }
  }

  /**
   * Returns the first line of the first non-empty file in path
   */
  private def findFirstLine(options: CSVOptions, lines: Dataset[String]): String = {
    import lines.sqlContext.implicits._
    val nonEmptyLines = lines.filter(length(trim($"value")) > 0)
    if (options.isCommentSet) {
      nonEmptyLines.filter(!$"value".startsWith(options.comment.toString)).first()
    } else {
      nonEmptyLines.first()
    }
  }

  private def readText(
      sparkSession: SparkSession,
      options: CSVOptions,
      inputPaths: Seq[String]): Dataset[String] = {
    if (Charset.forName(options.charset) == StandardCharsets.UTF_8) {
      sparkSession.baseRelationToDataFrame(
        DataSource.apply(
          sparkSession,
          paths = inputPaths,
          className = classOf[TextFileFormat].getName
        ).resolveRelation(checkFilesExist = false))
        .select("value").as[String](Encoders.STRING)
    } else {
      val charset = options.charset
      val rdd = sparkSession.sparkContext
        .hadoopFile[LongWritable, Text, TextInputFormat](inputPaths.mkString(","))
        .mapPartitions(_.map(pair => new String(pair._2.getBytes, 0, pair._2.getLength, charset)))
      sparkSession.createDataset(rdd)(Encoders.STRING)
    }
  }

  private def verifySchema(schema: StructType): Unit = {
    def verifyType(dataType: DataType): Unit = dataType match {
        case ByteType | ShortType | IntegerType | LongType | FloatType |
             DoubleType | BooleanType | _: DecimalType | TimestampType |
             DateType | StringType =>

        case udt: UserDefinedType[_] => verifyType(udt.sqlType)

        case _ =>
          throw new UnsupportedOperationException(
            s"CSV data source does not support ${dataType.simpleString} data type.")
    }

    schema.foreach(field => verifyType(field.dataType))
  }
}
