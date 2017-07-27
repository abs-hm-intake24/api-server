package controllers.system.asynchronous

import java.io.{File, FileWriter, IOException}
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.{Inject, Singleton}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import au.com.bytecode.opencsv.CSVWriter
import com.amazonaws.services.s3.{AmazonS3Client, AmazonS3ClientBuilder}
import controllers.system.asynchronous.ExportManager.TaskFinished
import org.slf4j.LoggerFactory
import parsers.SurveyCSVExporter
import play.api.{Configuration, Logger}
import uk.ac.ncl.openlab.intake24.FoodGroupRecord
import uk.ac.ncl.openlab.intake24.errors.AnyError
import uk.ac.ncl.openlab.intake24.services.fooddb.admin.FoodGroupsAdminService
import uk.ac.ncl.openlab.intake24.services.systemdb.admin._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

case class ExportTask(taskId: Long, surveyId: String, dateFrom: ZonedDateTime, dateTo: ZonedDateTime, dataScheme: CustomDataScheme,
                      foodGroups: Map[Int, FoodGroupRecord], localNutrients: Seq[LocalNutrientDescription], insertBOM: Boolean)


object ExportManager {

  case class QueueTask(task: ExportTask)

  case object TaskFinished

}

case class ExportManagerConfig(
                                batchSize: Int,
                                throttleRateMs: Int,
                                maxActiveTasks: Int,
                                s3BucketName: String,
                                s3PathPrefix: String
                              )

class ExportManager(exportService: DataExportService, config: ExportManagerConfig) extends Actor {

  case class CSVFileHandles(file: File, fileWriter: FileWriter, csvWriter: CSVWriter)

  val logger = LoggerFactory.getLogger(classOf[ExportManager])

  val s3client = AmazonS3ClientBuilder.defaultClient()

  val scheduler = new ThrottlingScheduler {
    def run(f: => Unit): Unit = context.system.scheduler.scheduleOnce(config.throttleRateMs milliseconds)(f)(play.api.libs.concurrent.Execution.defaultContext)
  }

  val queue = mutable.Queue[ExportTask]()


  var activeTasks = 0

  def dbSetStarted(taskId: Long): ThrottledTask[Unit] = ThrottledTask.fromAnyError {
    exportService.setExportTaskStarted(taskId)
  }

  def dbSetSuccessful(taskId: Long, downloadUrl: String): ThrottledTask[Unit] = ThrottledTask.fromAnyError {
    exportService.setExportTaskSuccess(taskId, downloadUrl)
  }

  def dbSetFailed(taskId: Long, cause: Throwable): ThrottledTask[Unit] = ThrottledTask.fromAnyError {
    exportService.setExportTaskFailure(taskId, cause)
  }

  def prepareFile(task: ExportTask): ThrottledTask[CSVFileHandles] = new ThrottledTask[CSVFileHandles] {
    def run(scheduler: ThrottlingScheduler)(onComplete: (Try[CSVFileHandles]) => Unit): Unit = {

      logger.debug(s"[${task.taskId}] creating a temporary CSV file for export")

      var file: File = null
      var fileWriter: FileWriter = null
      var csvWriter: CSVWriter = null

      try {

        file = SurveyCSVExporter.createTempFile()
        fileWriter = new FileWriter(file)
        csvWriter = new CSVWriter(fileWriter)

        logger.debug(s"[${task.taskId}] writing CSV header")

        SurveyCSVExporter.writeHeader(fileWriter, csvWriter, task.dataScheme, task.localNutrients, task.insertBOM)

        onComplete(Success(CSVFileHandles(file, fileWriter, csvWriter)))
      } catch {
        case e: IOException =>

          try {

            if (fileWriter != null)
              fileWriter.close()

            if (csvWriter != null)
              csvWriter.close()

            if (file != null)
              file.delete()
          } catch {
            case e: IOException =>
              logger.warn("Error when cleaning up CSV resources after prepareFile failure", e)
          }

          onComplete(Failure(e))
      }
    }
  }

  def tryWithHandles[T](handles: CSVFileHandles)(f: CSVFileHandles => T): Try[T] =
    try {
      Success(f(handles))
    } catch {
      case e: IOException =>
        try {
          handles.csvWriter.close()
          handles.fileWriter.close()
          handles.file.delete()
        } catch {
          case e2: IOException =>
            logger.warn("Error when cleaning up CSV resources after tryWithHandles failure", e)
        }

        Failure(e)
    }


  def closeFile(taskId: Long, handles: CSVFileHandles): ThrottledTask[Unit] = ThrottledTask.fromTry(tryWithHandles(handles) {
    logger.debug(s"[${taskId}] flushing and closing the CSV file")

    handles =>
      handles.csvWriter.close()
      handles.fileWriter.close()
  })

