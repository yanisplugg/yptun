package org.olcbox.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.R
import org.olcbox.app.ui.i18n.AppLocale
import org.olcbox.app.ui.i18n.Strings
import org.olcbox.app.ui.theme.AppTheme

/**
 * Widget appearance picker. Declared as `android:configure` on both widget providers, so it opens
 * when a widget is placed and again on "reconfigure" (Android 12+). Settings are per widget
 * instance — two YPtun widgets on the same screen can look completely different.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Placement is only confirmed when the user taps "Done"; backing out must leave nothing behind.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val strings = AppLocale.strings(applicationContext)
        val isToggleWidget = runCatching {
            AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
                ?.provider?.className?.endsWith("ToggleWidgetProvider") == true
        }.getOrDefault(false)

        setContent {
            AppTheme(useDynamicColor = false) {
                WidgetConfigScreen(
                    strings = strings,
                    initial = WidgetStyle.load(this, widgetId),
                    compact = isToggleWidget,
                    onDone = { style ->
                        WidgetStyle.save(this, widgetId, style)
                        WidgetRefresh.ping(this)
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        )
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun WidgetConfigScreen(
    strings: Strings,
    initial: WidgetStyle,
    compact: Boolean,
    onDone: (WidgetStyle) -> Unit,
) {
    var style by remember { mutableStateOf(initial) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                strings.widgetSettingsTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )

            WidgetPreview(style, compact, strings)

            Segmented(
                label = strings.widgetTheme,
                options = listOf(
                    strings.widgetThemeDark,
                    strings.widgetThemeLight,
                    strings.widgetThemeTransparent
                ),
                selected = style.theme,
                onSelect = { style = style.copy(theme = it) },
            )

            Column {
                Text(
                    "${strings.widgetOpacity}  ${style.opacity}%",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                )
                Slider(
                    value = style.opacity.toFloat(),
                    onValueChange = { style = style.copy(opacity = it.toInt()) },
                    valueRange = 0f..100f,
                )
            }

            if (!compact) {
                Segmented(
                    label = strings.widgetCorners,
                    options = listOf(
                        strings.widgetCornersSmall,
                        strings.widgetCornersMedium,
                        strings.widgetCornersRound
                    ),
                    selected = style.corners,
                    onSelect = { style = style.copy(corners = it) },
                )
            }

            Column {
                Text(strings.widgetAccent, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WidgetStyle.ACCENTS.forEach { swatch ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(swatch))
                                .border(
                                    width = if (style.accent == swatch) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    shape = CircleShape,
                                )
                                .clickable { style = style.copy(accent = swatch) }
                        )
                    }
                }
            }

            if (!compact) {
                ToggleRow(strings.widgetShowSpeed, style.showSpeed) { style = style.copy(showSpeed = it) }
                ToggleRow(strings.widgetShowControls, style.showControls) {
                    style = style.copy(showControls = it)
                }
            }

            Button(onClick = { onDone(style) }, modifier = Modifier.fillMaxWidth()) {
                Text(strings.widgetDone)
            }
        }
    }
}

/** Live approximation of the real RemoteViews layout, drawn on a checkerboard-ish neutral ground. */
@Composable
private fun WidgetPreview(style: WidgetStyle, compact: Boolean, strings: Strings) {
    val radius = when (style.corners) {
        WidgetStyle.CORNERS_SMALL -> 8.dp
        WidgetStyle.CORNERS_ROUND -> 34.dp
        else -> 20.dp
    }
    val background = Color(style.backgroundColor).copy(alpha = style.opacity / 100f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF3A4348)) // stand-in wallpaper, so transparency is visible
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .then(if (compact) Modifier.size(64.dp) else Modifier.fillMaxWidth().height(64.dp))
                .clip(if (compact) CircleShape else RoundedCornerShape(radius))
                .background(background)
                .padding(horizontal = if (compact) 0.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF6E7176).copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_widget_power),
                    contentDescription = null,
                    tint = Color(0xFF6E7176),
                    modifier = Modifier.size(23.dp),
                )
            }
            if (compact) return@Row

            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "🇫🇮 Finland[Main]",
                    color = Color(style.textPrimary),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    strings.widgetDisconnected,
                    color = Color(style.textSecondary),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            if (style.showControls) {
                Icon(
                    painter = painterResource(R.drawable.ic_widget_auto),
                    contentDescription = null,
                    tint = Color(style.accent),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_widget_prev),
                    contentDescription = null,
                    tint = Color(style.textSecondary),
                    modifier = Modifier.size(22.dp),
                )
                Icon(
                    painter = painterResource(R.drawable.ic_widget_next),
                    contentDescription = null,
                    tint = Color(style.textSecondary),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun Segmented(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, option ->
                val active = index == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onSelect(index) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option,
                        fontSize = 13.sp,
                        maxLines = 1,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
