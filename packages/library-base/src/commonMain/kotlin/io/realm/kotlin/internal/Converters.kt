/*
 * Copyright 2022 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm.kotlin.internal

import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.dynamic.DynamicMutableRealmObject
import io.realm.kotlin.dynamic.DynamicRealmObject
import io.realm.kotlin.internal.interop.Link
import io.realm.kotlin.internal.interop.RealmValue
import io.realm.kotlin.internal.interop.Timestamp
import io.realm.kotlin.internal.interop.UUIDWrapper
import io.realm.kotlin.internal.platform.realmObjectCompanionOrNull
import io.realm.kotlin.types.BaseRealmObject
import io.realm.kotlin.types.ObjectId
import io.realm.kotlin.types.RealmInstant
import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.RealmUUID
import org.mongodb.kbson.BsonObjectId
import kotlin.native.concurrent.SharedImmutable
import kotlin.reflect.KClass

// This file contains all code for converting public API values into values passed to the C-API.
// This conversion is split into a two-step operation to:
// - Maximize code reuse of individual conversion steps to ensure consistency throughout the
//   compiler plugin injected code and the library
// - Accommodate future public (or internal default) type converters
// The two steps are:
// 1. Converting public user facing types to internal "storage types" which are library specific
//    Kotlin types mimicing the various underlying core types.
// 2. Converting from the "library storage types" into the C-API intepretable corresponding value
// The "C-API values" are passed in and out of the C-API as RealmValue that is just a `value class`-
// wrapper around `Any` that is converted into `realm_value_t` in the `cinterop` layer.

/**
 * Interface for overall conversion between public types and C-API input/output types. This is the
 * main abstraction of conversion used throughout the library.
 */
internal interface RealmValueConverter<T> {
    public fun publicToRealmValue(value: T?): RealmValue
    public fun realmValueToPublic(realmValue: RealmValue): T?
}

/**
 * Interface for converting between public user facing type and library storage types.
 *
 * This corresponds to step 1. of the overall conversion described in the top of this file.
 */
internal interface PublicConverter<T, S> {
    public fun fromPublic(value: T?): S?
    public fun toPublic(value: S?): T?
}

/**
 * Interface for converting between library storage types and C-API input/output values.
 *
 * This corresponds to step 2. of the overall conversion described in the top of this file.
 */
internal interface StorageTypeConverter<T> {
    public fun fromRealmValue(realmValue: RealmValue): T? = realmValueToAny(realmValue) as T?
    public fun toRealmValue(value: T?): RealmValue = anyToRealmValue(value)
}
// Top level methods to allow inlining from compiler plugin
public inline fun realmValueToAny(realmValue: RealmValue): Any? = realmValue.value
public inline fun anyToRealmValue(value: Any?): RealmValue = RealmValue(value)

/**
 * Composite converters that combines a [PublicConverter] and a [StorageTypeConverter] into a
 * [RealmValueConverter].
 */
internal abstract class CompositeConverter<T, S> :
    RealmValueConverter<T>, PublicConverter<T, S>, StorageTypeConverter<S> {
    override fun publicToRealmValue(value: T?): RealmValue = toRealmValue(fromPublic(value))
    override fun realmValueToPublic(realmValue: RealmValue): T? =
        toPublic(fromRealmValue(realmValue))
}

// RealmValueConverter with default pass-through public-to-storage-type implementation
internal abstract class PassThroughPublicConverter<T> : CompositeConverter<T, T>() {
    override fun fromPublic(value: T?): T? = passthrough(value) as T?
    override fun toPublic(value: T?): T? = passthrough(value) as T?
}
// Top level methods to allow inlining from compiler plugin
public inline fun passthrough(value: Any?): Any? = value

// Static converters
internal object StaticPassThroughConverter : PassThroughPublicConverter<Any>()

internal object ByteConverter : CompositeConverter<Byte, Long>() {
    override inline fun fromPublic(value: Byte?): Long? = byteToLong(value)
    override inline fun toPublic(value: Long?): Byte? = longToByte(value)
}
// Top level methods to allow inlining from compiler plugin
public inline fun byteToLong(value: Byte?): Long? = value?.let { it.toLong() }
public inline fun longToByte(value: Long?): Byte? = value?.let { it.toByte() }

