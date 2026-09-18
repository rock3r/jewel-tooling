package dev.sebastiano.jewel.tooling

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.SmartPsiElementPointer
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
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

internal enum class Stability(val messageKey: String) {
  STABLE("hint.stable"),
  UNSTABLE("hint.unstable"),
  UNKNOWN("hint.unknown"),
}

internal enum class Evidence(val messageKey: String) {
  BUILTIN("evidence.builtin"),
  DECLARED_CONTRACT("evidence.contract"),
  SOURCE("evidence.source"),
  COMPILER_METADATA("evidence.compiler"),
  UNSUPPORTED("evidence.unsupported"),
}

internal data class StabilityAssessment(
  val stability: Stability,
  val reason: String,
  val evidence: Set<Evidence> = emptySet(),
  val sourceTarget: SmartPsiElementPointer<KtNamedDeclaration>? = null,
  val reasonCode: String = "reason.unsupported",
) {
  override fun equals(other: Any?): Boolean =
    other is StabilityAssessment &&
      stability == other.stability &&
      reason == other.reason &&
      evidence == other.evidence

  override fun hashCode(): Int =
    31 * (31 * stability.hashCode() + reason.hashCode()) + evidence.hashCode()
}

internal data class ParameterHint(
  val offset: Int,
  val name: String,
  val assessment: StabilityAssessment,
  val typeText: String = "",
)

internal data class FunctionStability(val name: String, val parameters: List<ParameterHint>)

/**
 * Analysis symbols stay inside analyze; only presentation data and optional source pointers escape.
 */
internal object StabilityAnalysis {
  fun hints(function: KtNamedFunction, visitLimit: Int = 256): List<ParameterHint> =
    inspect(function, visitLimit)?.parameters.orEmpty()

  fun inspect(
    function: KtNamedFunction,
    visitLimit: Int = 256,
    navigation: Boolean = false,
  ): FunctionStability? =
    analyze(function) {
      val symbol = function.symbol as? KaNamedFunctionSymbol ?: return@analyze null
      if (symbol.annotations.none { it.classId?.asFqNameString() == COMPOSABLE })
        return@analyze null
      if (function.valueParameters.size != symbol.valueParameters.size) return@analyze null
      val hints = mutableListOf<ParameterHint>()
      val budget = VisitBudget(visitLimit, navigation)
      val receiver = function.receiverTypeReference
      if (receiver != null) {
        hints +=
          ParameterHint(
            receiver.textRange.endOffset,
            JewelToolingBundle.message("hint.receiver"),
            classify(receiver.type, function, emptyMap(), mutableSetOf(), 0, budget),
            receiver.text,
          )
      }
      for ((parameter, parameterSymbol) in function.valueParameters.zip(symbol.valueParameters)) {
        ProgressManager.checkCanceled()
        val typeReference = parameter.typeReference ?: continue
        val result =
          if (budget.exhausted) assessment(Stability.UNKNOWN, "reason.bounded")
          else if (parameter.isVarArg) assessment(Stability.UNSTABLE, "reason.vararg")
          else classify(parameterSymbol.returnType, function, emptyMap(), mutableSetOf(), 0, budget)
        hints +=
          ParameterHint(
            typeReference.textRange.endOffset,
            parameter.name ?: "?",
            result,
            typeReference.text,
          )
      }
      FunctionStability(function.name.orEmpty(), hints)
    }

