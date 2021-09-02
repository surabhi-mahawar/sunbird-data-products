package org.sunbird.analytics.exhaust

import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession
import org.ekstep.analytics.framework.{DruidFilter, DruidQueryModel, FrameworkContext, JobConfig, StorageConfig}
import org.ekstep.analytics.framework.util.HadoopFileUtil
import org.sunbird.analytics.util.{BaseSpec, EmbeddedPostgresql}
import cats.syntax.either._
import ing.wbaa.druid._
import ing.wbaa.druid.client.DruidClient
import io.circe.Json
import io.circe.parser._
import org.apache.spark.sql.SQLContext
import org.ekstep.analytics.framework.conf.AppConf
import org.ekstep.analytics.framework.fetcher.DruidDataFetcher
import org.ekstep.analytics.framework.util.JSONUtils
import org.scalamock.scalatest.MockFactory

import scala.concurrent.Future
import org.scalatest.{BeforeAndAfterAll, Matchers}
import org.sunbird.cloud.storage.BaseStorageService

import java.text.SimpleDateFormat
import java.util.Calendar

class TestOnDemandDruidExhaustJob extends BaseSpec with Matchers with BeforeAndAfterAll with MockFactory with BaseReportsJob {
  val jobRequestTable = "job_request"
  implicit var spark: SparkSession = _
  implicit var sc: SparkContext = _
  val outputLocation = AppConf.getConfig("collection.exhaust.store.prefix")
  implicit var sqlContext : SQLContext = _
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val fc = mock[FrameworkContext]

  override def beforeAll(): Unit = {
    super.beforeAll()
    spark = getSparkSession();
    sc = spark.sparkContext
    sqlContext = new SQLContext(sc)
    EmbeddedPostgresql.start()
    EmbeddedPostgresql.createJobRequestTable()
  }

  override def afterAll() : Unit = {
    super.afterAll()
    new HadoopFileUtil().delete(spark.sparkContext.hadoopConfiguration, outputLocation)
    spark.close()
    EmbeddedPostgresql.close()
  }

  def getDate(pattern: String): SimpleDateFormat = {
    new SimpleDateFormat(pattern)
  }
  val reportDate = getDate("yyyyMMdd").format(Calendar.getInstance().getTime())
  "TestOnDemandDruidExhaustJob" should "generate report with correct values" in {
    val query = DruidQueryModel("scan", "dev_ml_project", "1901-01-01T00:00+00:00/2101-01-01T00:00:00+00:00", Option("all"),
      None, None, Option(List(DruidFilter("equals","private_program",Option("false"),None),
        DruidFilter("equals","sub_task_deleted_flag",Option("false"),None),
        DruidFilter("equals","task_deleted_flag",Option("false"),None),
        DruidFilter("equals","project_deleted_flag",Option("false"),None),
        DruidFilter("equals","program_id",Option("602512d8e6aefa27d9629bc3"),None),
        DruidFilter("equals","solution_id",Option("602a19d840d02028f3af00f0"),None))),None, None,
      Option(List("__time","createdBy","designation","state_name","district_name","block_name",
        "school_name","school_externalId", "organisation_name","program_name",
        "program_externalId","project_id","project_title_editable","project_description", "area_of_improvement",
        "project_duration","tasks","sub_task","task_evidence","task_remarks")), None, None,None,None,None,0)
    val druidQuery = DruidDataFetcher.getDruidQuery(query)
    val json: String ="""
            {"block_name":"ALLAVARAM","project_title_editable":"Test-कृपया उस प्रोजेक्ट का शीर्षक जोड़ें जिसे आप ब्लॉक के Prerak HT के लिए सबमिट करना चाहते हैं","task_evidence":"<NULL>",
            "designation":"hm","school_externalId":"unknown","project_duration":"1 महीना","__time":1.6133724E12,"sub_task":"<NULL>",
            "tasks":"यहां, आप अपने विद्यालय में परियोजना को पूरा करने के लिए अपने द्वारा किए गए कार्यों ( Tasks)को जोड़ सकते हैं।","project_id":"602a19d840d02028f3af00f0",
            "project_description":"test","program_externalId":"PGM-Prerak-Head-Teacher-of-the-Block-19-20-Feb2021",
            "organisation_name":"Pre-prod Custodian Organization","createdBy":"7651c7ab-88f9-4b23-8c1d-ac8d92844f8f",
            "area_of_improvement":"Education Leader","school_name":"MPPS (GN) SAMANTHAKURRU","district_name":"EAST GODAVARI",
            "program_name":"Prerak Head Teacher of the Block 19-20","state_name":"Andhra Pradesh","task_remarks":"<NULL>"}
             """.stripMargin
    val doc: Json = parse(json).getOrElse(Json.Null);
    val events = List(DruidScanResult.apply(doc))
    val results = DruidScanResults.apply("dev_ml_project_2020-06-08T00:00:00.000Z_2020-06-09T00:00:00.000Z_2020-11-20T06:13:29.089Z_45",List(),events)
    val druidResponse =  DruidScanResponse.apply(List(results))
    implicit val mockDruidConfig = DruidConfig.DefaultConfig
    val mockDruidClient = mock[DruidClient]
    (mockDruidClient.doQuery[DruidResponse](_:ing.wbaa.druid.DruidQuery)(_:DruidConfig)).expects(druidQuery, mockDruidConfig)
      .returns(Future(druidResponse)).anyNumberOfTimes()
    (fc.getDruidClient: () => DruidClient).expects().returns(mockDruidClient).anyNumberOfTimes()
    (fc.getHadoopFileUtil: () => HadoopFileUtil).expects()
      .returns(new HadoopFileUtil).anyNumberOfTimes()
    (fc.getStorageService(_:String,_:String,_:String)).expects(*,*,*)
      .returns(mock[BaseStorageService]).anyNumberOfTimes()

    var name = OnDemandDruidExhaustJob.name()
    EmbeddedPostgresql.execute(s"TRUNCATE $jobRequestTable")
    EmbeddedPostgresql.execute("INSERT INTO job_request (tag, request_id, job_id, status, request_data, requested_by, requested_channel, dt_job_submitted, " +
      "download_urls, dt_file_created, dt_job_completed, execution_time, err_message ,iteration, encryption_key) VALUES ('126796199493140000', " +
      "'888700F9A860E7A42DA968FBECDF3F22', 'druid-dataset', 'SUBMITTED', '{\"type\": \"ml-task-detail-exhaust\",\"params\":{\"programId\" :\"602512d8e6aefa27d9629bc3\"," +
      "\"solutionId\" : \"602a19d840d02028f3af00f0\"}}', '36b84ff5-2212-4219-bfed-24886969d890', 'ORG_001', '2021-05-09 19:35:18.666', '{}', " +
      "NULL, NULL, 0, '' ,0,'test@123');")
    val strConfig =
      """{"search":{"type":"none"},"model":"org.sunbird.analytics.exhaust.OnDemandDruidExhaustJob","modelParams":{"store":"local","container":"test-container",
        |"key":"ml_reports/","format":"csv"},"output":[{"to":"file","params":{"file":"ml_reports/"}}],"parallelization":8,"appName":"ML Druid Data Model"}""".stripMargin
    val jobConfig = JSONUtils.deserialize[JobConfig](strConfig)
    val requestId = "888700F9A860E7A42DA968FBECDF3F22"

    implicit val config = jobConfig
    implicit val conf = spark.sparkContext.hadoopConfiguration

    OnDemandDruidExhaustJob.execute()
    val postgresQuery = EmbeddedPostgresql.executeQuery("SELECT * FROM job_request WHERE job_id='druid-dataset'")
    while(postgresQuery.next()) {
      postgresQuery.getString("status") should be ("SUCCESS")
      postgresQuery.getString("requested_by") should be ("36b84ff5-2212-4219-bfed-24886969d890")
      postgresQuery.getString("requested_channel") should be ("ORG_001")
      postgresQuery.getString("err_message") should be ("")
      postgresQuery.getString("iteration") should be ("0")
      postgresQuery.getString("encryption_key") should be ("test@123")
      postgresQuery.getString("download_urls") should be (s"{ml_reports/ml-task-detail-exhaust/"+requestId+"_"+reportDate+".zip}")
    }
  }