internal object CharConverter : CompositeConverter<Char, Long>() {
    override inline fun fromPublic(value: Char?): Long? = charToLong(value)
    override inline fun toPublic(value: Long?): Char? = longToChar(value)
}
// Top level methods to allow inlining from compiler plugin
public inline fun charToLong(value: Char?): Long? = value?.let { it.code.toLong() }
public inline fun longToChar(value: Long?): Char? = value?.let { it.toInt().toChar() }

internal object ShortConverter : CompositeConverter<Short, Long>() {
    override inline fun fromPublic(value: Short?): Long? = shortToLong(value)
    override inline fun toPublic(value: Long?): Short? = longToShort(value)
}
// Top level methods to allow inlining from compiler plugin
public inline fun shortToLong(value: Short?): Long? = value?.let { it.toLong() }
public inline fun longToShort(value: Long?): Short? = value?.let { it.toShort() }

internal object IntConverter : CompositeConverter<Int, Long>() {
    override inline fun fromPublic(value: Int?): Long? = intToLong(value)
    override inline fun toPublic(value: Long?): Int? = longToInt(value)
}
// Top level methods to allow inlining from compiler plugin
public inline fun intToLong(value: Int?): Long? = value?.let { it.toLong() }
public inline fun longToInt(value: Long?): Int? = value?.let { it.toInt() }

internal object RealmInstantConverter : PassThroughPublicConverter<RealmInstant>() {
    override inline fun fromRealmValue(realmValue: RealmValue): RealmInstant? =
        realmValueToRealmInstant(realmValue)
}
// Top level method to allow inlining from compiler plugin
public inline fun realmValueToRealmInstant(realmValue: RealmValue): RealmInstant? =
    realmValue.value?.let { RealmInstantImpl(it as Timestamp) }

internal object RealmObjectIdConverter : PassThroughPublicConverter<ObjectId>() {
    override inline fun fromRealmValue(realmValue: RealmValue): ObjectId? =
        realmValueToRealmObjectId(realmValue)

    override inline fun toRealmValue(value: ObjectId?): RealmValue =
        realmObjectIdToRealmValue(value)
}
// Top level method to allow inlining from compiler plugin
public inline fun realmValueToRealmObjectId(realmValue: RealmValue): ObjectId? {
    return realmValue.value?.let { ObjectIdImpl((it as BsonObjectId).toByteArray()) }
}
public inline fun realmObjectIdToRealmValue(value: ObjectId?): RealmValue =
    RealmValue(value?.let { BsonObjectId((value as ObjectIdImpl).bytes) })

internal object RealmUUIDConverter : PassThroughPublicConverter<RealmUUID>() {
    override inline fun fromRealmValue(realmValue: RealmValue): RealmUUID? =
        realmValueToRealmUUID(realmValue)
}
// Top level method to allow inlining from compiler plugin
public inline fun realmValueToRealmUUID(realmValue: RealmValue): RealmUUID? {
    return realmValue.value?.let { RealmUUIDImpl(it as UUIDWrapper) }
}

internal object ByteArrayConverter : PassThroughPublicConverter<ByteArray>() {
    override inline fun fromRealmValue(realmValue: RealmValue): ByteArray? =
        realmValueToByteArray(realmValue)
}

public inline fun realmValueToByteArray(realmValue: RealmValue): ByteArray? {
    return realmValue.value?.let { it as ByteArray }
}

@SharedImmutable
internal val primitiveTypeConverters: Map<KClass<*>, RealmValueConverter<*>> =
    mapOf<KClass<*>, RealmValueConverter<*>>(
        Byte::class to ByteConverter,
        Char::class to CharConverter,
        Short::class to ShortConverter,
        Int::class to IntConverter,
        RealmInstant::class to RealmInstantConverter,
        RealmInstantImpl::class to RealmInstantConverter,
        BsonObjectId::class to StaticPassThroughConverter,
        ObjectId::class to RealmObjectIdConverter,
        ObjectIdImpl::class to RealmObjectIdConverter,
        RealmUUID::class to RealmUUIDConverter,
        RealmUUIDImpl::class to RealmUUIDConverter,
        ByteArray::class to ByteArrayConverter,
        String::class to StaticPassThroughConverter,
        Long::class to StaticPassThroughConverter,
        Boolean::class to StaticPassThroughConverter,
        Float::class to StaticPassThroughConverter,
        Double::class to StaticPassThroughConverter,
    )

