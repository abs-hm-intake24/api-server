package uk.ac.ncl.openlab.intake24.foodsql.user

import anorm.{Macro, SQL}
import uk.ac.ncl.openlab.intake24.errors.LookupError
import uk.ac.ncl.openlab.intake24.foodsql.FirstRowValidation
import uk.ac.ncl.openlab.intake24.services.fooddb.user.{InheritableAttributeSource, InheritableAttributeSources}
import uk.ac.ncl.openlab.intake24.sql.SqlResourceLoader

trait InheritedAttributesImpl extends SqlResourceLoader with FirstRowValidation {

  protected case class ResolvedInheritableAttributes(sameAsBeforeOption: Boolean, readyMealOption: Boolean, reasonableAmount: Int, useInRecipes: Int, sources: InheritableAttributeSources)

  private case class RecursiveAttributesRow(same_as_before_option: Option[Boolean], ready_meal_option: Option[Boolean], reasonable_amount: Option[Int], use_in_recipes: Option[Int], is_food_record: Boolean, code: Option[String])

  private lazy val inheritedAttributesQuery = sqlFromResource("user/inherited_attributes.sql")

  private def attributeRows(code: String)(implicit conn: java.sql.Connection): Either[LookupError, Seq[RecursiveAttributesRow]] = {

    val result = SQL(inheritedAttributesQuery).on('food_code -> code).executeQuery()

    parseWithFoodValidation(code, result, Macro.namedParser[RecursiveAttributesRow].+)()
  }

  private case class InheritableAttributeTemp(
                                               sameAsBeforeOption: Option[(Boolean, InheritableAttributeSource)],
                                               readyMealOption: Option[(Boolean, InheritableAttributeSource)],
                                               reasonableAmount: Option[(Int, InheritableAttributeSource)],
                                               useInRecipes: Option[(Int, InheritableAttributeSource)]) {
    def finalise = ResolvedInheritableAttributes(sameAsBeforeOption.get._1, readyMealOption.get._1, reasonableAmount.get._1, useInRecipes.get._1,
      InheritableAttributeSources(sameAsBeforeOption.get._2, readyMealOption.get._2, reasonableAmount.get._2, useInRecipes.get._2))
  }

  def inheritableAttributeSource(row: RecursiveAttributesRow): InheritableAttributeSource = row.code match {
    case Some(code) =>
      if (row.is_food_record)
        InheritableAttributeSource.FoodRecord(code)
      else
        InheritableAttributeSource.CategoryRecord(code)
    case None => InheritableAttributeSource.Default
  }

  protected def resolveInheritableAttributes(foodCode: String)(implicit conn: java.sql.Connection): Either[LookupError, ResolvedInheritableAttributes] = {
    attributeRows(foodCode).right.map {
      attrRows =>

        val attrFirstRowSrc = inheritableAttributeSource(attrRows.head)

        val attrTemp = InheritableAttributeTemp(
          attrRows.head.same_as_before_option.map((_, attrFirstRowSrc)),
          attrRows.head.ready_meal_option.map((_, attrFirstRowSrc)),
          attrRows.head.reasonable_amount.map((_, attrFirstRowSrc)),
          attrRows.head.use_in_recipes.map((_, attrFirstRowSrc)))

        val attributes = attrRows.tail.foldLeft(attrTemp) {
          case (result, row) => {

            val rowSrc = inheritableAttributeSource(row)

            InheritableAttributeTemp(
              result.sameAsBeforeOption.orElse(row.same_as_before_option.map((_, rowSrc))),
              result.readyMealOption.orElse(row.ready_meal_option.map((_, rowSrc))),
              result.reasonableAmount.orElse(row.reasonable_amount.map((_, rowSrc))),
              result.useInRecipes.orElse(row.reasonable_amount.map((_, rowSrc))))
          }
        }

        attributes.finalise
    }
  }
}