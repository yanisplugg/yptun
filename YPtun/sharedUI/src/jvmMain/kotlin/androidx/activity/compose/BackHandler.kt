// Desktop stub of androidx.activity.compose.BackHandler so UI files ported verbatim from
// androidMain compile on the JVM. Desktop has no system back gesture; sheets are closed
// with their own in-UI back buttons, so the handler is simply never invoked.
package androidx.activity.compose

import androidx.compose.runtime.Composable

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
}
