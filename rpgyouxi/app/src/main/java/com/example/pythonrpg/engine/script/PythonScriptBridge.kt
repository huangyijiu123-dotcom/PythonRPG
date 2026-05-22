package com.example.pythonrpg.engine.script

import com.example.pythonrpg.shared.*
import com.example.pythonrpg.engine.tick.TickEngine
import com.example.pythonrpg.engine.building.BuildingEngine
import com.example.pythonrpg.engine.building.BuildingType
import com.example.pythonrpg.engine.building.BuildingSnapshot
import com.example.pythonrpg.engine.entity.EntityStateManager
import com.example.pythonrpg.engine.map.GridMapData
import com.example.pythonrpg.engine.event.EventEngine
import com.example.pythonrpg.engine.event.ActiveEvent
import com.example.pythonrpg.engine.market.MarketEngine
import com.example.pythonrpg.engine.tech.TechEngine
import com.example.pythonrpg.engine.action.ActionProcessor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.util.concurrent.ConcurrentHashMap

// ── 脚本运行结果数据包 ──────────────────────────────────────────
data class ScriptResult(
    val status: String, // "SUCCESS" | "AST_VIOLATION" | "RUNTIME_ERROR" | "TIMEOUT"
    val commands: List<PlayerCommand> = emptyList(),
    val errorMessage: String? = null
)

// ── Kotlin Proxy 只读代理类 ──────────────────────────────────────
class WarehouseProxy(private val snapshot: WarehouseSnapshot) {
    val fill_level: Float
        get() = snapshot.inventory.values.sum().toFloat() / snapshot.capacity.toFloat()
    fun stock(item: String): Int = snapshot.inventory[item] ?: 0
}

class AdventurerProxy(private val snapshot: AdventurerSnapshot) {
    val id: Long get() = snapshot.id
    val name: String get() = snapshot.name
    val x: Int get() = snapshot.coordinate.x
    val y: Int get() = snapshot.coordinate.y
    val hp: Int get() = snapshot.hp
    val max_hp: Int get() = snapshot.maxHp
    val attack_power: Int get() = 10
    val defense: Int get() = 5
    val agility: Int get() = 8
    val level: Int get() = 1
    val fatigue: Int get() = snapshot.fatigue
    val status: String get() = snapshot.status.name
}

class EventProxy(private val event: ActiveEvent) {
    val type: String get() = event.type.name
    val position_x: Int? get() = event.targetX
    val position_y: Int? get() = event.targetY
    val remaining_time: Int get() = event.remainingTicks
}

class TileProxy(private val tile: TileData) {
    fun is_locked(): Boolean = tile.isBossLocked
    fun is_explored(): Boolean = tile.exploreStatus == ExploreStatus.EXPLORED
    fun has_treasure(): Boolean = tile.customAttributes["has_treasure"] as? Boolean ?: false
}

class CityStateProxy(private val cityId: Long, private val marketEngine: MarketEngine) {
    fun get_price(item: String): Float {
        return marketEngine.getPriceInfo(cityId, item)?.currentPrice ?: 0.0f
    }
    fun get_supply_rate(item: String): Float {
        return marketEngine.getPriceInfo(cityId, item)?.supplyRate ?: 0.0f
    }
}

class VillagerProxy(
    private val snapshot: VillagerSnapshot,
    private val commandCollector: MutableList<PlayerCommand>,
    private val warehousesProvider: () -> List<WarehouseSnapshot>
) {
    val id: Long get() = snapshot.id
    val name: String get() = snapshot.name
    val x: Int get() = snapshot.coordinate.x
    val y: Int get() = snapshot.coordinate.y
    val status: String get() = snapshot.status.name
    val job: String get() = snapshot.job
    val is_injured: Boolean get() = snapshot.isInjured
    val energy: Int get() = snapshot.energy
    val backpack: Map<String, Int> get() = snapshot.backpack
    val equipped_tools: Map<String, Int> get() = snapshot.equippedTools

    fun assign_job(job: String, targetX: Int, targetY: Int) {
        commandCollector.add(PlayerCommand.AssignJob(snapshot.id, job, targetX, targetY))
    }

    fun return_home() {
        commandCollector.add(PlayerCommand.ReturnHome(snapshot.id))
    }

    fun equip_tool(toolName: String): Boolean {
        val warehouses = warehousesProvider()
        var bestWarehouse: WarehouseSnapshot? = null
        var minDistance = Int.MAX_VALUE
        for (wh in warehouses) {
            val stock = wh.inventory[toolName] ?: 0
            if (stock > 0) {
                val dist = Math.abs(snapshot.coordinate.x - wh.coordinate.x) + 
                           Math.abs(snapshot.coordinate.y - wh.coordinate.y)
                if (dist < minDistance) {
                    minDistance = dist
                    bestWarehouse = wh
                }
            }
        }
        return if (bestWarehouse != null) {
            commandCollector.add(PlayerCommand.EquipTool(snapshot.id, toolName, bestWarehouse.id))
            true
        } else {
            false
        }
    }
}

