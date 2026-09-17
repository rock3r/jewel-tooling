import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("markers", Path(__file__).with_name("verify-trace-markers.py"))
markers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(markers)


class ProducerMarkerTest(unittest.TestCase):
    listing = """  public static final void GreetingRow(example.Greeting, androidx.compose.runtime.Composer, int);
      1: invokestatic #1 // Method isTraceInProgress:()Z
      4: ldc #2 // String example.GreetingRow (Example.kt:25)
      6: invokestatic #3 // Method traceEventStart:(IIILjava/lang/String;)V
      9: invokestatic #1 // Method isTraceInProgress:()Z
     12: invokestatic #4 // Method traceEventEnd:()V
  public static final void Other();
"""

    def test_complete_producer(self):
        markers.verify_listing(self.listing)

    def test_missing_gate_info_or_signature(self):
        mutations = [self.listing.replace("9: invokestatic #1 // Method isTraceInProgress:()Z", ""),
                     self.listing.replace("1: invokestatic #1 // Method isTraceInProgress:()Z", ""),
                     self.listing.replace("String example.GreetingRow (Example.kt:25)", "String "),
                     self.listing.replace("(IIILjava/lang/String;)V", "(III)V")]
        for listing in mutations:
            with self.subTest(listing=listing), self.assertRaises(ValueError):
                markers.verify_listing(listing)


if __name__ == "__main__":
    unittest.main()
