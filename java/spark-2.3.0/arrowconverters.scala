package sparklyr

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, FileInputStream, OutputStream}
import java.nio.channels.Channels

import scala.collection.JavaConverters._

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._
import org.apache.arrow.vector.ipc.{ArrowFileReader, ArrowStreamReader}
import org.apache.arrow.vector.ipc.message.{ArrowRecordBatch, MessageSerializer}
import org.apache.arrow.vector.ipc.WriteChannel
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel

import org.apache.spark.TaskContext
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.{DataFrame, Dataset, Row, SQLContext}
import org.apache.spark.sql.types._
import org.apache.spark.sql.execution.LogicalRDD
import org.apache.spark.sql.execution.arrow.ArrowUtils
import org.apache.spark.sql.execution.arrow.ArrowWriter
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch, ColumnVector}
import org.apache.spark.util.Utils

/**
 * Iterator interface to iterate over Arrow record batches and return rows
 */
trait ArrowRowIterator extends Iterator[org.apache.spark.sql.catalyst.InternalRow] {
}

private class ArrowBatchStreamWriter(
    schema: StructType,
    out: OutputStream,
    timeZoneId: String) {

  val arrowSchema = ArrowUtils.toArrowSchema(schema, timeZoneId)
  val writeChannel = new WriteChannel(Channels.newChannel(out))

  // Write the Arrow schema first, before batches
  MessageSerializer.serialize(writeChannel, arrowSchema)

  /**
   * Consume iterator to write each serialized ArrowRecordBatch to the stream.
   */
  def writeBatches(arrowBatchIter: Iterator[Array[Byte]]): Unit = {
    arrowBatchIter.foreach(writeChannel.write)
  }

  /**
   * End the Arrow stream, does not close output stream.
   */
  def end(): Unit = {
    writeChannel.writeIntLittleEndian(0);
  }
}

object ArrowConverters {
  def tryWithSafeFinally[T](block: => T)(finallyBlock: => Unit): T = {
    var originalThrowable: Throwable = null
    try {
      block
    } catch {
      case t: Throwable =>
        // Purposefully not using NonFatal, because even fatal exceptions
        // we don't want to have our finallyBlock suppress
        originalThrowable = t
        throw originalThrowable
    } finally {
      try {
        finallyBlock
      } catch {
        case t: Throwable if (originalThrowable != null && originalThrowable != t) =>
          originalThrowable.addSuppressed(t)
          throw originalThrowable
      }
    }
  }

  /**
   * Maps Iterator from InternalRow to serialized ArrowRecordBatches. Limit ArrowRecordBatch size
   * in a batch by setting maxRecordsPerBatch or use 0 to fully consume rowIter.
   */
  def toBatchIterator(
      rowIter: Iterator[org.apache.spark.sql.Row],
      schema: StructType,
      maxRecordsPerBatch: Int,
      timeZoneId: String,
      context: TaskContext): Iterator[Array[Byte]] = {

    val arrowSchema = ArrowUtils.toArrowSchema(schema, timeZoneId)
    val allocator =
      ArrowUtils.rootAllocator.newChildAllocator("toBatchIterator", 0, Long.MaxValue)

    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    val unloader = new VectorUnloader(root)
    val arrowWriter = ArrowWriter.create(root)

    context.addTaskCompletionListener { _ =>
      root.close()
      allocator.close()
    }

    val encoder = RowEncoder(schema)

    new Iterator[Array[Byte]] {

      override def hasNext: Boolean = rowIter.hasNext || {
        root.close()
        allocator.close()
        false
      }

      override def next(): Array[Byte] = {
        val out = new ByteArrayOutputStream()
        val writeChannel = new WriteChannel(Channels.newChannel(out))

        tryWithSafeFinally {
          var rowCount = 0
          while (rowIter.hasNext && (maxRecordsPerBatch <= 0 || rowCount < maxRecordsPerBatch)) {
            val row: org.apache.spark.sql.Row = rowIter.next()
            val internalRow: org.apache.spark.sql.catalyst.InternalRow = encoder.toRow(row)
            arrowWriter.write(internalRow)
            rowCount += 1
          }
          arrowWriter.finish()
          val batch = unloader.getRecordBatch()
          MessageSerializer.serialize(writeChannel, batch)
          batch.close()
        } {
          arrowWriter.reset()
        }

        out.toByteArray
      }
    }
  }

