package sh.edm.s1kiosk.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites the stored shape of a TFLite model's input tensor, in place, in the model's
 * FlatBuffer (`tensorflow/lite/schema/schema.fbs`: Model.subgraphs[0] -> SubGraph.inputs[i] ->
 * SubGraph.tensors[...] -> Tensor.shape).
 *
 * Why: openWakeWord's `melspectrogram.tflite` has a dynamic input (`shape_signature [-1, -1]`)
 * stored as shape `[1, 1]`. The TFLite Java `Interpreter` constructor always calls
 * `AllocateTensors()` at the stored shape, and at `[1, 1]` the model's CONV_2D fails to prepare,
 * so the constructor throws before we get a chance to `resizeInput`. (Python resizes before its
 * first `allocate_tensors()`, so upstream never hits this.) Patching the stored shape to a
 * valid size, e.g. `[1, 1760]`, fixes construction; `resizeInput` still works afterwards.
 */
object TfliteShapePatch {

    /**
     * Patches [model] (little-endian, positioned anywhere; absolute reads) so input [inputIndex]
     * of subgraph 0 has [newShape]. The rank must match the stored one. Returns the old shape.
     */
    fun patchInputShape(model: ByteBuffer, newShape: IntArray, inputIndex: Int = 0): IntArray {
        val b = model.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val root = b.getInt(0)
        val subgraphs = vector(b, field(b, root, 2) ?: error("model has no subgraphs"))
        val subgraph = table(b, subgraphs, 0)
        val inputs = vector(b, field(b, subgraph, 1) ?: error("subgraph has no inputs"))
        val tensorIndex = b.getInt(inputs + 4 + 4 * inputIndex)
        val tensors = vector(b, field(b, subgraph, 0) ?: error("subgraph has no tensors"))
        val tensor = table(b, tensors, tensorIndex)
        val shape = vector(b, field(b, tensor, 0) ?: error("input tensor has no shape"))
        val rank = b.getInt(shape)
        require(rank == newShape.size) { "input rank is $rank, new shape has ${newShape.size} dims" }
        val old = IntArray(rank) { b.getInt(shape + 4 + 4 * it) }
        val out = model.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until rank) out.putInt(shape + 4 + 4 * i, newShape[i])
        return old
    }

    /** Reads the stored shape of input [inputIndex] of subgraph 0. */
    fun inputShape(model: ByteBuffer, inputIndex: Int = 0): IntArray {
        val b = model.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val subgraph = table(b, vector(b, field(b, b.getInt(0), 2)!!), 0)
        val tensorIndex = b.getInt(vector(b, field(b, subgraph, 1)!!) + 4 + 4 * inputIndex)
        val shape = vector(b, field(b, table(b, vector(b, field(b, subgraph, 0)!!), tensorIndex), 0)!!)
        return IntArray(b.getInt(shape)) { b.getInt(shape + 4 + 4 * it) }
    }

    /** Absolute position of field [id] of the table at [table], or null if absent. */
    private fun field(b: ByteBuffer, table: Int, id: Int): Int? {
        val vtable = table - b.getInt(table)
        val vtableSize = b.getShort(vtable).toInt() and 0xFFFF
        val slot = 4 + 2 * id
        if (slot >= vtableSize) return null
        val off = b.getShort(vtable + slot).toInt() and 0xFFFF
        return if (off == 0) null else table + off
    }

    /** Follows the uoffset stored at [pos] to a vector; returns the position of its length. */
    private fun vector(b: ByteBuffer, pos: Int): Int = pos + b.getInt(pos)

    /** Element [i] of a vector of tables. */
    private fun table(b: ByteBuffer, vector: Int, i: Int): Int {
        val len = b.getInt(vector)
        require(i in 0 until len) { "index $i out of $len" }
        val elem = vector + 4 + 4 * i
        return elem + b.getInt(elem)
    }
}
