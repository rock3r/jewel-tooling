package dev.sebastiano.jewel.tooling

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.declaredMemberScope
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.findClass
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaDefinitelyNotNullType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaIntersectionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

internal enum class Stability(val messageKey: String) {
  STABLE("hint.stable"),
  UNSTABLE("hint.unstable"),
  UNKNOWN("hint.unknown"),
}

internal data class StabilityAssessment(val stability: Stability, val reason: String)

internal data class ParameterHint(
  val offset: Int,
  val name: String,
  val assessment: StabilityAssessment,
)

/** Analysis symbols stay inside analyze; only immutable presentation data escapes. */
internal object StabilityAnalysis {
  fun hints(function: KtNamedFunction, visitLimit: Int = 256): List<ParameterHint> =
    analyze(function) {
      val symbol = function.symbol as? KaNamedFunctionSymbol ?: return@analyze emptyList()
      if (symbol.annotations.none { it.classId?.asFqNameString() == COMPOSABLE })
        return@analyze emptyList()
      if (function.valueParameters.size != symbol.valueParameters.size) return@analyze emptyList()
      val hints = mutableListOf<ParameterHint>()
      val budget = VisitBudget(visitLimit)
      val receiver = function.receiverTypeReference
      if (receiver != null) {
        hints +=
          ParameterHint(
            receiver.textRange.endOffset,
            JewelToolingBundle.message("hint.receiver"),
            classify(receiver.type, function, emptyMap(), mutableSetOf(), 0, budget),
          )
      }
      for ((parameter, parameterSymbol) in function.valueParameters.zip(symbol.valueParameters)) {
        ProgressManager.checkCanceled()
        val typeReference = parameter.typeReference ?: continue
        val result =
          if (budget.exhausted) assessment(Stability.UNKNOWN, "reason.bounded")
          else if (parameter.isVarArg) assessment(Stability.UNSTABLE, "reason.vararg")
          else classify(parameterSymbol.returnType, function, emptyMap(), mutableSetOf(), 0, budget)
        hints += ParameterHint(typeReference.textRange.endOffset, parameter.name ?: "?", result)
      }
      hints
    }