class CaravanProxy(
    private val snapshot: CaravanSnapshot,
    private val commandCollector: MutableList<PlayerCommand>
) {
    val id: Long get() = snapshot.id
    val name: String get() = snapshot.name
    val x: Int get() = snapshot.coordinate.x
    val y: Int get() = snapshot.coordinate.y
    val status: String get() = snapshot.status.name
    val capacity: Int get() = snapshot.capacity
    val cargo: Map<String, Int> get() = snapshot.cargo

    fun assign_target(targetX: Int, targetY: Int) {
        commandCollector.add(PlayerCommand.AssignCaravanTarget(snapshot.id, targetX, targetY))
    }

    fun start() {
        commandCollector.add(PlayerCommand.StartCaravan(snapshot.id))
    }

    fun recall() {
        commandCollector.add(PlayerCommand.RecallCaravan(snapshot.id))
    }

    fun trade_with(cityId: Long, isBuy: Boolean, item: String, amount: Int) {
        commandCollector.add(PlayerCommand.TradeWithCityState(snapshot.id, cityId, isBuy, item, amount))
    }
}

class BuildingProxy(
    private val snapshot: BuildingSnapshot,
    private val commandCollector: MutableList<PlayerCommand>
) {
    val id: Long get() = snapshot.buildingId
    val type: String get() = snapshot.type.name
    val x: Int get() = snapshot.x
    val y: Int get() = snapshot.y
    val level: Int get() = snapshot.level
    val state: String get() = snapshot.state.name

    fun upgrade() {
        commandCollector.add(PlayerCommand.UpgradeBuilding(snapshot.x, snapshot.y))
    }

    fun queue_production(toolType: String, count: Int) {
        if (snapshot.type == BuildingType.WORKSHOP) {
            commandCollector.add(PlayerCommand.QueueProduction(snapshot.buildingId, toolType, count))
        }
    }

    fun forge_equipment(templateId: String) {
        if (snapshot.type == BuildingType.BLACKSMITH) {
            commandCollector.add(PlayerCommand.ForgeEquipment(templateId))
        }
    }
}

class TerritoryProxy(
    private val villagers: List<VillagerSnapshot>,
    private val caravans: List<CaravanSnapshot>,
    private val buildings: List<BuildingSnapshot>,
    private val commandCollector: MutableList<PlayerCommand>,
    private val warehousesProvider: () -> List<WarehouseSnapshot>
) {
    val memory = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun get_all_villagers(): List<VillagerProxy> = villagers.map { VillagerProxy(it, commandCollector, warehousesProvider) }
    fun get_idle_villagers(): List<VillagerProxy> = get_all_villagers().filter { it.status == "IDLE" }
    fun get_injured_villagers(): List<VillagerProxy> = get_all_villagers().filter { it.is_injured }
    fun get_all_caravans(): List<CaravanProxy> = caravans.map { CaravanProxy(it, commandCollector) }
    fun get_all_buildings(): List<BuildingProxy> = buildings.map { BuildingProxy(it, commandCollector) }

    fun build(x: Int, y: Int, buildingType: String) {
        commandCollector.add(PlayerCommand.BuildBuilding(x, y, buildingType))
    }

    fun start_research(techId: String) {
        commandCollector.add(PlayerCommand.StartResearch(techId))
    }

    fun enact_policy(policyType: String, isActive: Boolean) {
        commandCollector.add(PlayerCommand.EnactPolicy(policyType, isActive))
    }
}

