package com.gflix.extcore

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Read a Kotlin/Java property (field or getX/isX) by name. */
fun readProp(obj: Any, name: String): Any? {
    val cls = obj.javaClass
    runCatching {
        val field = cls.getField(name)
        return field.get(obj)
    }
    val capitalized = name.replaceFirstChar { it.uppercase() }
    for (getter in listOf("get$capitalized", "is$capitalized", name)) {
        runCatching {
            val method = cls.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
                ?: return@runCatching null
            return method.invoke(obj)
        }
    }
    return null
}

private fun suspendArityOf(method: java.lang.reflect.Method): Boolean =
    method.parameterTypes.lastOrNull() == kotlin.coroutines.Continuation::class.java

/** Invoke a suspend function by name with plain args (continuation appended). */
suspend fun callSuspend(obj: Any, methodName: String, vararg args: Any?): Any? {
    val cls = obj.javaClass
    val candidates = cls.methods.filter { it.name == methodName && suspendArityOf(it) }
    val method = candidates.firstOrNull { it.parameterTypes.size == args.size + 1 }
        ?: candidates.firstOrNull()
        ?: throw NoSuchMethodException("$methodName on ${cls.name}")
    return suspendCancellableCoroutine { cont ->
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(obj, *args, cont as kotlin.coroutines.Continuation<Any?>)
            if (result != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
                cont.resume(result)
            }
        }.onFailure { cont.resumeWithException(it.cause ?: it) }
    }
}

/** Invoke a regular (non-suspend) function by name, best-arity match. */
fun callSync(obj: Any, methodName: String, vararg args: Any?): Any? {
    val cls = obj.javaClass
    val candidates = cls.methods.filter { it.name == methodName && !suspendArityOf(it) }
    val method = candidates.firstOrNull { it.parameterTypes.size == args.size }
        ?: candidates.firstOrNull()
        ?: throw NoSuchMethodException("$methodName on ${cls.name}")
    return try {
        method.invoke(obj, *args)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.cause ?: e
    }
}

fun strProp(obj: Any, vararg names: String): String =
    names.firstNotNullOfOrNull { readProp(obj, it)?.toString()?.takeIf { s -> s.isNotBlank() } } ?: ""

fun intProp(obj: Any, vararg names: String): Int =
    names.firstNotNullOfOrNull {
        when (val v = readProp(obj, it)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
    } ?: 0

fun boolProp(obj: Any, vararg names: String): Boolean =
    names.firstNotNullOfOrNull {
        when (val v = readProp(obj, it)) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull()
            else -> null
        }
    } ?: false

@Suppress("UNCHECKED_CAST")
fun listProp(obj: Any, vararg names: String): List<Any?> =
    names.firstNotNullOfOrNull { readProp(obj, it) as? List<Any?> } ?: emptyList()