  def exportNextBatch(task: ExportTask, handles: CSVFileHandles, offset: Int): ThrottledTask[Boolean] = ThrottledTask.fromTry({

    logger.debug(s"[${task.taskId}] exporting next submissions batch using offset $offset")

    exportService.getSurveySubmissions(task.surveyId, Some(task.dateFrom), Some(task.dateTo), offset, config.batchSize, None) match {
      case Right(submissions) if submissions.size > 0 =>
        tryWithHandles(handles) {
          handles =>
            SurveyCSVExporter.writeSubmissionsBatch(handles.csvWriter, task.dataScheme, task.foodGroups, task.localNutrients, submissions)
            true
        }
      case Right(_) => Success(false)
      case Left(error) => Failure(error.exception)
    }
  })

  def exportRemaining(task: ExportTask, handles: CSVFileHandles, currentOffset: Int = 0): ThrottledTask[Unit] =
    exportNextBatch(task, handles, currentOffset).flatMap {
      hasMore =>
        if (hasMore)
          exportRemaining(task, handles, currentOffset + config.batchSize)
        else
          ThrottledTask {
            ()
          }
    }

  def uploadToS3(task: ExportTask, handles: CSVFileHandles): ThrottledTask[String] = ThrottledTask {

    logger.debug(s"[${task.taskId}] uploading CSV to S3")

    s3client.putObject("test", "export/test.csv", handles.file)
    s3client.getUrl("test", "export/test.csv").toString
  }

  def runExport(task: ExportTask): Unit = {

    val throttledTask = for (
      handles <- prepareFile(task);
      _ <- dbSetStarted(task.taskId);
      _ <- exportRemaining(task, handles);
      _ <- closeFile(task.taskId, handles);
      url <- uploadToS3(task, handles);
      _ <- dbSetSuccessful(task.taskId, url)
    ) yield url


    throttledTask.run(scheduler) {
      result =>
        result match {
          case Success(url) =>
            logger.debug(s"Export task ${task.taskId} successful, download URL is $url")
          case Failure(e) =>
            logger.error(s"Export task ${task.taskId} failed", e)
        }

        self ! TaskFinished
    }
  }

  def maybeStartNextTask() = {
    if (!queue.isEmpty && activeTasks < config.maxActiveTasks) {
      activeTasks += 1
      val task = queue.dequeue()

      runExport(task)

      logger.debug(s"Started task ${task.taskId}, current active tasks: " + activeTasks)
    }
  }

  def receive: Receive = {
    case ExportManager.QueueTask(task) =>
      queue += task
      logger.debug(s"Task ${task.taskId} queued, queue size: " + queue.size)
      maybeStartNextTask()

    case ExportManager.TaskFinished =>
      activeTasks -= 1
      maybeStartNextTask()
  }
}


object DataExporterCache {
  def progressKey(taskId: UUID) = s"DataExporter.$taskId.progress"

  def downloadUrlKey(taskId: UUID) = s"DataExporter.$taskId.url"
}

@Singleton
class AsynchronousDataExporter @Inject()(actorSystem: ActorSystem,
                                         configuration: Configuration,
                                         exportService: DataExportService,
                                         surveyAdminService: SurveyAdminService,
                                         foodGroupsAdminService: FoodGroupsAdminService) {

  val logger = LoggerFactory.getLogger(classOf[AsynchronousDataExporter])

  val configSection = "intake24.asyncDataExporter"

  val exportManagerConfig = ExportManagerConfig(
    configuration.getInt(s"$configSection.batchSize").get,
    configuration.getInt(s"$configSection.throttleRateMs").get,
    configuration.getInt(s"$configSection.maxConcurrentTasks").get,
    configuration.getString(s"$configSection.s3.bucketName").get,
    configuration.getString(s"$configSection.s3.pathPrefix").get
  )

  val exportManager = actorSystem.actorOf(Props(classOf[ExportManager], exportService, exportManagerConfig), "ExportManager")

  def unwrapAnyError[T](r: Either[AnyError, T]): Either[Throwable, T] = r.left.map(_.exception)

  def logFailureAndNotifyManager(taskId: Long, cause: Throwable, manager: ActorRef) = {
    exportService.setExportTaskFailure(taskId, cause) match {
      case Right(()) => manager
    }
  }

  def queueCsvExport(userId: Long, surveyId: String, dateFrom: ZonedDateTime, dateTo: ZonedDateTime, insertBOM: Boolean): Either[AnyError, Long] = {
    for (
      survey <- surveyAdminService.getSurveyParameters(surveyId).right;
      foodGroups <- foodGroupsAdminService.listFoodGroups(survey.localeId).right;
      dataScheme <- surveyAdminService.getCustomDataScheme(survey.schemeId).right;
      localNutrients <- surveyAdminService.getLocalNutrientTypes(survey.localeId).right;
      taskId <- exportService.createExportTask(ExportTaskParameters(userId, surveyId, dateFrom, dateTo)).right)
      yield {

        exportManager ! ExportManager.QueueTask(ExportTask(taskId, surveyId, dateFrom, dateTo, dataScheme, foodGroups, localNutrients, insertBOM))

        taskId
      }
  }
}