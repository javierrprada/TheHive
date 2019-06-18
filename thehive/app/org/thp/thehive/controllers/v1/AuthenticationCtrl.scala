package org.thp.thehive.controllers.v1
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

import play.api.Configuration
import play.api.mvc.{Action, AnyContent, Results}

import javax.inject.{Inject, Singleton}
import org.thp.scalligraph.AuthorizationError
import org.thp.scalligraph.auth.AuthSrv
import org.thp.scalligraph.controllers.{AuthenticateSrv, EntryPoint, FieldsParser}
import org.thp.scalligraph.models.Database
import org.thp.thehive.services.UserSrv

@Singleton
class AuthenticationCtrl @Inject()(
    entryPoint: EntryPoint,
    authenticated: AuthenticateSrv,
    configuration: Configuration,
    authSrv: AuthSrv,
    userSrv: UserSrv,
    db: Database,
    implicit val ec: ExecutionContext
) {

  val organisationHeader: String = configuration.get[String]("auth.organisationHeader")

  def login: Action[AnyContent] =
    entryPoint("login")
      .extract('login, FieldsParser[String].on("user"))
      .extract('password, FieldsParser[String].on("password"))
      .extract('organisation, FieldsParser[String].optional.on("organisation")) { implicit request ⇒
        val login: String                = request.body('login)
        val password: String             = request.body('password)
        val organisation: Option[String] = request.body('organisation) orElse request.headers.get(organisationHeader)
        for {
          authContext ← authSrv.authenticate(login, password, organisation)
          user        ← db.tryTransaction(userSrv.getOrFail(authContext.userId)(_))
          _ ← if (!user.locked) Success(())
          else Failure(AuthorizationError("Your account is locked"))
        } yield authenticated.setSessingUser(Results.Ok, authContext)
      }
}