  it should "insert status as Invalid request in the absence request_data" in {
    val query = DruidQueryModel("scan", "dev_ml_project", "1901-01-01T00:00+00:00/2101-01-01T00:00:00+00:00", Option("all"),
      None, None, Option(List(DruidFilter("equals","private_program",Option("false"),None),
        DruidFilter("equals","sub_task_deleted_flag",Option("false"),None),
        DruidFilter("equals","task_deleted_flag",Option("false"),None),
        DruidFilter("equals","project_deleted_flag",Option("false"),None),
        DruidFilter("equals","program_id",Option("602512d8e6aefa27d9629bc3"),None),
        DruidFilter("equals","solution_id",Option("602a19d840d02028f3af00f0"),None))),None, None,
      Option(List("__time","createdBy","designation","state_name","district_name","block_name",
        "school_name","school_externalId", "organisation_name","program_name",
        "program_externalId","project_id","project_title_editable","project_description", "area_of_improvement",
        "project_duration","tasks","sub_task","task_evidence","task_remarks")), None, None,None,None,None,0)
    val druidQuery = DruidDataFetcher.getDruidQuery(query)
    implicit val mockDruidConfig = DruidConfig.DefaultConfig
    val mockDruidClient = mock[DruidClient]
    (mockDruidClient.doQuery[DruidResponse](_:ing.wbaa.druid.DruidQuery)(_:DruidConfig)).expects(druidQuery, mockDruidConfig)
      .returns(Future.apply[DruidResponse](DruidScanResponse.apply(List()))).anyNumberOfTimes()
    (fc.getDruidClient: () => DruidClient).expects().returns(mockDruidClient).anyNumberOfTimes()

    EmbeddedPostgresql.execute(s"TRUNCATE $jobRequestTable")
    EmbeddedPostgresql.execute("INSERT INTO job_request (tag, request_id, job_id, status, request_data, requested_by, requested_channel, dt_job_submitted, " +
      "download_urls, dt_file_created, dt_job_completed, execution_time, err_message ,iteration, encryption_key) VALUES ('126796199493140000', " +
      "'888700F9A860E7A42DA968FBECDF3F22', 'druid-dataset', 'SUBMITTED', NULL, '36b84ff5-2212-4219-bfed-24886969d890', 'ORG_001', " +
      "'2021-05-09 19:35:18.666', NULL, NULL, NULL, 0, 'Invalid request' ,1,'test@123');")
    val strConfig =
      """{"search":{"type":"none"},"model":"org.sunbird.analytics.exhaust.OnDemandDruidExhaustJob","modelParams":{"store":"local","container":"test-container",
        |"key":"ml_reports/","format":"csv"},"output":[{"to":"file","params":{"file":"ml_reports/"}}],"parallelization":8,"appName":"ML Druid Data Model"}""".stripMargin
    val jobConfig = JSONUtils.deserialize[JobConfig](strConfig)
    implicit val config = jobConfig
    implicit val conf = spark.sparkContext.hadoopConfiguration

    OnDemandDruidExhaustJob.execute()
    val postgresQuery = EmbeddedPostgresql.executeQuery("SELECT * FROM job_request WHERE job_id='druid-dataset'")
    while (postgresQuery.next()) {
      postgresQuery.getString("status") should be ("FAILED")
      postgresQuery.getString("err_message") should be ("Invalid request")
      postgresQuery.getString("download_urls") should be ("{}")
    }
  }

