package com.lifesaver.detect

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

/** Yerçekimi ivmesi (m/s^2). Eşiklerin g cinsinden okunabilmesi için tek kaynak. */
const val G = 9.80665f

/** g cinsinden verilen bir eşiği m/s^2'ye çevirir. */
fun g(value: Float): Float = value * G

/**
 * Tek bir sensör örneği. [t] cihaz açılışından beri geçen nanosaniye
 * (SensorEvent.timestamp) — duvar saati DEĞİL, çünkü duvar saati geriye atlayabilir.
 */
data class Sample(
    val t: Long,
    val x: Float,
    val y: Float,
    val z: Float,
) {
    /** Signal Vector Magnitude — |a|. Yerçekimi dahildir: durgun telefonda ~9.81. */
    val magnitude: Float get() = sqrt(x * x + y * y + z * z)

    companion object {
        val ZERO = Sample(0L, 0f, 0f, 0f)
    }
}

/**
 * Sabit kapasiteli, tahsis yapmayan halka tampon. Sensör geri çağrıları 50-400 Hz
 * hızında geldiği için sıcak yolda GC baskısı yaratmamalıyız.
 */
class SampleRing(val capacity: Int) {
    private val ts = LongArray(capacity)
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val zs = FloatArray(capacity)

    private var head = 0
    var size = 0
        private set

    val isFull: Boolean get() = size == capacity

    fun clear() {
        head = 0
        size = 0
    }

    fun add(t: Long, x: Float, y: Float, z: Float) {
        ts[head] = t
        xs[head] = x
        ys[head] = y
        zs[head] = z
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    fun add(s: Sample) = add(s.t, s.x, s.y, s.z)

    /** index 0 = en eski tutulan örnek, size-1 = en yeni. */
    private fun idx(index: Int): Int {
        require(index in 0 until size) { "index $index, size $size" }
        val start = (head - size + capacity) % capacity
        return (start + index) % capacity
    }

    fun timeAt(index: Int): Long = ts[idx(index)]
    fun xAt(index: Int): Float = xs[idx(index)]
    fun yAt(index: Int): Float = ys[idx(index)]
    fun zAt(index: Int): Float = zs[idx(index)]

    fun sampleAt(index: Int): Sample {
        val i = idx(index)
        return Sample(ts[i], xs[i], ys[i], zs[i])
    }

    fun last(): Sample? = if (size == 0) null else sampleAt(size - 1)

    fun magnitudeAt(index: Int): Float {
        val i = idx(index)
        return sqrt(xs[i] * xs[i] + ys[i] * ys[i] + zs[i] * zs[i])
    }

    /** Tamponun kapsadığı süre (saniye). */
    fun spanSeconds(): Float {
        if (size < 2) return 0f
        return (timeAt(size - 1) - timeAt(0)) / 1_000_000_000f
    }

    /** Son [seconds] saniyeye karşılık gelen ilk indeks (yoksa 0). */
    fun indexAtOrAfter(cutoffNanos: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timeAt(mid) < cutoffNanos) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Son [seconds] saniyelik pencerede |a| istatistikleri. */
    fun windowStats(seconds: Float): WindowStats {
        if (size == 0) return WindowStats.EMPTY
        val newest = timeAt(size - 1)
        val cutoff = newest - (seconds * 1_000_000_000f).toLong()
        val from = indexAtOrAfter(cutoff)
        return statsBetween(from, size)
    }

    fun statsBetween(fromInclusive: Int, toExclusive: Int): WindowStats {
        val from = fromInclusive.coerceIn(0, size)
        val to = toExclusive.coerceIn(0, size)
        val n = to - from
        if (n <= 0) return WindowStats.EMPTY

        var minMag = Float.MAX_VALUE
        var maxMag = -Float.MAX_VALUE
        var sum = 0.0
        var sumSq = 0.0
        var minAt = from
        var maxAt = from
        for (i in from until to) {
            val m = magnitudeAt(i)
            if (m < minMag) { minMag = m; minAt = i }
            if (m > maxMag) { maxMag = m; maxAt = i }
            sum += m
            sumSq += m.toDouble() * m
        }
        val mean = (sum / n).toFloat()
        val variance = ((sumSq / n) - mean.toDouble() * mean).coerceAtLeast(0.0)
        return WindowStats(
            count = n,
            min = minMag,
            max = maxMag,
            mean = mean,
            stdDev = sqrt(variance).toFloat(),
            minIndex = minAt,
            maxIndex = maxAt,
        )
    }
}

data class WindowStats(
    val count: Int,
    val min: Float,
    val max: Float,
    val mean: Float,
    val stdDev: Float,
    val minIndex: Int,
    val maxIndex: Int,
) {
    /** Durgunluk ölçütü: yerçekiminden sapmanın saçılımı. */
    val isEmpty: Boolean get() = count == 0

    companion object {
        val EMPTY = WindowStats(0, 0f, 0f, 0f, 0f, 0, 0)
    }
}

/**
 * İki yerçekimi vektörü arasındaki açı (derece). Duruş değişimini ölçmek için:
 * ayaktan yatay konuma geçiş tipik olarak 60-90 derecedir.
 */
fun angleBetweenDegrees(
    ax: Float, ay: Float, az: Float,
    bx: Float, by: Float, bz: Float,
): Float {
    val na = sqrt(ax * ax + ay * ay + az * az)
    val nb = sqrt(bx * bx + by * by + bz * bz)
    if (na < 1e-3f || nb < 1e-3f) return 0f
    val dot = (ax * bx + ay * by + az * bz) / (na * nb)
    val clamped = min(1f, kotlin.math.max(-1f, dot))
    return Math.toDegrees(acos(clamped).toDouble()).toFloat()
}

/**
 * Cihazın yerçekimine göre eğimi (derece). 0 = ekran yukarı bakan düz yatay,
 * 90 = dik, 180 = ekran aşağı bakan düz yatay (ters).
 * Bu, tekne devrilmesi ve "yerde yatıyor" tespitinin temelidir.
 */
fun inclinationDegrees(gx: Float, gy: Float, gz: Float): Float =
    angleBetweenDegrees(gx, gy, gz, 0f, 0f, 1f)

/**
 * Basit tek kutuplu alçak geçiren filtre. Yerçekimi vektörünü ham ivmeden
 * ayırmak için kullanılır (TYPE_GRAVITY yoksa yedek yol).
 */
class LowPass(private val alpha: Float = 0.8f) {
    private var initialised = false
    var x = 0f; private set
    var y = 0f; private set
    var z = 0f; private set

    fun reset() { initialised = false; x = 0f; y = 0f; z = 0f }

    fun update(nx: Float, ny: Float, nz: Float) {
        if (!initialised) {
            x = nx; y = ny; z = nz
            initialised = true
            return
        }
        x = alpha * x + (1 - alpha) * nx
        y = alpha * y + (1 - alpha) * ny
        z = alpha * z + (1 - alpha) * nz
    }
}

/**
 * Sensörün ölçüm tavanına dayanma (clipping) tespiti. Gerçek bir araç kazası
 * telefonun ivmeölçerini (tipik tavan 8g veya 16g) doyurur; doyma görüldüğünde
 * gerçek tepe ivme ölçülenden BÜYÜKTÜR ve bu kendi başına güçlü bir kaza sinyalidir.
 */
fun isClipping(sample: Sample, maxRange: Float): Boolean {
    if (maxRange <= 0f) return false
    val edge = maxRange * 0.97f
    return abs(sample.x) >= edge || abs(sample.y) >= edge || abs(sample.z) >= edge
}
