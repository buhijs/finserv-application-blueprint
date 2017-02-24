package com.mapr.demo.finserv

import org.apache.spark.sql.SparkSession
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.spark.SparkConf
import org.apache.spark.streaming.{ Seconds, StreamingContext }
import org.apache.spark.streaming.kafka09.{ ConsumerStrategies, KafkaUtils, LocationStrategies }
import org.apache.spark.streaming.dstream.DStream

object SparkStreamingToHive {
  // Hive table name for persisted ticks
  val HIVE_TABLE: String = "streaming_ticks"

  case class Tick(date: Long, exchange: String, symbol: String, price: Double, volume: Double, sender: String, receivers: Array[String]) extends Serializable

  def parseTick(record: String): Tick = {
    val tick = new com.mapr.demo.finserv.Tick(record)
    val receivers: Array[String]  = (List(tick.getReceivers) map (_.toString)).toArray
    Tick(tick.getTimeInMillis, tick.getExchange, tick.getSymbolRoot, tick.getTradePrice, tick.getTradeVolume, tick.getSender, receivers)
  }

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      throw new IllegalArgumentException("You must specify the subscribe topic and hive table name. For example /user/mapr/taq:trades")
    }

    val Array(topics) = args

    val brokers = "maprdemo:9092" // not needed for MapR Streams, needed for Kafka
    val groupId = "sparkApplication"
    val batchInterval = "2"
    val pollTimeout = "10000"

    val sparkConf = new SparkConf().setAppName("TickStream")

    val ssc = new StreamingContext(sparkConf, Seconds(batchInterval.toInt))

    // Create direct kafka stream with brokers and topics
    val topicsSet = topics.split(",").toSet
    val kafkaParams = Map[String, String](
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> brokers,
      ConsumerConfig.GROUP_ID_CONFIG -> groupId,
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG ->
        "org.apache.kafka.common.serialization.StringDeserializer",
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest",
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG ->
        "org.apache.kafka.common.serialization.StringDeserializer",
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "true",
      "spark.kafka.poll.time" -> pollTimeout,
      "spark.streaming.kafka.consumer.poll.ms" -> "8192"
    )

    val consumerStrategy = ConsumerStrategies.Subscribe[String, String](topicsSet, kafkaParams)
    val messagesDStream = KafkaUtils.createDirectStream[String, String](
      ssc, LocationStrategies.PreferConsistent, consumerStrategy
    )
    // get message values from key,value
    val valuesDStream: DStream[String] = messagesDStream.map(_.value())

    valuesDStream.foreachRDD { rdd =>

      // There exists at least one element in RDD
      if (!rdd.isEmpty) {
        val count = rdd.count
        println("count received " + count)
//        val spark = SparkSession.builder.config(rdd.sparkContext.getConf).getOrCreate()
        val spark = SparkSession
          .builder()
          .appName("SparkSessionTicks")
          .config(rdd.sparkContext.getConf)
          .enableHiveSupport()
          .getOrCreate()
        import spark.implicits._
        import org.apache.spark.sql.functions._
        import org.apache.spark.sql.types._

        val df = rdd.map(parseTick).toDF()
        // Display the top 20 rows of DataFrame
        df.printSchema()
        df.show()

        df.createOrReplaceTempView("batchTable")

        // Validate the dataframe against the temp table
        df.groupBy("sender").count().show
        spark.sql("select sender, count(sender) as count from batchTable group by sender").show

        spark.sql("create table if not exists " + HIVE_TABLE + " as select * from batchTable")
        spark.sql("insert into " + HIVE_TABLE + " select * from batchTable limit 100")

        // Validate the dataframe against the Hive table
        df.groupBy("date").count().show
        spark.sql("select count(*) from" + HIVE_TABLE).show
      }
    }

    ssc.start()
    ssc.awaitTermination()

    ssc.stop(stopSparkContext = true, stopGracefully = true)
  }

}