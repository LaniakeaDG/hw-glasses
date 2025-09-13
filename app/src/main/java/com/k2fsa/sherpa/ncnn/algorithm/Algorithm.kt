package com.k2fsa.sherpa.ncnn.algorithm

import com.k2fsa.sherpa.ncnn.control.EventBus
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * 卸载算法输出
 * [0]: 语音转文本
 * [1]: 中英互译
 * [2]: 文本转语音
 * [3]: 图像识别男女
 * [4]: 图像识别手势
 */
// 数据类，用于表示模块配置文件
data class ModuleProfile(val latency: Double, val power: Double, val networkLatency: Double = 0.0, val cloudPower: Double = 0.0)
// 结果数据类
data class ModulePlacementResult(
    val path: List<Int>,
    val latency: Double,
    val edgeEnergy: Double,
    val cloudEnergy: Double,
    val score: Double
)

class Algorithm(private val event: EventBus) {

    private val pathTokenLatencies = mapOf(
        listOf(0, 0, 1, 0, 0) to 1384.0,
        listOf(0, 0, 1, 1, 0) to 1231.0,
        listOf(0, 0, 1, 0, 1) to 1359.0,
        listOf(0, 0, 1, 1, 1) to 1102.0,
        listOf(0, 1, 1, 0, 0) to 98.0,
        listOf(0, 1, 1, 1, 0) to 102.0,
        listOf(0, 1, 1, 0, 1) to 99.0,
        listOf(0, 1, 1, 1, 1) to 100.0,
        listOf(1, 1, 1, 0, 0) to 56.0,
        listOf(1, 1, 1, 1, 0) to 53.0,
        listOf(1, 1, 1, 0, 1) to 57.0,
        listOf(1, 1, 1, 1, 1) to 55.0,
        listOf(0, 0, 0, 0, 0) to 1624.0,
        listOf(0, 0, 0, 1, 0) to 1901.0,
        listOf(0, 0, 0, 0, 1) to 2014.0,
        listOf(0, 0, 0, 1, 1) to 1950.0,
        listOf(1, 0, 0, 0, 0) to 18.0,
        listOf(1, 0, 0, 1, 0) to 22.0,
        listOf(1, 0, 0, 0, 1) to 20.0,
        listOf(1, 0, 0, 1, 1) to 17.0
    )
    // 权重因子
    private val a = 0.6
    private val b = 0.3
    private val c = 0.1

    // 最大值归一化函数
    private fun maxValueNormalize(data: List<Double>): List<Double> {
        val maxValue = data.maxOrNull() ?: 0.0
        return if (maxValue == 0.0) {
            data
        } else {
            data.map { it / maxValue }
        }
    }

    // 根据带宽调整网络时延
    private fun adjustNetworkLatency(baseLatency: Double, baseBandwidth: Double, currentBandwidth: Double): Double {
        return if (currentBandwidth <= 0) {
            Double.POSITIVE_INFINITY
        } else {
            baseLatency * (baseBandwidth / currentBandwidth)
        }
    }

    // 计算给定路径的时延
    private fun calculateLatency(
        path: List<Int>,
        edgeModules: Map<String, ModuleProfile>,
        cloudModules: Map<String, ModuleProfile>,
        baseBandwidth: Double,
        bandwidth: Double,
        pathTokenLatencies: Map<List<Int>, Double>
    ): Double {
        var totalLatency = 0.0
        // 获取路径对应的首 Token 时延
        val tokenLatency = pathTokenLatencies[path] ?: 0.0
        val adjustedTokenLatency = adjustNetworkLatency(tokenLatency, baseBandwidth, bandwidth)
        totalLatency += adjustedTokenLatency
        return totalLatency
    }

    // 计算能耗，分为端侧和边侧能耗
    private fun calculateEnergy(
        path: List<Int>,
        edgeModules: Map<String, ModuleProfile>,
        cloudModules: Map<String, ModuleProfile>,
        bandwidth: Double,
        baseBandwidth: Double,
        transmissionPower: Double
    ): Pair<Double, Double> {
        var edgeEnergy = 0.0
        var cloudEnergy = 0.0
        val moduleKeys = edgeModules.keys.toList()

        path.forEachIndexed { i, selected ->
            val module = moduleKeys[i]
            if (selected == 0) {
                // 端侧能耗：功率 - 传输功率
                edgeEnergy += (edgeModules[module]!!.power - transmissionPower) * edgeModules[module]!!.latency
            } else {
                // 边侧能耗：传输功率 + 云端功率
                cloudEnergy += transmissionPower * (adjustNetworkLatency(cloudModules[module]!!.networkLatency, baseBandwidth, bandwidth) / 1000) +
                        (cloudModules[module]!!.cloudPower * cloudModules[module]!!.latency)
            }
        }
        return Pair(edgeEnergy, cloudEnergy)
    }


