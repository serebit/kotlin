// IGNORE_BACKEND_FIR: JVM_IR
// WITH_REFLECT

import kotlin.reflect.jvm.reflect

fun <T> test(f: () -> T): String {
    return if (f.reflect()!!.returnType.toString() == "kotlin.String") "NOK" else "OK"
}

fun main() {
    println(test<Any?> { "" })
}