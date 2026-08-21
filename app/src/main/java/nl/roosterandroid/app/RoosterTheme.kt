package nl.roosterandroid.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val RoosterLightColors = lightColorScheme(
    primary = Color(0xFF006B66),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF2EA),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF45617A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E5FF),
    onSecondaryContainer = Color(0xFF001D32),
    tertiary = Color(0xFF755B00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE17A),
    onTertiaryContainer = Color(0xFF241A00),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    background = Color(0xFFF7FAF9),
    surface = Color(0xFFF7FAF9),
    surfaceVariant = Color(0xFFDCE5E3),
    outlineVariant = Color(0xFFBFC9C7)
)

private val RoosterDarkColors = darkColorScheme(
    primary = Color(0xFF80D5CE),
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF00504C),
    onPrimaryContainer = Color(0xFF9CF2EA),
    secondary = Color(0xFFB4CAE6),
    onSecondary = Color(0xFF173349),
    secondaryContainer = Color(0xFF2E4961),
    onSecondaryContainer = Color(0xFFD0E5FF),
    tertiary = Color(0xFFE9C349),
    onTertiary = Color(0xFF3D2F00),
    tertiaryContainer = Color(0xFF584500),
    onTertiaryContainer = Color(0xFFFFE17A),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    background = Color(0xFF0F1413),
    surface = Color(0xFF0F1413),
    surfaceVariant = Color(0xFF3F4947),
    outlineVariant = Color(0xFF3F4947)
)

private val BaseTypography = Typography()
private val RoosterTypography = Typography(
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
)

private val RoosterShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun RoosterAndroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) RoosterDarkColors else RoosterLightColors,
        typography = RoosterTypography,
        shapes = RoosterShapes,
        content = content
    )
}
