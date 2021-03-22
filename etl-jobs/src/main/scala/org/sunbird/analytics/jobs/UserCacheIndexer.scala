package org.sunbird.analytics.jobs

import com.redislabs.provider.redis._
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.commons.lang.StringUtils
import org.apache.spark.sql.functions.{col, collect_set, concat_ws, explode_outer, lit, lower, to_json, _}
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode, SparkSession}
import org.apache.spark.storage.StorageLevel
import org.sunbird.analytics.util.JSONUtils

case class AnonymousData(userid:String, usersignintype: String, userlogintype: String)
case class LocationId(userid: String, locationids: List[String])
case class ProfileUserType(userid: String, usertype: String, usersubtype: String)

object UserCacheIndexer extends Serializable {

  private val config: Config = ConfigFactory.load

  def main(args: Array[String]): Unit = {

    var specificUserId: String = null
    var fromSpecificDate: String = null
    var populateAnonymousData: String = "false"
    if (!args.isEmpty) {
      specificUserId = args(0) // userid
      fromSpecificDate = args(1) // date in YYYY-MM-DD format
      populateAnonymousData = args(2) // populate anonymous data
    }
    val sunbirdKeyspace = "sunbird"

    val complexFieldTypes = Array("array", "map")
    val redisIndex = if (!populateAnonymousData.equalsIgnoreCase("true")) config.getString("redis.user.database.index") else config.getString("redis.user.input.index")

    val spark: SparkSession =
      SparkSession
        .builder()
        .appName("AppName")
        .config("spark.master", "local[*]")
        .config("spark.cassandra.connection.host", config.getString("spark.cassandra.connection.host"))
        .config("spark.redis.host", config.getString("redis.host"))
        .config("spark.redis.port", config.getString("redis.port"))
        .config("spark.redis.db", redisIndex)
        .config("spark.redis.max.pipeline.size", config.getString("redis.max.pipeline.size"))
        .config("spark.cassandra.read.timeoutMS", "300000")
        .config("spark.redis.scan.count", config.getString("redis.scan.count"))
        .getOrCreate()

    def filterUserData(userDF: DataFrame): DataFrame = {
      if (null != specificUserId && !StringUtils.equalsIgnoreCase(specificUserId, "null")) {
        println("Filtering for " + specificUserId)
        userDF.filter(col("id") === specificUserId)
      } else if (null != fromSpecificDate && !StringUtils.equalsIgnoreCase(fromSpecificDate, "null")) {
        println(s"Filtering for :$fromSpecificDate ")
        userDF.filter(col("updateddate").isNull || to_date(col("updateddate"), "yyyy-MM-dd HH:mm:ss:SSSZ").geq(lit(fromSpecificDate)))
      } else {
        println("No inputs found!! Returning user data without filter")
        userDF
      }
    }

    def populateUserData() {

      // Get CustodianOrgID
      val custRootOrgId = getCustodianOrgId()
      Console.println("#### custRootOrgId ####", custRootOrgId)

      val userDF = filterUserData(spark.read.format("org.apache.spark.sql.cassandra").option("table", "user").option("keyspace", sunbirdKeyspace).load()
          .select(col("firstname"),col("lastname"), col("email"), col("phone"),
            col("rootorgid"), col("framework"), col("userid"), col("language"))
        .filter(col("userid").isNotNull))
        .withColumn("medium", col("framework.medium"))  // Flattening the BGMS
        .withColumn("subject", col("framework.subject"))
        .withColumn("board", explode_outer(col("framework.board")))
        .withColumn("grade", col("framework.gradeLevel"))
        .withColumn("framework_id", explode_outer(col("framework.id")))
        .drop("framework")
        .withColumnRenamed("framework_id", "framework")
        .withColumn("usersignintype", when(col("rootorgid") === lit(custRootOrgId), "Self-Signed-In").otherwise("Validated"))
        .persist(StorageLevel.MEMORY_ONLY)

      Console.println("User records count:", userDF.count());
      val res1 = time(populateToRedis(userDF)) // Insert all userData Into redis
      Console.println("Time taken to insert user records", res1._1)

      val userOrgDF = spark.read.format("org.apache.spark.sql.cassandra").option("table", "user_organisation").option("keyspace", sunbirdKeyspace).load().filter(lower(col("isdeleted")) === "false")
        .select(col("userid"), col("organisationid"))

      val organisationDF = spark.read.format("org.apache.spark.sql.cassandra").option("table", "organisation").option("keyspace", sunbirdKeyspace).load()
        .select(col("id"), col("orgname"), col("channel"), col("externalid"),
          col("locationids"), col("isrootorg"))

      /**
        * Get a union of RootOrg and SubOrg information for a User
        */
      val userRootOrgDF = userDF
        .join(userOrgDF, userOrgDF.col("userid") === userDF.col("userid") && userOrgDF.col("organisationid") === userDF.col("rootorgid"))
        .select(userDF.col("userid"), col("rootorgid"), col("organisationid"))

      val userSubOrgDF = userDF
        .join(userOrgDF, userOrgDF.col("userid") === userDF.col("userid") && userOrgDF.col("organisationid") =!= userDF.col("rootorgid"))
        .select(userDF.col("userid"), col("rootorgid"), col("organisationid"))

      val rootOnlyOrgDF = userRootOrgDF.join(userSubOrgDF, Seq("userid"), "leftanti").select(userRootOrgDF.col("*"))
      val userOrgDenormDF = rootOnlyOrgDF.union(userSubOrgDF)

      /**
        * Resolve organization name for a RootOrg
        */
      val resolvedOrgNameDF = userOrgDenormDF
        .join(organisationDF, organisationDF.col("id") === userOrgDenormDF.col("rootorgid"), "left_outer")
        .groupBy("userid")
        .agg(concat_ws(",", collect_set("orgname")).as("orgname_resolved"))
      val filteredOrgDf = resolvedOrgNameDF.select(col("userid"), col("orgname_resolved").as("orgname"))

      val res2 = time(populateToRedis(filteredOrgDf))
      userDF.unpersist();
      Console.println("Time taken to insert user org records", res2._1)
    }

    def getCustodianOrgId(): String = {
      val systemSettingDF = spark.read.format("org.apache.spark.sql.cassandra").option("table", "system_settings").option("keyspace", sunbirdKeyspace).load()
      val df = systemSettingDF.where(col("id") === "custodianOrgId" && col("field") === "custodianOrgId").select(col("value"))
      df.select("value").first().getString(0)
    }

    def denormUserData(): Unit = {

      val userDF = filterUserData(spark.read.format("org.apache.spark.sql.cassandra").option("table", "user").option("keyspace", sunbirdKeyspace).load()
        .filter(col("userid").isNotNull))
        .select(col("userid"), col("profilelocation"), col("profileusertype")).persist(StorageLevel.MEMORY_ONLY)

      val userOrgDF = spark.read.format("org.apache.spark.sql.cassandra").option("table", "user_organisation").option("keyspace", sunbirdKeyspace).load().filter(lower(col("isdeleted")) === "false")
        .select(col("userid"), col("organisationid")).persist(StorageLevel.MEMORY_ONLY)

      val organisationDF = spark.read.format("org.apache.spark.sql.cassandra").option("table", "organisation").option("keyspace", sunbirdKeyspace).load()
        .select(col("id"), col("orgname"), col("externalid"), col("organisationtype")).persist(StorageLevel.MEMORY_ONLY)

      val locationDF = spark.read.format("org.apache.spark.sql.cassandra").option("table", "location").option("keyspace", sunbirdKeyspace).load().persist(StorageLevel.MEMORY_ONLY)

      val userDetailsDF = generateUserData(userDF, locationDF, userOrgDF, organisationDF)
      val res2 = time(populateToRedis(userDetailsDF.distinct()))
      Console.println("Time taken to insert report details", res2._1)
      userDF.unpersist()
    }

    def generateUserData(userDF: DataFrame, locationDF: DataFrame, userOrgDF: DataFrame, organisationDF: DataFrame): DataFrame = {
      implicit val sqlContext = new SQLContext(spark.sparkContext)
      val userLocationDF = getLocationDetails(userDF, locationDF)

      val profileUserTypeDF = getProfileUserType(userDF)

      val userLocationTypeDF = userLocationDF.join(profileUserTypeDF, Seq("userid"), "left")
        .drop("profilelocation", "profileusertype")

      val UserPivotDF = userLocationTypeDF
        .join(userOrgDF, userOrgDF.col("userid") === userLocationTypeDF.col("userid"), "left")
        .join(organisationDF, userOrgDF.col("organisationid") === organisationDF.col("id")
          && organisationDF.col("organisationtype").equalTo(2), "left")
        .withColumn("schoolname", organisationDF.col("orgname"))
        .withColumn("schooludisecode", organisationDF.col("externalid"))
        .select(
          userLocationTypeDF.col("userid"),
          col("schoolname"),
          col("schooludisecode"))

      val custodianUserDF = userLocationTypeDF.as("userLocDF")
        .join(UserPivotDF, Seq("userid"), "left")
        .select("userLocDF.*","schoolname", "schooludisecode")

      custodianUserDF
    }

    /***
      *
      * @param userDF, locationDF
      * @return userLocationDF (containing fields: userid, state,district,block,cluster)
      *
      * INPUT:
      * profilelocation (String)
      * ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
      * [{"id":"b38057d1-d41c-4b96-a548-939e335517aa","type":"district"},{"id":"1c0f7219-a0ec-4db0-83f7-e095fd2324ed","type":"block"},
      * {"id":"30a4dcf2-8990-4fbb-acd2-577692127aba","type":"state"}]
      *
      *  LOGIC: Get the locationIds from the profilelocation string and map it with the locationDF where type=state/district/block/cluster
      *
      *  OUTPUT:
      *    +------------------------------------+------+---------+---------------+
      *    |userid                              |state |district |block          |
      *    +------------------------------------+------+---------+----------------
      *    |56c2d9a3-fae9-4341-9862-4eeeead2e9a1|Andhra|Chittooor|Chittooorblock1|
      *    +------------------------------------+------+---------+----------------
      *
      */
    def getLocationDetails(userDF: DataFrame, locationDF: DataFrame)(implicit sqlContext: SQLContext): DataFrame = {
      import sqlContext.implicits._

     val userLocationIdDF =  userDF.select(col("profilelocation"), col("userid")).rdd.map(f => {
        var locList: List[String] = List()
        val userid = f.getString(1)
        if (null != f.getString(0)) {
          val loc = JSONUtils.deserialize[List[Map[String, String]]](f.getString(0))
          val stateIdList = loc.filter(f => f.getOrElse("type", "").equalsIgnoreCase("state")).map(f => f.getOrElse("id", ""))
          val stateId = if (!stateIdList.isEmpty) stateIdList.head else ""
          val districtIdList = loc.filter(f => f.getOrElse("type", "").equalsIgnoreCase("district")).map(f => f.getOrElse("id", ""))
          val districtId = if (!districtIdList.isEmpty) districtIdList.head else ""
          val blockIdList = loc.filter(f => f.getOrElse("type", "").equalsIgnoreCase("block")).map(f => f.getOrElse("id", ""))
          val blockId = if (!blockIdList.isEmpty) blockIdList.head else ""
          val clusterIdList = loc.filter(f => f.getOrElse("type", "").equalsIgnoreCase("cluster")).map(f => f.getOrElse("id", ""))
          val clusterId = if (!clusterIdList.isEmpty) clusterIdList.head else ""
          locList = List(stateId, districtId, blockId, clusterId)
        }
        LocationId(userid, locList)
      }).toDF

      val userExplodedLocationDF = userLocationIdDF
        .withColumn("exploded_location", explode_outer(col("locationids")))
        .select(col("userid"), col("exploded_location"), col("locationids"))

      val userStateDF = userExplodedLocationDF
        .join(locationDF, col("exploded_location") === locationDF.col("id") && locationDF.col("type") === "state")
        .select(userExplodedLocationDF.col("userid"), col("name").as("state"))

      val userDistrictDF = userExplodedLocationDF
        .join(locationDF, col("exploded_location") === locationDF.col("id") && locationDF.col("type") === "district")
        .select(userExplodedLocationDF.col("userid"), col("name").as("district"))

      val userBlockDF = userExplodedLocationDF
        .join(locationDF, col("exploded_location") === locationDF.col("id") && locationDF.col("type") === "block")
        .select(userExplodedLocationDF.col("userid"), col("name").as("block"))

      val userClusterDF = userExplodedLocationDF
        .join(locationDF, col("exploded_location") === locationDF.col("id") && locationDF.col("type") === "cluster")
        .select(userExplodedLocationDF.col("userid"), col("name").as("cluster"))

      userDF
        .join(userStateDF, Seq("userid"), "left")
        .join(userDistrictDF, Seq("userid"), "left")
        .join(userBlockDF, Seq("userid"), "left")
        .join(userClusterDF, Seq("userid"), "left")
        .select(
          userDF.col("*"),
          col("state"),
          col("district"),
          col("block"),
          col("cluster"))
    }

    /***
      *
      * @param userDF
      * @return userProfileTypeDF (containing fields: userid, usertype, usersubtype)
      *
      * INPUT:
      * profileusertype (String)
      * ------------------------------------------------------------------------------------------------------------------------------
      * {"subType":deo,"type":"teacher"}
      *
      *  LOGIC: usertype: profileusertype.type
      *         usersubtype: profileusertype.subType
      *
      *  OUTPUT:
      *    +------------------------------------+------+---------------+
      *    |userid                              |usertype |usersubtype |
      *    +------------------------------------+---------+------------+
      *    |56c2d9a3-fae9-4341-9862-4eeeead2e9a1|teacher  |deo         |
      *    +------------------------------------+---------+------------+
      *
      */

    def getProfileUserType(userDF: DataFrame)(implicit sqlContext: SQLContext): DataFrame = {
      import sqlContext.implicits._

      userDF.select(col("profileusertype"), col("userid")).rdd.map{f =>
        if (null != f.getString(0)) {
          val profileUserType = JSONUtils.deserialize[Map[String, String]](f.getString(0))
          val usertypeList = profileUserType.filter(f => f._1.equalsIgnoreCase("type")).map(f => f._2)
          val usertype = if (!usertypeList.isEmpty) usertypeList.head else ""
          val usersubtypeList = profileUserType.filter(f => f._1.equalsIgnoreCase("subType")).map(f => f._2)
          val usersubtype = if (!usersubtypeList.isEmpty) usersubtypeList.head else ""
          ProfileUserType(f.getString(1), usertype, usersubtype)
        } else ProfileUserType(f.getString(1),"","")
      }.toDF()
    }

    def populateToRedis(dataFrame: DataFrame): Unit = {
      val filteredDF = dataFrame.filter(col("userid").isNotNull)
      val schema = filteredDF.schema
      val complexFields = schema.fields.filter(field => complexFieldTypes.contains(field.dataType.typeName))

      val resultDF = complexFields.foldLeft(filteredDF)((df, field) =>
        df.withColumn(field.name, to_json(col(field.name))))

      resultDF.write
        .format("org.apache.spark.sql.redis")
        .option("host", config.getString("redis.host"))
        .option("port", config.getString("redis.port"))
        .option("dbNum", config.getString("redis.user.database.index"))
        .option("table", "user")
        .option("key.column", "userid")
        .mode(SaveMode.Append)
        .save()
    }

    if (!populateAnonymousData.equalsIgnoreCase("true")) {
      val res1 = time(populateUserData())
      val res2 = time(denormUserData())
      val totalTimeTaken = (res1._1 + res2._1).toDouble/1000
      Console.println("Time taken for individual steps:", "stage1", res1._1, "stage2", res2._1)
      Console.println("Time taken for complete script:", totalTimeTaken);
    } else {
      val res = time(populateAnonymousUserData())
      Console.println("Time taken for complete script:", res._1);
    }

    def populateAnonymousUserData(): Unit = {
      val sqlContext = new SQLContext(spark.sparkContext)
      import sqlContext.implicits._

      val anonymousDataRDD = spark.sparkContext.fromRedisKV("*")
      val filteredData = anonymousDataRDD.map{f => (f._1, JSONUtils.deserialize[Map[String, AnyRef]](f._2))}.filter(f => f._2.getOrElse("usersignintype", "").equals("Anonymous"))

      val anonymousDataDF = filteredData.map{f =>
        AnonymousData(f._1, f._2.getOrElse("usersignintype", "Anonymous").toString, f._2.getOrElse("userlogintype", "").toString)
      }.toDF()
      Console.println("Anonymous data user count: " + anonymousDataDF.count())

      val res1 = time(populateToRedis(anonymousDataDF)) // Insert all userData Into redis
      Console.println("Time taken to insert anonymous records", res1._1)
    }
  }

  def time[R](block: => R): (Long, R) = {
    val t0 = System.currentTimeMillis()
    val result = block // call-by-name
    val t1 = System.currentTimeMillis()
    ((t1 - t0), result)
  }

}