  // This ordered decision table keeps conservative precedence visible in one place.
  @Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "ComplexCondition",
    "LoopWithTooManyJumpStatements",
  )
  private fun KaSession.classify(
    type: KaType,
    usage: KtNamedFunction,
    substitutions: Map<KaTypeParameterSymbol, KaType>,
    visiting: MutableSet<KaClassSymbol>,
    depth: Int,
    budget: VisitBudget,
  ): StabilityAssessment {
    ProgressManager.checkCanceled()
    if (!budget.consume() || depth > MAX_DEPTH)
      return assessment(Stability.UNKNOWN, "reason.bounded")
    if (type is KaFlexibleType) return assessment(Stability.UNKNOWN, "reason.unsupported")
    if (type is KaIntersectionType || type is KaDefinitelyNotNullType)
      return assessment(Stability.UNKNOWN, "reason.unsupported")
    if (type is KaErrorType) return assessment(Stability.UNKNOWN, "reason.unresolved")
    if (type is KaTypeParameterType) {
      val actual = substitutions[type.symbol]
      return if (actual != null && actual != type)
        classify(actual, usage, substitutions, visiting, depth + 1, budget)
      else assessment(Stability.UNKNOWN, "reason.typeArgument")
    }
    if (type is KaFunctionType) return assessment(Stability.STABLE, "reason.function")
    val classType =
      type as? KaClassType ?: return assessment(Stability.UNKNOWN, "reason.unsupported")
    val fqName = classType.classId.asFqNameString()
    if (fqName in BUILTINS) return assessment(Stability.STABLE, "reason.builtin")
    val symbol =
      classType.expandedSymbol as? KaNamedClassSymbol
        ?: return assessment(Stability.UNKNOWN, "reason.unresolved")
    val contract = symbol.annotations.firstOrNull { it.classId?.asFqNameString() in CONTRACTS }
    if (contract != null)
      return assessment(
        Stability.STABLE,
        "reason.contract",
        contract.classId!!.shortClassName.asString(),
      )
    if (symbol.classKind == KaClassKind.ENUM_CLASS)
      return assessment(Stability.STABLE, "reason.enum")
    if (fqName in COLLECTIONS) return assessment(Stability.UNSTABLE, "reason.collection")
    if (
      symbol.annotations.any { annotation ->
        annotation.classId
          ?.let { findClass(it) }
          ?.annotations
          ?.any { it.classId?.asFqNameString() == "androidx.compose.runtime.StableMarker" } == true
      }
    )
      return assessment(Stability.UNKNOWN, "reason.unsupportedContract")
    if (
      symbol.classKind == KaClassKind.OBJECT || symbol.classKind == KaClassKind.COMPANION_OBJECT
    ) {
      return assessment(Stability.UNKNOWN, "reason.singleton")
    }
    if (symbol.isInline) return assessment(Stability.UNKNOWN, "reason.valueClass")
    if (fqName.startsWith("java.")) return assessment(Stability.UNKNOWN, "reason.platformType")
    val source = symbol.psi as? KtClass ?: return assessment(Stability.UNKNOWN, "reason.external")
    if (source.hasModifier(KtTokens.INNER_KEYWORD) || source.isLocal) {
      return assessment(Stability.UNKNOWN, "reason.captured")
    }
    val sourceModule = ModuleUtilCore.findModuleForPsiElement(source)
    if (sourceModule == null || sourceModule != ModuleUtilCore.findModuleForPsiElement(usage)) {
      return assessment(Stability.UNKNOWN, "reason.external")
    }
    if (
      source.isInterface() ||
        source.hasModifier(KtTokens.OPEN_KEYWORD) ||
        source.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
        source.hasModifier(KtTokens.SEALED_KEYWORD)
    )
      return assessment(Stability.UNKNOWN, "reason.open")
    if (source.superTypeListEntries.isNotEmpty())
      return assessment(Stability.UNKNOWN, "reason.inheritance")
    if (!visiting.add(symbol)) return assessment(Stability.UNKNOWN, "reason.recursive")
    try {
      val actuals = substitutions.toMutableMap()
      for ((parameter, argument) in symbol.typeParameters.zip(classType.typeArguments)) {
        val argumentType = argument.type ?: continue
        actuals[parameter] =
          if (argumentType is KaTypeParameterType)
            substitutions[argumentType.symbol] ?: argumentType
          else argumentType
      }
      var unknown: StabilityAssessment? = null
      for (property in symbol.declaredMemberScope.callables.filterIsInstance<KaPropertySymbol>()) {
        ProgressManager.checkCanceled()
        val psi = property.psi as? KtProperty
        if (psi?.hasDelegate() == true) {
          unknown = assessment(Stability.UNKNOWN, "reason.delegated", property.name.asString())
          continue
        }
        if (!property.hasBackingField) continue
        if (!property.isVal)
          return assessment(Stability.UNSTABLE, "reason.mutable", property.name.asString())
        val result = classify(property.returnType, usage, actuals, visiting, depth + 1, budget)
        if (result.stability == Stability.UNSTABLE) {
          return assessment(
            Stability.UNSTABLE,
            "reason.property",
            property.name.asString(),
            result.reason,
          )
        }
        if (result.stability == Stability.UNKNOWN) {
          unknown =
            assessment(
              Stability.UNKNOWN,
              "reason.property",
              property.name.asString(),
              result.reason,
            )
        }
      }
      return unknown ?: assessment(Stability.STABLE, "reason.fields")
    } finally {
      visiting.remove(symbol)
    }
  }

  private class VisitBudget(private var remaining: Int) {
    val exhausted: Boolean
      get() = remaining <= 0

    fun consume(): Boolean = remaining-- > 0
  }

  private fun assessment(stability: Stability, key: String, vararg arguments: Any) =
    StabilityAssessment(stability, JewelToolingBundle.message(key, *arguments))

  private const val MAX_DEPTH = 12
  private const val COMPOSABLE = "androidx.compose.runtime.Composable"
  private val CONTRACTS =
    setOf("androidx.compose.runtime.Stable", "androidx.compose.runtime.Immutable")
  private val BUILTINS =
    setOf(
      "kotlin.Boolean",
      "kotlin.Byte",
      "kotlin.Short",
      "kotlin.Int",
      "kotlin.Long",
      "kotlin.Char",
      "kotlin.Float",
      "kotlin.Double",
      "kotlin.String",
      "kotlin.Unit",
      "kotlin.Nothing",
    )
  private val COLLECTIONS =
    setOf(
      "java.util.List",
      "java.util.Set",
      "java.util.Map",
      "java.util.Collection",
      "java.lang.Iterable",
      "kotlin.collections.List",
      "kotlin.collections.MutableList",
      "kotlin.collections.Set",
      "kotlin.collections.MutableSet",
      "kotlin.collections.Map",
      "kotlin.collections.MutableMap",
      "kotlin.collections.Collection",
      "kotlin.collections.MutableCollection",
      "kotlin.collections.Iterable",
      "kotlin.collections.MutableIterable",
      "kotlin.Array",
      "kotlin.BooleanArray",
      "kotlin.ByteArray",
      "kotlin.ShortArray",
      "kotlin.IntArray",
      "kotlin.LongArray",
      "kotlin.CharArray",
      "kotlin.FloatArray",
      "kotlin.DoubleArray",
    )
}
