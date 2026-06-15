package com.example.optik.settings

object ExposureHelper {

    // === TỐC ĐỘ MÀN TRẬP chuẩn (1/3 stop), đơn vị: nanoseconds ===
    // Từ 1/8000s đến 30s
    private val SHUTTER_SPEEDS_NS: LongArray = longArrayOf(
        // 1/8000 .. 1/1000
        125_000L,      // 1/8000
        156_250L,      // 1/6400
        200_000L,      // 1/5000
        250_000L,      // 1/4000
        312_500L,      // 1/3200
        400_000L,      // 1/2500
        500_000L,      // 1/2000
        625_000L,      // 1/1600
        800_000L,      // 1/1250
        1_000_000L,    // 1/1000
        // 1/800 .. 1/100
        1_250_000L,    // 1/800
        1_562_500L,    // 1/640
        2_000_000L,    // 1/500
        2_500_000L,    // 1/400
        3_125_000L,    // 1/320
        4_000_000L,    // 1/250
        5_000_000L,    // 1/200
        6_250_000L,    // 1/160
        8_000_000L,    // 1/125
        10_000_000L,   // 1/100
        // 1/80 .. 1/1
        12_500_000L,   // 1/80
        16_666_667L,   // 1/60
        20_000_000L,   // 1/50
        25_000_000L,   // 1/40
        33_333_333L,   // 1/30
        40_000_000L,   // 1/25
        50_000_000L,   // 1/20
        66_666_667L,   // 1/15
        76_923_077L,   // 1/13
        100_000_000L,  // 1/10
        125_000_000L,  // 1/8
        166_666_667L,  // 1/6
        200_000_000L,  // 1/5
        250_000_000L,  // 1/4
        333_333_333L,  // 1/3
        400_000_000L,  // 0.4"
        500_000_000L,  // 0.5"
        625_000_000L,  // 0.6"
        769_230_769L,  // 0.8"
        1_000_000_000L,  // 1"
        1_300_000_000L,  // 1.3"
        1_600_000_000L,  // 1.6"
        2_000_000_000L,  // 2"
        2_500_000_000L,  // 2.5"
        3_200_000_000L,  // 3.2"
        4_000_000_000L,  // 4"
        5_000_000_000L,  // 5"
        6_000_000_000L,  // 6"
        8_000_000_000L,  // 8"
        10_000_000_000L, // 10"
        13_000_000_000L, // 13"
        15_000_000_000L, // 15"
        20_000_000_000L, // 20"
        25_000_000_000L, // 25"
        30_000_000_000L  // 30"
    )

    // Nhãn hiển thị tương ứng
    private val SHUTTER_LABELS: Array<String> = arrayOf(
        "1/8000", "1/6400", "1/5000", "1/4000", "1/3200", "1/2500",
        "1/2000", "1/1600", "1/1250", "1/1000",
        "1/800", "1/640", "1/500", "1/400", "1/320", "1/250",
        "1/200", "1/160", "1/125", "1/100",
        "1/80", "1/60", "1/50", "1/40", "1/30", "1/25",
        "1/20", "1/15", "1/13", "1/10", "1/8", "1/6",
        "1/5", "1/4", "1/3", "0.4\"", "0.5\"", "0.6\"",
        "0.8\"", "1\"", "1.3\"", "1.6\"", "2\"", "2.5\"",
        "3.2\"", "4\"", "5\"", "6\"", "8\"", "10\"",
        "13\"", "15\"", "20\"", "25\"", "30\""
    )

    // === ISO chuẩn (1/3 stop) ===
    private val ISO_VALUES: IntArray = intArrayOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200,
        250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000,
        2500, 3200, 4000, 5000, 6400, 8000, 10000, 12500, 16000,
        20000, 25600, 32000, 40000, 51200, 64000, 80000, 102400
    )

    /**
     * Trả về danh sách ISO chuẩn nằm trong [sensorMin, sensorMax].
     * sensorMin được làm tròn LÊN, sensorMax được làm tròn XUỐNG.
     */
    fun getStandardIsoRange(sensorMin: Int, sensorMax: Int): List<Int> {
        val result = mutableListOf<Int>()
        for (iso in ISO_VALUES) {
            if (iso in sensorMin..sensorMax) {
                result.add(iso)
            }
        }
        // Nếu không có giá trị nào khớp, thêm min/max gốc
        if (result.isEmpty()) {
            result.add(roundIsoUp(sensorMin))
        }
        return result
    }

    /** Làm tròn ISO LÊN giá trị chuẩn gần nhất */
    fun roundIsoUp(raw: Int): Int {
        for (iso in ISO_VALUES) {
            if (iso >= raw) return iso
        }
        return ISO_VALUES.last()
    }

    /** Làm tròn ISO XUỐNG giá trị chuẩn gần nhất */
    fun roundIsoDown(raw: Int): Int {
        for (i in ISO_VALUES.indices.reversed()) {
            if (ISO_VALUES[i] <= raw) return ISO_VALUES[i]
        }
        return ISO_VALUES.first()
    }

    /** Snap a raw ISO value to the absolute nearest standard ISO value */
    fun snapToStandardIso(raw: Int): Int {
        var bestIdx = 0
        var bestDist = Int.MAX_VALUE
        for (i in ISO_VALUES.indices) {
            val dist = Math.abs(ISO_VALUES[i] - raw)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return ISO_VALUES[bestIdx]
    }

    /**
     * Tìm giá trị chuẩn gần nhất với giá trị ns thực tế.
     * Trả về nhãn hiển thị (ví dụ "1/250").
     */
    fun formatShutterSpeed(ns: Long): String {
        if (ns <= 0) return "--"
        val bestIdx = getNearestShutterIndex(ns)
        return SHUTTER_LABELS[bestIdx]
    }

    /** Trả về dạng thập phân (Double) chuẩn để lưu vào EXIF. ExifInterface sẽ tự động parse thành RATIONAL. */
    fun formatShutterExif(ns: Long): String {
        if (ns <= 0) return ""
        val bestIdx = getNearestShutterIndex(ns)
        val standardNs = SHUTTER_SPEEDS_NS[bestIdx]
        return (standardNs / 1_000_000_000.0).toString()
    }

    private fun getNearestShutterIndex(ns: Long): Int {
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        val logNs = Math.log(ns.toDouble())
        for (i in SHUTTER_SPEEDS_NS.indices) {
            val dist = Math.abs(Math.log(SHUTTER_SPEEDS_NS[i].toDouble()) - logNs)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return bestIdx
    }

    /** Format ISO cho hiển thị, làm tròn về giá trị chuẩn gần nhất */
    fun formatIso(raw: Int): String {
        return snapToStandardIso(raw).toString()
    }
}
