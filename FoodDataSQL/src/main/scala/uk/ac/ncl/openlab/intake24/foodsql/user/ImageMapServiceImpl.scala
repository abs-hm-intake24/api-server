package uk.ac.ncl.openlab.intake24.foodsql.user

import anorm.Macro.ColumnNaming
import javax.inject.Inject
import javax.sql.DataSource
import anorm.NamedParameter.symbol
import anorm.{Macro, SQL, SqlParser, sqlToSimple, ~}
import com.google.inject.Singleton
import com.google.inject.name.Named
import uk.ac.ncl.openlab.intake24.errors.{LookupError, RecordNotFound}
import uk.ac.ncl.openlab.intake24.services.fooddb.user.{ImageMapService, UserImageMap, UserImageMapObject}
import uk.ac.ncl.openlab.intake24.sql.{SqlDataService, SqlResourceLoader}

@Singleton
class ImageMapServiceImpl @Inject()(@Named("intake24_foods") val dataSource: DataSource) extends ImageMapService with SqlDataService with SqlResourceLoader {

  private case class ObjectRow(imageMapId: String, objectId: Int, description: String, navigationIndex: Int, outlineCoordinates: Array[Double],
                               overlayImagePath: String)

  private case class ImageMapRow(id: String, description: String, path: String)

  def getImageMaps(ids: Seq[String]): Either[LookupError, Seq[UserImageMap]] = tryWithConnection {
    implicit conn =>
      withTransaction {


        val imageMaps = SQL("select image_maps.id, description, path from image_maps join processed_images on image_maps.base_image_id = processed_images.id where image_maps.id in ({ids})")
          .on('ids -> ids)
          .executeQuery()
          .as(Macro.namedParser[ImageMapRow].*)
          .foldLeft(Map[String, ImageMapRow]()) {
            case (res, row) => res + (row.id -> row)
          }

        val missingIds = ids.filterNot(imageMaps.keySet.contains(_))

        if (missingIds.nonEmpty)
          Left(RecordNotFound(new RuntimeException(s"Missing image maps: ${missingIds.mkString(", ")}")))
        else {
          val objects = SQL(
            """select image_map_id,
              |       image_map_objects.id as object_id,
              |       description,
              |       navigation_index,
              |       outline_coordinates,
              |       path                 as overlay_image_path
              |from image_map_objects
              |       join processed_images i on image_map_objects.overlay_image_id = i.id
              |where image_map_id in ({ids})""".stripMargin)
            .on('ids -> ids)
            .executeQuery()
            .as(Macro.namedParser[ObjectRow](ColumnNaming.SnakeCase).*)
            .groupBy(_.imageMapId)

          Right(ids.map {
            id =>
            val userObjects = objects.get(id) match {
              case Some(objects) =>
                objects.sortBy(_.navigationIndex).map {
                  obj =>
                    UserImageMapObject(obj.objectId, obj.description, obj.overlayImagePath, obj.outlineCoordinates)
                }
              case None => List()
            }

              UserImageMap(imageMaps(id).path, userObjects)

          })

        }
      }
  }

  def getImageMap(id: String): Either[LookupError, UserImageMap] = getImageMaps(Seq(id)).right.map {
    _.head
  }
}
