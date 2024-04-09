/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

// scalastyle:off import.ordering.noEmptyLine
import scala.collection.mutable

import org.apache.spark.sql.delta.actions.{Metadata, Protocol}
import org.apache.spark.sql.delta.commands.cdc.CDCReader
import org.apache.spark.sql.delta.constraints.{Constraint, Constraints}
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.schema.SchemaUtils
import org.apache.spark.sql.delta.sources.{DeltaSourceUtils, DeltaSQLConf}

import org.apache.spark.sql.{Column, DataFrame, Dataset, Encoder}
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.expressions.EqualNullSafe
import org.apache.spark.sql.catalyst.util.CaseInsensitiveMap
import org.apache.spark.sql.catalyst.util.ResolveDefaultColumns._
import org.apache.spark.sql.execution.QueryExecution
import org.apache.spark.sql.execution.streaming.{IncrementalExecution, StreamExecution}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{MetadataBuilder, StructField, StructType}

/**
 * Provide utilities to handle columns with default expressions.
 */
object ColumnWithDefaultExprUtils extends DeltaLogging {
  val USE_NULL_AS_DEFAULT_DELTA_OPTION = "__use_null_as_default"

  // Returns true if column `field` is defined as an IDENTITY column.
  def isIdentityColumn(field: StructField): Boolean = {
    val md = field.metadata
    val hasStart = md.contains(DeltaSourceUtils.IDENTITY_INFO_START)
    val hasStep = md.contains(DeltaSourceUtils.IDENTITY_INFO_STEP)
    val hasInsert = md.contains(DeltaSourceUtils.IDENTITY_INFO_ALLOW_EXPLICIT_INSERT)
    // Verify that we have all or none of the three fields.
    if (!((hasStart == hasStep) && (hasStart == hasInsert))) {
      throw DeltaErrors.identityColumnInconsistentMetadata(field.name, hasStart, hasStep, hasInsert)
    }
    hasStart && hasStep && hasInsert
  }

  // Return true if `schema` contains any number of IDENTITY column.
  def hasIdentityColumn(schema: StructType): Boolean = schema.exists(isIdentityColumn)

  // Return if `protocol` satisfies the requirement for IDENTITY columns.
  def satisfiesIdentityColumnProtocol(protocol: Protocol): Boolean =
    protocol.minWriterVersion == 6 || protocol.writerFeatureNames.contains("identityColumns")

  // Return true if the column `col` has default expressions (and can thus be omitted from the
  // insertion list).
  def columnHasDefaultExpr(
      protocol: Protocol,
      col: StructField,
      nullAsDefault: Boolean): Boolean = {
    col.metadata.contains(CURRENT_DEFAULT_COLUMN_METADATA_KEY) ||
    (col.nullable && nullAsDefault) ||
    GeneratedColumn.isGeneratedColumn(protocol, col)
  }

  // Return true if the column `col` cannot be included as the input data column of COPY INTO.
  // TODO: ideally column with default value can be optionally excluded.
  def shouldBeExcludedInCopyInto(protocol: Protocol, col: StructField): Boolean = {
    GeneratedColumn.isGeneratedColumn(protocol, col)
  }

  // Return true if the table with `metadata` has default expressions.
  def tableHasDefaultExpr(
      protocol: Protocol,
      metadata: Metadata,
      nullAsDefault: Boolean): Boolean = {
    metadata.schema.exists { f =>
      f.metadata.contains(CURRENT_DEFAULT_COLUMN_METADATA_KEY) ||
        (f.nullable && nullAsDefault)
    } ||
    GeneratedColumn.enforcesGeneratedColumns(protocol, metadata)
  }

