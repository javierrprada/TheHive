package org.thp.thehive.connector.cortex.services

import java.util.zip.{ZipEntry, ZipFile}

import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.Try

import gremlin.scala._
import javax.inject.{Inject, Singleton}
import org.thp.scalligraph.EntitySteps
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models.{BaseVertexSteps, Database, Entity}
import org.thp.scalligraph.services._
import org.thp.thehive.connector.cortex.models.ReportTemplate

@Singleton
class ReportTemplateSrv @Inject()(
    implicit db: Database
) extends VertexSrv[ReportTemplate, ReportTemplateSteps] {

  override def steps(raw: GremlinScala[Vertex])(implicit graph: Graph): ReportTemplateSteps = new ReportTemplateSteps(raw)

  override def get(idOrName: String)(implicit graph: Graph): ReportTemplateSteps =
    if (db.isValidId(idOrName)) getByIds(idOrName)
    else initSteps.getByName(idOrName)

  def readZipEntry(file: ZipFile, entry: ZipEntry): Try[String] =
    Try {
      val stream = file.getInputStream(entry)
      try Source.fromInputStream(stream).mkString
      finally stream.close()
    }

  /**
    * Creates or updates if found templates contained in a zip file
    *
    * @param db          needed database connection
    * @param file        the zip file
    * @param authContext needed auth context for db query
    * @return
    */
  def importZipFile(db: Database, file: ZipFile)(implicit authContext: AuthContext): Map[String, Try[ReportTemplate with Entity]] =
    file
      .entries
      .asScala
      .filter(entry => !entry.isDirectory)
      .map(entry => entry.getName.takeWhile(c => c != '/' && c != '.') -> entry)
      .foldLeft(Map.empty[String, Try[ReportTemplate with Entity]]) {
        case (templateMap, (analyzerId, _)) if templateMap.contains(analyzerId) => templateMap
        case (templateMap, (analyzerId, entry)) =>
          val reportTemplate = readZipEntry(file, entry)
            .flatMap { content =>
              db.tryTransaction { implicit graph =>
                get(analyzerId)
                  .update("content" -> content)
                  .recoverWith { case _ => create(ReportTemplate(analyzerId, content)) }
              }
            }
          templateMap + (analyzerId -> reportTemplate)
      }
}

@EntitySteps[ReportTemplate]
class ReportTemplateSteps(raw: GremlinScala[Vertex])(implicit db: Database, graph: Graph)
    extends BaseVertexSteps[ReportTemplate, ReportTemplateSteps](raw) {

  def get(idOrName: String): ReportTemplateSteps =
    if (db.isValidId(idOrName)) getByIds(idOrName)
    else getByName(idOrName)

  /**
    * Looks for a template that has the workerId supplied
    *
    * @param workerId the id to look for
    * @return
    */
  def getByName(workerId: String): ReportTemplateSteps = new ReportTemplateSteps(raw.has(Key("workerId") of workerId))

  override def newInstance(raw: GremlinScala[Vertex]): ReportTemplateSteps = new ReportTemplateSteps(raw)
}
