// A small shared editor fixture keeps its model and composable visible together.
@file:Suppress("MatchingDeclarationName")

package example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text

class Greeting(val title: String)

@Suppress("MagicNumber") // Fixed layout spacing in the public example.
@Composable
fun GreetingRow(greeting: Greeting, items: List<String>, onIncrement: () -> Unit) {
  Column {
    Text(greeting.title)
    Spacer(Modifier.height(16.dp))
    Text("Items: ${items.size}", modifier = Modifier.testTag("items-count"))
    Spacer(Modifier.height(16.dp))
    DefaultButton(onClick = onIncrement, modifier = Modifier.testTag("add-item")) {
      Text("Add item")
    }
  }
}
