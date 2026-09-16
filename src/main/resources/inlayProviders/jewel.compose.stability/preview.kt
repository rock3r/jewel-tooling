import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

@Immutable data class Label(val text: String)

@Composable
fun Example(
  title: String /*<# stable #>*/,
  label: Label /*<# stable #>*/,
  items: List<String>, /*<# unstable #>*/
) {}