  it should "insert as failed with No Range" in {
    val query = DruidQueryModel("scan", "dev_ml_project", "", Option("all"),
      None, None, Option(List(DruidFilter("equals","private_program",Option("false"),None),
        DruidFilter("equals","sub_task_deleted_flag",Option("false"),None),
        DruidFilter("equals","task_deleted_flag",Option("false"),None),
        DruidFilter("equals","project_deleted_flag",Option("false"),None),
        DruidFilter("equals","program_id",Option("602512d8e6aefa27d9629bc3"),None),
        DruidFilter("equals","solution_id",Option("602a19d840d02028f3af00f0"),None))),None, None,
      Option(List("__time","createdBy","designation","state_name","district_name","block_name",
        "school_name","school_externalId", "organisation_name","program_name",
        "program_externalId","project_id","project_title_editable","project_description", "area_of_improvement",
        "project_duration","tasks","sub_task","task_evidence","task_remarks")), None, None,None,None,None,0)
    val druidQuery = DruidDataFetcher.getDruidQuery(query)
    implicit val mockDruidConfig = DruidConfig.DefaultConfig
    val mockDruidClient = mock[DruidClient]
    (mockDruidClient.doQuery[DruidResponse](_:ing.wbaa.druid.DruidQuery)(_:DruidConfig)).expects(druidQuery, mockDruidConfig)
      .returns(Future.apply[DruidResponse](DruidScanResponse.apply(List()))).anyNumberOfTimes()
    (fc.getDruidClient: () => DruidClient).expects().returns(mockDruidClient).anyNumberOfTimes()

    EmbeddedPostgresql.execute(s"TRUNCATE $jobRequestTable")
    EmbeddedPostgresql.execute("INSERT INTO job_request (tag, request_id, job_id, status, request_data, requested_by, requested_channel, dt_job_submitted, " +
      "download_urls, dt_file_created, dt_job_completed, execution_time, err_message ,iteration, encryption_key) VALUES ('126796199493140000', " +
      "'888700F9A860E7A42DA968FBECDF3F22', 'druid-dataset', 'SUBMITTED', '{\"type\": \"ml-task-detail-exhaust-no-range\",\"params\":{\"programId\" :" +
      "\"602512d8e6aefa27d9629bc3\",\"solutionId\" : \"602a19d840d02028f3af00f0\"}}', '36b84ff5-2212-4219-bfed-24886969d890', 'ORG_001', " +
      "'2021-05-09 19:35:18.666', '{}', NULL, NULL, 0, '' ,0,'test@123');")
    val strConfig =
      """{"search":{ "type":"none"},"model":"org.sunbird.analytics.exhaust.OnDemandDruidExhaustJob","modelParams":{"store":"local","container":"test-container",
        |"key":"ml_reports/","format": "csv"},"output":[{"to":"file","params":{"file":"ml_reports/"}}],
        |"parallelization":8,"appName":"ML Druid Data Model"}""".stripMargin
    val jobConfig = JSONUtils.deserialize[JobConfig](strConfig)
    val requestId ="888700F9A860E7A42DA968FBECDF3F22"
    implicit val config = jobConfig
    implicit val conf = spark.sparkContext.hadoopConfiguration
    OnDemandDruidExhaustJob.execute()
    val postgresQuery = EmbeddedPostgresql.executeQuery("SELECT * FROM job_request WHERE job_id='druid-dataset'")
    while (postgresQuery.next()) {
      postgresQuery.getString("status") should be ("FAILED")
    }
  }

  it should "insert status as FAILED  with No Interval" in {
    val query = DruidQueryModel("scan", "dev_ml_project", "", Option("all"),
      None, None, Option(List(DruidFilter("equals","private_program",Option("false"),None),
        DruidFilter("equals","sub_task_deleted_flag",Option("false"),None),
        DruidFilter("equals","task_deleted_flag",Option("false"),None),
        DruidFilter("equals","project_deleted_flag",Option("false"),None),
        DruidFilter("equals","program_id",Option("602512d8e6aefa27d9629bc3"),None),
        DruidFilter("equals","solution_id",Option("602a19d840d02028f3af00f0"),None))),None, None,
      Option(List("__time","createdBy","designation","state_name","district_name","block_name",
        "school_name","school_externalId", "organisation_name","program_name",
        "program_externalId","project_id","project_title_editable","project_description", "area_of_improvement",
        "project_duration","tasks","sub_task","task_evidence","task_remarks")), None, None,None,None,None,0)
    val druidQuery = DruidDataFetcher.getDruidQuery(query)
    implicit val mockDruidConfig = DruidConfig.DefaultConfig
    val mockDruidClient = mock[DruidClient]
    (mockDruidClient.doQuery[DruidResponse](_:ing.wbaa.druid.DruidQuery)(_:DruidConfig)).expects(druidQuery, mockDruidConfig)
      .returns(Future.apply[DruidResponse](DruidScanResponse.apply(List()))).anyNumberOfTimes()
    (fc.getDruidClient: () => DruidClient).expects().returns(mockDruidClient).anyNumberOfTimes()

    EmbeddedPostgresql.execute(s"TRUNCATE $jobRequestTable")
    EmbeddedPostgresql.execute("INSERT INTO job_request (tag, request_id, job_id, status, request_data, requested_by, requested_channel, dt_job_submitted, " +
      "download_urls, dt_file_created, dt_job_completed, execution_time, err_message ,iteration, encryption_key) VALUES ('126796199493140000', " +
      "'888700F9A860E7A42DA968FBECDF3F22', 'druid-dataset', 'SUBMITTED', '{\"type\": \"ml-task-detail-exhaust-no-interval\",\"params\":{\"programId\" :" +
      "\"602512d8e6aefa27d9629bc3\",\"solutionId\" : \"602a19d840d02028f3af00f0\"}}', '36b84ff5-2212-4219-bfed-24886969d890', 'ORG_001', " +
      "'2021-05-09 19:35:18.666', '{}', NULL, NULL, 0, '' ,0,'test@123');")
    val strConfig =
      """{"search":{"type":"none"},"model":"org.sunbird.analytics.exhaust.OnDemandDruidExhaustJob","modelParams":{"store":"local","container":"test-container",
        |"key":"ml_reports/","format":"csv"},"output":[{"to":"file","params":{"file":"ml_reports/"}}],"parallelization":8,"appName":"ML Druid Data Model"}""".stripMargin
    val jobConfig = JSONUtils.deserialize[JobConfig](strConfig)
    implicit val config = jobConfig
    implicit val conf = spark.sparkContext.hadoopConfiguration
    OnDemandDruidExhaustJob.execute()
    val postgresQuery = EmbeddedPostgresql.executeQuery("SELECT * FROM job_request WHERE job_id='druid-dataset'")
    while (postgresQuery.next()) {
      postgresQuery.getString("status") should be ("FAILED")
      postgresQuery.getString("err_message") should be ("Invalid request")
    }
  }

