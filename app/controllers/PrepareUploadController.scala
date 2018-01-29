package controllers

import javax.inject.{Inject, Singleton}

import domain.{Link, PrepareUploadService, PreparedUpload, UploadSettings}
import play.api.libs.json.{JsPath, JsValue, Json, Writes}
import play.api.mvc.Action
import uk.gov.hmrc.play.bootstrap.controller.BaseController
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.concurrent.Future

@Singleton
class PrepareUploadController @Inject() (prepareUploadService : PrepareUploadService) extends BaseController  {

  implicit val uploadedFileSettingsFormat = Json.format[UploadSettings]

  implicit val linkWrites: Writes[Link] = (
    (JsPath \ "href").write[String] and
      (JsPath \ "method").write[String]
    )(unlift(Link.unapply))

  implicit val uploadWrites: Writes[PreparedUpload] = new Writes[PreparedUpload] {
    def writes(preparedUpload: PreparedUpload) = Json.obj(
      "reference" -> preparedUpload.reference.value,
      "_links" -> Json.obj(
        "upload" -> preparedUpload.uploadLink,
        "download" -> preparedUpload.downloadLink
      )
    )
  }

  def prepareUpload(): Action[JsValue] = Action.async(parse.json) { implicit request =>
    withJsonBody[UploadSettings] {
      (fileUploadDetails: UploadSettings) =>
      val result: PreparedUpload = prepareUploadService.setupUpload(fileUploadDetails)
      Future.successful(Ok(Json.toJson(result)))
    }
  }

}