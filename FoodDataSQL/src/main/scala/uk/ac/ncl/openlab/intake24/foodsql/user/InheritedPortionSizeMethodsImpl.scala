package uk.ac.ncl.openlab.intake24.foodsql.user

import uk.ac.ncl.openlab.intake24.foodsql.SqlResourceLoader
import uk.ac.ncl.openlab.intake24.foodsql.FirstRowValidation
import uk.ac.ncl.openlab.intake24.PortionSizeMethod
import uk.ac.ncl.openlab.intake24.services.fooddb.errors.LocalLookupError
import anorm.Macro
import anorm.SQL
import uk.ac.ncl.openlab.intake24.services.fooddb.user.SourceLocale
import java.sql.Connection
import uk.ac.ncl.openlab.intake24.services.fooddb.user.SourceRecord
import uk.ac.ncl.openlab.intake24.foodsql.shared.FoodPortionSizeShared
import uk.ac.ncl.openlab.intake24.foodsql.FirstRowValidationClause
import org.slf4j.LoggerFactory
import uk.ac.ncl.openlab.intake24.foodsql.SimpleValidation
import uk.ac.ncl.openlab.intake24.foodsql.SqlDataService

trait InheritedPortionSizeMethodsImpl extends FoodPortionSizeShared with SqlResourceLoader with SimpleValidation with SqlDataService {

  private case class RecursivePsmResultRow(id: Long, category_code: String, method: String, description: String, image_url: String, use_for_recipes: Boolean, param_name: Option[String], param_value: Option[String])

  private def mkRecursivePortionSizeMethods(rows: Seq[RecursivePsmResultRow]): (Seq[PortionSizeMethod], SourceRecord) =
    if (rows.isEmpty)
      (Seq(), SourceRecord.NoRecord)
    else {
      val firstCategoryCode = rows.head.category_code
      (mkPortionSizeMethods(rows.takeWhile(_.category_code == firstCategoryCode).map(r => PsmResultRow(r.id, r.method, r.description, r.image_url, r.use_for_recipes, r.param_name, r.param_value))), SourceRecord.CategoryRecord(firstCategoryCode))
    }

  private lazy val inheritedPsmQuery = sqlFromResource("user/inherited_psm.sql")

  private def inheritedPortionSizeMethodsImpl(code: String, locale: String)(implicit conn: Connection): Either[LocalLookupError, (Seq[PortionSizeMethod], SourceRecord)] =
    withTransaction {
      validateFoodAndLocale(code, locale).right.flatMap {
        _ =>
          Right(mkRecursivePortionSizeMethods(SQL(inheritedPsmQuery).on('food_code -> code, 'locale_id -> locale).executeQuery().as(Macro.namedParser[RecursivePsmResultRow].*)))
      }
    }

  private def resolveLocalPortionSizeMethods(code: String, locale: String)(implicit conn: Connection): Either[LocalLookupError, (Seq[PortionSizeMethod], SourceRecord)] = {
    foodPortionSizeMethodsImpl(code, locale).right.flatMap {
      foodPsm =>
        if (foodPsm.nonEmpty)
          Right((foodPsm, SourceRecord.FoodRecord(code)))
        else
          inheritedPortionSizeMethodsImpl(code, locale)
    }
  }

  protected case class ResolvedPortionSizeMethods(methods: Seq[PortionSizeMethod], sourceLocale: SourceLocale, sourceRecord: SourceRecord)

  protected def resolvePortionSizeMethods(foodCode: String, locale: String, prototypeLocale: Option[String])(implicit conn: java.sql.Connection): Either[LocalLookupError, ResolvedPortionSizeMethods] = {
    resolveLocalPortionSizeMethods(foodCode, locale).right.flatMap {
      case (localPsm, localPsmSrcRec) =>
        (localPsm.isEmpty, prototypeLocale) match {
          case (true, Some(pl)) => resolveLocalPortionSizeMethods(foodCode, pl).right.map {
            case (protoPsm, protoPsmSrcRec) => ResolvedPortionSizeMethods(protoPsm, SourceLocale.Prototype(pl), protoPsmSrcRec)
          }
          case _ => Right(ResolvedPortionSizeMethods(localPsm, SourceLocale.Current(locale), localPsmSrcRec))
        }
    }
  }
}