  it should "insert status as Success with interval" in {
    val query = DruidQueryModel("scan", "dev_ml_project", "1901-01-01T05:30:00/2101-01-01T05:30:00", Option("all"),
      None, None, Option(List(DruidFilter("equals","private_program",Option("false"),None),
        DruidFilter("equals","sub_task_deleted_flag",Option("false"),None),
        DruidFilter("equals","task_deleted_flag",Option("false"),None),
        DruidFilter("equals","project_deleted_flag",Option("false"),None),
        DruidFilter("equals","program_id",Option("602512d8e6aefa27d9629bc3"),None),
        DruidFilter("equals","solution_id",Option("602a19d840d02028f3af00f0"),None))),None, None,
      Option(List("__time","createdBy","designation","state_name","district_name","block_name",
        "school_name","school_externalId", "organisation_name","program_name",
        "program_externalId","project_id","project_title_editable","project_description", "area_of_improvement",
        "project_duration","tasks","sub_task","task_evidence","task_remarks")), None, None,None,None,None,0)
    val druidQuery = DruidDataFetcher.getDruidQuery(query)

    val json: String ="""
            {"block_name":"ALLAVARAM","project_title_editable":"Test-कृपया उस प्रोजेक्ट का शीर्षक जोड़ें जिसे आप ब्लॉक के Prerak HT के लिए सबमिट करना चाहते हैं","task_evidence":"<NULL>",
            "designation":"hm","school_externalId":"unknown","project_duration":"1 महीना","__time":1.6133724E12,"sub_task":"<NULL>",
            "tasks":"यहां, आप अपने विद्यालय में परियोजना को पूरा करने के लिए अपने द्वारा किए गए कार्यों ( Tasks)को जोड़ सकते हैं।","project_id":"602a19d840d02028f3af00f0",
            "project_description":"test","program_externalId":"PGM-Prerak-Head-Teacher-of-the-Block-19-20-Feb2021",
            "organisation_name":"Pre-prod Custodian Organization","createdBy":"7651c7ab-88f9-4b23-8c1d-ac8d92844f8f",
            "area_of_improvement":"Education Leader","school_name":"MPPS (GN) SAMANTHAKURRU","district_name":"EAST GODAVARI",
            "program_name":"Prerak Head Teacher of the Block 19-20","state_name":"Andhra Pradesh","task_remarks":"<NULL>"}
             """.stripMargin
    val doc: Json = parse(json).getOrElse(Json.Null);
    val events = List(DruidScanResult.apply(doc))
    val results = DruidScanResults.apply("dev_ml_project_2020-06-08T00:00:00.000Z_2020-06-09T00:00:00.000Z_2020-11-20T06:13:29.089Z_45",List(),events)
    val druidResponse =  DruidScanResponse.apply(List(results))
    implicit val mockDruidConfig = DruidConfig.DefaultConfig
    val mockDruidClient = mock[DruidClient]
    (mockDruidClient.doQuery[DruidResponse](_:ing.wbaa.druid.DruidQuery)(_:DruidConfig)).expects(druidQuery, mockDruidConfig)
      .returns(Future(druidResponse)).anyNumberOfTimes()
    (fc.getDruidClient: () => DruidClient).expects().returns(mockDruidClient).anyNumberOfTimes()
    (fc.getHadoopFileUtil: () => HadoopFileUtil).expects()
      .returns(new HadoopFileUtil).anyNumberOfTimes()
    (fc.getStorageService(_:String,_:String,_:String)).expects(*,*,*)
      .returns(mock[BaseStorageService]).anyNumberOfTimes()

    EmbeddedPostgresql.execute(s"TRUNCATE $jobRequestTable")
    EmbeddedPostgresql.execute("INSERT INTO job_request (tag, request_id, job_id, status, request_data, requested_by, requested_channel, dt_job_submitted, " +
      "download_urls, dt_file_created, dt_job_completed, execution_time, err_message ,iteration, encryption_key) VALUES ('126796199493140000', " +
      "'888700F9A860E7A42DA968FBECDF3F22', 'druid-dataset', 'SUBMITTED', '{\"type\": \"ml-task-detail-exhaust-static-interval\",\"params\":{\"programId\" :" +
      "\"602512d8e6aefa27d9629bc3\",\"solutionId\" : \"602a19d840d02028f3af00f0\"}}', '36b84ff5-2212-4219-bfed-24886969d890', 'ORG_001', " +
      "'2021-05-09 19:35:18.666', '{}', NULL, NULL, 0, '' ,0,'test@123');")
    val strConfig =
      """{"search":{"type":"none"},"model":"org.sunbird.analytics.exhaust.OnDemandDruidExhaustJob","modelParams":{"store":"local","container":"test-container",
        |"key":"ml_reports/","format":"csv"},"output":[{"to":"file","params":{"file":"ml_reports/"}}],"parallelization":8,"appName":"ML Druid Data Model"}""".stripMargin
    val jobConfig = JSONUtils.deserialize[JobConfig](strConfig)
    val requestId = "888700F9A860E7A42DA968FBECDF3F22"
    implicit val config = jobConfig
    implicit val conf = spark.sparkContext.hadoopConfiguration
    OnDemandDruidExhaustJob.execute()
    val postgresQuery = EmbeddedPostgresql.executeQuery("SELECT * FROM job_request WHERE job_id='druid-dataset'")
    while (postgresQuery.next()) {
      postgresQuery.getString("status") should be ("SUCCESS")
      postgresQuery.getString("download_urls") should be (s"{ml_reports/ml-task-detail-exhaust/"+requestId+"_"+reportDate+".zip}")
    }
  }