  // This ordered decision table keeps conservative precedence visible in one place.
  @Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "ComplexCondition",
    "LoopWithTooManyJumpStatements",
    "LongParameterList",
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
        checkNotNull(contract.classId).shortClassName.asString(),
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
    if (source.containingKtFile.isCompiled) {
      return classifyBinary(
        classType,
        symbol,
        source,
        usage,
        substitutions,
        visiting,
        depth,
        budget,
      )
    }
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
      val evidence = mutableSetOf(Evidence.SOURCE)
      for (property in symbol.declaredMemberScope.callables.filterIsInstance<KaPropertySymbol>()) {
        ProgressManager.checkCanceled()
        val psi = property.psi as? KtProperty
        if (psi?.hasDelegate() == true) {
          unknown =
            assessment(Stability.UNKNOWN, "reason.delegated", property.name.asString())
              .copy(sourceTarget = budget.target(property.psi))
          continue
        }
        if (!property.hasBackingField) continue
        if (!property.isVal)
          return assessment(Stability.UNSTABLE, "reason.mutable", property.name.asString())
            .copy(sourceTarget = budget.target(property.psi))
        val result = classify(property.returnType, usage, actuals, visiting, depth + 1, budget)
        evidence += result.evidence
        if (result.stability == Stability.UNSTABLE) {
          return assessment(
              Stability.UNSTABLE,
              "reason.property",
              property.name.asString(),
              result.reason,
            )
            .copy(
              evidence = result.evidence + Evidence.SOURCE,
              sourceTarget = result.sourceTarget ?: budget.target(property.psi),
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
              .copy(
                evidence = result.evidence + Evidence.SOURCE,
                sourceTarget = result.sourceTarget ?: budget.target(property.psi),
              )
        }
      }
      return unknown
        ?: assessment(Stability.STABLE, "reason.fields")
          .copy(evidence = evidence.toSet(), sourceTarget = budget.target(source))
    } finally {
      visiting.remove(symbol)
    }
  }

  // Keep unsupported-case rejection and unstable precedence explicit, as in the source decision
  // table.
  @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongParameterList")
  private fun KaSession.classifyBinary(
    type: KaClassType,
    symbol: KaNamedClassSymbol,
    source: KtClass,
    usage: KtNamedFunction,
    substitutions: Map<KaTypeParameterSymbol, KaType>,
    visiting: MutableSet<KaClassSymbol>,
    depth: Int,
    budget: VisitBudget,
  ): StabilityAssessment {
    if (symbol.isInner || source.isLocal) return assessment(Stability.UNKNOWN, "reason.captured")
    val file =
      source.containingKtFile.virtualFile ?: return assessment(Stability.UNKNOWN, "reason.external")
    val id = type.classId
    val internalName =
      binaryInternalName(id.packageFqName.asString(), id.relativeClassName.asString())
    val metadata =
      budget.binaries.read(file, internalName, symbol.typeParameters.size)
        as? CompilerStabilityMetadata.Result.Proven
        ?: return assessment(Stability.UNKNOWN, "reason.external")
    val compiler = setOf(Evidence.COMPILER_METADATA)
    if (metadata.unstableBase)
      return assessment(Stability.UNSTABLE, "reason.compilerUnstable").copy(evidence = compiler)
    if (!visiting.add(symbol))
      return assessment(Stability.UNKNOWN, "reason.recursive")
        .copy(evidence = compiler + Evidence.UNSUPPORTED)
    try {
      val evidence = compiler.toMutableSet()
      val reasons = mutableListOf<String>()
      var unknown = false
      for ((index, parameter) in symbol.typeParameters.withIndex()) {
        if (metadata.argumentMask and (1 shl index) == 0) continue
        ProgressManager.checkCanceled()
        val argument = type.typeArguments.getOrNull(index)?.type
        val result =
          if (argument == null) assessment(Stability.UNKNOWN, "reason.typeArgument")
          else classify(argument, usage, substitutions, visiting, depth + 1, budget)
        evidence += result.evidence
        val reason =
          JewelToolingBundle.message(
            "reason.compilerArgument",
            parameter.name.asString(),
            result.reason,
          )
        if (result.stability == Stability.UNSTABLE)
          return StabilityAssessment(
            Stability.UNSTABLE,
            reason,
            evidence.toSet(),
            result.sourceTarget,
            "reason.compilerArgument",
          )
        if (result.stability == Stability.UNKNOWN) unknown = true
        reasons += reason
      }
      return StabilityAssessment(
        if (unknown) Stability.UNKNOWN else Stability.STABLE,
        if (reasons.isEmpty()) JewelToolingBundle.message("reason.compilerStable")
        else reasons.joinToString("\n"),
        evidence.toSet(),
        reasonCode = if (reasons.isEmpty()) "reason.compilerStable" else "reason.compilerArgument",
      )
    } finally {
      visiting.remove(symbol)
    }
  }

  private fun binaryInternalName(packageName: String, relativeName: String) =
    listOf(packageName.replace('.', '/'), relativeName.replace('.', '$'))
      .filter { it.isNotEmpty() }
      .joinToString("/")

  private class VisitBudget(private var remaining: Int, private val navigation: Boolean) {
    fun target(element: com.intellij.psi.PsiElement?) =
      if (navigation) StabilityNavigation.pointer(element) else null

    val binaries = BinaryMetadataReader()
    val exhausted: Boolean
      get() = remaining <= 0

    fun consume(): Boolean = remaining-- > 0
  }

  private fun assessment(stability: Stability, key: String, vararg arguments: Any) =
    StabilityAssessment(
      stability,
      JewelToolingBundle.message(key, *arguments),
      setOf(
        when (key) {
          "reason.builtin",
          "reason.function",
          "reason.enum",
          "reason.collection",
          "reason.vararg" -> Evidence.BUILTIN
          "reason.contract" -> Evidence.DECLARED_CONTRACT
          "reason.fields",
          "reason.mutable" -> Evidence.SOURCE
          else -> Evidence.UNSUPPORTED
        }
      ),
      reasonCode = key,
    )

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
