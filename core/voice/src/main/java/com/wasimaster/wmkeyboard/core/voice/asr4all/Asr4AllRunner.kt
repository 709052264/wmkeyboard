package com.wasimaster.wmkeyboard.core.voice.asr4all

/**
 * A tensor as it crosses into and out of a model: its [shape] and its values
 * in [data], which is a FloatArray (float32 and float16), LongArray (int64),
 * IntArray (int32) or ByteArray (bool, uint8).
 */
class Tensor(val shape: LongArray, val data: Any) {

    val floats: FloatArray get() = data as FloatArray
    val longs: LongArray
        get() = when (data) {
            is LongArray -> data
            is IntArray -> LongArray(data.size) { data[it].toLong() }
            else -> error("not an integer tensor")
        }

    companion object {
        /** Zeros of [spec]'s shape and type, the state a stream starts from. */
        fun zeros(spec: TensorSpec): Tensor {
            val n = spec.count
            val data: Any = when (spec.dtype) {
                DType.FLOAT, DType.HALF -> FloatArray(n)
                DType.LONG -> LongArray(n)
                DType.INT -> IntArray(n)
                else -> ByteArray(n)
            }
            return Tensor(spec.dims, data)
        }

        /** [values] as a tensor of [spec]'s integer type and the shape given. */
        fun integers(spec: TensorSpec, shape: LongArray, values: LongArray): Tensor = Tensor(
            shape,
            if (spec.dtype == DType.INT) IntArray(values.size) { values[it].toInt() } else values,
        )
    }
}

/** ExecuTorch's scalar type codes, the ones these models use. */
object DType {
    const val BYTE = 0
    const val INT = 3
    const val LONG = 4
    const val HALF = 5
    const val FLOAT = 6
    const val BOOL = 11
}

/** One input or output a method declares: its scalar type and its fixed shape. */
class TensorSpec(val dtype: Int, val dims: LongArray) {
    val count: Int get() = dims.fold(1L) { a, d -> a * d }.toInt()
}

/** What a method takes and returns. */
class MethodShape(val inputs: List<TensorSpec>, val outputs: List<TensorSpec>)

/**
 * Runs the methods of one loaded program. The native one is [Asr4AllNative];
 * tests drive [Asr4AllStream] through a fake, since the decoding is all
 * arithmetic on what comes back.
 */
interface Asr4AllRunner {
    /** The tensors [method] takes and returns, or null when the program has no such method. */
    fun shape(method: String): MethodShape?

    /** [method] on [inputs], in the order and types [shape] declares. */
    fun run(method: String, inputs: List<Tensor>): List<Tensor>
}