class MapProxy(
    private val tiles: Map<Coordinate, TileData>,
    private val isAdvancedExplorationUnlocked: Boolean
) {
    fun get_tile(x: Int, y: Int): TileProxy {
        if (!isAdvancedExplorationUnlocked) {
            throw SecurityException("ADVANCED_EXPLORATION technology is locked!")
        }
        val coord = Coordinate(x, y)
        val tile = tiles[coord] ?: throw IllegalArgumentException("Tile at ($x, $y) not found")
        return TileProxy(tile)
    }
}

class GuildProxy(
    private val adventurers: List<AdventurerSnapshot>,
    private val commandCollector: MutableList<PlayerCommand>
) {
    fun get_all_adventurers(): List<AdventurerProxy> = adventurers.map { AdventurerProxy(it) }
    fun get_idle_adventurers(): List<AdventurerProxy> = get_all_adventurers().filter { it.status == "IDLE" }

    fun dispatch(adventurerId: Long, x: Int, y: Int) {
        commandCollector.add(PlayerCommand.DispatchAdventurer(adventurerId, x, y))
    }

    fun recall(adventurerId: Long) {
        commandCollector.add(PlayerCommand.RecallAdventurer(adventurerId))
    }
}

/**
 * PythonScriptBridge - 安全的 Python 3 脚本桥接器
 */
