package org.utbot.engine

import com.microsoft.z3.Context
import com.microsoft.z3.Sort
import java.util.Objects

/**
 *
 */
sealed class UtSort {
    override fun toString(): String = this.javaClass.simpleName.removeSurrounding("Ut", "Sort")
}

data class UtArraySort(val indexSort: UtSort, val itemSort: UtSort) : UtSort() {
    private val hashCode = Objects.hash(indexSort, itemSort)

    override fun toString() = "$indexSort -> $itemSort"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UtArraySort

        if (indexSort != other.indexSort) return false
        if (itemSort != other.itemSort) return false

        return true
    }

    override fun hashCode() = hashCode
}

// String literal (not a String Java object!)
object UtSeqSort : UtSort()

sealed class UtPrimitiveSort(val bitwidth: Int) : UtSort() {
    override fun toString() = "bv_$bitwidth"
}

//float, double
object UtFp32Sort : UtPrimitiveSort(Float.SIZE_BITS)
object UtFp64Sort : UtPrimitiveSort(Double.SIZE_BITS)

//boolean
object UtBoolSort : UtPrimitiveSort(1)

//integers and char
sealed class UtBvSort(val size: Int) : UtPrimitiveSort(size)

//@formatter:off
object UtByteSort   : UtBvSort(Byte     .SIZE_BITS)
object UtShortSort  : UtBvSort(Short    .SIZE_BITS)
object UtIntSort    : UtBvSort(Int      .SIZE_BITS)
object UtLongSort   : UtBvSort(Long     .SIZE_BITS)
object UtCharSort   : UtBvSort(Char     .SIZE_BITS) // the same size as short
object UtFloatSort   : UtBvSort(Float   .SIZE_BITS)
object UtDoubleSort   : UtBvSort(Double .SIZE_BITS)
object UtVoidSort   : UtBvSort(0)

val UtInt32Sort = UtIntSort
val UtAddrSort = UtInt32Sort //maybe int64 in future

////@formatter:on


fun UtSort.toZ3Sort(ctx: Context): Sort = when (this) {

    is UtBvSort -> ctx.mkBitVecSort(size)
    UtFp32Sort -> ctx.mkFPSort32()
    UtFp64Sort -> ctx.mkFPSort64()
    UtBoolSort -> ctx.mkBoolSort()

    UtSeqSort -> ctx.stringSort

    is UtArraySort -> ctx.mkArraySort(indexSort.toZ3Sort(ctx), itemSort.toZ3Sort(ctx))
}

// TODO remove it
fun equalSorts(fst: UtSort, snd: UtSort) =
    when (fst) {
        is UtCharSort -> snd == UtShortSort
        is UtShortSort -> snd == UtCharSort
        else -> false
    }
