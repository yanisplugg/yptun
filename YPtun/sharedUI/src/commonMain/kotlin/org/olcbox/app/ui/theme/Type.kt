package org.olcbox.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.google_sans_flex_bold
import multiplatform_app.sharedui.generated.resources.google_sans_flex_medium
import multiplatform_app.sharedui.generated.resources.google_sans_flex_regular
import multiplatform_app.sharedui.generated.resources.google_sans_flex_semi_bold
import org.jetbrains.compose.resources.Font

@Composable
fun getAppTypography(): Typography {
    val googleSansFlex = FontFamily(
        Font(Res.font.google_sans_flex_regular, weight = FontWeight.Normal),
        Font(Res.font.google_sans_flex_medium, weight = FontWeight.Medium),
        Font(Res.font.google_sans_flex_semi_bold, weight = FontWeight.SemiBold),
        Font(Res.font.google_sans_flex_bold, weight = FontWeight.Bold)
    )

    val defaultTypography = Typography()

    return Typography(
        displayLarge = defaultTypography.displayLarge.copy(fontFamily = googleSansFlex),
        displayMedium = defaultTypography.displayMedium.copy(fontFamily = googleSansFlex),
        displaySmall = defaultTypography.displaySmall.copy(fontFamily = googleSansFlex),
        headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = googleSansFlex),
        headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = googleSansFlex),
        headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = googleSansFlex),
        titleLarge = defaultTypography.titleLarge.copy(fontFamily = googleSansFlex),
        titleMedium = defaultTypography.titleMedium.copy(fontFamily = googleSansFlex),
        titleSmall = defaultTypography.titleSmall.copy(fontFamily = googleSansFlex),
        bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = googleSansFlex),
        bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = googleSansFlex),
        bodySmall = defaultTypography.bodySmall.copy(fontFamily = googleSansFlex),
        labelLarge = defaultTypography.labelLarge.copy(fontFamily = googleSansFlex),
        labelMedium = defaultTypography.labelMedium.copy(fontFamily = googleSansFlex),
        labelSmall = defaultTypography.labelSmall.copy(fontFamily = googleSansFlex)
    )
}