  it should "insert status as SUCCESS encryption key not provided" in {
    val query = DruidQueryModel("scan", "dev_ml_project", "1901-01-01T00:00+00:00/2101-01-01T00:00:00+00:00", Option("all"),
      None, None, Option(List(DruidFilter("equals","private_program",Option("false"),None),
        DruidFilter("equals","sub_task_deleted_flag",Option("false"),None),
        DruidFilter("equals","task_deleted_flag",Option("false"),None),
        DruidFilter("equals","project_deleted_flag",Option("false"),None),
        DruidFilter("equals","program_id",Option("602512d8e6aefa27d9629bc3"),None),
        DruidFilter("equals","solution_id",Option("602a19d840d02028f3af00f0"),None))),None, None,
      Option(List("__time","createdBy","designation","state_name","district_name","block_name",
        "school_name","school_externalId", "organisation_name","program_name",
        "program_externalId","project_id","project_title_editable","project_description", "area_of_improvement",
        "project_duration","tasks","sub_task","task_evidence","task_remarks")), None, None,None,None,None,0)
    val druidQuery = DruidDataFetcher.getDruidQuery(query)
    val json: String ="""
            {"block_name":"ALLAVARAM","project_title_editable":"Test-कृपया उस प्रोजेक्ट का शीर्षक जोड़ें जिसे आप ब्लॉक के Prerak HT के लिए सबमिट करना चाहते हैं","task_evidence":"<NULL>",
            "designation":"hm","school_externalId":"unknown","project_duration":"1 महीना","__time":1.6133724E12,"sub_task":"<NULL>",
            "tasks":"यहां, आप अपने विद्यालय में परियोजना को पूरा करने के लिए अपने द्वारा किए गए कार्यों ( Tasks)को जोड़ सकते हैं।","project_id":"602a19d840d02028f3af00f0",
            "project_description":"test","program_externalId":"PGM-Prerak-Head-Teacher-of-the-Block-19-20-Feb2021",
            "organisation_name":"Pre-prod Custodian Organization","createdBy":"7651c7ab-88f9-4b23-8c1d-ac8d92844f8f","area_of_improvement":"Education Leader",
            "school_name":"MPPS (GN) SAMANTHAKURRU","district_name":"EAST GODAVARI","program_name":"Prerak Head Teacher of the Block 19-20",
            "state_name":"Andhra Pradesh","task_remarks":"<NULL>"}
             """.stripMargin
    val doc: Json = parse(json).getOrElse(Json.Null);
    val events = List(DruidScanResult.apply(doc))
    val results = DruidScanResults.apply("dev_ml_project_2020-06-08T00:00:00.000Z_2020-06-09T00:00:00.000Z_2020-11-20T06:13:29.089Z_45",List(),events)
    val druidResponse =  DruidScanResponse.apply(List(results))
    implicit val mockDruidConfig = DruidConfig.DefaultConfig
    val mockDruidClient = mock[DruidClient]
    (mockDruidClient.doQuery[DruidResponse](_:ing.wbaa.druid.DruidQuery)(_:DruidConfig)).expects(druidQuery, mockDruidConfig)
      .returns(Future(druidResponse)).anyNumberOfTimes()
    (fc.getDruidClient: () => DruidClient).expects().returns(mockDruidClient).anyNumberOfTimes()
    (fc.getHadoopFileUtil: () => HadoopFileUtil).expects()
      .returns(new HadoopFileUtil).anyNumberOfTimes()
    (fc.getStorageService(_:String,_:String,_:String)).expects(*,*,*)
      .returns(mock[BaseStorageService]).anyNumberOfTimes()

    EmbeddedPostgresql.execute(s"TRUNCATE $jobRequestTable")
    EmbeddedPostgresql.execute("INSERT INTO job_request (tag, request_id, job_id, status, request_data, requested_by, requested_channel, dt_job_submitted, " +
      "download_urls, dt_file_created, dt_job_completed, execution_time, err_message ,iteration, encryption_key) VALUES ('126796199493140000', " +
      "'888700F9A860E7A42DA968FBECDF3F22', 'druid-dataset', 'SUBMITTED', '{\"type\": \"ml-task-detail-exhaust\",\"params\":{\"programId\" :" +
      "\"602512d8e6aefa27d9629bc3\",\"solutionId\" : \"602a19d840d02028f3af00f0\"}}', '36b84ff5-2212-4219-bfed-24886969d890', 'ORG_001', " +
      "'2021-05-09 19:35:18.666', '{ml_reports/ml-task-detail-exhaust/1626335633616_888700F9A860E7A42DA968FBECDF3F22.csv}', NULL, NULL, 0, '' " +
      ",0,NULL);")

    val strConfig =
      """{"search":{"type":"none"},"model":"org.sunbird.analytics.exhaust.OnDemandDruidExhaustJob","modelParams":{"store":"local","container":"test-container",
        |"key":"ml_reports/","format":"csv"},"output":[{"to":"file","params":{"file":"ml_reports/"}}],"parallelization":8,"appName":"ML Druid Data Model"}""".stripMargin
    val jobConfig = JSONUtils.deserialize[JobConfig](strConfig)
    val requestId = "888700F9A860E7A42DA968FBECDF3F22"
    implicit val config = jobConfig
    implicit val conf = spark.sparkContext.hadoopConfiguration
    OnDemandDruidExhaustJob.execute()

    val postgresQuery = EmbeddedPostgresql.executeQuery("SELECT * FROM job_request WHERE job_id='ml-task-detail-exhaust'")
    while (postgresQuery.next()) {
      postgresQuery.getString("status") should be ("SUCCESS")
      postgresQuery.getString("err_message") should be ("")
      postgresQuery.getString("download_urls") should be (s"{ml_reports/ml-task-detail-exhaust/"+requestId+"_"+reportDate+".csv}")
    }
  }

  it should "execute the update and save request method" in {
    val jobRequest = JobRequest("126796199493140000", "888700F9A860E7A42DA968FBECDF3F22", "druid-dataset", "SUBMITTED", "{\"type\": \"ml-task-detail-exhaust\"," +
      "\"params\":{\"programId\" :\"602512d8e6aefa27d9629bc3\",\"solutionId\" : \"602a19d840d02028f3af00f0\"}}",
      "36b84ff5-2212-4219-bfed-24886969d890", "ORG_001", System.currentTimeMillis(), None, None, None, Option(0), Option("") ,Option(0),Option("test@123"))
    val req = new JobRequest()
    val jobRequestArr = Array(jobRequest)
    val storageConfig = StorageConfig("local", "", outputLocation)
    implicit val conf = spark.sparkContext.hadoopConfiguration

    OnDemandDruidExhaustJob.saveRequests(storageConfig, jobRequestArr)
  }