  /**
   * Maps Iterator from ArrowPayload to Row. Returns a pair containing the row iterator
   * and the schema from the first batch of Arrow data read.
   */
  def fromPayloadIterator(
      payloadIter: Iterator[Array[Byte]],
      context: TaskContext,
      schema: StructType): ArrowRowIterator = {
    val allocator =
      ArrowUtils.rootAllocator.newChildAllocator("fromPayloadIterator", 0, Long.MaxValue)

    new ArrowRowIterator {
      private var reader: ArrowStreamReader = null
      private var rowIter = if (payloadIter.hasNext) nextBatch() else Iterator.empty

      context.addTaskCompletionListener { _ =>
        closeReader()
        allocator.close()
      }

      override def hasNext: Boolean = rowIter.hasNext || {
        closeReader()
        if (payloadIter.hasNext) {
          rowIter = nextBatch()
          true
        } else {
          allocator.close()
          false
        }
      }

      override def next(): org.apache.spark.sql.catalyst.InternalRow = rowIter.next()

      private def closeReader(): Unit = {
        if (reader != null) {
          reader.close()
          reader = null
        }
      }

      private val encoder = RowEncoder(schema)

      private def nextBatch(): Iterator[org.apache.spark.sql.catalyst.InternalRow] = {
        val in = new ByteArrayReadableSeekableByteChannel(payloadIter.next())
        reader = new ArrowStreamReader(in, allocator)
        reader.loadNextBatch()  // throws IOException
        val root = reader.getVectorSchemaRoot  // throws IOException

        val columns = root.getFieldVectors.asScala.map { vector =>
          new ArrowColumnVector(vector).asInstanceOf[ColumnVector]
        }.toArray

        val batch = new ColumnarBatch(columns)
        batch.setNumRows(root.getRowCount)
        batch.rowIterator().asScala
      }
    }
  }

  def toArrowBatchRdd(
      df: DataFrame,
      sparkSession: SparkSession): Array[Byte] = {

    val schema = df.schema
    val maxRecordsPerBatch = sparkSession.sessionState.conf.arrowMaxRecordsPerBatch
    val timeZoneId = sparkSession.sessionState.conf.sessionLocalTimeZone

    val encoder = org.apache.spark.sql.Encoders.BINARY

    val batches: Array[Array[Byte]] = df.mapPartitions(
      iter => toBatchIterator(iter, schema, maxRecordsPerBatch, timeZoneId, TaskContext.get())
    )(encoder).collect()

    val out = new ByteArrayOutputStream()
    val batchWriter = new ArrowBatchStreamWriter(schema, out, timeZoneId)
    batchWriter.writeBatches(batches.iterator)
    batchWriter.end()

    out.toByteArray()
  }

  def toDataFrame(
      payloadRDD: JavaRDD[Array[Byte]],
      schema: StructType,
      sparkSession: SparkSession): DataFrame = {
    val rdd = payloadRDD.rdd.mapPartitions { iter =>
      val context = TaskContext.get()
      ArrowConverters.fromPayloadIterator(iter, context, schema)
    }

    val logger = new Logger("Arrow", 0)
    val invoke = new Invoke()
    var streaming: Boolean = false

    invoke.invoke(
      sparkSession.sqlContext.getClass,
      "",
      sparkSession.sqlContext,
      "internalCreateDataFrame",
      Array(rdd, schema, streaming.asInstanceOf[Object]),
      logger
    ).asInstanceOf[DataFrame]
  }
}
