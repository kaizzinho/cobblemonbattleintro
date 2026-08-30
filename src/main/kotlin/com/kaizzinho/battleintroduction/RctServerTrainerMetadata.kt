package com.kaizzinho.battleintroduction

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.entity.LivingEntity
import org.slf4j.LoggerFactory
import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Small reflection-only bridge for RCT trainer definitions.
 *
 * RCT does not expose a capture-ball field in trainer datapacks, so Battle
 * Introduction only needs the configured team size here. The caller can then
 * use ordinary Poké Balls as a visual fallback until Cobblemon's battle actor
 * exposes the instantiated trainer Pokémon.
 */
object RctServerTrainerMetadata {

    data class TrainerMetadata(
        val trainerId: String,
        val displayName: String?,
        val partySize: Int
    )

    private const val RCT_MOD_ID = "rctmod"
    private const val RCT_MOD_CLASS =
        "com.gitlab.srcmc.rctmod.api.RCTMod"
    private const val MAX_PARTY_SIZE = 6

    private val logger =
        LoggerFactory.getLogger(
            "battleintroduction/RctServerTrainerMetadata"
        )

    fun resolve(
        entity: LivingEntity?
    ): TrainerMetadata? {
        if (
            entity == null ||
            !FabricLoader.getInstance().isModLoaded(RCT_MOD_ID)
        ) {
            return null
        }

        return runCatching {
            val trainerId =
                invokeNoArg(entity, "getTrainerId")
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return null

            val data =
                resolveTrainerData(entity, trainerId)
                    ?: return null

            val identity =
                displayString(
                    readMember(
                        data,
                        "getIdentity",
                        "identity"
                    )
                )

            val partySize =
                collectionSize(
                    readMember(
                        data,
                        "getTeam",
                        "team"
                    )
                )
                    .coerceIn(0, MAX_PARTY_SIZE)

            TrainerMetadata(
                trainerId = trainerId,
                displayName = identity,
                partySize = partySize
            )
        }.onFailure {
            logger.debug(
                "RCT trainer metadata lookup failed for entity={}: {}",
                entity.javaClass.name,
                failureMessage(it)
            )
        }.getOrNull()
    }

    private fun resolveTrainerData(
        entity: LivingEntity,
        trainerId: String
    ): Any? {
        invokeNoArg(entity, "getData")
            ?.let { return it }

        val classLoader = entity.javaClass.classLoader
        val rctModClass =
            runCatching {
                Class.forName(
                    RCT_MOD_CLASS,
                    false,
                    classLoader
                )
            }.getOrNull()
                ?: return null

        val getInstance =
            rctModClass.methods.firstOrNull {
                it.name == "getInstance" &&
                    it.parameterCount == 0 &&
                    Modifier.isStatic(it.modifiers)
            } ?: return null

        val rct = getInstance.invoke(null) ?: return null
        val manager =
            invokeNoArg(rct, "getTrainerManager")
                ?: return null

        return invokeOneArg(
            manager,
            "getData",
            trainerId
        ) ?: invokeOneArg(
            manager,
            "getData",
            entity
        )
    }

    private fun readMember(
        target: Any,
        vararg names: String
    ): Any? {
        for (name in names) {
            invokeNoArg(target, name)
                ?.let { return it }

            readField(target, name)
                ?.let { return it }
        }

        return null
    }

    private fun readField(
        target: Any,
        fieldName: String
    ): Any? {
        val field =
            findField(
                target.javaClass,
                fieldName
            ) ?: return null

        return runCatching {
            if (!field.canAccess(target)) {
                field.isAccessible = true
            }
            field.get(target)
        }.getOrNull()
    }

    private fun findField(
        type: Class<*>,
        fieldName: String
    ): Field? {
        var current: Class<*>? = type

        while (current != null) {
            current.declaredFields
                .firstOrNull {
                    it.name.equals(
                        fieldName,
                        ignoreCase = true
                    )
                }
                ?.let { return it }

            current = current.superclass
        }

        return null
    }

    private fun displayString(
        value: Any?
    ): String? {
        if (value == null) {
            return null
        }

        val text =
            when (value) {
                is String -> value
                else ->
                    invokeNoArg(value, "getString")
                        ?.toString()
                        ?: value.toString()
            }

        return text
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun collectionSize(
        value: Any?
    ): Int =
        when (value) {
            null -> 0
            is Collection<*> -> value.size
            is Map<*, *> -> value.size
            is Iterable<*> -> value.count()
            else ->
                if (value.javaClass.isArray) {
                    Array.getLength(value)
                } else {
                    0
                }
        }

    private fun invokeNoArg(
        target: Any,
        methodName: String
    ): Any? {
        val method =
            findMethod(
                target.javaClass,
                methodName,
                0
            ) ?: return null

        return invoke(
            method,
            target
        )
    }

    private fun invokeOneArg(
        target: Any,
        methodName: String,
        argument: Any
    ): Any? {
        val method =
            allMethods(target.javaClass)
                .firstOrNull {
                    it.name == methodName &&
                        it.parameterCount == 1 &&
                        isCompatible(
                            it.parameterTypes[0],
                            argument.javaClass
                        )
                } ?: return null

        return invoke(
            method,
            target,
            argument
        )
    }

    private fun findMethod(
        type: Class<*>,
        methodName: String,
        parameterCount: Int
    ): Method? =
        allMethods(type)
            .firstOrNull {
                it.name == methodName &&
                    it.parameterCount == parameterCount
            }

    private fun allMethods(
        type: Class<*>
    ): Sequence<Method> =
        sequence {
            yieldAll(type.methods.asSequence())

            var current: Class<*>? = type
            while (current != null) {
                yieldAll(
                    current.declaredMethods.asSequence()
                )
                current = current.superclass
            }
        }

    private fun invoke(
        method: Method,
        target: Any,
        vararg arguments: Any
    ): Any? =
        runCatching {
            if (!method.canAccess(target)) {
                method.isAccessible = true
            }
            method.invoke(
                target,
                *arguments
            )
        }.getOrNull()

    private fun isCompatible(
        parameterType: Class<*>,
        argumentType: Class<*>
    ): Boolean =
        parameterType.isAssignableFrom(argumentType) ||
            primitiveWrapper(parameterType)
                ?.isAssignableFrom(argumentType) == true

    private fun primitiveWrapper(
        type: Class<*>
    ): Class<*>? =
        when (type) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            else -> null
        }

    private fun failureMessage(
        error: Throwable
    ): String =
        error.cause?.message
            ?: error.message
            ?: error.javaClass.simpleName
}
