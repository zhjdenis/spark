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

package org.apache.spark.sql.hive.execution

import scala.util.control.NonFatal

import org.apache.spark.sql.{AnalysisException, Row, SaveMode, SparkSession}
import org.apache.spark.sql.catalyst.catalog.{CatalogTable, SimpleCatalogRelation}
import org.apache.spark.sql.catalyst.plans.logical.{InsertIntoTable, LogicalPlan, SubqueryAlias}
import org.apache.spark.sql.execution.command.RunnableCommand
import org.apache.spark.sql.hive.MetastoreRelation


/**
 * Create table and insert the query result into it.
 *
 * @param tableDesc the Table Describe, which may contains serde, storage handler etc.
 * @param query the query whose result will be insert into the new relation
 * @param mode SaveMode
 */
case class CreateHiveTableAsSelectCommand(
    tableDesc: CatalogTable,
    query: LogicalPlan,
    mode: SaveMode)
  extends RunnableCommand {

  private val tableIdentifier = tableDesc.identifier

  override def innerChildren: Seq[LogicalPlan] = Seq(query)

  override def run(sparkSession: SparkSession): Seq[Row] = {
    lazy val metastoreRelation: MetastoreRelation = {
      import org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat
      import org.apache.hadoop.hive.serde2.`lazy`.LazySimpleSerDe
      import org.apache.hadoop.io.Text
      import org.apache.hadoop.mapred.TextInputFormat

      val withFormat =
        tableDesc.withNewStorage(
          inputFormat =
            tableDesc.storage.inputFormat.orElse(Some(classOf[TextInputFormat].getName)),
          outputFormat =
            tableDesc.storage.outputFormat
              .orElse(Some(classOf[HiveIgnoreKeyTextOutputFormat[Text, Text]].getName)),
          serde = tableDesc.storage.serde.orElse(Some(classOf[LazySimpleSerDe].getName)),
          compressed = tableDesc.storage.compressed)

      val withSchema = if (withFormat.schema.isEmpty) {
        tableDesc.copy(schema = query.schema)
      } else {
        withFormat
      }

      sparkSession.sessionState.catalog.createTable(withSchema, ignoreIfExists = true)

      // Get the Metastore Relation
      sparkSession.sessionState.catalog.lookupRelation(tableIdentifier) match {
        case SubqueryAlias(_, r: SimpleCatalogRelation, _) =>
          val tableMeta = r.metadata
          MetastoreRelation(tableMeta.database, tableMeta.identifier.table)(tableMeta, sparkSession)
      }
    }
    // TODO ideally, we should get the output data ready first and then
    // add the relation into catalog, just in case of failure occurs while data
    // processing.
    if (sparkSession.sessionState.catalog.tableExists(tableIdentifier)) {
      assert(mode != SaveMode.Overwrite,
        s"Expect the table $tableIdentifier has been dropped when the save mode is Overwrite")

      if (mode == SaveMode.ErrorIfExists) {
        throw new AnalysisException(s"$tableIdentifier already exists.")
      }
      if (mode == SaveMode.Ignore) {
        // Since the table already exists and the save mode is Ignore, we will just return.
        return Seq.empty
      }
      sparkSession.sessionState.executePlan(InsertIntoTable(
        metastoreRelation, Map(), query, overwrite = false, ifNotExists = false)).toRdd
    } else {
      try {
        sparkSession.sessionState.executePlan(InsertIntoTable(
          metastoreRelation, Map(), query, overwrite = true, ifNotExists = false)).toRdd
      } catch {
        case NonFatal(e) =>
          // drop the created table.
          sparkSession.sessionState.catalog.dropTable(tableIdentifier, ignoreIfNotExists = true,
            purge = false)
          throw e
      }
    }

    Seq.empty[Row]
  }

  override def argString: String = {
    s"[Database:${tableDesc.database}}, " +
    s"TableName: ${tableDesc.identifier.table}, " +
    s"InsertIntoHiveTable]"
  }
}