  /**
   * If there are columns with default expressions in `schema`, add a new project to generate
   * those columns missing in the schema, and return constraints for generated columns existing in
   * the schema.
   *
   * @param deltaLog The table's [[DeltaLog]] used for logging.
   * @param queryExecution Used to check whether the original query is a streaming query or not.
   * @param schema Table schema.
   * @param data The data to be written into the table.
   * @param nullAsDefault If true, use null literal as the default value for missing columns.
   * @return The data with potentially additional default expressions projected and constraints
   *         from generated columns if any.
   */
  def addDefaultExprsOrReturnConstraints(
      deltaLog: DeltaLog,
      protocol: Protocol,
      queryExecution: QueryExecution,
      schema: StructType,
      data: DataFrame,
      nullAsDefault: Boolean): (DataFrame, Seq[Constraint], Set[String]) = {
    val topLevelOutputNames = CaseInsensitiveMap(data.schema.map(f => f.name -> f).toMap)
    lazy val metadataOutputNames = CaseInsensitiveMap(schema.map(f => f.name -> f).toMap)
    val constraints = mutable.ArrayBuffer[Constraint]()
    val track = mutable.Set[String]()
    var selectExprs = schema.flatMap { f =>
      GeneratedColumn.getGenerationExpression(f) match {
        case Some(expr) if GeneratedColumn.satisfyGeneratedColumnProtocol(protocol) =>
          if (topLevelOutputNames.contains(f.name)) {
            val column = SchemaUtils.fieldToColumn(f)
            // Add a constraint to make sure the value provided by the user is the same as the value
            // calculated by the generation expression.
            constraints += Constraints.Check(s"Generated Column", EqualNullSafe(column.expr, expr))
            Some(column)
          } else {
            Some(new Column(expr).alias(f.name))
          }
        case _ =>
            if (topLevelOutputNames.contains(f.name) ||
                !data.sparkSession.conf.get(DeltaSQLConf.GENERATED_COLUMN_ALLOW_NULLABLE)) {
              Some(SchemaUtils.fieldToColumn(f))
            } else {
              // we only want to consider columns that are in the data's schema or are generated
              // to allow DataFrame with null columns to be written.
              // The actual check for nullability on data is done in the DeltaInvariantCheckerExec
              getDefaultValueExprOrNullLit(f, nullAsDefault).map(new Column(_))
            }
      }
    }
    val cdcSelectExprs = CDCReader.CDC_COLUMNS_IN_DATA.flatMap { cdcColumnName =>
      topLevelOutputNames.get(cdcColumnName).flatMap { cdcField =>
        if (metadataOutputNames.contains(cdcColumnName)) {
          // The column is in the table schema. It's not a CDC auto generated column. Skip it since
          // it's already in `selectExprs`.
          None
        } else {
          // The column is not in the table schema,
          // so it must be a column generated by CDC. Adding it back as it's not in `selectExprs`.
          Some(SchemaUtils.fieldToColumn(cdcField).alias(cdcField.name))
        }
      }
    }
    selectExprs = selectExprs ++ cdcSelectExprs

    val rowIdExprs = data.queryExecution.analyzed.output
      .filter(RowId.RowIdMetadataAttribute.isRowIdColumn)
      .map(new Column(_))
    selectExprs = selectExprs ++ rowIdExprs

    val newData = queryExecution match {
      case incrementalExecution: IncrementalExecution =>
        selectFromStreamingDataFrame(incrementalExecution, data, selectExprs: _*)
      case _ => data.select(selectExprs: _*)
    }
    recordDeltaEvent(deltaLog, "delta.generatedColumns.write")
    (newData, constraints.toSeq, track.toSet)
  }

  // Removes the default expressions properties from the schema. If `keepGeneratedColumns` is
  // true, generated column expressions are kept. If `keepIdentityColumns` is true, IDENTITY column
  // properties are kept.
  def removeDefaultExpressions(
      schema: StructType,
      keepGeneratedColumns: Boolean = false,
      keepIdentityColumns: Boolean = false): StructType = {
    var updated = false
    val updatedSchema = schema.map { field =>
      if (!keepGeneratedColumns && GeneratedColumn.isGeneratedColumn(field)) {
        updated = true
        val newMetadata = new MetadataBuilder()
          .withMetadata(field.metadata)
          .remove(DeltaSourceUtils.GENERATION_EXPRESSION_METADATA_KEY)
          .build()
        field.copy(metadata = newMetadata)
      } else if (!keepIdentityColumns && isIdentityColumn(field)) {
        updated = true
        val newMetadata = new MetadataBuilder()
          .withMetadata(field.metadata)
          .remove(DeltaSourceUtils.IDENTITY_INFO_ALLOW_EXPLICIT_INSERT)
          .remove(DeltaSourceUtils.IDENTITY_INFO_HIGHWATERMARK)
          .remove(DeltaSourceUtils.IDENTITY_INFO_START)
          .remove(DeltaSourceUtils.IDENTITY_INFO_STEP)
          .build()
        field.copy(metadata = newMetadata)
      } else {
        field
      }
    }
    if (updated) {
      StructType(updatedSchema)
    } else {
      schema
    }
  }

  /**
   * Select `cols` from a micro batch DataFrame. Directly calling `select` won't work because it
   * will create a `QueryExecution` rather than inheriting `IncrementalExecution` from
   * the micro batch DataFrame. A streaming micro batch DataFrame to execute should use
   * `IncrementalExecution`.
   */
  private def selectFromStreamingDataFrame(
      incrementalExecution: IncrementalExecution,
      df: DataFrame,
      cols: Column*): DataFrame = {
    val newMicroBatch = df.select(cols: _*)
    val newIncrementalExecution = new IncrementalExecution(
      newMicroBatch.sparkSession,
      newMicroBatch.queryExecution.logical,
      incrementalExecution.outputMode,
      incrementalExecution.checkpointLocation,
      incrementalExecution.queryId,
      incrementalExecution.runId,
      incrementalExecution.currentBatchId,
      incrementalExecution.prevOffsetSeqMetadata,
      incrementalExecution.offsetSeqMetadata,
      incrementalExecution.watermarkPropagator
    )
    newIncrementalExecution.executedPlan // Force the lazy generation of execution plan


    // Use reflection to call the private constructor.
    val constructor =
      classOf[Dataset[_]].getConstructor(classOf[QueryExecution], classOf[Encoder[_]])
    constructor.newInstance(
      newIncrementalExecution,
      ExpressionEncoder(newIncrementalExecution.analyzed.schema)).asInstanceOf[DataFrame]
  }
}
