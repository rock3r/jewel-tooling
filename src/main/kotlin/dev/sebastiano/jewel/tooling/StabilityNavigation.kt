package dev.sebastiano.jewel.tooling

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

internal data class StabilityReportSnapshot(
    val report: FunctionStability,
    val psiCount: Long,
    val editorStamp: Long,
)

internal data class DeclarationLocation(val file: VirtualFile, val offset: Int)

internal enum class NavigationStatus {
    READY,
    STALE,
    INVALID,
    CLOSED,
}

internal object StabilityNavigation {
    @Suppress(
        "LongParameterList"
    ) // The gate compares every stale-input field without hidden state.
    fun gate(
        snapshot: StabilityReportSnapshot,
        current: StabilityReportSnapshot?,
        contextAlive: Boolean,
        indexing: Boolean,
        psiCount: Long,
        editorStamp: Long,
        targetValid: Boolean,
    ): NavigationStatus =
        when {
            !contextAlive || snapshot !== current -> NavigationStatus.CLOSED
            indexing || snapshot.psiCount != psiCount || snapshot.editorStamp != editorStamp ->
                NavigationStatus.STALE
            !targetValid -> NavigationStatus.INVALID
            else -> NavigationStatus.READY
        }

    /** Call within a read action. Only physical Kotlin declarations can become targets. */
    fun pointer(element: PsiElement?): SmartPsiElementPointer<KtNamedDeclaration>? {
        val declaration = source(element) ?: return null
        return SmartPointerManager.getInstance(declaration.project)
            .createSmartPsiElementPointer(declaration)
    }

    /** Call within a read action. Open the returned location after the read action ends. */
    @Suppress("ReturnCount")
    fun resolve(pointer: SmartPsiElementPointer<KtNamedDeclaration>): DeclarationLocation? {
        val declaration = source(pointer.element) ?: return null
        val file = declaration.containingKtFile.virtualFile ?: return null
        return DeclarationLocation(
            file,
            declaration.nameIdentifier?.textOffset ?: declaration.textOffset,
        )
    }

    @Suppress("ReturnCount")
    private fun source(element: PsiElement?): KtNamedDeclaration? {
        val declaration = element as? KtNamedDeclaration ?: return null
        if (declaration !is KtClass && declaration !is KtProperty && declaration !is KtParameter)
            return null
        if (
            !declaration.isValid ||
                !declaration.isPhysical ||
                declaration.containingKtFile.isCompiled
        )
            return null
        if (declaration.containingKtFile.virtualFile?.isValid != true) return null
        return declaration
    }
}