  it should "generate the report with quote column" in {
    val query = DruidQueryModel("scan", "dev_ml_project", "1901-01-01T00:00+00:00/2101-01-01T00:00:00+00:00", Option("all"),
      None, None, Option(List(DruidFilter("equals","private_program",Option("false"),None),
        DruidFilter("equals","sub_task_deleted_flag",Option("false"),None),
        DruidFilter("equals","task_deleted_flag",Option("false"),None),
        DruidFilter("equals","project_deleted_flag",Option("false"),None),
        DruidFilter("equals","program_id",Option("602512d8e6aefa27d9629bc3"),None),
        DruidFilter("equals","solution_id",Option("602a19d840d02028f3af00f0"),None))),None, None,
      Option(List("__time","createdBy","designation","state_name","district_name","block_name",
        "school_name","school_externalId", "organisation_name","program_name",
        "program_externalId","project_id","project_title_editable","project_description", "area_of_improvement",
        "project_duration","tasks","sub_task","task_evidence","task_remarks")), None, None,None,None,None,0)
    val druidQuery = DruidDataFetcher.getDruidQuery(query)
    val json: String ="""
            {"block_name":"ALLAVARAM","project_title_editable":"Test-कृपया उस प्रोजेक्ट का शीर्षक जोड़ें जिसे आप ब्लॉक के Prerak HT के लिए सबमिट करना चाहते हैं","task_evidence":"<NULL>",
            "designation":"hm","school_externalId":"unknown","project_duration":"1 महीना","__time":1.6133724E12,"sub_task":"<NULL>",
            "tasks":"यहां, आप अपने विद्यालय में परियोजना को पूरा करने के लिए अपने द्वारा किए गए कार्यों ( Tasks)को जोड़ सकते हैं।","project_id":"602a19d840d02028f3af00f0",
            "project_description":"test","program_externalId":"PGM-Prerak-Head-Teacher-of-the-Block-19-20-Feb2021",
            "organisation_name":"Pre-prod Custodian Organization","createdBy":"7651c7ab-88f9-4b23-8c1d-ac8d92844f8f","area_of_improvement":"Education Leader",
            "school_name":"MPPS (GN) SAMANTHAKURRU","district_name":"EAST GODAVARI","program_name":"Prerak Head Teacher of the Block 19-20",
            "state_name":"Andhra Pradesh","task_remarks":"<NULL>"}
             """.stripMargin
    val doc: Json = parse(json).getOrElse(Json.Null);
    val events = List(DruidScanResult.apply(doc))
    val results = DruidScanResults.apply("dev_ml_project_2020-06-08T00:00:00.000Z_2020-06-09T00:00:00.000Z_2020-11-20T06:13:29.089Z_45",List(),events)
    val druidResponse =  DruidScanResponse.apply(List(results))
    implicit val mockDruidConfig = DruidConfig.DefaultConfig
    val mockDruidClient = mock[DruidClient]
    (mockDruidClient.doQuery[DruidResponse](_:ing.wbaa.druid.DruidQuery)(_:DruidConfig)).expects(druidQuery, mockDruidConfig)
      .returns(Future(druidResponse)).anyNumberOfTimes()
    (fc.getDruidClient: () => DruidClient).expects().returns(mockDruidClient).anyNumberOfTimes()
    (fc.getHadoopFileUtil: () => HadoopFileUtil).expects()
      .returns(new HadoopFileUtil).anyNumberOfTimes()
    (fc.getStorageService(_:String,_:String,_:String)).expects(*,*,*)
      .returns(mock[BaseStorageService]).anyNumberOfTimes()

    EmbeddedPostgresql.execute(s"TRUNCATE $jobRequestTable")
    EmbeddedPostgresql.execute("INSERT INTO job_request (tag, request_id, job_id, status, request_data, requested_by, requested_channel, dt_job_submitted, " +
      "download_urls, dt_file_created, dt_job_completed, execution_time, err_message ,iteration, encryption_key) VALUES ('126796199493140000', " +
      "'888700F9A860E7A42DA968FBECDF3F22', 'druid-dataset', 'SUBMITTED', '{\"type\": \"ml-task-detail-exhaust-quote-column\",\"params\":{\"programId\" :\"602512d8e6aefa27d9629bc3\"," +
      "\"solutionId\" : \"602a19d840d02028f3af00f0\"}}', '36b84ff5-2212-4219-bfed-24886969d890', 'ORG_001', '2021-05-09 19:35:18.666', " +
      "'{}', NULL, NULL, 0, '' ,0,'test@123');")
    val strConfig =
      """{"search":{ "type":"none"},"model":"org.sunbird.analytics.exhaust.OnDemandDruidExhaustJob","modelParams":{"store":"local","container":"test-container",
        |        "key":"ml_reports/","format": "csv","quoteColumns": ["Role","Declared State","District","Block","School Name","Organisation Name",
        |        "Program Name","Project Title","Project Objective","Category","Tasks","Sub-Tasks","Remarks"]},"output":[{"to":"file",
        |        "params":{"file":"ml_reports/"}}],"parallelization":8,"appName":"ML Druid Data Model"}""".stripMargin
    val jobConfig = JSONUtils.deserialize[JobConfig](strConfig)
    val requestId ="888700F9A860E7A42DA968FBECDF3F22"
    implicit val config = jobConfig
    implicit val conf = spark.sparkContext.hadoopConfiguration
    OnDemandDruidExhaustJob.execute()

    val postgresQuery = EmbeddedPostgresql.executeQuery("SELECT * FROM job_request WHERE job_id='druid-dataset'")
    while (postgresQuery.next()) {
      postgresQuery.getString("status") should be ("SUCCESS")
      postgresQuery.getString("download_urls") should be (s"{ml_reports/ml-task-detail-exhaust/"+requestId+"_"+reportDate+".zip}")
    }
  }

  it should "generate the report  with no label" in {
    val query = DruidQueryModel("scan", "dev_ml_project", "1901-01-01T00:00+00:00/2101-01-01T00:00:00+00:00", Option("all"),
      None, None, Option(List(DruidFilter("equals","private_program",Option("false"),None),
        DruidFilter("equals","sub_task_deleted_flag",Option("false"),None),
        DruidFilter("equals","task_deleted_flag",Option("false"),None),
        DruidFilter("equals","project_deleted_flag",Option("false"),None),
        DruidFilter("equals","program_id",Option("602512d8e6aefa27d9629bc3"),None),
        DruidFilter("equals","solution_id",Option("602a19d840d02028f3af00f0"),None))),None, None,
      Option(List("__time","createdBy","designation","state_name","district_name","block_name",
        "school_name","school_externalId", "organisation_name","program_name",
        "program_externalId","project_id","project_title_editable","project_description", "area_of_improvement",
        "project_duration","tasks","sub_task","task_evidence","task_remarks")), None, None,None,None,None,0)
    val druidQuery = DruidDataFetcher.getDruidQuery(query)
    val json: String ="""
            {"block_name":"ALLAVARAM","project_title_editable":"Test-कृपया उस प्रोजेक्ट का शीर्षक जोड़ें जिसे आप ब्लॉक के Prerak HT के लिए सबमिट करना चाहते हैं","task_evidence":"<NULL>",
            "designation":"hm","school_externalId":"unknown","project_duration":"1 महीना","__time":1.6133724E12,"sub_task":"<NULL>",
            "tasks":"यहां, आप अपने विद्यालय में परियोजना को पूरा करने के लिए अपने द्वारा किए गए कार्यों ( Tasks)को जोड़ सकते हैं।","project_id":"602a19d840d02028f3af00f0",
            "project_description":"test","program_externalId":"PGM-Prerak-Head-Teacher-of-the-Block-19-20-Feb2021",
            "organisation_name":"Pre-prod Custodian Organization","createdBy":"7651c7ab-88f9-4b23-8c1d-ac8d92844f8f","area_of_improvement":"Education Leader",
            "school_name":"MPPS (GN) SAMANTHAKURRU","district_name":"EAST GODAVARI","program_name":"Prerak Head Teacher of the Block 19-20",
            "state_name":"Andhra Pradesh","task_remarks":"<NULL>"}
             """.stripMargin
    val doc: Json = parse(json).getOrElse(Json.Null);
    val events = List(DruidScanResult.apply(doc))
    val results = DruidScanResults.apply("dev_ml_project_2020-06-08T00:00:00.000Z_2020-06-09T00:00:00.000Z_2020-11-20T06:13:29.089Z_45",List(),events)
    val druidResponse =  DruidScanResponse.apply(List(results))
    implicit val mockDruidConfig = DruidConfig.DefaultConfig
    val mockDruidClient = mock[DruidClient]
    (mockDruidClient.doQuery[DruidResponse](_:ing.wbaa.druid.DruidQuery)(_:DruidConfig)).expects(druidQuery, mockDruidConfig)
      .returns(Future(druidResponse)).anyNumberOfTimes()
    (fc.getDruidClient: () => DruidClient).expects().returns(mockDruidClient).anyNumberOfTimes()
    (fc.getHadoopFileUtil: () => HadoopFileUtil).expects()
      .returns(new HadoopFileUtil).anyNumberOfTimes()
    (fc.getStorageService(_:String,_:String,_:String)).expects(*,*,*)
      .returns(mock[BaseStorageService]).anyNumberOfTimes()

    EmbeddedPostgresql.execute(s"TRUNCATE $jobRequestTable")
    EmbeddedPostgresql.execute("INSERT INTO job_request (tag, request_id, job_id, status, request_data, requested_by, requested_channel, dt_job_submitted, " +
      "download_urls, dt_file_created, dt_job_completed, execution_time, err_message ,iteration, encryption_key) VALUES ('126796199493140000'," +
      " '888700F9A860E7A42DA968FBECDF3F22', 'druid-dataset', 'SUBMITTED', '{\"type\": \"ml-task-detail-exhaust-no-label\",\"params\":" +
      "{\"programId\" :\"602512d8e6aefa27d9629bc3\",\"solutionId\" : \"602a19d840d02028f3af00f0\"}}', " +
      "'36b84ff5-2212-4219-bfed-24886969d890', 'ORG_001', '2021-05-09 19:35:18.666', '{}', NULL, NULL, 0, '' ,0,'test@123');")
    val strConfig =
      """{"search":{"type":"none"},"model":"org.sunbird.analytics.exhaust.OnDemandDruidExhaustJob","modelParams":{"store":"local","container":"test-container",
        |"key":"ml_reports/","format":"csv"},"output":[{"to":"file","params":{"file":"ml_reports/"}}],"parallelization":8,"appName":"ML Druid Data Model"}""".stripMargin
    val jobConfig = JSONUtils.deserialize[JobConfig](strConfig)
    implicit val config = jobConfig
    implicit val conf = spark.sparkContext.hadoopConfiguration
    OnDemandDruidExhaustJob.execute()
    val postgresQuery = EmbeddedPostgresql.executeQuery("SELECT * FROM job_request WHERE job_id='druid-dataset'")
    while (postgresQuery.next()) {
      postgresQuery.getString("status") should be ("FAILED")
    }
  }

