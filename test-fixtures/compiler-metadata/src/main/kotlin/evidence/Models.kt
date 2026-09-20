package evidence

import androidx.compose.runtime.Immutable

class Stable(val title: String)

class Mutable(var title: String)

class Used<T>(val value: T)

class Unused<T>(val title: String)

class Partial<A, B, C>(val middle: B)

class MethodOnly<T>(val title: String) {
    fun echo(value: T): T = value
}

class Covariant<out T>(val value: T)

class Bounded<T : CharSequence>(val value: T)

class MutableGeneric<T>(var value: T)

open class Base<T>(val value: T)

class Inherited<T>(value: T) : Base<T>(value)

class Outer(var title: String) {
    class Nested(val value: String)

    inner class Inner
}

class WithInitializer(val title: String) {
    companion object {
        val timestamp = System.nanoTime()
    }
}

@Immutable class Contract(var title: String)

object Singleton

@JvmInline value class Value(val value: Int)

enum class Choice {
    FIRST
}

@Suppress("LongParameterList") // Compiler fixture for a class that uses eleven type parameters.
class Used11<A, B, C, D, E, F, G, H, I, J, K>(
    val a: A,
    val b: B,
    val c: C,
    val d: D,
    val e: E,
    val f: F,
    val g: G,
    val h: H,
    val i: I,
    val j: J,
    val k: K,
)

class Unused3<A, B, C>(val title: String)
