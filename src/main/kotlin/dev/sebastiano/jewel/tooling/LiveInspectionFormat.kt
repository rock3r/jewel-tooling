package dev.sebastiano.jewel.tooling

import java.text.NumberFormat

internal object LiveInspectionFormat {
  fun integer(value: Number): String = NumberFormat.getIntegerInstance().format(value)

  fun milliseconds(value: Double): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = FRACTION_DIGITS }.format(value)

  private const val FRACTION_DIGITS = 3
}