class PythonScriptBridge(
    private val tickEngine: TickEngine,
    private val entityState: EntityStateManager,
    private val buildingEngine: BuildingEngine,
    private val gridMapData: GridMapData,
    private val eventEngine: EventEngine,
    private val techEngine: TechEngine,
    private val marketEngine: MarketEngine,
    private val actionProcessor: ActionProcessor,
    private val coroutineScope: CoroutineScope
) {
    // 跨 Tick 共享记忆
    private val globalMemory = ConcurrentHashMap<String, Any>()

    // 最近 5 次错误历史记录
    private val errorHistory = mutableListOf<String>()

    // 时段策略挂载字典
    private val strategies = ConcurrentHashMap<TimePeriod, MutableList<String>>()

    private val gson = Gson()

    // 友好村长字典
    private val errorTranslations = mapOf(
        "IndexError" to "👴 村长：大人，您指向了一个不存在的位置！列表的下标是从 0 开始的哦。",
        "TypeError" to "👴 村长：大人，类型搞错了。给函数传了错误类型的参数？",
        "AttributeError" to "👴 村长：大人，这个对象没有这个属性或方法。是不是写错了？",
        "KeyError" to "👴 村长：大人，字典里没有这个键！先检查 key 是否存在？",
        "ZeroDivisionError" to "👴 村长：大人，除数不能为零！您的脚本出现了除以零的情况。"
    )

    init {
        // 订阅并监听 timePeriodChangeFlow 自转推进
        coroutineScope.launch {
            tickEngine.timePeriodChangeFlow.collect { period ->
                val scripts = strategies[period] ?: return@collect
                for (script in scripts) {
                    val result = executeScript(script)
                    for (cmd in result.commands) {
                        actionProcessor.queueCommand(cmd)
                    }
                }
            }
        }
    }

    /**
     * 注册指定时段的自动化持久化运行策略
     */
    fun registerStrategy(period: TimePeriod, scriptCode: String) {
        strategies.computeIfAbsent(period) { mutableListOf() }.add(scriptCode)
    }

    /**
     * 清空所有已注册的自转策略
     */
    fun clearStrategies() {
        strategies.clear()
    }

    /**
     * 村长友好异常翻译器
     */
    private fun translateError(errorType: String, rawMessage: String): String {
        synchronized(errorHistory) {
            errorHistory.add(errorType)
            if (errorHistory.size > 5) {
                errorHistory.removeAt(0)
            }
            val baseMsg = when (errorType) {
                "NameError" -> {
                    val nameRegex = "'([^']*)'".toRegex()
                    val matchedName = nameRegex.find(rawMessage)?.groupValues?.get(1) ?: "未知"
                    "👴 村长：大人，'$matchedName' 是啥？我没见过这个名字。检查一下拼写？"
                }
                else -> errorTranslations[errorType] ?: "👴 村长：大人，您的脚本执行出错了: $rawMessage"
            }
            val count = errorHistory.count { it == errorType }
            return if (count >= 3) {
                "$baseMsg\n👴 这个错误我已经提醒大人 $count 次了，要不要打开帮助文档？"
            } else {
                baseMsg
            }
        }
    }

    /**
     * 核心脚本执行入口 (带 2 秒超时强杀熔断)
     */
    fun executeScript(scriptCode: String): ScriptResult {
        // A. 准备 JSON 数据集
        val stateMap = mutableMapOf<String, Any?>()
        stateMap["script"] = scriptCode
        stateMap["memory"] = globalMemory.toMap()
        stateMap["isAdvancedExplorationUnlocked"] = techEngine.isTechUnlocked("ADVANCED_EXPLORATION")
        stateMap["villagers"] = entityState.getAllVillagers()
        stateMap["adventurers"] = entityState.getAllAdventurers()
        stateMap["caravans"] = entityState.getAllCaravans()
        stateMap["buildings"] = buildingEngine.getAllBuildings()
        stateMap["warehouses"] = entityState.getAllWarehouses()
        
        val allTiles = gridMapData.filterTiles { true }
        stateMap["tiles"] = allTiles

        val activeEvts = eventEngine.getActiveEvents()
        stateMap["events"] = activeEvts

        // 构造城邦价格信息表
        val pricesMap = mutableMapOf<String, Map<String, Any>>()
        for (cityId in marketEngine.getAllCityIds()) {
            val cityPrices = mutableMapOf<String, Any>()
            val items = listOf("FOOD", "WOOD", "STONE", "IRON_ORE", "GOLD", "RELIC")
            for (item in items) {
                val priceInfo = marketEngine.getPriceInfo(cityId, item)
                if (priceInfo != null) {
                    cityPrices[item] = mapOf(
                        "currentPrice" to priceInfo.currentPrice,
                        "supplyRate" to priceInfo.supplyRate
                    )
                }
            }
            pricesMap[cityId.toString()] = cityPrices
        }
        stateMap["prices"] = pricesMap

        val inputJson = gson.toJson(stateMap)

        // B. 写出 python 运行包装脚本
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val runnerFile = File(tempDir, "python_sandbox_runner_${System.nanoTime()}.py")
        runnerFile.writeText(pythonRunnerScript)

        var process: Process? = null
        var completed = false
        var stdout = ""
        var stderr = ""

        try {
            // C. 启动本地 Python 进程
            val pb = ProcessBuilder("python", runnerFile.absolutePath)
            pb.directory(tempDir)
            process = pb.start()

            // D. 开启协程或直接写入 Standard Input
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream, "UTF-8"))
            writer.write(inputJson)
            writer.flush()
            writer.close()

            // E. Kotlin 挂起协程超时限制
            val job = coroutineScope.launch(Dispatchers.IO) {
                try {
                    stdout = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    stderr = process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } catch (e: Exception) {
                    // Isolated read error
                }
            }

            runBlocking {
                val success = withTimeoutOrNull(2000L) {
                    while (process.isAlive) {
                        delay(20L)
                    }
                    job.join()
                    true
                }
                if (success != null) {
                    completed = true
                }
            }
        } catch (t: Throwable) {
            // Execute start crash
            return ScriptResult("RUNTIME_ERROR", emptyList(), "Failed to launch python: ${t.message}")
        } finally {
            if (!completed && process != null) {
                process.destroyForcibly()
            }
            if (runnerFile.exists()) {
                runnerFile.delete()
            }
        }

        if (!completed) {
            return ScriptResult("TIMEOUT", emptyList(), "TIMEOUT: Python execution timed out after 2000ms.")
        }

        // F. 解析 Python 返回结果
        try {
            val outputMapType = object : TypeToken<Map<String, Any?>>() {}.type
            val outputMap: Map<String, Any?> = gson.fromJson(stdout, outputMapType) ?: return ScriptResult(
                "RUNTIME_ERROR", emptyList(), "Invalid empty output from Python runner"
            )

            val status = outputMap["status"] as? String ?: "RUNTIME_ERROR"
            if (status == "AST_VIOLATION") {
                val err = outputMap["error"] as? String ?: "AST verification failed"
                return ScriptResult("AST_VIOLATION", emptyList(), err)
            }

            if (status == "RUNTIME_ERROR") {
                val errType = outputMap["error_type"] as? String ?: "UnknownError"
                val errMsg = outputMap["error_message"] as? String ?: "No message"
                val translated = translateError(errType, errMsg)
                return ScriptResult("RUNTIME_ERROR", emptyList(), translated)
            }

            if (status == "SUCCESS") {
                // 更新跨 Tick memory
                val newMemory = outputMap["memory"] as? Map<String, Any>
                if (newMemory != null) {
                    globalMemory.clear()
                    globalMemory.putAll(newMemory)
                }

                // 反序列化指令集
                val rawCmds = outputMap["commands"] as? List<Map<String, Any>> ?: emptyList()
                val playerCommands = rawCmds.mapNotNull { raw ->
                    val type = raw["type"] as? String ?: return@mapNotNull null
                    try {
                        when (type) {
                            "AssignJob" -> {
                                val villagerId = (raw["villagerId"] as Double).toLong()
                                val job = raw["job"] as String
                                val targetX = (raw["targetX"] as Double).toInt()
                                val targetY = (raw["targetY"] as Double).toInt()
                                PlayerCommand.AssignJob(villagerId, job, targetX, targetY)
                            }
                            "ReturnHome" -> {
                                val villagerId = (raw["villagerId"] as Double).toLong()
                                PlayerCommand.ReturnHome(villagerId)
                            }
                            "EquipTool" -> {
                                val villagerId = (raw["villagerId"] as Double).toLong()
                                val toolId = raw["toolId"] as String
                                val warehouseId = (raw["warehouseId"] as Double).toLong()
                                PlayerCommand.EquipTool(villagerId, toolId, warehouseId)
                            }
                            "DispatchAdventurer" -> {
                                val adventurerId = (raw["adventurerId"] as Double).toLong()
                                val targetX = (raw["targetX"] as Double).toInt()
                                val targetY = (raw["targetY"] as Double).toInt()
                                PlayerCommand.DispatchAdventurer(adventurerId, targetX, targetY)
                            }
                            "RecallAdventurer" -> {
                                val adventurerId = (raw["adventurerId"] as Double).toLong()
                                PlayerCommand.RecallAdventurer(adventurerId)
                            }
                            "AssignCaravanTarget" -> {
                                val caravanId = (raw["caravanId"] as Double).toLong()
                                val targetX = (raw["targetX"] as Double).toInt()
                                val targetY = (raw["targetY"] as Double).toInt()
                                PlayerCommand.AssignCaravanTarget(caravanId, targetX, targetY)
                            }
                            "StartCaravan" -> {
                                val caravanId = (raw["caravanId"] as Double).toLong()
                                PlayerCommand.StartCaravan(caravanId)
                            }
                            "RecallCaravan" -> {
                                val caravanId = (raw["caravanId"] as Double).toLong()
                                PlayerCommand.RecallCaravan(caravanId)
                            }
                            "TradeWithCityState" -> {
                                val caravanId = (raw["caravanId"] as Double).toLong()
                                val cityId = (raw["cityId"] as Double).toLong()
                                val isBuy = raw["isBuy"] as Boolean
                                val item = raw["item"] as String
                                val amount = (raw["amount"] as Double).toInt()
                                PlayerCommand.TradeWithCityState(caravanId, cityId, isBuy, item, amount)
                            }
                            "BuildBuilding" -> {
                                val x = (raw["x"] as Double).toInt()
                                val y = (raw["y"] as Double).toInt()
                                val buildingType = raw["buildingType"] as String
                                PlayerCommand.BuildBuilding(x, y, buildingType)
                            }
                            "UpgradeBuilding" -> {
                                val x = (raw["x"] as Double).toInt()
                                val y = (raw["y"] as Double).toInt()
                                PlayerCommand.UpgradeBuilding(x, y)
                            }
                            "QueueProduction" -> {
                                val workshopId = (raw["workshopId"] as Double).toLong()
                                val toolType = raw["toolType"] as String
                                val count = (raw["count"] as Double).toInt()
                                PlayerCommand.QueueProduction(workshopId, toolType, count)
                            }
                            "ForgeEquipment" -> {
                                val templateId = raw["templateId"] as String
                                PlayerCommand.ForgeEquipment(templateId)
                            }
                            "StartResearch" -> {
                                val techId = raw["techId"] as String
                                PlayerCommand.StartResearch(techId)
                            }
                            "EnactPolicy" -> {
                                val policyType = raw["policyType"] as String
                                val isActive = raw["isActive"] as Boolean
                                PlayerCommand.EnactPolicy(policyType, isActive)
                            }
                            else -> null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                val truncated = outputMap["truncated"] as? Boolean ?: false
                val errMsg = if (truncated) {
                    "👴 村长警告：大人在此 tick 下达的策略指令已超过 200 个最大负荷！超出的命令已被强力截断。"
                } else {
                    null
                }

                return ScriptResult("SUCCESS", playerCommands, errMsg)
            }

            return ScriptResult("RUNTIME_ERROR", emptyList(), "Unknown response status: $status")
        } catch (e: Exception) {
            return ScriptResult("RUNTIME_ERROR", emptyList(), "Failed to parse Python result: ${e.message}\nSTDOUT: $stdout\nSTDERR: $stderr")
        }
    }

    // ── 内嵌的 Python Sandbox 验证与执行包装源码 ───────────────────────────
    private val pythonRunnerScript = """
import sys
import json
import ast
import math

class SecurityException(Exception):
    pass

def ast_verify(code_str):
    try:
        root = ast.parse(code_str)
    except SyntaxError as e:
        return f"SyntaxError: {e}"
    
    for node in ast.walk(root):
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            return "import is not allowed"
        if isinstance(node, ast.Call):
            if isinstance(node.func, ast.Name):
                if node.func.id in ['eval', 'exec', 'compile', 'open', '__import__']:
                    return f"{node.func.id} is not allowed"
        if isinstance(node, ast.Try):
            for handler in node.handlers:
                if handler.type is None:
                    return "naked except is not allowed"
        if isinstance(node, ast.While):
            is_true = False
            if isinstance(node.test, ast.Constant) and node.test.value is True:
                is_true = True
            elif isinstance(node.test, ast.Name) and node.test.id == 'True':
                is_true = True
            if is_true:
                has_break = False
                for body_node in ast.walk(node):
                    if isinstance(body_node, ast.Break):
                        has_break = True
                        break
                if not has_break:
                    return "while True without break is not allowed"
    return None

safe_builtins = {
    'print': print,
    'len': len,
    'range': range,
    'int': int,
    'float': float,
    'str': str,
    'bool': bool,
    'list': list,
    'dict': dict,
    'tuple': tuple,
    'set': set,
    'min': min,
    'max': max,
    'sum': sum,
    'abs': abs,
    'round': round,
    'sorted': sorted,
    'enumerate': enumerate,
    'zip': zip,
    'type': type,
    'isinstance': isinstance,
    'getattr': getattr,
    'math': math
}

class WarehouseProxy:
    def __init__(self, data):
        self._data = data
        self.capacity = data.get("capacity", 1)
        self.inventory = data.get("inventory", {})
        total_inv = sum(self.inventory.values())
        self.fill_level = float(total_inv) / float(self.capacity)
    def stock(self, item):
        return self.inventory.get(item, 0)

class AdventurerProxy:
    def __init__(self, data):
        self._data = data
        self.id = data["id"]
        self.name = data["name"]
        self.x = data["coordinate"]["x"]
        self.y = data["coordinate"]["y"]
        self.hp = data["hp"]
        self.max_hp = data["maxHp"]
        self.attack_power = 10
        self.defense = 5
        self.agility = 8
        self.level = 1
        self.fatigue = data["fatigue"]
        self.status = data["status"]

class EventProxy:
    def __init__(self, data):
        self._data = data
        self.type = data["type"]
        self.position_x = data.get("targetX")
        self.position_y = data.get("targetY")
        self.remaining_time = data["remainingTicks"]

class TileProxy:
    def __init__(self, data):
        self._data = data
        self._locked = data.get("isBossLocked", False)
        self._explored = data.get("exploreStatus") == "EXPLORED"
        self._treasure = data.get("customAttributes", {}).get("has_treasure", False)
    def is_locked(self):
        return self._locked
    def is_explored(self):
        return self._explored
    def has_treasure(self):
        return self._treasure

class CityStateProxy:
    def __init__(self, city_id, prices):
        self.city_id = city_id
        self._prices = prices.get(str(city_id), {})
    def get_price(self, item):
        return float(self._prices.get(item, {}).get("currentPrice", 0.0))
    def get_supply_rate(self, item):
        return float(self._prices.get(item, {}).get("supplyRate", 0.0))

class VillagerProxy:
    def __init__(self, data, append_cmd, warehouses):
        self._data = data
        self._append_cmd = append_cmd
        self._warehouses = warehouses
        self.id = data["id"]
        self.name = data["name"]
        self.x = data["coordinate"]["x"]
        self.y = data["coordinate"]["y"]
        self.status = data["status"]
        self.job = data["job"]
        self.is_injured = data["isInjured"]
        self.energy = data["energy"]
        self.backpack = data.get("backpack", {})
        self.equipped_tools = data.get("equippedTools", {})
        
    def assign_job(self, job, target_x, target_y):
        self._append_cmd({
            "type": "AssignJob",
            "villagerId": self.id,
            "job": job,
            "targetX": target_x,
            "targetY": target_y
        })
        
    def return_home(self):
        self._append_cmd({
            "type": "ReturnHome",
            "villagerId": self.id
        })
        
    def equip_tool(self, tool_name):
        best_wh = None
        min_dist = 9999999
        for wh in self._warehouses:
            stock = wh.get("inventory", {}).get(tool_name, 0)
            if stock > 0:
                dist = abs(self.x - wh["coordinate"]["x"]) + abs(self.y - wh["coordinate"]["y"])
                if dist < min_dist:
                    min_dist = dist
                    best_wh = wh
        if best_wh is not None:
            best_wh["inventory"][tool_name] -= 1
            self._append_cmd({
                "type": "EquipTool",
                "villagerId": self.id,
                "toolId": tool_name,
                "warehouseId": best_wh["id"]
            })
            return True
        return False

class CaravanProxy:
    def __init__(self, data, append_cmd):
        self._data = data
        self._append_cmd = append_cmd
        self.id = data["id"]
        self.name = data["name"]
        self.x = data["coordinate"]["x"]
        self.y = data["coordinate"]["y"]
        self.status = data["status"]
        self.capacity = data.get("capacity", 0)
        self.cargo = data.get("cargo", {})
        
    def assign_target(self, target_x, target_y):
        self._append_cmd({
            "type": "AssignCaravanTarget",
            "caravanId": self.id,
            "targetX": target_x,
            "targetY": target_y
        })
        
    def start(self):
        self._append_cmd({
            "type": "StartCaravan",
            "caravanId": self.id
        })
        
    def recall(self):
        self._append_cmd({
            "type": "RecallCaravan",
            "caravanId": self.id
        })
        
    def trade_with(self, city_id, is_buy, item, amount):
        self._append_cmd({
            "type": "TradeWithCityState",
            "caravanId": self.id,
            "cityId": city_id,
            "isBuy": is_buy,
            "item": item,
            "amount": amount
        })

class BuildingProxy:
    def __init__(self, data, append_cmd):
        self._data = data
        self._append_cmd = append_cmd
        self.id = data["buildingId"]
        self.type = data["type"]
        self.x = data["x"]
        self.y = data["y"]
        self.level = data["level"]
        self.state = data["state"]
        
    def upgrade(self):
        self._append_cmd({
            "type": "UpgradeBuilding",
            "x": self.x,
            "y": self.y
        })
        
    def queue_production(self, tool_type, count):
        if self.type == "WORKSHOP":
            self._append_cmd({
                "type": "QueueProduction",
                "workshopId": self.id,
                "toolType": tool_type,
                "count": count
            })
            
    def forge_equipment(self, template_id):
        if self.type == "BLACKSMITH":
            self._append_cmd({
                "type": "ForgeEquipment",
                "templateId": template_id
            })

class TerritoryProxy:
    def __init__(self, data, append_cmd):
        self._data = data
        self._append_cmd = append_cmd
        self.memory = data.get("memory", {})
        self._warehouses = [wh for wh in data.get("warehouses", [])]
        
    def get_all_villagers(self):
        return [VillagerProxy(v, self._append_cmd, self._warehouses) for v in self._data.get("villagers", [])]
        
    def get_idle_villagers(self):
        return [v for v in self.get_all_villagers() if v.status == "IDLE"]
        
    def get_injured_villagers(self):
        return [v for v in self.get_all_villagers() if v.is_injured]
        
    def get_all_caravans(self):
        return [CaravanProxy(c, self._append_cmd) for c in self._data.get("caravans", [])]
        
    def get_all_buildings(self):
        return [BuildingProxy(b, self._append_cmd) for b in self._data.get("buildings", [])]
        
    def build(self, x, y, building_type):
        self._append_cmd({
            "type": "BuildBuilding",
            "x": x,
            "y": y,
            "buildingType": building_type
        })
        
    def start_research(self, tech_id):
        self._append_cmd({
            "type": "StartResearch",
            "techId": tech_id
        })
        
    def enact_policy(self, policy_type, is_active):
        self._append_cmd({
            "type": "EnactPolicy",
            "policyType": policy_type,
            "isActive": is_active
        })

class MapProxy:
    def __init__(self, data):
        self._data = data
        self._unlocked = data.get("isAdvancedExplorationUnlocked", False)
        self._tiles = {}
        for t in data.get("tiles", []):
            coord = t["coordinate"]
            key = f"{coord['x']},{coord['y']}"
            self._tiles[key] = t
            
    def get_tile(self, x, y):
        if not self._unlocked:
            raise SecurityException("ADVANCED_EXPLORATION technology is locked!")
        key = f"{x},{y}"
        if key not in self._tiles:
            raise ValueError(f"Tile at ({x}, {y}) not found")
        return TileProxy(self._tiles[key])

class GuildProxy:
    def __init__(self, data, append_cmd):
        self._data = data
        self._append_cmd = append_cmd
        
    def get_all_adventurers(self):
        return [AdventurerProxy(a) for a in self._data.get("adventurers", [])]
        
    def get_idle_adventurers(self):
        return [a for a in self.get_all_adventurers() if a.status == "IDLE"]
        
    def dispatch(self, adventurer_id, x, y):
        self._append_cmd({
            "type": "DispatchAdventurer",
            "adventurerId": adventurer_id,
            "targetX": x,
            "targetY": y
        })
        
    def recall(self, adventurer_id):
        self._append_cmd({
            "type": "RecallAdventurer",
            "adventurerId": adventurer_id
        })

def main():
    try:
        input_data = json.loads(sys.stdin.read())
    except Exception as e:
        print(json.dumps({"status": "RUNTIME_ERROR", "error_type": "ValueError", "error_message": f"Invalid JSON stdin: {e}"}))
        return

    script_code = input_data.get("script", "")
    
    ast_err = ast_verify(script_code)
    if ast_err:
        print(json.dumps({"status": "AST_VIOLATION", "error": ast_err}))
        return

    commands = []
    truncated_flag = [False]
    
    def append_cmd(cmd):
        if len(commands) < 200:
            commands.append(cmd)
        else:
            truncated_flag[0] = True

    territory = TerritoryProxy(input_data, append_cmd)
    map_proxy = MapProxy(input_data)
    guild = GuildProxy(input_data, append_cmd)

    whitelisted_globals = {}
    for name, val in safe_builtins.items():
        whitelisted_globals[name] = val

    whitelisted_globals["territory"] = territory
    whitelisted_globals["map"] = map_proxy
    whitelisted_globals["guild"] = guild
    whitelisted_globals["SecurityException"] = SecurityException

    try:
        exec(script_code, whitelisted_globals, {})
        print(json.dumps({
            "status": "SUCCESS",
            "commands": commands,
            "memory": territory.memory,
            "truncated": truncated_flag[0]
        }))
    except Exception as e:
        print(json.dumps({
            "status": "RUNTIME_ERROR",
            "error_type": type(e).__name__,
            "error_message": str(e)
        }))

if __name__ == "__main__":
    main()
""".trimIndent()
}