  it should "insert status as failed when filter doesn't match" in {
    EmbeddedPostgresql.execute(s"TRUNCATE $jobRequestTable")
    EmbeddedPostgresql.execute("INSERT INTO job_request (tag, request_id, job_id, status, request_data, requested_by, requested_channel, dt_job_submitted, " +
      "download_urls, dt_file_created, dt_job_completed, execution_time, err_message ,iteration, encryption_key) VALUES ('126796199493140000', " +
      "'888700F9A860E7A42DA968FBECDF3F22', 'druid-dataset', 'SUBMITTED', '{\"type\": \"ml-task-detail-exhaust\",\"params\":" +
      "{\"programId\" :\"602512d8e6aefa27d9629bc3\",\"solutionId\" : \"602a19d840d0202sd23234jadasf0\"}}', " +
      "'36b84ff5-2212-4219-bfed-24886969d890', 'ORG_001', '2021-05-09 19:35:18.666', '{}', NULL, NULL, 0, 'No data found from druid' ,0,'test@123');")
    val strConfig =
      """{"search":{"type":"none"},"model":"org.sunbird.analytics.exhaust.OnDemandDruidExhaustJob","modelParams":{"store":"local","container":"test-container",
        |"key":"ml_reports/","format":"csv"},"output":[{"to":"file","params":{"file":"ml_reports/"}}],"parallelization":8,"appName":"ML Druid Data Model"}""".stripMargin

    val jobConfig = JSONUtils.deserialize[JobConfig](strConfig)
    implicit val config = jobConfig
    implicit val conf = spark.sparkContext.hadoopConfiguration

    val query = DruidQueryModel("scan", "dev_ml_project", "1901-01-01T00:00+00:00/2101-01-01T00:00:00+00:00", Option("all"),
      None, None, Option(List(DruidFilter("equals","private_program",Option("false"),None),
        DruidFilter("equals","sub_task_deleted_flag",Option("false"),None),
        DruidFilter("equals","task_deleted_flag",Option("false"),None),
        DruidFilter("equals","project_deleted_flag",Option("false"),None),
        DruidFilter("equals","program_id",Option("602512d8e6aefa27d9629bc3"),None),
        DruidFilter("equals","solution_id",Option("602a19d840d0202sd23234jadasf0"),None))),None, None,
      Option(List("__time","createdBy","designation","state_name","district_name","block_name",
        "school_name","school_externalId", "organisation_name","program_name",
        "program_externalId","project_id","project_title_editable","project_description", "area_of_improvement",
        "project_duration","tasks","sub_task","task_evidence","task_remarks")), None, None,None,None,None,0)
    val druidQuery = DruidDataFetcher.getDruidQuery(query)
    implicit val mockDruidConfig = DruidConfig.DefaultConfig
    val mockDruidClient = mock[DruidClient]
    (mockDruidClient.doQuery[DruidResponse](_:ing.wbaa.druid.DruidQuery)(_:DruidConfig)).expects(druidQuery, mockDruidConfig)
      .returns(Future.apply[DruidResponse](DruidScanResponse.apply(List()))).anyNumberOfTimes()
    (fc.getDruidClient: () => DruidClient).expects().returns(mockDruidClient).anyNumberOfTimes()

    OnDemandDruidExhaustJob.execute()
    val postgresQuery = EmbeddedPostgresql.executeQuery("SELECT * FROM job_request WHERE job_id='druid-dataset'")
    while(postgresQuery.next()) {
      postgresQuery.getString("status") should be ("FAILED")
      postgresQuery.getString("err_message") should be ("No data found from druid")
    }
  }

