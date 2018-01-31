package uk.ac.ncl.openlab.intake24.sql.tools.food.nutrients

import uk.ac.ncl.openlab.intake24.nutrientsndns.CsvNutrientTableParser

object AusnutMapping {

  import CsvNutrientTableParser.{excelColumnToOffset => col}

  val map = Map(
    2l -> col("F"),
    11l -> col("H"),
    13l -> col("J"),
    17l -> col("P"),
    20l -> col("Q"),
    21l -> col("L"),
    22l -> col("M"),
    49l -> col("I"),
    50l -> col("AV"),
    51l -> col("AW"),
    52l -> col("AX"),
    59l -> col("AT"),
    114l -> col("S"),
    117l -> col("T"),
    118l -> col("U"),
    121l -> col("V"),
    123l -> col("W"),
    124l -> col("X"),
    125l -> col("Y"),
    127l -> col("AU"),
    128l -> col("Z"),
    129l -> col("AG"),
    130l -> col("AI"),
    131l -> col("AH"),
    132l -> col("AE"),
    133l -> col("AF"),
    134l -> col("AC"),
    135l -> col("AA"),
    138l -> col("AQ"),
    139l -> col("AO"),
    140l -> col("AJ"),
    141l -> col("AM"),
    142l -> col("AN"),
    143l -> col("AL"),
    147l -> col("AR"),
    149l -> col("AK"),
    152l -> col("AP"),
    153l -> col("AY"),
    157l -> col("R"),
    158l -> col("AS"),
    162l -> col("AD"),
    171l -> col("N"),
    228l -> col("O"),
    229l -> col("AB"),
    230l -> col("AZ"),
    231l -> col("BD"),
    232l -> col("BE"),
    233l -> col("G"),
    234l -> col("BA"),
    235l -> col("BB"),
    236l -> col("BC"),
    237l -> col("E"),
    238l -> col("J"),
    239l -> col("K"))

}