// Dynamic default primitive value converter to translate primary keys and query arguments to RealmValues
internal object RealmValueArgumentConverter {
    fun convertArg(value: Any?): RealmValue {
        return value?.let {
            when (value) {
                is RealmObject -> {
                    realmObjectToRealmValueOrError(value)
                }
                else -> primitiveTypeConverters.get(value::class)?.let {
                    (it as RealmValueConverter<Any?>).publicToRealmValue(value)
                } ?: throw IllegalArgumentException("Cannot use object of type ${value::class::simpleName} as query argument")
            }
        } ?: RealmValue(null)
    }
    fun convertArgs(value: Array<out Any?>): Array<RealmValue> = value.map { convertArg(it) }.toTypedArray()
}

// Realm object converter that also imports (copyToRealm) objects when setting it
internal fun <T : BaseRealmObject> realmObjectConverter(
    clazz: KClass<T>,
    mediator: Mediator,
    realmReference: RealmReference
): RealmValueConverter<T> {
    return object : PassThroughPublicConverter<T>() {
        override fun fromRealmValue(realmValue: RealmValue): T? =
            // TODO OPTIMIZE We could lookup the companion and keep a reference to
            //  `companion.newInstance` method to avoid repeated mediator lookups in Link.toRealmObject()
            realmValueToRealmObject(realmValue, clazz, mediator, realmReference)

        override fun toRealmValue(value: T?): RealmValue =
            realmObjectToRealmValueWithImport(value as BaseRealmObject?, mediator, realmReference)
    }
}

internal inline fun <T : BaseRealmObject> realmValueToRealmObject(
    realmValue: RealmValue,
    clazz: KClass<T>,
    mediator: Mediator,
    realmReference: RealmReference
): T? {
    return realmValue.value?.let {
        (it as Link).toRealmObject(
            clazz,
            mediator,
            realmReference
        )
    }
}

// Will return a RealmValue wrapping a managed realm object reference (or null) or throw when
// called with an unmanaged object
internal inline fun realmObjectToRealmValueOrError(
    value: BaseRealmObject?,
): RealmValue {
    return RealmValue(
        value?.let {
            value.runIfManaged { this }
                ?: throw IllegalArgumentException("Cannot lookup unmanaged objects in realm")
        }
    )
}

// Will return a RealmValue wrapping a managed realm object reference (or null). If the object
// is unmanaged it will be imported according to the update policy. If the object is an outdated
// object it will will throw an error.
internal inline fun realmObjectToRealmValueWithImport(
    value: BaseRealmObject?,
    mediator: Mediator,
    realmReference: RealmReference,
    updatePolicy: UpdatePolicy = UpdatePolicy.ERROR,
    cache: UnmanagedToManagedObjectCache = mutableMapOf()
): RealmValue {
    // FIXME Would we actually rather like to error out on managed objects from different versions?
    return RealmValue(
        value?.let {
            val realmObjectReference = value.realmObjectReference
            // If managed ...
            if (realmObjectReference != null) {
                // and from the same version we just use object as is
                if (realmObjectReference.owner == realmReference) {
                    value
                } else {
                    throw IllegalArgumentException(
                        """Cannot import an outdated object. Use findLatest(object) to find an 
                            |up-to-date version of the object in the given context before importing 
                            |it.
                        """.trimMargin()
                    )
                }
            } else {
                // otherwise we will import it
                copyToRealm(mediator, realmReference.asValidLiveRealmReference(), value, updatePolicy, cache = cache)
            }.realmObjectReference
        }
    )
}

// Returns a converter fixed to convert objects of the given type in the context of the given mediator/realm
internal fun <T> converter(
    clazz: KClass<*>,
    mediator: Mediator,
    realmReference: RealmReference
): RealmValueConverter<T> {
    return if (realmObjectCompanionOrNull(clazz) != null || clazz in setOf<KClass<*>>(
            DynamicRealmObject::class,
            DynamicMutableRealmObject::class
        )
    ) {
        realmObjectConverter(
            clazz as KClass<out RealmObject>,
            mediator,
            realmReference
        )
    } else {
        primitiveTypeConverters.getValue(clazz)
    } as RealmValueConverter<T>
}
