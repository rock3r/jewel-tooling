package dev.sebastiano.jewel.tooling.mcp.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestJobsTest {
  @Test(timeout = 5000)
  fun closeWaitsForRequestCleanup() = runBlocking {
    val requests = RequestJobs()
    val started = CompletableDeferred<Unit>()
    var cleaned = false
    val handler = launch {
      coroutineScope {
        assertTrue(requests.admit(currentCoroutineContext().job))
        started.complete(Unit)
        try {
          awaitCancellation()
        } finally {
          withContext(NonCancellable) {
            delay(50)
            cleaned = true
          }
        }
      }
    }
    started.await()
    requests.close()
    assertTrue(cleaned)
    handler.join()
  }

  @Test(timeout = 5000)
  fun closingAdmissionRejectsLaterRequestJobs() = runBlocking {
    val requests = RequestJobs()
    requests.close()
    coroutineScope { assertFalse(requests.admit(currentCoroutineContext().job)) }
    requests.close()
  }
}