  it should "generate report with other generic query" in {
    val query = DruidQueryModel("scan", "dev_ml_observation", "1901-01-01T00:00+00:00/2101-01-01T00:00:00+00:00", Option("all"),
      None, None, Option(List(DruidFilter("equals","isAPrivateProgram",Option("false"),None),
        DruidFilter("equals","programId",Option("60549338acf1c71f0b2409c3"),None),
        DruidFilter("equals","solutionId",Option("605c934eda9dea6400302afc"),None))),None, None,
      Option(List("__time","createdBy","role_title","user_stateName","user_districtName","user_blockName","user_schoolName","user_schoolUDISE_code",
        "organisation_name","programName","programExternalId","solutionName","solutionExternalId","observationSubmissionId","questionExternalId","questionName",
        "questionResponseLabel","minScore","evidences","remarks")), None, None,None,None,None,0)
    val druidQuery = DruidDataFetcher.getDruidQuery(query)
    val json: String ="""
                        |{"questionName":"Tick the following which are available:","user_districtName":"ANANTAPUR","evidences":"<NULL>",
                        |"questionResponseLabel":"Newspaper Stands","solutionExternalId":"96e4f796-8d6c-11eb-abd8-441ca8998ea1-OBSERVATION-TEMPLATE_CHILD_V2",
                        |"user_schoolUDISE_code":"28226200815","role_title":"hm","__time":1.6258464E12,"minScore":"<NULL>","programName":"3.8.0 testing program",
                        |"date":"2021-07-09","questionExternalId":"P47_1616678305996-1616679757967","organisation_name":"Staging Custodian Organization",
                        |"createdBy":"7a8fa12b-75a7-41c5-9180-538f5ea5191a","remarks":"<NULL>","user_blockName":"AGALI",
                        |"solutionName":"School Needs Assessment - Primary","user_schoolName":"APMS AGALI","programExternalId":"PGM-3542-3.8.0_testing_program",
                        |"user_stateName":"Andhra Pradesh","observationSubmissionId":"60e848e9f1252714cff1c1a4"}
             """.stripMargin
    val doc: Json = parse(json).getOrElse(Json.Null);

    val json1: String ="""
                         |{"questionName":"Tick the following which are available:","user_districtName":"ANANTAPUR",
                         |"evidences":"<NULL>","questionResponseLabel":"Library books shelf/rack","solutionExternalId":
                         |"96e4f796-8d6c-11eb-abd8-441ca8998ea1-OBSERVATION-TEMPLATE_CHILD_V2",
                         |"user_schoolUDISE_code":"28226200815","role_title":"hm","__time":1.6258464E12,
                         |"minScore":"<NULL>","programName":"3.8.0 testing program","date":"2021-07-09",
                         |"questionExternalId":"P47_1616678305996-1616679757967",
                         |"organisation_name":"Staging Custodian Organization",
                         |"createdBy":"7a8fa12b-75a7-41c5-9180-538f5ea5191a","remarks":"<NULL>",
                         |"user_blockName":"AGALI",
                         |"solutionName":"School Needs Assessment - Primary","user_schoolName":"APMS AGALI",
                         |"programExternalId":"PGM-3542-3.8.0_testing_program","user_stateName":"Andhra Pradesh",
                         |"observationSubmissionId":"60e848e9f1252714cff1c1a4"}
                       """.stripMargin
    val doc1: Json = parse(json1).getOrElse(Json.Null);

    val events = List(DruidScanResult.apply(doc),DruidScanResult.apply(doc1))
    val results = DruidScanResults.apply("dev_ml_observation_2020-06-08T00:00:00.000Z_2020-06-09T00:00:00.000Z_2020-11-20T06:13:29.089Z_45",List(),events)
    val druidResponse =  DruidScanResponse.apply(List(results))
    implicit val mockDruidConfig = DruidConfig.DefaultConfig
    val mockDruidClient = mock[DruidClient]
    (mockDruidClient.doQuery[DruidResponse](_:ing.wbaa.druid.DruidQuery)(_:DruidConfig)).expects(druidQuery, mockDruidConfig)
      .returns(Future(druidResponse)).anyNumberOfTimes()
    (fc.getDruidClient: () => DruidClient).expects().returns(mockDruidClient).anyNumberOfTimes()
    (fc.getHadoopFileUtil: () => HadoopFileUtil).expects()
      .returns(new HadoopFileUtil).anyNumberOfTimes()
    (fc.getStorageService(_:String,_:String,_:String)).expects(*,*,*)
      .returns(mock[BaseStorageService]).anyNumberOfTimes()

    EmbeddedPostgresql.execute(s"TRUNCATE $jobRequestTable")
    EmbeddedPostgresql.execute("INSERT INTO job_request (tag, request_id, job_id, status, request_data, requested_by, requested_channel, dt_job_submitted, " +
      "download_urls, dt_file_created, dt_job_completed, execution_time, err_message ,iteration, encryption_key) VALUES ('126796199493140000', " +
      "'999700F9A860E7A42DA968FBECDF3F22', 'druid-dataset', 'SUBMITTED', '{\"type\": \"ml-obs-question-detail-exhaust\",\"params\":" +
      "{\"programId\" :\"60549338acf1c71f0b2409c3\",\"solutionId\" : \"605c934eda9dea6400302afc\"}}', " +
      "'36b84ff5-2212-4219-bfed-24886969d890', 'ORG_001', '2021-07-15 19:35:18.666', '{}', NULL, NULL, 0, '' ,0,'demo@123');")
    val strConfig =
      """{"search":{"type":"none"},"model":"org.sunbird.analytics.exhaust.OnDemandDruidExhaustJob","modelParams":{"store":"local","container":"test-container",
        |"key":"ml_reports/","format":"csv"},"output":[{"to":"file","params":{"file":"ml_reports/"}}],"parallelization":8,"appName":"ML Druid Data Model"}""".stripMargin

    val jobConfig = JSONUtils.deserialize[JobConfig](strConfig)
    val requestId = "999700F9A860E7A42DA968FBECDF3F22"

    implicit val config = jobConfig
    implicit val conf = spark.sparkContext.hadoopConfiguration
    OnDemandDruidExhaustJob.execute()
    val postgresQuery = EmbeddedPostgresql.executeQuery("SELECT * FROM job_request WHERE job_id='druid-dataset'")
    while(postgresQuery.next()) {
      postgresQuery.getString("status") should be ("SUCCESS")
      postgresQuery.getString("requested_by") should be ("36b84ff5-2212-4219-bfed-24886969d890")
      postgresQuery.getString("requested_channel") should be ("ORG_001")
      postgresQuery.getString("err_message") should be ("")
      postgresQuery.getString("iteration") should be ("0")
      postgresQuery.getString("encryption_key") should be ("demo@123")
      postgresQuery.getString("download_urls") should be (s"{ml_reports/ml-obs-question-detail-exhaust/"+requestId+"_"+reportDate+".zip}")
    }
  }
}