    // 优化模块部署策略
    fun optimizeModulePlacement(
        bandwidth: Double,
        battery: Double,
        pathTokenLatencies: Map<List<Int>, Double> = this.pathTokenLatencies,
        a: Double = this.a,
        b: Double = this.b,
        c: Double = this.c
    ): ModulePlacementResult {
        val baseBandwidth = 100.0 // 参考带宽，单位 Mbps
        val transmissionPower = 1.584 // 传输功率，单位 W

        // 端侧模块配置
        val edgeModules = mapOf(
            "gesture" to ModuleProfile(50.0, 1.9),
            "gender" to ModuleProfile(160.0, 1.85),
            "stt" to ModuleProfile(620000.0, 1.8),
            "translate" to ModuleProfile(5000.0, 1.9),
            "tts" to ModuleProfile(200.0, 2.1)
        )

        // 边侧模块配置
        val cloudModules = mapOf(
            "gesture" to ModuleProfile(245.0, 0.0, 550.0, 200.0),
            "gender" to ModuleProfile(60.0, 0.0, 550.0, 200.0),
            "stt" to ModuleProfile(60.0, 0.0, 550.0, 200.0),
            "translate" to ModuleProfile(200.0, 0.0, 550.0, 200.0),
            "tts" to ModuleProfile(1389.0, 0.0, 1631.0, 200.0)
        )

        // 定义所有路径
        val paths = listOf(
            listOf(0, 0, 1, 0, 0), listOf(0, 0, 1, 1, 0), listOf(0, 0, 1, 0, 1), listOf(0, 0, 1, 1, 1),
            listOf(0, 1, 1, 0, 0), listOf(0, 1, 1, 1, 0), listOf(0, 1, 1, 0, 1), listOf(0, 1, 1, 1, 1),
            listOf(1, 1, 1, 0, 0), listOf(1, 1, 1, 1, 0), listOf(1, 1, 1, 0, 1), listOf(1, 1, 1, 1, 1),
            listOf(0, 0, 0, 0, 0), listOf(0, 0, 0, 1, 0), listOf(0, 0, 0, 0, 1), listOf(0, 0, 0, 1, 1),
            listOf(1, 0, 0, 0, 0), listOf(1, 0, 0, 1, 0), listOf(1, 0, 0, 0, 1), listOf(1, 0, 0, 1, 1)
        )

        // 计算所有路径的时延和能耗
        val pathLatencies = mutableListOf<Double>()
        val pathEdgeEnergies = mutableListOf<Double>()
        val pathCloudEnergies = mutableListOf<Double>()
        val pathScores = mutableListOf<Double>()

        paths.forEach { path ->
            val latency = calculateLatency(path, edgeModules, cloudModules, baseBandwidth, bandwidth, pathTokenLatencies)
            val (edgeEnergy, cloudEnergy) = calculateEnergy(path, edgeModules, cloudModules, bandwidth, baseBandwidth, transmissionPower)

            pathLatencies.add(latency)
            pathEdgeEnergies.add(edgeEnergy)
            pathCloudEnergies.add(cloudEnergy)
        }

        // 归一化时延和能耗
        val latenciesNormalized = maxValueNormalize(pathLatencies)
        val edgeEnergiesNormalized = maxValueNormalize(pathEdgeEnergies)
        val cloudEnergiesNormalized = maxValueNormalize(pathCloudEnergies)

        // 计算加权评分
        paths.indices.forEach { i ->
            val score = a * latenciesNormalized[i] + b * edgeEnergiesNormalized[i] + c * cloudEnergiesNormalized[i]
            pathScores.add(score)
        }

        // 根据最小评分选择最佳路径
        val bestPathIndex = pathScores.indexOf(pathScores.minOrNull()!!)

        return ModulePlacementResult(
            path = paths[bestPathIndex],
            latency = pathLatencies[bestPathIndex],
            edgeEnergy = pathEdgeEnergies[bestPathIndex],
            cloudEnergy = pathCloudEnergies[bestPathIndex],
            score = pathScores[bestPathIndex]
        )
    }


    private var lowBandTime = 0
    private var highBandTime = 0
    private var switchTime = 4
    private var canSwitch = true


    fun strategy(bandwidth: Double): Int {
        if (bandwidth == 0.0) {
            // 带宽测量失败
            return -1
        }

        // 计数
        if (bandwidth < 2) {
            lowBandTime += 1
            highBandTime -= 1
        } else {
            lowBandTime -= 1
            highBandTime += 1
        }
        // 控制在0~2
        if (lowBandTime < 0) {
            lowBandTime = 0
        } else if (lowBandTime >= 2) {
            lowBandTime = 2
        }
        if (highBandTime < 0) {
            highBandTime = 0
        } else if (highBandTime >= 2) {
            highBandTime = 2
        }

        if (canSwitch) {
            if (lowBandTime >= 2) {
                // 切换B3
                lowBandTime = 0
                return 3
            } else if (highBandTime >= 2 ) {
                // 切换B2
                highBandTime = 0
                return 2
            }
        } else {
            switchTime -= 1
            if (switchTime == 0) {
                switchTime = 4
                canSwitch = true
            }
            return 0
        }
        return 0
    }
}